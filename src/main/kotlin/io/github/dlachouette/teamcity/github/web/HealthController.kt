package io.github.dlachouette.teamcity.github.web

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.config.LogPathResolver
import io.github.dlachouette.teamcity.github.config.WebhookConfig
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.springframework.web.servlet.ModelAndView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

// Machine-pollable liveness/readiness endpoint for load balancers and
// uptime monitors. Anonymous, GET-only, returns a small JSON snapshot.
// `status` is "ok" when the webhook secret is configured (the plugin can
// actually accept deliveries), "degraded" otherwise.
class HealthController(
    webManager: WebControllerManager,
    authInterceptor: AuthorizationInterceptor,
    private val webhookConfig: WebhookConfig,
    private val logPathResolver: LogPathResolver,
    private val serverSettings: BridgeServerSettings,
    private val pluginDescriptor: PluginDescriptor,
) : BaseController() {

    init {
        webManager.registerController(PATH, this)
        authInterceptor.addPathNotRequiringAuth(PATH)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val secretConfigured = webhookConfig.isSecretConfigured()
        val payload = linkedMapOf<String, Any>(
            "status" to if (secretConfigured) "ok" else "degraded",
            "pluginVersion" to (pluginDescriptor.pluginVersion ?: "unknown"),
            "secretConfigured" to secretConfigured,
            "logConfigured" to logPathResolver.isConfigured(),
            "dryRun" to serverSettings.dryRun(),
            "replayProtection" to serverSettings.replayProtectionEnabled(),
        )
        response.contentType = "application/json; charset=UTF-8"
        // 200 even when degraded: the endpoint itself is up. Monitors key
        // off the "status" field; a missing secret is an operator concern,
        // not a process-liveness failure.
        response.status = HttpServletResponse.SC_OK
        response.writer.write(MAPPER.writeValueAsString(payload))
        return null
    }

    companion object {
        const val PATH: String = "/app/teamcity-github-bridge/health"
        private val MAPPER = ObjectMapper()
    }
}
