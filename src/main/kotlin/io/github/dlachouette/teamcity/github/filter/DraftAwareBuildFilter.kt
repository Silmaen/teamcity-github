package io.github.dlachouette.teamcity.github.filter

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import jetbrains.buildServer.BuildAgent
import jetbrains.buildServer.serverSide.BuildPromotion
import jetbrains.buildServer.serverSide.buildDistribution.BuildDistributorInput
import jetbrains.buildServer.serverSide.buildDistribution.QueuedBuildInfo
import jetbrains.buildServer.serverSide.buildDistribution.SimpleWaitReason
import jetbrains.buildServer.serverSide.buildDistribution.StartBuildPrecondition
import jetbrains.buildServer.serverSide.buildDistribution.WaitReason

class DraftAwareBuildFilter(
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
) : StartBuildPrecondition {

    override fun canStart(
        queuedBuild: QueuedBuildInfo,
        canBeStarted: Map<QueuedBuildInfo, BuildAgent>,
        buildDistributorInput: BuildDistributorInput,
        emulationMode: Boolean,
    ): WaitReason? {
        val promotion = queuedBuild.buildPromotionInfo as? BuildPromotion ?: return null
        val branchName = promotion.branch?.name ?: return null
        if (!branchName.startsWith("pull/")) return null

        val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return null
        val buildType = promotion.buildType ?: return null

        if (buildType.parameters[PARAM_IGNORE_DRAFTS] != "true") return null

        val repoSlug = buildType.parameters[PARAM_REPO_SLUG] ?: return null
        val connectionId = buildType.parameters[PARAM_CONNECTION_ID] ?: return null

        // TokenResolver logs the underlying cause (rate-limited); a
        // null return here is silent so we don't double-spam the log.
        val accessToken = tokenResolver.resolveAccessToken(buildType.project, connectionId) ?: return null

        val pr = prInfoCache.get(RepoCoords.parse(repoSlug), prNumber, accessToken)
        if (pr == null) {
            LOG.warn("Cannot fetch PR info for $repoSlug#$prNumber; allowing build to proceed")
            return null
        }

        return if (pr.draft) {
            LOG.info("Suppressing build of ${buildType.externalId} for draft PR $repoSlug#$prNumber")
            SimpleWaitReason("PR #$prNumber is draft and $PARAM_IGNORE_DRAFTS is enabled")
        } else {
            null
        }
    }

    companion object {
        const val PARAM_IGNORE_DRAFTS: String = "teamcity.github.bridge.ignoreDrafts"
        const val PARAM_REPO_SLUG: String = "teamcity.github.bridge.repo"
        const val PARAM_CONNECTION_ID: String = "teamcity.github.bridge.connectionId"

        private val LOG = Logger.getInstance(DraftAwareBuildFilter::class.java.name)
    }
}
