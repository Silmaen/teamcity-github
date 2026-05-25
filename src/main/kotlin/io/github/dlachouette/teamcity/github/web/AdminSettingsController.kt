package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
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

    companion object {
        const val PATH: String = "/admin/bridge/saveSecret.html"
        private val LOG = Logger.getInstance(AdminSettingsController::class.java.name)
    }
}
