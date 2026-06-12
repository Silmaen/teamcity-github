package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.springframework.web.servlet.ModelAndView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

// Exposes the plugin's counters in Prometheus text format at
// /app/teamcity-github-bridge/metrics. Anonymous (same trust posture as
// /health and /info). Returns 404 when metrics are disabled in settings.
class MetricsController(
    webManager: WebControllerManager,
    authInterceptor: AuthorizationInterceptor,
    private val metrics: BridgeMetrics,
    private val serverSettings: BridgeServerSettings,
) : BaseController() {

    init {
        webManager.registerController(PATH, this)
        authInterceptor.addPathNotRequiringAuth(PATH)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        if (!serverSettings.metricsEnabled()) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            return null
        }
        response.contentType = "text/plain; version=0.0.4; charset=UTF-8"
        response.status = HttpServletResponse.SC_OK
        response.writer.write(metrics.renderPrometheus())
        return null
    }

    companion object {
        const val PATH: String = "/app/teamcity-github-bridge/metrics"
    }
}
