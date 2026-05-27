package io.github.dlachouette.teamcity.github.enrich

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
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

        val config = BridgeFeatureReader.read(buildType) ?: return

        // TokenResolver already logs the cause (rate-limited).
        val access = tokenResolver.resolveAccessToken(buildType.project, config.connectionId, config.repo) ?: return

        val pr = prInfoCache.get(config.repo, prNumber, access.token, access.apiBase)
        if (pr == null) {
            LOG.warn("Cannot fetch PR info for ${config.repo.slug}#$prNumber; skipping enrichment")
            return
        }

        val plan = computePlan(build.buildNumber, build.tags, pr)
        plan.newBuildNumber?.let { build.buildNumber = it }
        if (plan.tagsToAdd.isNotEmpty()) {
            build.setTags(build.tags + plan.tagsToAdd)
        }
        LOG.info("Enriched build ${buildType.externalId} #${build.buildId} for ${config.repo.slug}#$prNumber (draft=${pr.draft})")
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
