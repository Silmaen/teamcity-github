package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.api.AppManager
import io.github.dlachouette.teamcity.github.api.AppVerification
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.config.LogPathResolver
import io.github.dlachouette.teamcity.github.config.WebhookConfig
import io.github.dlachouette.teamcity.github.config.TestResult
import java.util.UUID
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
    private val serverSettings: BridgeServerSettings,
    private val appManager: AppManager,
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
        val webhookUrl = RequestUrlBuilder.absoluteUrl(request, PluginWebhookController.WEBHOOK_PATH)
        model["pluginVersion"] = pluginDescriptor.pluginVersion ?: "unknown"
        model["tcVersion"] = buildServer.fullServerVersion
        model["webhookUrl"] = webhookUrl
        model["infoUrl"] = RequestUrlBuilder.absoluteUrl(request, WebhookInfoController.INFO_PATH)
        model["infoMarkdownUrl"] = RequestUrlBuilder.absoluteUrl(request, WebhookInfoController.INFO_PATH_MARKDOWN)
        model["secretConfigured"] = webhookConfig.isSecretConfigured()
        model["secretSource"] = webhookConfig.source().name
        model["logFile"] = logPathResolver.expectedFile().absolutePath
        model["logConfigured"] = logPathResolver.isConfigured()
        model["logStateLabel"] = logPathResolver.stateLabel()
        model["recentEvents"] = recentEventsLog.snapshot().map { it.toView() }
        model["recommendedEvents"] = WebhookEvents.RECOMMENDED
        model["snippetResourceName"] = "teamcity-github-bridge-log4j-snippet.xml"
        model["saveSecretUrl"] = request.contextPath.trimEnd('/') + AdminSettingsController.PATH
        model["saveSettingsUrl"] = request.contextPath.trimEnd('/') + AdminSettingsController.PATH
        model["runTestsUrl"] = request.contextPath.trimEnd('/') + AdminTestController.PATH

        // Current server-tuning / feature-flag values for the settings form.
        model["set_apiBase"] = serverSettings.apiBaseOverride().orEmpty()
        model["set_apiVersion"] = serverSettings.apiVersion()
        model["set_ttlSeconds"] = serverSettings.prInfoCacheTtlSeconds()
        model["set_staleGraceSeconds"] = serverSettings.prInfoStaleGraceSeconds()
        model["set_httpMaxAttempts"] = serverSettings.httpMaxAttempts()
        model["set_httpBaseDelayMs"] = serverSettings.httpBaseDelayMs()
        model["set_replayEnabled"] = serverSettings.replayProtectionEnabled()
        model["set_dryRun"] = serverSettings.dryRun()
        model["set_metricsEnabled"] = serverSettings.metricsEnabled()
        model["set_legacyAliases"] = serverSettings.legacyAliasesEnabled()
        model["set_prComment"] = serverSettings.prCommentEnabled()
        model["set_branchPrLookup"] = serverSettings.branchPrLookupEnabled()
        model["set_rerunAllOnlyFailed"] = serverSettings.rerunAllOnlyFailed()
        model["set_artifactLinks"] = serverSettings.artifactLinksEnabled()
        model["set_annotations"] = serverSettings.checkRunAnnotationsEnabled()
        model["set_testStats"] = serverSettings.checkRunTestStatsEnabled()
        model["set_timings"] = serverSettings.checkRunTimingsEnabled()
        model["set_infraNeutral"] = serverSettings.infraFailureNeutralEnabled()
        model["set_queueCleanup"] = serverSettings.queueCleanupEnabled()
        model["set_prTag"] = serverSettings.prTagEnabled()
        model["set_prTagPrefix"] = serverSettings.prTagPrefix()
        model["set_commentAssociations"] = serverSettings.commentTriggerAllowedAssociations().joinToString(",")
        model["apiTokenConfigured"] = serverSettings.isApiEnabled()

        // Managed GitHub App card.
        model["managedConnectionId"] = BridgeServerSettings.MANAGED_CONNECTION_ID
        model["managedAppConfigured"] = serverSettings.hasManagedApp()
        val slug = serverSettings.managedAppSlug().orEmpty()
        model["managedAppSlug"] = slug
        if (slug.isNotBlank()) {
            model["appSettingsUrl"] = "https://github.com/settings/apps/$slug"
            model["appInstallUrl"] = "https://github.com/apps/$slug/installations/new"
        }
        // App-manifest creation form: a fresh state (seeded into the session
        // for the callback to validate) and the manifest JSON.
        val callbackUrl = RequestUrlBuilder.absoluteUrl(request, AppManifestController.PATH)
        val state = UUID.randomUUID().toString()
        request.getSession(true).setAttribute(AppManifestController.STATE_SESSION_ATTR, state)
        model["appState"] = state
        model["appCallbackUrl"] = callbackUrl
        model["appManifestJson"] = appManager.buildManifest(
            name = "TeamCity GitHub Bridge (${request.serverName})",
            webhookUrl = webhookUrl,
            redirectUrl = callbackUrl,
        )

        // Verification result stashed by AdminSettingsController (PRG).
        val verifySession = request.getSession(false)
        (verifySession?.getAttribute(AdminSettingsController.VERIFY_SESSION_ATTR) as? AppVerification)?.let { v ->
            model["appVerification"] = mapOf(
                "ok" to v.ok,
                "reachable" to v.reachable,
                "slug" to (v.slug ?: ""),
                "missingPermissions" to v.missingPermissions,
                "missingEvents" to v.missingEvents,
                "detail" to v.detail,
            )
            verifySession.removeAttribute(AdminSettingsController.VERIFY_SESSION_ATTR)
        }
        model["set_repoAllowlist"] = serverSettings.repoAllowlist().joinToString("\n")
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
        "settingsSaved" -> mapOf("level" to "ok", "text" to "Server settings saved and applied (no restart needed).")
        "appCreated" -> mapOf("level" to "ok", "text" to "GitHub App created and its credentials stored. Install it on your org/repos, then set connectionId=managed on your build configurations.")
        "appVerified" -> mapOf("level" to "ok", "text" to "GitHub App verification finished; see the GitHub App card below.")
        "appError" -> mapOf("level" to "bad", "text" to "GitHub App operation failed. Check the dedicated log for details.")
        "error" -> mapOf("level" to "bad", "text" to "Could not complete the operation. Check the dedicated log for details.")
        else -> null
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
