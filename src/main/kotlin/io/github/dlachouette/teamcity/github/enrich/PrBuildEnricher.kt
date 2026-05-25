package io.github.dlachouette.teamcity.github.enrich

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SRunningBuild

class PrBuildEnricher(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
    }

    override fun buildStarted(build: SRunningBuild) {
        try {
            enrich(build)
        } catch (e: Exception) {
            LOG.warn("Failed to enrich build ${build.buildId}: ${e.message}", e)
        }
    }

    private fun enrich(build: SRunningBuild) {
        val branchName = build.branch?.name ?: return
        if (!branchName.startsWith("pull/")) return

        val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return
        val buildType = build.buildType ?: return

        val repoSlug = buildType.parameters[DraftAwareBuildFilter.PARAM_REPO_SLUG] ?: return
        val connectionId = buildType.parameters[DraftAwareBuildFilter.PARAM_CONNECTION_ID] ?: return

        val accessToken = tokenResolver.resolveAccessToken(buildType.project, connectionId)
        if (accessToken == null) {
            LOG.warn("Cannot resolve token for ${buildType.externalId}; skipping enrichment")
            return
        }

        val pr = prInfoCache.get(RepoCoords.parse(repoSlug), prNumber, accessToken)
        if (pr == null) {
            LOG.warn("Cannot fetch PR info for $repoSlug#$prNumber; skipping enrichment")
            return
        }

        val plan = computePlan(build.buildNumber, build.tags, pr)
        plan.newBuildNumber?.let { build.buildNumber = it }
        if (plan.tagsToAdd.isNotEmpty()) {
            build.setTags(build.tags + plan.tagsToAdd)
        }
        LOG.info("Enriched build ${buildType.externalId} #${build.buildId} for $repoSlug#$prNumber (draft=${pr.draft})")
    }

    companion object {
        private val LOG = Logger.getInstance(PrBuildEnricher::class.java.name)

        // Pure function — split out so it can be tested without TC SDK mocks.
        fun computePlan(
            currentBuildNumber: String?,
            currentTags: List<String>,
            pr: PrInfo,
        ): EnrichmentPlan {
            val stateTag = if (pr.draft) TAG_DRAFT else TAG_READY
            val tagsToAdd = if (currentTags.contains(stateTag)) emptyList() else listOf(stateTag)

            val newBuildNumber = currentBuildNumber
                ?.takeIf { it.isNotBlank() && pr.headRef.isNotBlank() && !it.contains(pr.headRef) }
                ?.let { "$it ${pr.headRef}" }

            return EnrichmentPlan(newBuildNumber, tagsToAdd)
        }

        const val TAG_DRAFT: String = "draft"
        const val TAG_READY: String = "ready"
    }
}

data class EnrichmentPlan(
    val newBuildNumber: String?,
    val tagsToAdd: List<String>,
)
