package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
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
    private val serverSettings: BridgeServerSettings,
    private val replayGuard: DeliveryReplayGuard,
    private val metrics: BridgeMetrics,
) : BaseController() {

    init {
        webManager.registerController(WEBHOOK_PATH, this)
        authInterceptor.addPathNotRequiringAuth(WEBHOOK_PATH)
        LOG.info("Registered webhook controller at $WEBHOOK_PATH (anonymous, HMAC verified)")
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val event = request.getHeader("X-GitHub-Event")
        metrics.inc(BridgeMetrics.WEBHOOKS_RECEIVED)
        if (event == null) {
            metrics.inc(BridgeMetrics.WEBHOOKS_REJECTED)
            response.status = HttpServletResponse.SC_BAD_REQUEST
            response.writer.write("Missing X-GitHub-Event header")
            recentEventsLog.record(
                event = "(missing)", repo = null, action = null,
                httpStatus = HttpServletResponse.SC_BAD_REQUEST,
                outcome = Outcome.REJECTED, detail = "missing X-GitHub-Event header",
            )
            return null
        }

        // Bound the read: this endpoint is anonymous and the HMAC is only
        // verified AFTER the body is in hand, so an unbounded read is a
        // cheap memory-exhaustion vector. GitHub caps webhook payloads at
        // 25 MB; reject anything larger before buffering it all.
        val declared = request.contentLength
        if (declared > MAX_PAYLOAD_BYTES) {
            rejectTooLarge(response, event, "declared content-length $declared")
            return null
        }
        val payload = readBounded(request.inputStream, MAX_PAYLOAD_BYTES)
        if (payload == null) {
            rejectTooLarge(response, event, "stream exceeded $MAX_PAYLOAD_BYTES bytes")
            return null
        }

        if (!verifySignature(request, payload)) {
            metrics.inc(BridgeMetrics.WEBHOOKS_REJECTED)
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

        // Replay protection: a redelivered payload carries the same
        // X-GitHub-Delivery id. Ack with 200 (so GitHub stops retrying)
        // but do not re-process. Signature is already verified above.
        val deliveryId = request.getHeader(DELIVERY_HEADER)
        if (serverSettings.replayProtectionEnabled() && deliveryId != null &&
            replayGuard.checkAndRecord(deliveryId)
        ) {
            metrics.inc(BridgeMetrics.WEBHOOKS_REPLAYED)
            LOG.info("Ignoring duplicate webhook delivery $deliveryId (event=$event)")
            response.status = HttpServletResponse.SC_OK
            response.writer.write("duplicate delivery ignored")
            recentEventsLog.record(
                event = event, repo = null, action = null,
                httpStatus = HttpServletResponse.SC_OK,
                outcome = Outcome.SKIPPED, detail = "duplicate delivery $deliveryId",
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
            "pull_request_review" -> {
                val parsed = WebhookPayloadParser.parseReviewApproved(String(payload, Charsets.UTF_8))
                if (parsed != null) pullRequestEventListener.handleReviewApproved(parsed)
                response.status = HttpServletResponse.SC_OK
                recentEventsLog.record(
                    event = event, repo = parsed?.repo?.slug, action = "approved",
                    httpStatus = HttpServletResponse.SC_OK,
                    outcome = if (parsed != null) Outcome.ACCEPTED else Outcome.SKIPPED,
                    detail = if (parsed != null) "approval handled" else "not an approval",
                )
            }
            "issue_comment" -> {
                val parsed = WebhookPayloadParser.parseIssueComment(String(payload, Charsets.UTF_8))
                if (parsed != null) pullRequestEventListener.handleCommentCommand(parsed)
                response.status = HttpServletResponse.SC_OK
                recentEventsLog.record(
                    event = event, repo = parsed?.repo?.slug, action = "created",
                    httpStatus = HttpServletResponse.SC_OK,
                    outcome = if (parsed != null) Outcome.ACCEPTED else Outcome.SKIPPED,
                    detail = if (parsed != null) "PR comment handled" else "not a PR comment",
                )
            }
            "check_run" -> {
                val parsed = WebhookPayloadParser.parseRerunRequest(String(payload, Charsets.UTF_8))
                if (parsed != null) pullRequestEventListener.handleRerun(parsed)
                response.status = HttpServletResponse.SC_OK
                recentEventsLog.record(
                    event = event, repo = parsed?.repo?.slug, action = "rerequested",
                    httpStatus = HttpServletResponse.SC_OK,
                    outcome = if (parsed != null) Outcome.ACCEPTED else Outcome.SKIPPED,
                    detail = if (parsed != null) "re-run handled" else "not a rerequest",
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

    private fun rejectTooLarge(response: HttpServletResponse, event: String, detail: String) {
        metrics.inc(BridgeMetrics.WEBHOOKS_TOO_LARGE)
        LOG.warn("Webhook payload rejected as too large (event=$event): $detail")
        response.status = SC_PAYLOAD_TOO_LARGE
        response.writer.write("Payload too large")
        recentEventsLog.record(
            event = event, repo = null, action = null,
            httpStatus = SC_PAYLOAD_TOO_LARGE,
            outcome = Outcome.REJECTED, detail = "payload too large ($detail)",
        )
    }

    // Read at most `max` bytes. Returns null if the stream carries more
    // (i.e. the limit was exceeded), so the caller can reject without
    // having buffered an unbounded amount.
    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > max) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
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
        const val DELIVERY_HEADER: String = "X-GitHub-Delivery"

        // GitHub's documented maximum webhook payload size.
        const val MAX_PAYLOAD_BYTES: Int = 25 * 1024 * 1024

        // javax.servlet has no named constant for 413.
        private const val SC_PAYLOAD_TOO_LARGE: Int = 413

        private val LOG = Logger.getInstance(PluginWebhookController::class.java.name)
    }
}
