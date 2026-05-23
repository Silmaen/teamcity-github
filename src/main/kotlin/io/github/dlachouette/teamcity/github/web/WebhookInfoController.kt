package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.config.WebhookConfig
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.springframework.web.servlet.ModelAndView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class WebhookInfoController(
    webManager: WebControllerManager,
    private val buildServer: SBuildServer,
    private val webhookConfig: WebhookConfig,
) : BaseController() {

    init {
        webManager.registerController(INFO_PATH, this)
        webManager.registerController(INFO_PATH_MARKDOWN, this)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val info = WebhookInfo(
            payloadUrl = absoluteWebhookUrl(request),
            contentType = "application/json",
            sslVerification = request.scheme == "https",
            recommendedEvents = WebhookEvents.RECOMMENDED,
            secretConfigured = webhookConfig.isSecretConfigured(),
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

    private fun absoluteWebhookUrl(request: HttpServletRequest): String {
        val scheme = request.scheme
        val host = request.serverName
        val port = request.serverPort
        val portPart = when {
            scheme == "http" && port == 80 -> ""
            scheme == "https" && port == 443 -> ""
            else -> ":$port"
        }
        val ctx = request.contextPath.trimEnd('/')
        return "$scheme://$host$portPart$ctx${PluginWebhookController.WEBHOOK_PATH}"
    }

    companion object {
        const val INFO_PATH: String = "/app/teamcity-github-bridge/info"
        const val INFO_PATH_MARKDOWN: String = "/app/teamcity-github-bridge/info.md"
    }
}
