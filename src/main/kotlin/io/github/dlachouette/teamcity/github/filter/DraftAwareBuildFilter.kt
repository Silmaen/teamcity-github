package io.github.dlachouette.teamcity.github.filter

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeGate
import io.github.dlachouette.teamcity.github.feature.GateDecision
import jetbrains.buildServer.BuildAgent
import jetbrains.buildServer.serverSide.BuildPromotion
import jetbrains.buildServer.serverSide.buildDistribution.BuildDistributorInput
import jetbrains.buildServer.serverSide.buildDistribution.QueuedBuildInfo
import jetbrains.buildServer.serverSide.buildDistribution.SimpleWaitReason
import jetbrains.buildServer.serverSide.buildDistribution.StartBuildPrecondition
import jetbrains.buildServer.serverSide.buildDistribution.WaitReason

// Safety net: if `DraftBuildQueueCleaner` failed to remove a build
// that the gate says should be suppressed, this filter holds it with
// a visible wait reason at agent-assignment time.
//
// Decision is delegated to `BridgeGate` so the listener, the
// cleaner, and this filter all agree on what a "suppressed" build is.
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
        val buildType = promotion.buildType ?: return null
        val config = BridgeFeatureReader.read(buildType) ?: return null

        val isManual = promotion.queuedBuild?.triggeredBy?.isTriggeredByUser == true
        val isPr = branchName.startsWith("pull/")

        // For PR builds we need draft state + headRef to feed the gate.
        // For non-PR builds, only the branch name matters.
        val prDraft: Boolean?
        val prHeadRef: String?
        if (isPr) {
            val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return null
            val access = tokenResolver.resolveAccessToken(buildType.project, config.connectionId, config.repo)
            val pr = access?.let { prInfoCache.get(config.repo, prNumber, it.token, it.apiBase) }
            if (pr == null) {
                LOG.warn("Cannot fetch PR info for ${config.repo.slug}#$prNumber; allowing build to proceed")
                return null
            }
            prDraft = pr.draft
            prHeadRef = pr.headRef
        } else {
            prDraft = null
            prHeadRef = null
        }

        return when (BridgeGate.decide(config, branchName, prDraft, prHeadRef, isManual)) {
            GateDecision.ALLOW -> null
            GateDecision.SUPPRESS_HARD -> {
                LOG.info("Holding ${buildType.externalId} on $branchName (HARD-blocked by GitHub Bridge feature)")
                SimpleWaitReason("Build excluded by the GitHub Bridge feature on this BuildType")
            }
            GateDecision.SUPPRESS_DRAFT -> {
                LOG.info("Holding draft-PR build of ${buildType.externalId} on $branchName")
                SimpleWaitReason("PR is draft and this BuildType has triggerOnPrDraft=false")
            }
            GateDecision.SUPPRESS_BRANCH_PR -> {
                LOG.info("Holding out-of-scope PR build of ${buildType.externalId} on $branchName (headRef=$prHeadRef)")
                SimpleWaitReason("PR source branch '$prHeadRef' is excluded by this BuildType's PR branch filter")
            }
            GateDecision.SUPPRESS_BRANCH_NON_PR -> {
                LOG.info("Holding out-of-scope non-PR build of ${buildType.externalId} on $branchName")
                SimpleWaitReason("Branch '$branchName' is excluded by this BuildType's branch filter")
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DraftAwareBuildFilter::class.java.name)
    }
}
