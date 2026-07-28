package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.feature.BridgeProjectParams
import io.github.dlachouette.teamcity.github.feature.PrBuildRef
import jetbrains.buildServer.controllers.admin.projects.EditProjectTab
import jetbrains.buildServer.controllers.admin.projects.GroupableEditProjectTab
import jetbrains.buildServer.web.CSRFFilter
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import javax.servlet.http.HttpServletRequest

// Project-administration tab ("Integrations" group) that gives the
// project-level parameters a real form, instead of making
// operators hand-edit raw configuration parameters under
// Administration -> <project> -> Parameters.
//
// Values are read effective (own + inherited) for display; the save
// controller writes them as OWN parameters on the project.
class BridgeProjectSettingsTab(
    pagePlaces: PagePlaces,
    pluginDescriptor: PluginDescriptor,
) : EditProjectTab(
    pagePlaces,
    TAB_ID,
    pluginDescriptor.getPluginResourcesPath("project/bridgeProjectSettings.jsp"),
    TAB_TITLE,
) {

    init {
        register()
    }

    override fun getEditProjectTabGroup(): String = GroupableEditProjectTab.INTEGRATIONS_GROUP

    override fun fillModel(model: MutableMap<String, Any>, request: HttpServletRequest) {
        val project = getProject(request) ?: return
        val params = project.parameters

        model["repo"] = params[BridgeProjectParams.REPO].orEmpty()
        model["connectionId"] = params[BridgeProjectParams.CONNECTION_ID].orEmpty()
        // Toggles default to enabled (param absent or != "false").
        model["branchTriggerEnabled"] = params[BridgeProjectParams.BRANCH_TRIGGER_ENABLED] != "false"
        model["branchTriggerBranches"] = params[BridgeProjectParams.BRANCH_TRIGGER_BRANCHES].orEmpty()
        model["prTriggerEnabled"] = params[BridgeProjectParams.PR_TRIGGER_ENABLED] != "false"
        model["prTriggerBranches"] = params[BridgeProjectParams.PR_TRIGGER_BRANCHES].orEmpty()
        model["prBuildRefBranch"] = PrBuildRef.parse(params[BridgeProjectParams.PR_BUILD_REF]) == PrBuildRef.BRANCH

        model["projectExternalId"] = project.externalId
        model["projectId"] = project.projectId
        model["saveUrl"] = request.contextPath.trimEnd('/') + BridgeProjectSettingsController.PATH
        model["csrfToken"] = CSRFFilter.setSessionAttribute(request.getSession(true))
        model["csrfTokenName"] = CSRFFilter.ATTRIBUTE
        bannerFor(request.getParameter("bridgeResult"))?.let { model["resultBanner"] = it }
    }

    private fun bannerFor(code: String?): Map<String, String>? = when (code) {
        "saved" -> mapOf("level" to "ok", "text" to "GitHub Bridge project settings saved.")
        "invalid" -> mapOf("level" to "bad", "text" to "Could not save: the repository must be in 'owner/name' form.")
        "missing" -> mapOf("level" to "bad", "text" to "Repository and connection ID are both required (use 'managed' or a TeamCity connection ID).")
        "error" -> mapOf("level" to "bad", "text" to "Could not save the project settings. Check the dedicated log for details.")
        else -> null
    }

    companion object {
        const val TAB_ID: String = "bridgeProjectSettings"
        const val TAB_TITLE: String = "GitHub Bridge"
    }
}
