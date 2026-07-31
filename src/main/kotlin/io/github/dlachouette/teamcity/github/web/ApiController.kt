package io.github.dlachouette.teamcity.github.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.config.WebhookConfig
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.springframework.web.servlet.ModelAndView
import java.security.MessageDigest
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

// Authenticated HTTP API for external applications. Bearer-token auth
// (the token is set on the admin page; null = API disabled). The token
// is compared constant-time. Read endpoints expose status/events/metrics;
// the single write endpoint triggers a build.
//
//   GET  /app/teamcity-github-bridge/api/status   -> JSON snapshot
//   GET  /app/teamcity-github-bridge/api/events    -> recent webhook events
//   GET  /app/teamcity-github-bridge/api/metrics   -> counter snapshot (JSON)
//   POST /app/teamcity-github-bridge/api/trigger    -> enqueue a build
//                                                      body: {"buildTypeId","branch"}
class ApiController(
    webManager: WebControllerManager,
    authInterceptor: AuthorizationInterceptor,
    private val serverSettings: BridgeServerSettings,
    private val metrics: BridgeMetrics,
    private val recentEventsLog: RecentEventsLog,
    private val webhookConfig: WebhookConfig,
    private val pluginDescriptor: PluginDescriptor,
    private val pullRequestEventListener: PullRequestEventListener,
) : BaseController() {

    init {
        listOf(STATUS, EVENTS, METRICS, TRIGGER).forEach {
            webManager.registerController(it, this)
            // We do our own bearer-token auth; skip TeamCity's session auth.
            authInterceptor.addPathNotRequiringAuth(javaClass, it)
        }
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        response.contentType = "application/json; charset=UTF-8"

        if (!serverSettings.isApiEnabled()) {
            return json(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, mapOf("error" to "API disabled (no token configured)"))
        }
        if (!authorized(request)) {
            return json(response, HttpServletResponse.SC_UNAUTHORIZED, mapOf("error" to "invalid or missing bearer token"))
        }

        val path = request.requestURI.removeSuffix("/")
        return when {
            path.endsWith(STATUS) && request.method == "GET" -> handleStatus(response)
            path.endsWith(EVENTS) && request.method == "GET" -> handleEvents(response)
            path.endsWith(METRICS) && request.method == "GET" -> handleMetrics(response)
            path.endsWith(TRIGGER) && request.method == "POST" -> handleTrigger(request, response)
            else -> json(response, HttpServletResponse.SC_NOT_FOUND, mapOf("error" to "no such API route for ${request.method} $path"))
        }
    }

    private fun authorized(request: HttpServletRequest): Boolean {
        val expected = serverSettings.apiToken() ?: return false
        val header = request.getHeader("Authorization")?.trim() ?: return false
        val provided = header.removePrefix("Bearer ").trim().takeIf { header.startsWith("Bearer ") } ?: return false
        return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    private fun handleStatus(response: HttpServletResponse): ModelAndView? = json(
        response, HttpServletResponse.SC_OK,
        linkedMapOf(
            "pluginVersion" to (pluginDescriptor.pluginVersion ?: "unknown"),
            "secretConfigured" to webhookConfig.isSecretConfigured(),
            "dryRun" to serverSettings.dryRun(),
            "replayProtection" to serverSettings.replayProtectionEnabled(),
            "metricsEnabled" to serverSettings.metricsEnabled(),
            "repoAllowlist" to serverSettings.repoAllowlist(),
        ),
    )

    private fun handleEvents(response: HttpServletResponse): ModelAndView? {
        val events = recentEventsLog.snapshot().map {
            linkedMapOf(
                "timestampMs" to it.timestampMs,
                "event" to it.event,
                "repo" to it.repo,
                "action" to it.action,
                "httpStatus" to it.httpStatus,
                "outcome" to it.outcome.name,
                "detail" to it.detail,
            )
        }
        return json(response, HttpServletResponse.SC_OK, mapOf("events" to events))
    }

    private fun handleMetrics(response: HttpServletResponse): ModelAndView? =
        json(response, HttpServletResponse.SC_OK, metrics.snapshot())

    private fun handleTrigger(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val body = try {
            MAPPER.readTree(request.inputStream.readBytes())
        } catch (e: Exception) {
            return json(response, HttpServletResponse.SC_BAD_REQUEST, mapOf("error" to "invalid JSON body"))
        }
        val buildTypeId = body.path("buildTypeId").asText("").takeIf { it.isNotBlank() }
            ?: return json(response, HttpServletResponse.SC_BAD_REQUEST, mapOf("error" to "missing 'buildTypeId'"))
        val branch = body.path("branch").asText("").takeIf { it.isNotBlank() }
            ?: return json(response, HttpServletResponse.SC_BAD_REQUEST, mapOf("error" to "missing 'branch'"))

        val result = pullRequestEventListener.triggerBuild(buildTypeId, branch)
        LOG.info("External API trigger: $buildTypeId on $branch -> ${result.detail}")
        val status = if (result.queued) HttpServletResponse.SC_OK else HttpServletResponse.SC_CONFLICT
        return json(response, status, mapOf("queued" to result.queued, "detail" to result.detail))
    }

    private fun json(response: HttpServletResponse, status: Int, payload: Any): ModelAndView? {
        response.status = status
        response.writer.write(MAPPER.writeValueAsString(payload))
        return null
    }

    companion object {
        private const val BASE = "/app/teamcity-github-bridge/api"
        const val STATUS = "$BASE/status"
        const val EVENTS = "$BASE/events"
        const val METRICS = "$BASE/metrics"
        const val TRIGGER = "$BASE/trigger"
        private val MAPPER = ObjectMapper()
        private val LOG = Logger.getInstance(ApiController::class.java.name)
    }
}
