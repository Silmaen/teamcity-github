package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.PrInfoCache
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeTriggerMarker
import io.github.dlachouette.teamcity.github.report.checkRunName
import jetbrains.buildServer.serverSide.BuildPromotion
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.ViewBuildTab
import javax.servlet.http.HttpServletRequest

// A "Pull request" tab on the build page: what this build is judging, and a
// clickable link to it on GitHub.
//
// **Why a tab and not a line on the page.** The bridge carried everything one
// way — TeamCity's verdict reaches the pull request — and nothing back. Standing
// on a build page there was no way to open the pull request. The inline places
// the API offers for that (`PlaceId.BUILD_SUMMARY`, `BUILD_ACTIONS`,
// `BUILD_RESULTS_FRAGMENT`) render on the **classic** build page only, and
// `BUILD_RESULTS_BUILD_PROBLEM` hangs off a build problem, which a green build
// does not have. `BUILD_RESULTS_TAB` — this — is the one place on a build page
// the current UI renders. The SDK exposes no Sakura-specific place: searching
// every jar for the word finds nothing.
//
// The client-side overlay the plugin already ships was the tempting shortcut and
// is a trap: the modern UI is a single-page app, so navigating from one build to
// the next does not re-render the page footer. The injected link would keep
// pointing at the first build viewed, and a link to the wrong pull request is
// worse than no link.
//
// **Cost per render: zero calls.** Everything shown comes from the parameters
// the build already carries (`PrTabModel`), so opening a build page never talks
// to GitHub.
class BridgePrTab(
    pagePlaces: PagePlaces,
    server: SBuildServer,
    pluginDescriptor: PluginDescriptor,
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
    private val serverSettings: BridgeServerSettings,
) : ViewBuildTab(TAB_TITLE, TAB_ID, pagePlaces, server) {

    init {
        setIncludeUrl(pluginDescriptor.getPluginResourcesPath("buildTab/bridgePrTab.jsp"))
        register()
        LOG.info("Registered the '$TAB_TITLE' build tab ($TAB_ID)")
    }

    // Hidden — not empty — for a build that has no pull request. A tab that
    // renders "no pull request" on every build of `main` is noise on every build
    // page in the server.
    override fun isAvailable(request: HttpServletRequest, promotion: BuildPromotion): Boolean =
        modelFor(promotion) != null

    override fun fillModel(model: MutableMap<String, Any>, request: HttpServletRequest, promotion: BuildPromotion) {
        val pr = modelFor(promotion) ?: return
        model["pr"] = pr

        // The other half of the tab: not what the pull request is, but what the
        // bridge did with it. These are the questions a build page actually
        // raises — "was this reported to GitHub, and under what name?", "who
        // started it?" — and the answers are one field read each, all local.
        val buildType = promotion.buildType
        if (buildType != null) {
            model["checkRunName"] = checkRunName(buildType)
            val config = try {
                BridgeFeatureReader.read(buildType)
            } catch (e: Exception) {
                null
            }
            if (config != null) {
                model["publishes"] = config.publishChecks
                model["prBuildRef"] = config.prBuildRef.name.lowercase()
            }
            // The project's own list of bridge builds, filtered on this pull
            // request: the sibling builds of the same PR, one click away.
            model["siblingsUrl"] = "/project/${buildType.project.externalId}" +
                "?tab=${BridgeBuildsTab.TAB_ID}&q=%23${pr.number}"
        }

        // Set only on builds the bridge enqueued from an explicit GitHub-side
        // command (a PR comment, a review approval, a Re-run button, the external
        // API). Absent means "not one of those", which is the common case and
        // needs no line on the page.
        val trigger = paramOf(promotion, BridgeTriggerMarker.PARAM)
        if (!trigger.isNullOrBlank()) model["triggerSource"] = trigger

        addChangedFiles(model, promotion, pr.number)
    }

    // The file list is the one thing on this tab that does not come from the
    // build's own parameters, and it cannot: a parameter holding hundreds of
    // paths would reach every agent, every log and every screen that prints
    // parameters. It comes from the PR-info cache, where it was filled by the
    // same compare call that resolved the merge base — so on a warm cache this
    // costs nothing, and on a cold one it is one call, made only because a human
    // opened this tab.
    //
    // It is therefore the pull request **as it stands now**, not as the build saw
    // it, and the page says so. For "what did this build judge", the head commit
    // above is the answer.
    private fun addChangedFiles(model: MutableMap<String, Any>, promotion: BuildPromotion, prNumber: Int) {
        if (!serverSettings.prTabChangedFilesEnabled()) return
        val buildType = promotion.buildType ?: return
        try {
            val config = BridgeFeatureReader.read(buildType) ?: return
            val access = tokenResolver.resolveAccessToken(buildType.project, config.connectionId, config.repo) ?: return
            val pr = prInfoCache.get(config.repo, prNumber, access.token, access.apiBase) ?: return
            if (pr.changedFileNames.isEmpty()) return
            model["changedFiles"] = pr.changedFileNames.take(MAX_LISTED_FILES)
            model["changedFilesMore"] = (pr.changedFileNames.size - MAX_LISTED_FILES).coerceAtLeast(0)
            model["changedFilesTruncated"] = pr.changedFilesTruncated
        } catch (e: Exception) {
            // A build page must render with or without this.
            LOG.debug("Could not list the changed files of #$prNumber: ${e.message}")
        }
    }

    private fun paramOf(promotion: BuildPromotion, key: String): String? = try {
        promotion.associatedBuild?.parametersProvider?.get(key) ?: promotion.parameters[key]
    } catch (e: Exception) {
        null
    }

    // The build's resolved parameters, via the associated build when there is
    // one. A promotion still in the queue has not had them resolved yet, and its
    // page is transient anyway.
    private fun modelFor(promotion: BuildPromotion): PrTabModel? = try {
        val build = promotion.associatedBuild
        if (build != null) {
            PrTabModel.from { build.parametersProvider.get(it) }
        } else {
            // Fall back to whatever the promotion carries; enough for a queued
            // build that was enqueued with the parameters stamped on it.
            val params = promotion.parameters
            PrTabModel.from { params[it] }
        }
    } catch (e: Exception) {
        // A tab must never break a build page.
        LOG.debug("Could not build the PR tab model for promotion ${promotion.id}: ${e.message}")
        null
    }

    companion object {
        // Enough to read a change at a glance; beyond it, GitHub's own
        // "Files changed" is one click away and better at it.
        const val MAX_LISTED_FILES: Int = 100

        const val TAB_ID: String = "bridgePrTab"
        const val TAB_TITLE: String = "Pull request"

        private val LOG = Logger.getInstance(BridgePrTab::class.java.name)
    }
}
