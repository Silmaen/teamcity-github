package io.github.dlachouette.teamcity.github.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

open class GitHubClient {

    open fun getPr(
        accessToken: String,
        repo: RepoCoords,
        number: Int,
        apiBase: String = DEFAULT_API_BASE,
    ): PrInfo? {
        val url = URL("$apiBase/repos/${repo.slug}/pulls/$number")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", apiVersion)
            connectTimeout = 5000
            readTimeout = 10000
        }

        return try {
            val code = conn.responseCode
            when (code) {
                200 -> parsePrInfo(conn.inputStream.bufferedReader().readText())
                else -> {
                    LOG.warn("GitHub returned $code for ${repo.slug}#$number")
                    null
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed querying ${repo.slug}#$number: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    open fun postCheckRun(
        accessToken: String,
        repo: RepoCoords,
        request: CheckRunRequest,
        apiBase: String = DEFAULT_API_BASE,
    ): Boolean {
        val url = URL("$apiBase/repos/${repo.slug}/check-runs")
        val body = encodeCheckRunPayload(request)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", apiVersion)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 10000
            doOutput = true
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // 201 Created on success per GitHub Check Runs API.
            if (code in 200..299) {
                true
            } else {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                LOG.warn("GitHub Check Run POST returned $code for ${repo.slug}@${request.headSha}: $err")
                false
            }
        } catch (e: Exception) {
            LOG.warn("Failed POST check-run for ${repo.slug}@${request.headSha}: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    // ----- App-level endpoints (used by AppTokenMinter) -----
    //
    // Both endpoints authenticate with a JWT signed by the App's
    // private key (not an installation token). The caller passes the
    // ready-made JWT string.

    // GET /app/installations -> list every installation of the App
    // (one per org/user the App is installed on).
    open fun listInstallations(jwt: String, apiBase: String = DEFAULT_API_BASE): List<InstallationInfo> {
        val url = URL("$apiBase/app/installations")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $jwt")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", apiVersion)
            connectTimeout = 5000
            readTimeout = 10000
        }
        return try {
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                LOG.warn("GET /app/installations returned $code: $err")
                emptyList()
            } else {
                parseInstallations(conn.inputStream.bufferedReader().readText())
            }
        } catch (e: Exception) {
            LOG.warn("Failed listing installations: ${e.message}")
            emptyList()
        } finally {
            conn.disconnect()
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
        val url = URL("$apiBase/app/installations/$installationId/access_tokens")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $jwt")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", apiVersion)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 10000
            doOutput = true
        }
        val body = if (repositoryNames.isNullOrEmpty()) {
            "{}"
        } else {
            val root = MAPPER.createObjectNode()
            val arr = root.putArray("repositories")
            repositoryNames.forEach { arr.add(it) }
            MAPPER.writeValueAsString(root)
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                LOG.warn("POST /app/installations/$installationId/access_tokens returned $code: $err")
                null
            } else {
                parseCreatedToken(conn.inputStream.bufferedReader().readText())
            }
        } catch (e: Exception) {
            LOG.warn("Failed creating installation token for installation $installationId: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private val apiVersion: String = "2022-11-28"

    companion object {
        private val LOG = Logger.getInstance(GitHubClient::class.java.name)
        private val MAPPER = ObjectMapper()

        const val DEFAULT_API_BASE: String = "https://api.github.com"

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

        // Public for testing — parses the object returned by
        // POST /app/installations/{id}/access_tokens.
        fun parseCreatedToken(json: String): CreatedToken? {
            return try {
                val node = MAPPER.readTree(json)
                val token = node.path("token").asText("").takeIf { it.isNotBlank() } ?: return null
                val expiresAtText = node.path("expires_at").asText("")
                val expiresAt = expiresAtText.takeIf { it.isNotBlank() }?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                } ?: Instant.now().plusSeconds(3600)
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
            request.detailsUrl?.let { root.put("details_url", it) }
            return MAPPER.writeValueAsString(root)
        }
    }
}

data class CheckRunRequest(
    val name: String,
    val headSha: String,
    val status: CheckRunStatus,
    val conclusion: CheckRunConclusion?,
    val outputTitle: String,
    val outputSummary: String,
    val detailsUrl: String? = null,
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

data class CreatedToken(
    val token: String,
    val expiresAt: Instant,
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
