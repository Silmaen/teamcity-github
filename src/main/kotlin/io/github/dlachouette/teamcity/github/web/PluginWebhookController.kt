package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.config.WebhookConfig
import io.github.dlachouette.teamcity.github.retrigger.ReadyForReviewListener
import io.github.dlachouette.teamcity.github.retrigger.ReadyForReviewPayload
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.springframework.web.servlet.ModelAndView
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
        val provided = request.getHeader(WebhookConfig.SIGNATURE_HEADER) ?: return false
        if (!provided.startsWith(WebhookConfig.SIGNATURE_PREFIX)) return false

        val expected = computeHmacSha256(payload, secret)
        val providedHex = provided.removePrefix(WebhookConfig.SIGNATURE_PREFIX)
        return constantTimeEquals(providedHex, expected)
    }

    private fun computeHmacSha256(payload: ByteArray, secret: String): String {
        val mac = Mac.getInstance(WebhookConfig.SIGNATURE_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), WebhookConfig.SIGNATURE_ALGORITHM))
        return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun handlePullRequest(payload: String) {
        val parsed = parseReadyForReview(payload) ?: return
        readyForReviewListener.handle(parsed)
    }

    private fun parseReadyForReview(payload: String): ReadyForReviewPayload? {
        return null
    }

    companion object {
        const val WEBHOOK_PATH: String = "/app/teamcity-github-bridge/webhook"
        private val LOG = Logger.getInstance(PluginWebhookController::class.java.name)
    }
}
