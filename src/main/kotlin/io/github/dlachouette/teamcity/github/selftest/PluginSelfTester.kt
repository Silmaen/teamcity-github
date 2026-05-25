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
        out += testGitHubApiReachable()
        out += testHmacRoundtrip()
        out += testWebhookSelfDelivery(webhookUrl)
        val projects = collectOptedInProjects()
        val tokenResults = mutableMapOf<String, String?>()
        out += testTokenResolutionForProjects(projects, tokenResults)
        out += testGitHubApiWithToken(projects, tokenResults)
        return out
    }

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

    // 3. GitHub API reachability without auth
    private fun testGitHubApiReachable(): TestResult {
        val url = URL("https://api.github.com/zen")
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> TestResult("GitHub API reachable", Status.PASS, "GET /zen returned 200")
                    else -> TestResult("GitHub API reachable", Status.FAIL, "GET /zen returned $code")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            TestResult("GitHub API reachable", Status.FAIL, "Connection failed: ${e.message}")
        }
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
    // tokens by project+conn key for test 7.
    private fun testTokenResolutionForProjects(
        targets: List<OptedInTarget>,
        tokenResults: MutableMap<String, String?>,
    ): List<TestResult> {
        if (targets.isEmpty()) {
            return listOf(TestResult("Token resolution", Status.SKIP, "No buildType has the two opt-in parameters (teamcity.github.bridge.repo + teamcity.github.bridge.connectionId)."))
        }
        return targets.map { target ->
            val token = try {
                tokenResolver.resolveAccessToken(target.project, target.connectionId)
            } catch (e: Exception) {
                null
            }
            tokenResults[target.key()] = token
            val name = "Token resolution / ${target.project.externalId} / ${target.repo}"
            if (token != null) {
                TestResult(name, Status.PASS, "TC produced an installation token for connection ${target.connectionId}")
            } else {
                TestResult(
                    name, Status.FAIL,
                    "TC could not produce an installation token. See the dedicated log for the specific cause. Most common: the GitHub App is not installed on ${target.repo}.",
                )
            }
        }
    }

    // 7. GitHub API auth roundtrip with the resolved token (calls /rate_limit
    // which is cheap and accessible with any valid token).
    private fun testGitHubApiWithToken(
        targets: List<OptedInTarget>,
        tokenResults: Map<String, String?>,
    ): List<TestResult> {
        if (targets.isEmpty()) return emptyList()
        return targets.map { target ->
            val token = tokenResults[target.key()]
            val name = "GitHub API auth / ${target.project.externalId} / ${target.repo}"
            if (token == null) {
                TestResult(name, Status.SKIP, "Skipped: token resolution failed")
            } else {
                callRateLimit(token, name)
            }
        }
    }

    private fun callRateLimit(accessToken: String, testName: String): TestResult {
        val url = URL("https://api.github.com/rate_limit")
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
