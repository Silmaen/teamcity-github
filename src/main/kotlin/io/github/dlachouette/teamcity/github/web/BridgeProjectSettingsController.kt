package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.feature.BridgeProjectParams
import io.github.dlachouette.teamcity.github.feature.PrBuildRef
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SimpleParameter
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

// Backs the form in BridgeProjectSettingsTab. Writes the project-level
// parameters as OWN parameters of the target project and persists.
//
// Auth: the user must hold EDIT_PROJECT on the target project.
class BridgeProjectSettingsController(
    webManager: WebControllerManager,
    private val projectManager: ProjectManager,
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

        val externalId = request.getParameter("projectExternalId").orEmpty()
        val project = projectManager.findProjectByExternalId(externalId)
        if (project == null) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            return null
        }
        if (!user.isPermissionGrantedForProject(project.projectId, Permission.EDIT_PROJECT)) {
            LOG.warn("User ${user.username} attempted to edit GitHub Bridge settings of ${project.externalId} without EDIT_PROJECT")
            response.status = HttpServletResponse.SC_FORBIDDEN
            return null
        }

        val repo = request.getParameter("repo").orEmpty().trim()
        val connectionId = request.getParameter("connectionId").orEmpty().trim()
        // Both are mandatory and the config is useless without them; reject
        // a blank save with a clear message instead of silently clearing.
        if (repo.isEmpty() || connectionId.isEmpty()) {
            return redirect(request, project.projectId, "missing")
        }
        try {
            RepoCoords.parse(repo)
        } catch (e: IllegalArgumentException) {
            return redirect(request, project.projectId, "invalid")
        }

        val result = try {
            applyParam(project, BridgeProjectParams.REPO, repo)
            applyParam(project, BridgeProjectParams.CONNECTION_ID, connectionId)
            applyBool(project, BridgeProjectParams.BRANCH_TRIGGER_ENABLED, request.getParameter("branchTriggerEnabled") != null)
            applyParam(project, BridgeProjectParams.BRANCH_TRIGGER_BRANCHES, request.getParameter("branchTriggerBranches").orEmpty().trim())
            applyBool(project, BridgeProjectParams.PR_TRIGGER_ENABLED, request.getParameter("prTriggerEnabled") != null)
            applyParam(project, BridgeProjectParams.PR_TRIGGER_BRANCHES, request.getParameter("prTriggerBranches").orEmpty().trim())
            // Checkbox: ticked = branch-source builds, unticked = the
            // historical `pull/N` ref. Written explicitly (not cleared) so the
            // choice is visible in the project's parameter list.
            applyParam(
                project, BridgeProjectParams.PR_BUILD_REF,
                if (request.getParameter("prBuildRefBranch") != null) PrBuildRef.BRANCH.name.lowercase()
                else PrBuildRef.PULL.name.lowercase(),
            )
            // Written explicitly either way: this is the parameter a parent
            // project uses to hold annotations off for its whole subtree, so
            // "what does this project say" must be readable without guessing.
            applyBool(project, BridgeProjectParams.ANNOTATIONS_ENABLED, request.getParameter("annotationsEnabled") != null)
            project.persist()
            LOG.info("GitHub Bridge project settings for ${project.externalId} updated by ${user.username}")
            "saved"
        } catch (e: Exception) {
            LOG.warn("Failed saving GitHub Bridge project settings for ${project.externalId}: ${e.message}", e)
            "error"
        }
        return redirect(request, project.projectId, result)
    }

    // Set the OWN parameter, or remove it when the value is blank.
    private fun applyParam(project: jetbrains.buildServer.serverSide.SProject, key: String, value: String) {
        if (value.isBlank()) project.removeParameter(key)
        else project.addParameter(SimpleParameter(key, value))
    }

    // Toggle stored explicitly as "true"/"false" so the UI round-trips
    // unambiguously (absence would also read as enabled, but storing the
    // literal keeps the form state self-describing).
    private fun applyBool(project: jetbrains.buildServer.serverSide.SProject, key: String, enabled: Boolean) {
        project.addParameter(SimpleParameter(key, enabled.toString()))
    }

    private fun redirect(request: HttpServletRequest, projectId: String, result: String): ModelAndView {
        val url = request.contextPath.trimEnd('/') +
            "/admin/editProject.html?projectId=$projectId&tab=${BridgeProjectSettingsTab.TAB_ID}&bridgeResult=$result"
        return ModelAndView(RedirectView(url, true))
    }

    companion object {
        const val PATH: String = "/admin/bridge/saveProjectSettings.html"
        private val LOG = Logger.getInstance(BridgeProjectSettingsController::class.java.name)
    }
}
