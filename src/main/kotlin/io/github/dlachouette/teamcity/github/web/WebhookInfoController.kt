package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.config.LogPathResolver
import io.github.dlachouette.teamcity.github.config.WebhookConfig
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.web.openapi.PluginDescriptor
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
    private val pluginDescriptor: PluginDescriptor,
) : BaseController() {

    init {
        webManager.registerController(INFO_PATH, this)
        webManager.registerController(INFO_PATH_MARKDOWN, this)
        authInterceptor.addPathNotRequiringAuth(javaClass, INFO_PATH)
        authInterceptor.addPathNotRequiringAuth(javaClass, INFO_PATH_MARKDOWN)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val scheme = RequestUrlBuilder.resolvedScheme(request)
        val info = WebhookInfo(
            payloadUrl = RequestUrlBuilder.absoluteUrl(request, PluginWebhookController.WEBHOOK_PATH),
            contentType = "application/json",
            sslVerification = scheme == "https",
            recommendedEvents = WebhookEvents.RECOMMENDED,
            secretConfigured = webhookConfig.isSecretConfigured(),
            logFile = logPathResolver.expectedFile().absolutePath,
            logConfigured = logPathResolver.isConfigured(),
            // Nullable in the SDK; `/health` already defaults it the same way.
            pluginVersion = pluginDescriptor.pluginVersion ?: "unknown",
            teamcityVersion = buildServer.fullServerVersion,
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

    companion object {
        const val INFO_PATH: String = "/app/teamcity-github-bridge/info"
        const val INFO_PATH_MARKDOWN: String = "/app/teamcity-github-bridge/info.md"
    }
}
