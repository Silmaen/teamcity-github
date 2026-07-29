package io.github.dlachouette.teamcity.github.filter

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeGate
import io.github.dlachouette.teamcity.github.feature.GateContextResolver
import io.github.dlachouette.teamcity.github.feature.GateDecision
import io.github.dlachouette.teamcity.github.queue.QueueCleanupPolicy
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
// Decision is delegated to `BridgeGate`, through the same
// `GateContextResolver` the cleaner uses, so the listener, the cleaner and
// this filter always agree on what a "suppressed" build is.
class DraftAwareBuildFilter(
    private val gateContextResolver: GateContextResolver,
) : StartBuildPrecondition {

    override fun canStart(
        queuedBuild: QueuedBuildInfo,
        canBeStarted: Map<QueuedBuildInfo, BuildAgent>,
        buildDistributorInput: BuildDistributorInput,
        emulationMode: Boolean,
    ): WaitReason? {
        val promotion = queuedBuild.buildPromotionInfo as? BuildPromotion ?: return null
        val buildType = promotion.buildType ?: return null
        val config = BridgeFeatureReader.read(buildType) ?: return null

        val triggeredByUser = promotion.queuedBuild?.triggeredBy?.isTriggeredByUser == true
        val ctx = gateContextResolver.resolve(promotion, config, triggeredByUser) ?: return null
        val headRef = ctx.pr?.headRef

        val decision = BridgeGate.decide(
            config, ctx.branchName, ctx.prNumber, ctx.pr?.draft, headRef, ctx.trigger,
            ctx.pr?.title.orEmpty(), ctx.pr?.body.orEmpty(), ctx.pr?.labels.orEmpty(),
        )
        // Hold only what the cleaner would have removed: a build somebody
        // started explicitly must never be blocked here, and "this build
        // configuration is not part of that path" is not a reason to hold
        // anything either.
        if (!QueueCleanupPolicy.removes(decision, ctx.trigger)) return null

        return when (decision) {
            GateDecision.SUPPRESS_DRAFT -> {
                LOG.info("Holding draft-PR build of ${buildType.externalId} on ${ctx.branchName}")
                SimpleWaitReason("PR is draft and this BuildType has triggerOnPrDraft=false")
            }
            GateDecision.SUPPRESS_BRANCH_PR -> {
                LOG.info("Holding out-of-scope PR build of ${buildType.externalId} on ${ctx.branchName} (headRef=$headRef)")
                SimpleWaitReason("PR source branch '$headRef' is excluded by this BuildType's PR branch filter")
            }
            GateDecision.SUPPRESS_BRANCH_NON_PR -> {
                LOG.info("Holding out-of-scope non-PR build of ${buildType.externalId} on ${ctx.branchName}")
                SimpleWaitReason("Branch '${ctx.branchName}' is excluded by this BuildType's branch filter")
            }
            GateDecision.SUPPRESS_METADATA -> {
                LOG.info("Holding metadata-excluded PR build of ${buildType.externalId} on ${ctx.branchName}")
                SimpleWaitReason("PR title/body or labels are excluded by this BuildType's metadata filter")
            }
            GateDecision.ALLOW, GateDecision.SUPPRESS_HARD -> null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DraftAwareBuildFilter::class.java.name)
    }
}
