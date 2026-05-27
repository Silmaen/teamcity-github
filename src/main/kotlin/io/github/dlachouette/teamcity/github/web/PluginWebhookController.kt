package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.config.WebhookConfig
import io.github.dlachouette.teamcity.github.retrigger.PullRequestEventListener
import jetbrains.buildServer.controllers.AuthorizationInterceptor
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.springframework.web.servlet.ModelAndView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class PluginWebhookController(
    webManager: WebControllerManager,
    authInterceptor: AuthorizationInterceptor,
    private val webhookConfig: WebhookConfig,
    private val pullRequestEventListener: PullRequestEventListener,
    private val recentEventsLog: RecentEventsLog,
) : BaseController() {

    init {
        webManager.registerController(WEBHOOK_PATH, this)
        authInterceptor.addPathNotRequiringAuth(WEBHOOK_PATH)
        LOG.info("Registered webhook controller at $WEBHOOK_PATH (anonymous, HMAC verified)")
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val event = request.getHeader("X-GitHub-Event")
        if (event == null) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            response.writer.write("Missing X-GitHub-Event header")
            recentEventsLog.record(
                event = "(missing)", repo = null, action = null,
                httpStatus = HttpServletResponse.SC_BAD_REQUEST,
                outcome = Outcome.REJECTED, detail = "missing X-GitHub-Event header",
            )
            return null
        }

        val payload = request.inputStream.readBytes()

        if (!verifySignature(request, payload)) {
            LOG.warn("Webhook with invalid or missing signature rejected (event=$event)")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.writer.write("Invalid signature")
            recentEventsLog.record(
                event = event, repo = null, action = null,
                httpStatus = HttpServletResponse.SC_UNAUTHORIZED,
                outcome = Outcome.REJECTED, detail = "invalid or missing signature",
            )
            return null
        }

        when (event) {
            "ping" -> {
                response.status = HttpServletResponse.SC_OK
                response.writer.write("pong")
                recentEventsLog.record(event, null, null, HttpServletResponse.SC_OK, Outcome.ACCEPTED, "pong")
            }
            "pull_request" -> {
                val result = handlePullRequest(String(payload, Charsets.UTF_8))
                response.status = HttpServletResponse.SC_OK
                recentEventsLog.record(
                    event = event,
                    repo = result.repo,
                    action = result.action,
                    httpStatus = HttpServletResponse.SC_OK,
                    outcome = if (result.handled) Outcome.ACCEPTED else Outcome.SKIPPED,
                    detail = if (result.handled) "pull_request.${result.action} handled" else "action ignored",
                )
            }
            else -> {
                LOG.debug("Ignoring unsupported event: $event")
                response.status = HttpServletResponse.SC_NO_CONTENT
                recentEventsLog.record(event, null, null, HttpServletResponse.SC_NO_CONTENT, Outcome.SKIPPED, "unsupported event")
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

    private fun handlePullRequest(payload: String): HandledResult {
        val (action, repo) = WebhookPayloadParser.peekActionAndRepo(payload)
        val parsed = WebhookPayloadParser.parsePullRequestEvent(payload)
        return if (parsed != null) {
            pullRequestEventListener.handle(parsed)
            HandledResult(handled = true, action = action ?: parsed.action.value, repo = repo ?: parsed.repo.slug)
        } else {
            HandledResult(handled = false, action = action, repo = repo)
        }
    }

    private data class HandledResult(val handled: Boolean, val action: String?, val repo: String?)

    companion object {
        const val WEBHOOK_PATH: String = "/app/teamcity-github-bridge/webhook"
        private val LOG = Logger.getInstance(PluginWebhookController::class.java.name)
    }
}
