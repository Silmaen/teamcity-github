package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.selftest.PluginSelfTester
import io.github.dlachouette.teamcity.github.selftest.TestResult
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

// Runs the full self-test battery on POST and stashes the result list
// in the user's session. The admin page picks it up on the next render
// (PRG pattern). CSRF is enforced by TC's filter; the form must carry
// the tc-csrf-token field, which the admin JSP populates.
class AdminTestController(
    webManager: WebControllerManager,
    private val tester: PluginSelfTester,
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
        val user = SessionUser.getUser(request)
        if (user == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            return null
        }
        if (!user.isPermissionGrantedGlobally(Permission.CHANGE_SERVER_SETTINGS)) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            return null
        }

        val webhookUrl = absoluteWebhookUrl(request)
        val results: List<TestResult> = try {
            tester.runAllTests(webhookUrl)
        } catch (e: Exception) {
            LOG.warn("Self-test crashed: ${e.message}", e)
            return ModelAndView(RedirectView(redirectUrl(request, "error"), true))
        }

        request.getSession(true).setAttribute(SESSION_ATTR, results)
        LOG.info("Self-test run by ${user.username}: ${results.count { it.status.name == "PASS" }}/${results.size} PASS")
        return ModelAndView(RedirectView(redirectUrl(request, "tested"), true))
    }

    private fun redirectUrl(request: HttpServletRequest, marker: String): String {
        return request.contextPath.trimEnd('/') +
            "/admin/admin.html?item=bridgeAdmin&tab=bridgeAdmin&bridgeResult=$marker"
    }

    private fun absoluteWebhookUrl(request: HttpServletRequest): String {
        val scheme = request.getHeader("X-Forwarded-Proto")?.substringBefore(',')?.trim()?.lowercase()
            ?: request.scheme
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
        val ctx = request.contextPath.trimEnd('/')
        return "$scheme://$authority$ctx${PluginWebhookController.WEBHOOK_PATH}"
    }

    companion object {
        const val PATH: String = "/admin/bridge/runTests.html"
        const val SESSION_ATTR: String = "bridgeTestResults"
        private val LOG = Logger.getInstance(AdminTestController::class.java.name)
    }
}
