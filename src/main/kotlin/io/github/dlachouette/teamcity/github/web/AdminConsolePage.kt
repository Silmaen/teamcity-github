package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.config.LogPathResolver
import io.github.dlachouette.teamcity.github.config.WebhookConfig
import io.github.dlachouette.teamcity.github.selftest.TestResult
import jetbrains.buildServer.controllers.admin.AdminPage
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.web.CSRFFilter
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import javax.servlet.http.HttpServletRequest

class AdminConsolePage(
    pagePlaces: PagePlaces,
    private val pluginDescriptor: PluginDescriptor,
    private val buildServer: SBuildServer,
    private val webhookConfig: WebhookConfig,
    private val logPathResolver: LogPathResolver,
    private val recentEventsLog: RecentEventsLog,
) : AdminPage(
    pagePlaces,
    PAGE_ID,
    pluginDescriptor.getPluginResourcesPath("admin/bridgeAdmin.jsp"),
    TAB_TITLE,
) {

    init {
        register()
    }

    override fun getGroup(): String = SERVER_RELATED_GROUP

    override fun fillModel(model: MutableMap<String, Any>, request: HttpServletRequest) {
        val scheme = resolvedScheme(request)
        val webhookUrl = absoluteWebhookUrl(request, scheme)

        model["pluginVersion"] = pluginDescriptor.pluginVersion ?: "unknown"
        model["tcVersion"] = buildServer.fullServerVersion
        model["webhookUrl"] = webhookUrl
        model["infoUrl"] = absoluteUrl(request, scheme, WebhookInfoController.INFO_PATH)
        model["infoMarkdownUrl"] = absoluteUrl(request, scheme, WebhookInfoController.INFO_PATH_MARKDOWN)
        model["secretConfigured"] = webhookConfig.isSecretConfigured()
        model["secretSource"] = webhookConfig.source().name
        model["logFile"] = logPathResolver.expectedFile().absolutePath
        model["logConfigured"] = logPathResolver.isConfigured()
        model["logStateLabel"] = logPathResolver.stateLabel()
        model["recentEvents"] = recentEventsLog.snapshot().map { it.toView() }
        model["recommendedEvents"] = WebhookEvents.RECOMMENDED
        model["snippetResourceName"] = "teamcity-github-bridge-log4j-snippet.xml"
        model["saveSecretUrl"] = request.contextPath.trimEnd('/') + AdminSettingsController.PATH
        model["runTestsUrl"] = request.contextPath.trimEnd('/') + AdminTestController.PATH
        model["csrfToken"] = CSRFFilter.setSessionAttribute(request.getSession(true))
        model["csrfTokenName"] = CSRFFilter.ATTRIBUTE
        resultBannerFor(request.getParameter("bridgeResult"))?.let { model["resultBanner"] = it }

        // Pick up self-test results stashed by AdminTestController (PRG).
        val session = request.getSession(false)
        if (session != null) {
            val results = session.getAttribute(AdminTestController.SESSION_ATTR)
            if (results is List<*>) {
                model["testResults"] = results.mapNotNull { r ->
                    val tr = r as? TestResult ?: return@mapNotNull null
                    mapOf(
                        "name" to tr.name,
                        "status" to tr.status.label(),
                        "cssClass" to tr.status.cssClass(),
                        "detail" to tr.detail,
                    )
                }
                session.removeAttribute(AdminTestController.SESSION_ATTR)
            }
        }
    }

    private fun resultBannerFor(code: String?): Map<String, String>? = when (code) {
        "saved" -> mapOf("level" to "ok", "text" to "Webhook secret saved.")
        "cleared" -> mapOf("level" to "ok", "text" to "Webhook secret cleared. Until a new secret is set, every webhook delivery will be rejected with 401.")
        "blank" -> mapOf("level" to "warn", "text" to "Submitted secret was blank; nothing changed.")
        "tested" -> mapOf("level" to "ok", "text" to "Self-tests finished; results below.")
        "error" -> mapOf("level" to "bad", "text" to "Could not complete the operation. Check the dedicated log for details.")
        else -> null
    }

    private fun resolvedScheme(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-Proto")?.substringBefore(',')?.trim()
        return forwarded?.lowercase()?.takeIf { it.isNotBlank() } ?: request.scheme
    }

    private fun absoluteWebhookUrl(request: HttpServletRequest, scheme: String): String =
        absoluteUrl(request, scheme, PluginWebhookController.WEBHOOK_PATH)

    private fun absoluteUrl(request: HttpServletRequest, scheme: String, path: String): String {
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
        return "$scheme://$authority$ctx$path"
    }

    private fun RecentEvent.toView(): Map<String, String> = mapOf(
        "timestamp" to ISO_FORMATTER.get().format(Date(timestampMs)),
        "event" to event,
        "repo" to (repo ?: "-"),
        "action" to (action ?: "-"),
        "httpStatus" to httpStatus.toString(),
        "outcome" to outcome.displayName,
        "outcomeClass" to outcome.cssClass(),
        "detail" to (detail ?: ""),
    )

    private fun Outcome.cssClass(): String = when (this) {
        Outcome.ACCEPTED -> "bridge-accepted"
        Outcome.SKIPPED -> "bridge-skipped"
        Outcome.REJECTED -> "bridge-rejected"
    }

    companion object {
        const val PAGE_ID: String = "bridgeAdmin"
        const val TAB_TITLE: String = "GitHub Bridge"
        const val SERVER_RELATED_GROUP: String = "SERVER_RELATED_GROUP"

        private val ISO_FORMATTER: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").apply {
                timeZone = TimeZone.getDefault()
            }
        }
    }
}
