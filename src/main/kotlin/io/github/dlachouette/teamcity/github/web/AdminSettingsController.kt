package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.AppManager
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.config.PluginSettingsStorage
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

// POST endpoint that backs the secret-management form in
// AdminConsolePage. The form submits to /admin/bridge/saveSecret with
// fields:
//   action=set    + secret=<value>   -> set the secret
//   action=clear                     -> remove the stored value
// then redirects back to the admin page.
//
// Auth: only users with CHANGE_SERVER_SETTINGS permission can mutate;
// otherwise we return 403.
class AdminSettingsController(
    webManager: WebControllerManager,
    private val settingsStorage: PluginSettingsStorage,
    private val serverSettings: BridgeServerSettings,
    private val gitHubClient: GitHubClient,
    private val prInfoCache: PrInfoCache,
    private val appManager: AppManager,
) : BaseController() {

    init {
        webManager.registerController(PATH, this)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        if (request.method != "POST") {
            response.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
            response.setHeader("Allow", "POST")
            return null
        }

        val user: SUser? = SessionUser.getUser(request)
        if (user == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            return null
        }
        if (!user.isPermissionGrantedGlobally(Permission.CHANGE_SERVER_SETTINGS)) {
            LOG.warn("User ${user.username} attempted to mutate plugin settings without CHANGE_SERVER_SETTINGS")
            response.status = HttpServletResponse.SC_FORBIDDEN
            return null
        }

        val action = request.getParameter("action").orEmpty()
        val result = when (action) {
            "set" -> handleSet(request, user)
            "clear" -> handleClear(user)
            "saveSettings" -> handleSaveSettings(request, user)
            "setApiToken" -> handleSetApiToken(request, user)
            "clearApiToken" -> handleClearApiToken(user)
            "verifyApp" -> handleVerifyApp(request)
            else -> "error"
        }

        // Redirect back to the admin page (PRG pattern, avoids resubmit on refresh).
        val redirect = request.contextPath.trimEnd('/') + "/admin/admin.html?item=bridgeAdmin&tab=bridgeAdmin&bridgeResult=$result"
        return ModelAndView(RedirectView(redirect, true))
    }

    private fun handleSet(request: HttpServletRequest, user: SUser): String {
        val raw = request.getParameter("secret").orEmpty()
        return if (raw.isBlank()) {
            "blank"
        } else {
            try {
                settingsStorage.setSecret(raw)
                LOG.info("Webhook secret updated by ${user.username}")
                "saved"
            } catch (e: Exception) {
                LOG.warn("Failed to save webhook secret: ${e.message}", e)
                "error"
            }
        }
    }

    private fun handleClear(user: SUser): String {
        return try {
            settingsStorage.clearSecret()
            LOG.info("Webhook secret cleared by ${user.username}")
            "cleared"
        } catch (e: Exception) {
            LOG.warn("Failed to clear webhook secret: ${e.message}", e)
            "error"
        }
    }

    private fun handleSetApiToken(request: HttpServletRequest, user: SUser): String {
        val raw = request.getParameter("apiToken").orEmpty()
        if (raw.isBlank()) return "blank"
        return try {
            settingsStorage.set(BridgeServerSettings.KEY_API_TOKEN, raw)
            LOG.info("External API token updated by ${user.username}")
            "settingsSaved"
        } catch (e: Exception) {
            LOG.warn("Failed to save API token: ${e.message}", e)
            "error"
        }
    }

    private fun handleClearApiToken(user: SUser): String {
        return try {
            settingsStorage.set(BridgeServerSettings.KEY_API_TOKEN, "")
            LOG.info("External API token cleared by ${user.username}")
            "cleared"
        } catch (e: Exception) {
            LOG.warn("Failed to clear API token: ${e.message}", e)
            "error"
        }
    }

    // Persists the server-tuning / feature-flag form and re-applies the
    // per-operation values to the live beans so edits take effect without
    // a restart. Blank text fields clear the key (reverting to the default
    // or the legacy internal property).
    private fun handleSaveSettings(request: HttpServletRequest, user: SUser): String {
        return try {
            fun put(key: String, raw: String?) {
                settingsStorage.set(key, raw?.trim().orEmpty())
            }
            fun putBool(key: String, present: Boolean) {
                settingsStorage.set(key, present.toString())
            }
            put(BridgeServerSettings.KEY_API_BASE, request.getParameter("apiBase"))
            put(BridgeServerSettings.KEY_API_VERSION, request.getParameter("apiVersion"))
            put(BridgeServerSettings.KEY_TTL_SECONDS, request.getParameter("ttlSeconds"))
            put(BridgeServerSettings.KEY_STALE_GRACE_SECONDS, request.getParameter("staleGraceSeconds"))
            put(BridgeServerSettings.KEY_HTTP_MAX_ATTEMPTS, request.getParameter("httpMaxAttempts"))
            put(BridgeServerSettings.KEY_HTTP_BASE_DELAY_MS, request.getParameter("httpBaseDelayMs"))
            put(BridgeServerSettings.KEY_REPO_ALLOWLIST, request.getParameter("repoAllowlist"))
            put(BridgeServerSettings.KEY_COMMENT_ASSOCIATIONS, request.getParameter("commentAssociations"))
            // Checkboxes: present in the POST only when ticked.
            putBool(BridgeServerSettings.KEY_REPLAY_ENABLED, request.getParameter("replayEnabled") != null)
            putBool(BridgeServerSettings.KEY_DRY_RUN, request.getParameter("dryRun") != null)
            putBool(BridgeServerSettings.KEY_METRICS_ENABLED, request.getParameter("metricsEnabled") != null)
            putBool(BridgeServerSettings.KEY_LEGACY_ALIASES, request.getParameter("legacyAliases") != null)
            putBool(BridgeServerSettings.KEY_PR_COMMENT_ENABLED, request.getParameter("prComment") != null)
            putBool(BridgeServerSettings.KEY_BRANCH_PR_LOOKUP, request.getParameter("branchPrLookup") != null)
            putBool(BridgeServerSettings.KEY_RERUN_ONLY_FAILED, request.getParameter("rerunAllOnlyFailed") != null)

            serverSettings.applyTo(gitHubClient, prInfoCache)
            LOG.info("GitHub Bridge server settings updated by ${user.username}")
            "settingsSaved"
        } catch (e: Exception) {
            LOG.warn("Failed to save server settings: ${e.message}", e)
            "error"
        }
    }

    private fun handleVerifyApp(request: HttpServletRequest): String {
        val verification = appManager.verify()
            ?: return "appError" // no managed App configured
        request.getSession(true).setAttribute(VERIFY_SESSION_ATTR, verification)
        return "appVerified"
    }

    companion object {
        const val PATH: String = "/admin/bridge/saveSecret.html"
        const val VERIFY_SESSION_ATTR: String = "bridgeAppVerification"
        private val LOG = Logger.getInstance(AdminSettingsController::class.java.name)
    }
}
