package io.github.dlachouette.teamcity.github.web

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.enrich.PrBuildEnricher
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeRefs
import io.github.dlachouette.teamcity.github.report.safeUrl
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.SQueuedBuild
import jetbrains.buildServer.serverSide.WebLinks
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.project.ProjectTab
import javax.servlet.http.HttpServletRequest

// One row of the branch/PR view: a build (queued, running or finished) with
// both of its keys — the branch it ran on and the pull request it belongs
// to — so the same list answers "what happened on this branch?" and "what
// happened for PR #189?".
data class BridgeBuildRow(
    val buildTypeId: String,
    val buildTypeName: String,
    val branch: String,
    val prNumber: Int?,
    val state: String,
    // "ok" | "bad" | "pending" — drives the colour, no styling logic in the JSP.
    val level: String,
    val buildNumber: String,
    val url: String?,
    val artifactsUrl: String?,
    val draft: Boolean?,
    // Sort key: build start, or Long.MAX_VALUE for queued builds so they
    // stay on top of a newest-first list.
    val startedAt: Long,
)

// Project tab listing the bridge's builds with their branch AND their PR
// number (G12). One list, not two: a build on a `Feature/*` branch and a
// build on a `pull/N` ref are the same kind of row, and either key finds it.
//
// The PR number comes from the build's `pr-<n>` tag (placed by
// PrBuildEnricher, back-filled by the listener's retro-association) or from
// the ref itself — so rendering the page costs no GitHub API call.
class BridgeBuildsTab(
    pagePlaces: PagePlaces,
    projectManager: ProjectManager,
    pluginDescriptor: PluginDescriptor,
    private val webLinks: WebLinks,
    private val serverSettings: io.github.dlachouette.teamcity.github.config.BridgeServerSettings,
) : ProjectTab(TAB_ID, TAB_TITLE, pagePlaces, projectManager) {

    init {
        setIncludeUrl(pluginDescriptor.getPluginResourcesPath("project/bridgeBuilds.jsp"))
        register()
    }

    override fun fillModel(
        model: MutableMap<String, Any>,
        request: HttpServletRequest,
        project: SProject,
        user: SUser?,
    ) {
        val query = request.getParameter("q").orEmpty().trim()
        val sort = request.getParameter("sort").orEmpty().trim().ifEmpty { SORT_TIME }

        // Read once per render: the prefix is a server setting, not per row.
        val prTagPrefix = if (serverSettings.prTagEnabled()) serverSettings.prTagPrefix() else ""
        val rows = try {
            filterAndSort(collectRows(project, prTagPrefix), query, sort)
        } catch (e: Exception) {
            LOG.warn("Failed collecting GitHub Bridge builds for ${project.externalId}: ${e.message}", e)
            emptyList()
        }

        model["rows"] = rows
        model["query"] = query
        model["sort"] = sort
        model["historyDepth"] = HISTORY_DEPTH
    }

    // Only opted-in build configurations, and only the recent past: this page
    // is a live view, not an audit log.
    private fun collectRows(project: SProject, prTagPrefix: String): List<BridgeBuildRow> {
        val buildTypes = project.buildTypes.filter { BridgeFeatureReader.read(it) != null }
        if (buildTypes.isEmpty()) return emptyList()

        val rows = ArrayList<BridgeBuildRow>(buildTypes.size * HISTORY_DEPTH)
        buildTypes.forEach { bt ->
            bt.getQueuedBuilds(null).forEach { rows += queuedRow(bt, it, prTagPrefix) }
            bt.runningBuilds.forEach { rows += buildRow(bt, it, "Running", "pending", prTagPrefix) }
            bt.history.asSequence().take(HISTORY_DEPTH).forEach { build ->
                val level = when {
                    build.canceledInfo != null -> "pending"
                    build.buildStatus.isSuccessful -> "ok"
                    else -> "bad"
                }
                rows += buildRow(bt, build, build.buildStatus.text, level, prTagPrefix)
            }
        }
        return rows
    }

    private fun queuedRow(bt: SBuildType, queued: SQueuedBuild, prTagPrefix: String) = BridgeBuildRow(
        buildTypeId = bt.externalId,
        buildTypeName = bt.name,
        branch = queued.buildPromotion.branch?.name.orEmpty(),
        prNumber = prNumberOf(queued.buildPromotion.branch?.name, queued.buildPromotion.tags, prTagPrefix),
        state = "Queued",
        level = "pending",
        buildNumber = "",
        url = safeUrl { webLinks.getQueuedBuildUrl(queued) },
        artifactsUrl = null,
        draft = draftOf(queued.buildPromotion.tags),
        startedAt = Long.MAX_VALUE,
    )

    private fun buildRow(
        bt: SBuildType,
        build: SBuild,
        state: String,
        level: String,
        prTagPrefix: String,
    ) = BridgeBuildRow(
        buildTypeId = bt.externalId,
        buildTypeName = bt.name,
        branch = build.branch?.name.orEmpty(),
        prNumber = prNumberOf(build.branch?.name, build.tags, prTagPrefix),
        state = state,
        level = level,
        buildNumber = build.buildNumber,
        url = safeUrl { webLinks.getViewResultsUrl(build) },
        artifactsUrl = if (build.isArtifactsExists) safeUrl { webLinks.getViewArtifactsUrl(build) } else null,
        draft = draftOf(build.tags),
        startedAt = build.startDate.time,
    )

    companion object {
        private val LOG = Logger.getInstance(BridgeBuildsTab::class.java.name)

        const val TAB_ID: String = "bridgeBuilds"
        const val TAB_TITLE: String = "Branches & PRs"

        // Recent finished builds per build configuration. Deep enough to
        // cover a working day, shallow enough to render without paging.
        const val HISTORY_DEPTH: Int = 30

        const val SORT_TIME: String = "time"
        const val SORT_BRANCH: String = "branch"
        const val SORT_PR: String = "pr"

        // The PR a build belongs to: the PR tag when present (works for any
        // ref, including a plain branch), else the `pull/N` ref itself. An
        // empty prefix means PR tagging is off — the ref is all we have.
        fun prNumberOf(branchName: String?, tags: List<String>, prTagPrefix: String): Int? =
            tags.firstNotNullOfOrNull { PrBuildEnricher.prNumberFromTag(it, prTagPrefix) }
                ?: BridgeRefs.prNumberFromRef(branchName)

        fun draftOf(tags: List<String>): Boolean? = when {
            tags.contains(PrBuildEnricher.TAG_DRAFT) -> true
            tags.contains(PrBuildEnricher.TAG_READY) -> false
            else -> null
        }

        // Pure view logic, tested without the SDK.
        //
        // The query matches either key: a number (with or without '#') is a
        // PR number, anything else is a case-insensitive substring of the
        // branch name or of the build configuration name.
        fun filterAndSort(rows: List<BridgeBuildRow>, query: String, sort: String): List<BridgeBuildRow> {
            val filtered = if (query.isEmpty()) rows else {
                val asPr = query.removePrefix("#").toIntOrNull()
                val needle = query.lowercase()
                rows.filter { row ->
                    (asPr != null && row.prNumber == asPr) ||
                        row.branch.lowercase().contains(needle) ||
                        row.buildTypeName.lowercase().contains(needle)
                }
            }
            val newestFirst = compareByDescending<BridgeBuildRow> { it.startedAt }
            return when (sort) {
                // Branch A-Z, newest first inside a branch.
                SORT_BRANCH -> filtered.sortedWith(
                    compareBy<BridgeBuildRow> { it.branch.lowercase() }.then(newestFirst)
                )
                // Highest PR first (the ones being worked on), branch builds last.
                SORT_PR -> filtered.sortedWith(
                    compareByDescending<BridgeBuildRow> { it.prNumber ?: Int.MIN_VALUE }.then(newestFirst)
                )
                else -> filtered.sortedWith(newestFirst)
            }
        }
    }
}
