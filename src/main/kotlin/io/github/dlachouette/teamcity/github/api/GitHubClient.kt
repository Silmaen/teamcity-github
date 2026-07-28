package io.github.dlachouette.teamcity.github.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

open class GitHubClient {

    // GitHub REST API version pinned on every request via the
    // `X-GitHub-Api-Version` header. A `var` so the server-settings
    // layer can override the compiled-in default at runtime.
    var apiVersion: String = DEFAULT_API_VERSION

    // HTTP resilience knobs, pushed from BridgeServerSettings at startup.
    // maxAttempts=1 means "no retry". Retries cover transport failures,
    // 5xx, and rate-limit exhaustion (403/429 with remaining=0).
    var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    var baseDelayMs: Long = DEFAULT_BASE_DELAY_MS

    // Injectable so tests never actually sleep.
    var sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) }

    open fun getPr(
        accessToken: String,
        repo: RepoCoords,
        number: Int,
        apiBase: String = DEFAULT_API_BASE,
    ): PrInfo? {
        val resp = request("GET", "$apiBase/repos/${repo.slug}/pulls/$number", accessToken)
            ?: return null
        return if (resp.isSuccess) {
            parsePrInfo(resp.body)
        } else {
            LOG.warn("GitHub returned ${resp.code} for ${repo.slug}#$number")
            null
        }
    }

    open fun postCheckRun(
        accessToken: String,
        repo: RepoCoords,
        request: CheckRunRequest,
        apiBase: String = DEFAULT_API_BASE,
    ): Boolean {
        val resp = request(
            "POST",
            "$apiBase/repos/${repo.slug}/check-runs",
            accessToken,
            encodeCheckRunPayload(request),
        ) ?: return false
        // 201 Created on success per GitHub Check Runs API.
        if (resp.isSuccess) return true
        LOG.warn("GitHub Check Run POST returned ${resp.code} for ${repo.slug}@${request.headSha}: ${resp.body}")
        return false
    }

    // GET /repos/{slug}/pulls/{n}/files -> the paths changed by the PR.
    // Paginated (100/page); capped at MAX_PR_FILES_PAGES so a huge PR
    // can't make us page forever. Used for monorepo path filtering.
    open fun listPrFiles(
        accessToken: String,
        repo: RepoCoords,
        number: Int,
        apiBase: String = DEFAULT_API_BASE,
    ): List<String> {
        val files = mutableListOf<String>()
        var page = 1
        while (page <= MAX_PR_FILES_PAGES) {
            val resp = request("GET", "$apiBase/repos/${repo.slug}/pulls/$number/files?per_page=100&page=$page", accessToken)
                ?: break
            if (!resp.isSuccess) {
                LOG.warn("GET pulls/$number/files returned ${resp.code} for ${repo.slug}: ${resp.body}")
                break
            }
            val names = parsePrFileNames(resp.body)
            files += names
            if (names.size < 100) break
            page++
        }
        return files
    }

    // GET /repos/{slug}/commits/{sha}/pulls -> every pull request whose
    // branch contains this commit (open and closed alike, newest first).
    // Used to attach a build launched on a plain branch ref (not a
    // `pull/N` ref) to the pull request that branch belongs to, so such
    // builds still get PR parameters, tags and the summary comment.
    // Callers filter the result via `PrInfoCache.selectForCommit`.
    open fun listPrsForCommit(
        accessToken: String,
        repo: RepoCoords,
        sha: String,
        apiBase: String = DEFAULT_API_BASE,
    ): List<PrInfo> {
        val resp = request("GET", "$apiBase/repos/${repo.slug}/commits/$sha/pulls?per_page=100", accessToken)
            ?: return emptyList()
        return if (resp.isSuccess) {
            parsePrList(resp.body)
        } else {
            LOG.warn("GET commits/$sha/pulls returned ${resp.code} for ${repo.slug}: ${resp.body}")
            emptyList()
        }
    }

    // ----- GitHub App management (manifest creation + verification) -----

    // POST /app-manifests/{code}/conversions — exchanges the temporary
    // code from the App-manifest creation flow for the new App's id,
    // slug, private key (PEM) and webhook secret. No auth: the one-time
    // code IS the credential. The code is valid for ~1 hour.
    open fun convertManifest(code: String, apiBase: String = DEFAULT_API_BASE): ManifestConversion? {
        val resp = request("POST", "$apiBase/app-manifests/$code/conversions", null) ?: return null
        return if (resp.isSuccess) parseManifestConversion(resp.body) else {
            LOG.warn("POST /app-manifests/$code/conversions returned ${resp.code}: ${resp.body}")
            null
        }
    }

    // GET /app — the authenticated App's own record (slug, permissions,
    // subscribed events). Authenticated with an App JWT.
    open fun getApp(jwt: String, apiBase: String = DEFAULT_API_BASE): AppInfo? {
        val resp = request("GET", "$apiBase/app", jwt) ?: return null
        return if (resp.isSuccess) parseApp(resp.body) else {
            LOG.warn("GET /app returned ${resp.code}: ${resp.body}")
            null
        }
    }

    // ----- Issue comments (used by the optional PR summary comment) -----

    // GET /repos/{slug}/issues/{number}/comments (first page, 100). PRs
    // are issues for the comments API. Used to find our sticky comment.
    open fun listIssueComments(
        accessToken: String,
        repo: RepoCoords,
        number: Int,
        apiBase: String = DEFAULT_API_BASE,
    ): List<IssueComment> {
        val resp = request("GET", "$apiBase/repos/${repo.slug}/issues/$number/comments?per_page=100", accessToken)
            ?: return emptyList()
        return if (resp.isSuccess) parseIssueComments(resp.body) else {
            LOG.warn("GET issues/$number/comments returned ${resp.code} for ${repo.slug}: ${resp.body}")
            emptyList()
        }
    }

    open fun createIssueComment(
        accessToken: String,
        repo: RepoCoords,
        number: Int,
        body: String,
        apiBase: String = DEFAULT_API_BASE,
    ): Boolean {
        val payload = MAPPER.createObjectNode().put("body", body)
        val resp = request("POST", "$apiBase/repos/${repo.slug}/issues/$number/comments", accessToken, MAPPER.writeValueAsString(payload))
            ?: return false
        if (!resp.isSuccess) LOG.warn("POST issue comment returned ${resp.code} for ${repo.slug}#$number: ${resp.body}")
        return resp.isSuccess
    }

    // DELETE rather than PATCH: java.net.HttpURLConnection rejects the
    // PATCH method outright, so the sticky comment is refreshed by
    // delete-then-create instead of an in-place edit.
    open fun deleteIssueComment(
        accessToken: String,
        repo: RepoCoords,
        commentId: Long,
        apiBase: String = DEFAULT_API_BASE,
    ): Boolean {
        val resp = request("DELETE", "$apiBase/repos/${repo.slug}/issues/comments/$commentId", accessToken)
            ?: return false
        if (!resp.isSuccess) LOG.warn("DELETE issue comment $commentId returned ${resp.code} for ${repo.slug}: ${resp.body}")
        return resp.isSuccess
    }

    // ----- App-level endpoints (used by AppTokenMinter) -----
    //
    // Both endpoints authenticate with a JWT signed by the App's
    // private key (not an installation token). The caller passes the
    // ready-made JWT string.

    // GET /app/installations -> list every installation of the App
    // (one per org/user the App is installed on).
    open fun listInstallations(jwt: String, apiBase: String = DEFAULT_API_BASE): List<InstallationInfo> {
        val resp = request("GET", "$apiBase/app/installations", jwt) ?: return emptyList()
        return if (resp.isSuccess) {
            parseInstallations(resp.body)
        } else {
            LOG.warn("GET /app/installations returned ${resp.code}: ${resp.body}")
            emptyList()
        }
    }

    // POST /app/installations/{id}/access_tokens -> mint a fresh ghs_*
    // installation token. Optional body restricts the token to a subset
    // of repos; we pass null to inherit the App's full installation
    // scope.
    open fun createInstallationToken(
        jwt: String,
        installationId: Long,
        apiBase: String = DEFAULT_API_BASE,
        repositoryNames: List<String>? = null,
    ): CreatedToken? {
        val body = if (repositoryNames.isNullOrEmpty()) {
            "{}"
        } else {
            val root = MAPPER.createObjectNode()
            val arr = root.putArray("repositories")
            repositoryNames.forEach { arr.add(it) }
            MAPPER.writeValueAsString(root)
        }
        val resp = request("POST", "$apiBase/app/installations/$installationId/access_tokens", jwt, body)
            ?: return null
        return if (resp.isSuccess) {
            parseCreatedToken(resp.body)
        } else {
            LOG.warn("POST /app/installations/$installationId/access_tokens returned ${resp.code}: ${resp.body}")
            null
        }
    }

    // Retry wrapper around executeOnce. Retries transport failures, 5xx,
    // and rate-limit exhaustion up to maxAttempts, with exponential
    // backoff that honours a Retry-After header when GitHub sends one.
    // Returns null only when every attempt failed at the transport layer.
    protected open fun request(
        method: String,
        urlSpec: String,
        token: String?,
        jsonBody: String? = null,
    ): HttpResponse? {
        var attempt = 1
        while (true) {
            val resp = executeOnce(method, urlSpec, token, jsonBody)
            if (attempt >= maxAttempts || !isRetryable(resp)) return resp
            val delay = backoffMs(attempt, resp)
            val why = resp?.let { "HTTP ${it.code}" } ?: "transport error"
            LOG.warn("GitHub $method $urlSpec: $why (attempt $attempt/$maxAttempts); retrying in ${delay}ms")
            sleeper(delay)
            attempt++
        }
    }

    private fun isRetryable(resp: HttpResponse?): Boolean {
        if (resp == null) return true // transport error (IOException etc.)
        if (resp.code in 500..599) return true
        // Primary/secondary rate limit: GitHub returns 403 or 429 with the
        // remaining budget at 0. A plain 403 (permissions) is NOT retried.
        if (resp.code == 403 || resp.code == 429) {
            return resp.header("X-RateLimit-Remaining")?.trim() == "0" ||
                resp.header("Retry-After") != null
        }
        return false
    }

    private fun backoffMs(attempt: Int, resp: HttpResponse?): Long {
        // Honour an explicit Retry-After (delta seconds) when present.
        resp?.header("Retry-After")?.trim()?.toLongOrNull()?.let {
            return (it * 1000L).coerceIn(0L, MAX_BACKOFF_MS)
        }
        // Otherwise exponential backoff: base * 2^(attempt-1), capped.
        return (baseDelayMs shl (attempt - 1)).coerceIn(0L, MAX_BACKOFF_MS)
    }

    // Single home for the HttpURLConnection boilerplate every endpoint
    // shared: common auth/accept/version headers, fixed timeouts,
    // optional JSON body, response/error stream draining, and guaranteed
    // disconnect. Returns null only when the call could not complete at
    // the transport layer (IOException etc.) — an HTTP error response
    // (4xx/5xx) still comes back as an HttpResponse so the retry layer
    // can inspect the status and headers.
    private fun executeOnce(
        method: String,
        urlSpec: String,
        token: String?,
        jsonBody: String? = null,
    ): HttpResponse? {
        val conn = (URL(urlSpec).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", ACCEPT_HEADER)
            setRequestProperty("X-GitHub-Api-Version", apiVersion)
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (jsonBody != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
        }
        return try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResponse(code = code, body = text, headers = conn.headerFields ?: emptyMap())
        } catch (e: Exception) {
            LOG.warn("HTTP $method $urlSpec failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(GitHubClient::class.java.name)
        private val MAPPER = ObjectMapper()

        const val DEFAULT_API_BASE: String = "https://api.github.com"
        const val DEFAULT_API_VERSION: String = "2022-11-28"
        const val ACCEPT_HEADER: String = "application/vnd.github+json"
        const val CONNECT_TIMEOUT_MS: Int = 5000
        const val READ_TIMEOUT_MS: Int = 10000

        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_BASE_DELAY_MS: Long = 500L
        // Upper bound on any single backoff sleep, so a hostile Retry-After
        // can't park a build-server thread for minutes.
        const val MAX_BACKOFF_MS: Long = 30_000L

        // Cap pagination of the PR-files endpoint (100/page).
        const val MAX_PR_FILES_PAGES: Int = 10

        // Public for testing — parses the `filename` of each element of
        // the pulls/{n}/files array.
        fun parsePrFileNames(json: String): List<String> {
            return try {
                val node = MAPPER.readTree(json)
                if (!node.isArray) return emptyList()
                node.mapNotNull { it.path("filename").asText("").takeIf { n -> n.isNotBlank() } }
            } catch (e: Exception) {
                LOG.warn("Failed parsing pulls/files response: ${e.message}")
                emptyList()
            }
        }

        // Convert the user-facing GitHub URL stored on a TC connection
        // (e.g. "https://github.com/", "https://github.acme.com/")
        // into the REST API base needed for /app/installations,
        // /repos/.../pulls etc.
        //
        // github.com -> api.github.com (separate API host).
        // Anything else (GHE) -> <host>/api/v3 on the same host.
        fun apiBaseFromGitHubUrl(gitHubUrl: String?): String {
            if (gitHubUrl.isNullOrBlank()) return DEFAULT_API_BASE
            val cleaned = gitHubUrl.trim().trimEnd('/')
            return try {
                val url = URL(cleaned)
                val host = url.host.lowercase()
                if (host == "github.com" || host == "api.github.com") {
                    DEFAULT_API_BASE
                } else {
                    val portPart = if (url.port > 0 && url.port != url.defaultPort) ":${url.port}" else ""
                    "${url.protocol}://${url.host}${portPart}/api/v3"
                }
            } catch (e: Exception) {
                DEFAULT_API_BASE
            }
        }

        // Public for testing — parses the array returned by
        // GET /app/installations.
        fun parseInstallations(json: String): List<InstallationInfo> {
            return try {
                val node = MAPPER.readTree(json)
                if (!node.isArray) return emptyList()
                node.mapNotNull { item ->
                    val id = item.path("id").asLong(-1L).takeIf { it >= 0L } ?: return@mapNotNull null
                    val owner = item.path("account").path("login").asText("").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val accountType = item.path("account").path("type").asText("")
                    InstallationInfo(id = id, accountLogin = owner, accountType = accountType)
                }
            } catch (e: Exception) {
                LOG.warn("Failed parsing /app/installations response: ${e.message}")
                emptyList()
            }
        }

        // Public for testing — parses the App-manifest conversion response.
        fun parseManifestConversion(json: String): ManifestConversion? {
            return try {
                val node = MAPPER.readTree(json)
                val id = node.path("id").asLong(-1L).takeIf { it >= 0L } ?: return null
                val pem = node.path("pem").asText("").takeIf { it.isNotBlank() } ?: return null
                ManifestConversion(
                    appId = id.toString(),
                    slug = node.path("slug").asText(""),
                    pem = pem,
                    webhookSecret = node.path("webhook_secret").asText("").takeIf { it.isNotBlank() },
                    htmlUrl = node.path("html_url").asText(""),
                )
            } catch (e: Exception) {
                LOG.warn("Failed parsing app-manifest conversion response: ${e.message}")
                null
            }
        }

        // Public for testing — parses GET /app (slug + permissions + events).
        fun parseApp(json: String): AppInfo? {
            return try {
                val node = MAPPER.readTree(json)
                val slug = node.path("slug").asText("").takeIf { it.isNotBlank() } ?: return null
                val perms = node.path("permissions").fields().asSequence()
                    .associate { (k, v) -> k to v.asText("") }
                val events = node.path("events").mapNotNull { it.asText("").takeIf { e -> e.isNotBlank() } }
                AppInfo(slug = slug, permissions = perms, events = events)
            } catch (e: Exception) {
                LOG.warn("Failed parsing GET /app response: ${e.message}")
                null
            }
        }

        // Public for testing — parses the issue-comments array.
        fun parseIssueComments(json: String): List<IssueComment> {
            return try {
                val node = MAPPER.readTree(json)
                if (!node.isArray) return emptyList()
                node.mapNotNull { item ->
                    val id = item.path("id").asLong(-1L).takeIf { it >= 0L } ?: return@mapNotNull null
                    IssueComment(id = id, body = item.path("body").asText(""))
                }
            } catch (e: Exception) {
                LOG.warn("Failed parsing issue comments response: ${e.message}")
                emptyList()
            }
        }

        // Public for testing — parses the object returned by
        // POST /app/installations/{id}/access_tokens.
        fun parseCreatedToken(json: String): CreatedToken? {
            return try {
                val node = MAPPER.readTree(json)
                val token = node.path("token").asText("").takeIf { it.isNotBlank() } ?: return null
                // Parse strictly. If GitHub omits/garbles `expires_at` we
                // surface that as a null expiry rather than fabricating one
                // hour off the wall clock — the caller (AppTokenMinter) owns
                // the conservative fallback against its injected clock.
                val expiresAt = node.path("expires_at").asText("").takeIf { it.isNotBlank() }?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                }
                CreatedToken(token = token, expiresAt = expiresAt)
            } catch (e: Exception) {
                LOG.warn("Failed parsing access_tokens response: ${e.message}")
                null
            }
        }

        fun parsePrInfo(json: String): PrInfo? {
            return try {
                val node = MAPPER.readTree(json)
                parsePrInfo(node)
            } catch (e: Exception) {
                LOG.warn("Failed to parse PR JSON: ${e.message}")
                null
            }
        }

        // Public for testing — parses an ARRAY of PR objects (the shape
        // returned by GET /commits/{sha}/pulls). Elements that don't parse
        // are dropped rather than failing the whole list.
        fun parsePrList(json: String): List<PrInfo> {
            return try {
                val node = MAPPER.readTree(json)
                if (!node.isArray) return emptyList()
                node.mapNotNull { parsePrInfo(it) }
            } catch (e: Exception) {
                LOG.warn("Failed parsing PR list response: ${e.message}")
                emptyList()
            }
        }

        fun parsePrInfo(node: JsonNode): PrInfo? {
            val number = node.path("number").asInt(-1).takeIf { it >= 0 } ?: return null
            val head = node.path("head")
            val base = node.path("base")
            return PrInfo(
                number = number,
                title = node.path("title").asText(""),
                author = node.path("user").path("login").asText(""),
                headRef = head.path("ref").asText(""),
                baseRef = base.path("ref").asText(""),
                headSha = head.path("sha").asText(""),
                draft = node.path("draft").asBoolean(false),
                state = node.path("state").asText("open"),
                body = node.path("body").asText(""),
                labels = node.path("labels").mapNotNull {
                    it.path("name").asText("").takeIf { n -> n.isNotBlank() }
                },
                headRepo = head.path("repo").path("full_name").asText(""),
            )
        }

        // Public for testing — verifies we build the exact JSON shape GitHub expects.
        // GitHub Check Runs API: when status != completed, the conclusion field
        // must NOT be sent. Otherwise it's required.
        fun encodeCheckRunPayload(request: CheckRunRequest): String {
            val root: ObjectNode = MAPPER.createObjectNode()
            root.put("name", request.name)
            root.put("head_sha", request.headSha)
            root.put("status", request.status.apiValue)
            if (request.status == CheckRunStatus.COMPLETED) {
                val conclusion = request.conclusion
                    ?: error("CheckRunRequest with status=COMPLETED must carry a conclusion")
                root.put("conclusion", conclusion.apiValue)
            }
            val output: ObjectNode = root.putObject("output")
            output.put("title", request.outputTitle)
            output.put("summary", request.outputSummary)
            request.outputText?.let { output.put("text", it) }
            request.detailsUrl?.let { root.put("details_url", it) }
            return MAPPER.writeValueAsString(root)
        }
    }
}

