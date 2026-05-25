package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.config.LogPathResolver
import io.github.dlachouette.teamcity.github.config.WebhookConfig
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.springframework.web.servlet.ModelAndView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class WebhookInfoController(
    webManager: WebControllerManager,
    authInterceptor: AuthorizationInterceptor,
    private val buildServer: SBuildServer,
    private val webhookConfig: WebhookConfig,
    private val logPathResolver: LogPathResolver,
) : BaseController() {

    init {
        webManager.registerController(INFO_PATH, this)
        webManager.registerController(INFO_PATH_MARKDOWN, this)
        authInterceptor.addPathNotRequiringAuth(INFO_PATH)
        authInterceptor.addPathNotRequiringAuth(INFO_PATH_MARKDOWN)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val scheme = resolvedScheme(request)
        val info = WebhookInfo(
            payloadUrl = absoluteWebhookUrl(request, scheme),
            contentType = "application/json",
            sslVerification = scheme == "https",
            recommendedEvents = WebhookEvents.RECOMMENDED,
            secretConfigured = webhookConfig.isSecretConfigured(),
            logFile = logPathResolver.expectedFile().absolutePath,
            logConfigured = logPathResolver.isConfigured(),
            pluginVersion = buildServer.fullServerVersion,
        )

        return when (request.requestURI.removeSuffix("/")) {
            INFO_PATH -> {
                response.contentType = "application/json; charset=UTF-8"
                response.writer.write(info.toJson())
                null
            }
            INFO_PATH_MARKDOWN -> {
                response.contentType = "text/markdown; charset=UTF-8"
                response.writer.write(info.toMarkdown())
                null
            }
            else -> {
                response.status = HttpServletResponse.SC_NOT_FOUND
                null
            }
        }
    }

    private fun resolvedScheme(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-Proto")?.substringBefore(',')?.trim()
        return forwarded?.lowercase()?.takeIf { it.isNotBlank() } ?: request.scheme
    }

    private fun absoluteWebhookUrl(request: HttpServletRequest, scheme: String): String {
        val ctx = request.contextPath.trimEnd('/')
        val hostHeader = request.getHeader("X-Forwarded-Host")?.substringBefore(',')?.trim()
        val authority = if (!hostHeader.isNullOrBlank()) {
            hostHeader
        } else {
            val name = request.serverName
            val port = request.getHeader("X-Forwarded-Port")?.toIntOrNull() ?: request.serverPort
            val portPart = when {
                scheme == "http" && port == 80 -> ""
                scheme == "https" && port == 443 -> ""
                else -> ":$port"
            }
            "$name$portPart"
        }
        return "$scheme://$authority$ctx${PluginWebhookController.WEBHOOK_PATH}"
    }

    companion object {
        const val INFO_PATH: String = "/app/teamcity-github-bridge/info"
        const val INFO_PATH_MARKDOWN: String = "/app/teamcity-github-bridge/info.md"
    }
}
