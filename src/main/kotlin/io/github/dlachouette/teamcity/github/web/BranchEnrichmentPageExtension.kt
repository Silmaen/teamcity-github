package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PlaceId
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.SimplePageExtension
import javax.servlet.http.HttpServletRequest

// Injects a CSS+JS fragment into every TeamCity page footer so that
// the draft/ready tags placed on promotions by PrPromotionTagger are
// rendered with a coloured pill instead of TC's default grey chip.
//
// Why not a server-side BuildBranchInfoProvider? TeamCity 2026.1 does
// not expose one (verified via SDK introspection - see
// doc/roadmap.md Gap 2 spike). Pure client-side enrichment is the
// pragmatic alternative; the JS does not call the GitHub API at all,
// it only re-styles tags TC already renders.
class BranchEnrichmentPageExtension(
    pagePlaces: PagePlaces,
    pluginDescriptor: PluginDescriptor,
) : SimplePageExtension(
    pagePlaces,
    PlaceId.ALL_PAGES_FOOTER_PLUGIN_CONTAINER,
    EXTENSION_ID,
    pluginDescriptor.getPluginResourcesPath("display/tcghBranchEnrichment.jsp"),
) {

    init {
        register()
        LOG.info("Registered BranchEnrichmentPageExtension at ALL_PAGES_FOOTER_PLUGIN_CONTAINER")
    }

    override fun isAvailable(request: HttpServletRequest): Boolean = true

    companion object {
        const val EXTENSION_ID: String = "tcghBranchEnrichment"
        private val LOG = Logger.getInstance(BranchEnrichmentPageExtension::class.java.name)
    }
}
