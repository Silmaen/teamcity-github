package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.AppManager
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

// Redirect target of the GitHub App-manifest creation flow. GitHub sends
// the operator's browser here with a one-time `code` (and the `state` we
// seeded on the admin page). We exchange the code for the new App's
// credentials and store them, then bounce back to the admin page.
//
// Requires a logged-in admin (the redirect lands in the operator's
// authenticated TeamCity session); the `state` check defends against a
// forged callback.
class AppManifestController(
    webManager: WebControllerManager,
    private val appManager: AppManager,
) : BaseController() {

    init {
        webManager.registerController(PATH, this)
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val user = SessionUser.getUser(request)
        if (user == null || !user.isPermissionGrantedGlobally(Permission.CHANGE_SERVER_SETTINGS)) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            return null
        }

        val expectedState = request.getSession(false)?.getAttribute(STATE_SESSION_ATTR) as? String
        val state = request.getParameter("state")
        if (expectedState == null || state == null || expectedState != state) {
            LOG.warn("App-manifest callback with missing/mismatched state (user=${user.username})")
            return redirect(request, "appError")
        }
        request.getSession(false)?.removeAttribute(STATE_SESSION_ATTR)

        val code = request.getParameter("code")
        if (code.isNullOrBlank()) return redirect(request, "appError")

        val conversion = try {
            appManager.storeConversion(code)
        } catch (e: Exception) {
            LOG.warn("App-manifest conversion failed: ${e.message}", e)
            null
        }
        return if (conversion != null) {
            LOG.info("Created managed GitHub App '${conversion.slug}' (#${conversion.appId}) for ${user.username}")
            redirect(request, "appCreated")
        } else {
            redirect(request, "appError")
        }
    }

    private fun redirect(request: HttpServletRequest, result: String): ModelAndView {
        val url = request.contextPath.trimEnd('/') +
            "/admin/admin.html?item=bridgeAdmin&tab=bridgeAdmin&bridgeResult=$result"
        return ModelAndView(RedirectView(url, true))
    }

    companion object {
        const val PATH: String = "/app/teamcity-github-bridge/app-callback"
        const val STATE_SESSION_ATTR: String = "bridgeAppState"
        private val LOG = Logger.getInstance(AppManifestController::class.java.name)
    }
}
