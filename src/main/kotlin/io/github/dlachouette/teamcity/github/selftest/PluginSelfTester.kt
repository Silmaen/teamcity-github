package io.github.dlachouette.teamcity.github.selftest

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.config.LogPathResolver
import io.github.dlachouette.teamcity.github.config.WebhookConfig
import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
import io.github.dlachouette.teamcity.github.web.SignatureVerifier
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SProject
import java.net.HttpURLConnection
import java.net.URL

// Runs a battery of end-to-end checks against the running plugin
// instance and returns a structured report. Invoked from the admin
// page's "Run tests" button.
//
// Tests are best-effort: a failed test is reported but never throws.
// The same test can return SKIP when its prerequisite is missing
// (e.g. token tests skip when no opt-in buildType exists).
class PluginSelfTester(
    private val webhookConfig: WebhookConfig,
    private val logPathResolver: LogPathResolver,
    private val tokenResolver: TokenResolver,
    private val gitHubClient: GitHubClient,
    private val projectManager: ProjectManager,
) {

    fun runAllTests(webhookUrl: String): List<TestResult> {
        val out = mutableListOf<TestResult>()
        out += testSecretConfigured()
        out += testDedicatedLog()
        val projects = collectOptedInProjects()
        out += testGitHubApiReachable(projects)
        out += testHmacRoundtrip()
        out += testWebhookSelfDelivery(webhookUrl)
        val tokenResults = mutableMapOf<String, ResolvedAccessForTest?>()
        out += testTokenResolutionForProjects(projects, tokenResults)
        out += testGitHubApiWithToken(projects, tokenResults)
        return out
    }

    // Local pair-like holder so the self-tester doesn't have to know
    // about the production ResolvedAccess type's exact shape.
    private data class ResolvedAccessForTest(val token: String, val apiBase: String)

    // 1. Webhook secret configured (passive)
    private fun testSecretConfigured(): TestResult = if (webhookConfig.isSecretConfigured()) {
        TestResult("Webhook secret", Status.PASS, "configured (source: ${webhookConfig.source().name})")
    } else {
        TestResult(
            "Webhook secret", Status.FAIL,
            "Not configured. Set it in the form above or in TC internal.properties (teamcity.github.bridge.webhook.secret). Until set, every webhook delivery is rejected with 401.",
        )
    }

    // 2. Dedicated log file (passive)
    private fun testDedicatedLog(): TestResult {
        val configured = logPathResolver.isConfigured()
        val label = logPathResolver.stateLabel()
        return when {
            configured -> TestResult("Dedicated log file", Status.PASS, "$label at ${logPathResolver.expectedFile().absolutePath}")
            else -> TestResult("Dedicated log file", Status.WARN, "Not configured. Plugin entries will land in teamcity-server.log; this only affects discoverability, not correctness.")
        }
    }

    // 3. Network reachability to the GitHub API host. Any well-formed
    // HTTP response (including 401/403) means we crossed the TLS
    // handshake and the host is alive - that's the only thing this
    // test checks. Token-authenticated correctness lives in test #7.
    //
    // /zen is public on github.com but requires auth on GHE 3.x+
    // (returns 403 there). We therefore accept any < 500 response
    // as PASS - the goal is "host reachable", not "endpoint readable".
    private fun testGitHubApiReachable(targets: List<OptedInTarget>): TestResult {
        val apiBase = firstApiBase(targets) ?: GitHubClient.DEFAULT_API_BASE
        val url = URL("$apiBase/zen")
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            try {
                val code = conn.responseCode
                when {
                    code == 200 -> TestResult("GitHub API reachable", Status.PASS, "GET $apiBase/zen returned 200")
                    code in 400..499 -> TestResult(
                        "GitHub API reachable", Status.PASS,
                        "GET $apiBase/zen returned $code (host reachable; this endpoint requires auth on GitHub Enterprise, which is fine - test #7 covers authenticated access)",
                    )
                    code in 300..399 -> TestResult(
                        "GitHub API reachable", Status.PASS,
                        "GET $apiBase/zen returned $code (host reachable, redirect not followed)",
                    )
                    else -> TestResult(
                        "GitHub API reachable", Status.WARN,
                        "GET $apiBase/zen returned $code. Host reachable but responding with a server-side error.",
                    )
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            TestResult(
                "GitHub API reachable", Status.FAIL,
                "Connection to $apiBase/zen failed: ${e.message}. Check the TC host can reach this GitHub instance.",
            )
        }
    }

    // Best-effort: pick the apiBase from the first connection that
    // resolves. Lets the GitHub-reachability test point at the right
    // host instead of api.github.com when the operator is on GHE.
    private fun firstApiBase(targets: List<OptedInTarget>): String? {
        return targets.asSequence()
            .mapNotNull { tokenResolver.computeApiBase(it.project, it.connectionId) }
            .firstOrNull()
    }

    // 4. HMAC verification roundtrip (uses the same code path as webhook verification)
    private fun testHmacRoundtrip(): TestResult {
        val secret = webhookConfig.secret()
            ?: return TestResult("HMAC roundtrip", Status.SKIP, "Skipped: secret not configured")
        val payload = "{\"selftest\":true}".toByteArray()
        val expected = "${SignatureVerifier.PREFIX}${SignatureVerifier.computeHmacSha256Hex(payload, secret)}"
        return if (SignatureVerifier.verify(payload, expected, secret)) {
            TestResult("HMAC roundtrip", Status.PASS, "Sign + verify with the configured secret matched")
        } else {
            TestResult("HMAC roundtrip", Status.FAIL, "Sign + verify mismatch (this should never happen)")
        }
    }

    // 5. Self-deliver a signed ping to our own webhook endpoint
    private fun testWebhookSelfDelivery(webhookUrl: String): TestResult {
        val secret = webhookConfig.secret()
            ?: return TestResult("Webhook self-delivery", Status.SKIP, "Skipped: secret not configured")
        val payload = "{\"zen\":\"selftest\"}".toByteArray()
        val sig = "${SignatureVerifier.PREFIX}${SignatureVerifier.computeHmacSha256Hex(payload, secret)}"
        return try {
            val conn = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-GitHub-Event", "ping")
                setRequestProperty("X-Hub-Signature-256", sig)
                setRequestProperty("User-Agent", "teamcity-github-bridge-selftest")
                connectTimeout = 5000
                readTimeout = 5000
            }
            conn.outputStream.use { it.write(payload) }
            try {
                val code = conn.responseCode
                val body = runCatching { conn.inputStream.bufferedReader().readText() }.getOrNull().orEmpty()
                when {
                    code == 200 && body.trim() == "pong" -> TestResult("Webhook self-delivery", Status.PASS, "POST $webhookUrl -> 200 pong")
                    code == 200 -> TestResult("Webhook self-delivery", Status.WARN, "POST $webhookUrl -> 200 but body was '$body' (expected 'pong')")
                    code == 401 -> TestResult("Webhook self-delivery", Status.FAIL, "POST $webhookUrl -> 401. Either the controller is not registered anonymously or the secret used to sign differs from the one verified on the server side.")
                    else -> TestResult("Webhook self-delivery", Status.FAIL, "POST $webhookUrl -> $code")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            TestResult("Webhook self-delivery", Status.FAIL, "Could not POST to $webhookUrl: ${e.message}. The server may not be able to reach its own public URL; check DNS / firewall / reverse proxy.")
        }
    }

    // Collect every (project, connectionId, repo) tuple from opted-in
    // buildTypes. De-dup by (projectExternalId, connectionId, repo).
    private fun collectOptedInProjects(): List<OptedInTarget> {
        val seen = mutableSetOf<Triple<String, String, String>>()
        return projectManager.activeBuildTypes.asSequence()
            .mapNotNull { bt ->
                val params = bt.parameters
                val repo = params[DraftAwareBuildFilter.PARAM_REPO_SLUG]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val conn = params[DraftAwareBuildFilter.PARAM_CONNECTION_ID]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!seen.add(Triple(bt.project.externalId, conn, repo))) return@mapNotNull null
                OptedInTarget(bt.project, repo, conn)
            }
            .toList()
    }

    // 6. Token resolution for each opted-in project. Stores successful
    // access (token + apiBase) by project+conn key for test 7.
    private fun testTokenResolutionForProjects(
        targets: List<OptedInTarget>,
        tokenResults: MutableMap<String, ResolvedAccessForTest?>,
    ): List<TestResult> {
        if (targets.isEmpty()) {
            return listOf(TestResult("Token resolution", Status.SKIP, "No buildType has the two opt-in parameters (teamcity.github.bridge.repo + teamcity.github.bridge.connectionId)."))
        }
        return targets.map { target ->
            val name = "Token resolution / ${target.project.externalId} / ${target.repo}"
            val repo = try {
                RepoCoords.parse(target.repo)
            } catch (e: IllegalArgumentException) {
                tokenResults[target.key()] = null
                return@map TestResult(
                    name, Status.FAIL,
                    "teamcity.github.bridge.repo='${target.repo}' is not a valid owner/name slug.",
                )
            }
            val access = try {
                tokenResolver.resolveAccessToken(target.project, target.connectionId, repo)
            } catch (e: Exception) {
                null
            }
            tokenResults[target.key()] = access?.let { ResolvedAccessForTest(it.token, it.apiBase) }
            val apiBaseHint = tokenResolver.computeApiBase(target.project, target.connectionId)
                ?.let { " (apiBase=$it)" }
                ?: " (no GitHub URL on connection)"
            if (access != null) {
                TestResult(name, Status.PASS, "TC produced an installation token for connection ${target.connectionId}$apiBaseHint")
            } else {
                TestResult(
                    name, Status.FAIL,
                    "TC could not produce an installation token$apiBaseHint. See the dedicated log for the specific cause. " +
                        "Common causes: the App is not installed on '${target.repo}'s owner, the App's private key cannot be parsed, " +
                        "or the TC host cannot reach the GitHub API.",
                )
            }
        }
    }

    // 7. GitHub API auth roundtrip with the resolved token (calls /rate_limit
    // which is cheap and accessible with any valid token). The call
    // goes to the same apiBase the token was minted against, so GHE
    // installations are validated against their own host.
    private fun testGitHubApiWithToken(
        targets: List<OptedInTarget>,
        tokenResults: Map<String, ResolvedAccessForTest?>,
    ): List<TestResult> {
        if (targets.isEmpty()) return emptyList()
        return targets.map { target ->
            val access = tokenResults[target.key()]
            val name = "GitHub API auth / ${target.project.externalId} / ${target.repo}"
            if (access == null) {
                TestResult(name, Status.SKIP, "Skipped: token resolution failed")
            } else {
                callRateLimit(access.token, access.apiBase, name)
            }
        }
    }

    private fun callRateLimit(accessToken: String, apiBase: String, testName: String): TestResult {
        val url = URL("$apiBase/rate_limit")
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                connectTimeout = 5000
                readTimeout = 5000
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> TestResult(testName, Status.PASS, "GET /rate_limit returned 200; token accepted by GitHub")
                    401 -> TestResult(testName, Status.FAIL, "GET /rate_limit returned 401; the token TC produced is rejected by GitHub. Re-issue the connection token.")
                    403 -> TestResult(testName, Status.FAIL, "GET /rate_limit returned 403; the App permissions are likely too narrow.")
                    else -> TestResult(testName, Status.FAIL, "GET /rate_limit returned $code")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            TestResult(testName, Status.FAIL, "Could not call api.github.com/rate_limit: ${e.message}")
        }
    }

    private data class OptedInTarget(
        val project: SProject,
        val repo: String,
        val connectionId: String,
    ) {
        fun key(): String = "${project.externalId}|$connectionId|$repo"
    }

    companion object {
        private val LOG = Logger.getInstance(PluginSelfTester::class.java.name)
    }
}

data class TestResult(
    val name: String,
    val status: Status,
    val detail: String,
)

enum class Status {
    PASS, WARN, FAIL, SKIP;

    fun cssClass(): String = when (this) {
        PASS -> "bridge-test-pass"
        WARN -> "bridge-test-warn"
        FAIL -> "bridge-test-fail"
        SKIP -> "bridge-test-skip"
    }

    fun label(): String = when (this) {
        PASS -> "PASS"
        WARN -> "WARN"
        FAIL -> "FAIL"
        SKIP -> "SKIP"
    }
}