// Transport-level result of a single GitHub HTTP call. `body` holds the
// response text (the input stream on 2xx, the error stream otherwise);
// `headers` is the raw header map, used to read rate-limit signals.
data class HttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    val isSuccess: Boolean get() = code in 200..299

    // `name.equals(it.key, …)` (not `it.key.equals(name)`) because the
    // raw header map can carry a null key — Java's HttpURLConnection
    // stores the status line under one — and calling through `name`
    // tolerates it.
    fun header(name: String): String? =
        headers.entries.firstOrNull { name.equals(it.key, ignoreCase = true) }
            ?.value?.firstOrNull()
}

data class CheckRunRequest(
    val name: String,
    val headSha: String,
    val status: CheckRunStatus,
    val conclusion: CheckRunConclusion?,
    val outputTitle: String,
    val outputSummary: String,
    val detailsUrl: String? = null,
    // Optional Markdown detail body (GitHub `output.text`, max 65535
    // chars). Used to surface build-failure reasons in the PR's Checks
    // panel beyond the short summary line.
    val outputText: String? = null,
)

enum class CheckRunStatus(val apiValue: String) {
    QUEUED("queued"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
}

data class InstallationInfo(
    val id: Long,
    val accountLogin: String,
    val accountType: String = "",
)

data class IssueComment(
    val id: Long,
    val body: String,
)

// Result of the App-manifest conversion (the credentials of the App
// GitHub just created on the operator's behalf).
data class ManifestConversion(
    val appId: String,
    val slug: String,
    val pem: String,
    val webhookSecret: String?,
    val htmlUrl: String,
)

// The authenticated App's own record, used to verify it is configured
// with the permissions/events the plugin needs.
data class AppInfo(
    val slug: String,
    val permissions: Map<String, String>,
    val events: List<String>,
)

data class CreatedToken(
    val token: String,
    // null when GitHub's response carried no parseable `expires_at`;
    // AppTokenMinter then applies a conservative default lifetime.
    val expiresAt: Instant?,
)

enum class CheckRunConclusion(val apiValue: String) {
    SUCCESS("success"),
    FAILURE("failure"),
    NEUTRAL("neutral"),
    SKIPPED("skipped"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out"),
    ACTION_REQUIRED("action_required"),
}
