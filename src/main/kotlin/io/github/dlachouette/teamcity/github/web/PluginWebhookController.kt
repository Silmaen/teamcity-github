package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.config.WebhookConfig
import io.github.dlachouette.teamcity.github.retrigger.ReadyForReviewListener
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.springframework.web.servlet.ModelAndView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class PluginWebhookController(
    webManager: WebControllerManager,
    private val webhookConfig: WebhookConfig,
    private val readyForReviewListener: ReadyForReviewListener,
) : BaseController() {

    init {
        webManager.registerController(WEBHOOK_PATH, this)
        LOG.info("Registered webhook controller at $WEBHOOK_PATH")
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val event = request.getHeader("X-GitHub-Event")
        if (event == null) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            response.writer.write("Missing X-GitHub-Event header")
            return null
        }

        val payload = request.inputStream.readBytes()

        if (!verifySignature(request, payload)) {
            LOG.warn("Webhook with invalid or missing signature rejected (event=$event)")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.writer.write("Invalid signature")
            return null
        }

        when (event) {
            "ping" -> {
                response.status = HttpServletResponse.SC_OK
                response.writer.write("pong")
            }
            "pull_request" -> {
                handlePullRequest(String(payload, Charsets.UTF_8))
                response.status = HttpServletResponse.SC_OK
            }
            else -> {
                LOG.debug("Ignoring unsupported event: $event")
                response.status = HttpServletResponse.SC_NO_CONTENT
            }
        }
        return null
    }

    private fun verifySignature(request: HttpServletRequest, payload: ByteArray): Boolean {
        val secret = webhookConfig.secret() ?: run {
            LOG.warn("Webhook secret is not configured (set internal property ${WebhookConfig.SECRET_PROPERTY}) - refusing request")
            return false
        }
        val provided = request.getHeader(WebhookConfig.SIGNATURE_HEADER)
        return SignatureVerifier.verify(payload, provided, secret)
    }

    private fun handlePullRequest(payload: String) {
        val parsed = WebhookPayloadParser.parseReadyForReview(payload) ?: return
        readyForReviewListener.handle(parsed)
    }

    companion object {
        const val WEBHOOK_PATH: String = "/app/teamcity-github-bridge/webhook"
        private val LOG = Logger.getInstance(PluginWebhookController::class.java.name)
    }
}
