package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
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
// doc/roadmap.md, "Blocked on JetBrains"). Pure client-side enrichment is the
// pragmatic alternative; the JS does not call the GitHub API at all,
// it only re-styles tags TC already renders.
//
// Styling only, deliberately. Linking a TeamCity page to the pull request
// was tried twice and dropped both times:
//
//   - on the tag pill: in TeamCity a tag IS a filter, the React pages bind
//     that behaviour by delegation on an ancestor (whose CAPTURE listener
//     runs before anything we can attach to the pill), and a re-render drops
//     attributes set on a node React owns;
//   - on the build page, via `PlaceId.BUILD_SUMMARY` / `BUILD_ACTIONS`: those
//     containers are only rendered by the CLASSIC build page, so on a 2026.1
//     server the link was invisible where people actually work.
//
// The pull request is reachable from GitHub's own Checks panel (every Check
// Run links back to its build), which is the direction that does work. Do not
// re-add a link here without a way to verify it renders on the React pages.
class BranchEnrichmentPageExtension(
    pagePlaces: PagePlaces,
    pluginDescriptor: PluginDescriptor,
    private val serverSettings: BridgeServerSettings,
) : SimplePageExtension(
    pagePlaces,
    PlaceId.ALL_PAGES_FOOTER_PLUGIN_CONTAINER,
    EXTENSION_ID,
    pluginDescriptor.getPluginResourcesPath("display/bridgeBranchEnrichment.jsp"),
) {

    init {
        register()
        LOG.info("Registered BranchEnrichmentPageExtension at ALL_PAGES_FOOTER_PLUGIN_CONTAINER")
    }

    override fun isAvailable(request: HttpServletRequest): Boolean = true

    // The PR tag prefix is admin-configurable (`prTag.prefix`, default `pr-`),
    // and the fragment needs it to tell a `pr-189` chip apart from a team's
    // own tags. It is handed to the JSP, which interpolates it into a JS
    // string literal — hence the sanitising.
    override fun fillModel(model: MutableMap<String, Any>, request: HttpServletRequest) {
        model[MODEL_PR_TAG_PREFIX] = sanitizeTagPrefix(serverSettings.prTagPrefix())
    }

    companion object {
        const val EXTENSION_ID: String = "bridgeBranchEnrichment"
        const val MODEL_PR_TAG_PREFIX: String = "bridgePrTagPrefix"

        private val LOG = Logger.getInstance(BranchEnrichmentPageExtension::class.java.name)

        // Keep only what a tag prefix can legitimately contain, so nothing
        // that would break out of a JS string literal (quote, backslash,
        // angle bracket, newline) can reach the page. An admin who types
        // something exotic loses the colour rather than the page; a prefix
        // that sanitises to empty disables the PR colouring, which the
        // fragment checks for.
        fun sanitizeTagPrefix(prefix: String): String =
            prefix.filter { it.isLetterOrDigit() || it in "-_./:" }
    }
}
