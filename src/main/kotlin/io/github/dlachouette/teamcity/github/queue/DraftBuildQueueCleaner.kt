package io.github.dlachouette.teamcity.github.queue

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeGate
import io.github.dlachouette.teamcity.github.feature.GateContextResolver
import io.github.dlachouette.teamcity.github.feature.GateDecision
import io.github.dlachouette.teamcity.github.report.DraftCheckRunReporter
import io.github.dlachouette.teamcity.github.report.SkipReason
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SQueuedBuild

// Removes queued builds that the gate says should be suppressed.
// Posts Skipped Check Runs for PR contexts (DRAFT_PR / BRANCH_FILTER /
// METADATA_FILTER). Stays silent for non-PR branch contexts (no Check Run
// there) and for HARD blocks.
//
// Builds the bridge enqueued from an explicit GitHub command (comment,
// approval, re-run, API) are classified COMMAND by `GateContextResolver`
// and therefore keep their SOFT filters bypassed here — otherwise this
// listener would undo every on-demand build the bridge just started.
class DraftBuildQueueCleaner(
    buildServer: SBuildServer,
    private val gateContextResolver: GateContextResolver,
    private val draftCheckRunReporter: DraftCheckRunReporter,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
    }

    override fun buildTypeAddedToQueue(queuedBuild: SQueuedBuild) {
        try {
            maybeRemove(queuedBuild)
        } catch (e: Exception) {
            LOG.warn("Queue cleanup failed for ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    private fun maybeRemove(queuedBuild: SQueuedBuild) {
        val promotion = queuedBuild.buildPromotion
        val buildType = promotion.buildType ?: return
        val config = BridgeFeatureReader.read(buildType) ?: return

        val ctx = gateContextResolver.resolve(promotion, config, queuedBuild.triggeredBy.isTriggeredByUser)
            ?: return
        val decision = BridgeGate.decide(
            config, ctx.branchName, ctx.prNumber, ctx.pr?.draft, ctx.pr?.headRef, ctx.trigger,
            ctx.pr?.title.orEmpty(), ctx.pr?.body.orEmpty(), ctx.pr?.labels.orEmpty(),
        )
        if (decision == GateDecision.ALLOW) return

        val reason = when (decision) {
            GateDecision.SUPPRESS_HARD -> "BT excluded by GitHub Bridge feature (HARD)"
            GateDecision.SUPPRESS_DRAFT -> "PR is draft; this BuildType has triggerOnPrDraft=false"
            GateDecision.SUPPRESS_BRANCH_PR -> "PR source branch '${ctx.pr?.headRef}' is excluded by the BT's PR branch filter"
            GateDecision.SUPPRESS_BRANCH_NON_PR -> "Branch '${ctx.branchName}' is excluded by the BT's branch filter"
            GateDecision.SUPPRESS_METADATA -> "PR metadata (title/body/labels) excluded by the BT's metadata filter"
            GateDecision.ALLOW -> error("unreachable")
        }

        try {
            queuedBuild.removeFromQueue(null, reason)
            LOG.info("Removed ${buildType.externalId} from queue (${ctx.branchName}): $reason")
        } catch (e: Exception) {
            LOG.warn("removeFromQueue threw for ${buildType.externalId} (${ctx.branchName}): ${e.message}. " +
                "The DraftAwareBuildFilter safety net will hold the build instead.", e)
            return
        }

        // Post the user-visible Skipped Check Run for PR contexts only.
        // SUPPRESS_HARD on PR is silent by design (the BT just doesn't
        // participate in PRs at all; a CR would be noise).
        val prNumber = ctx.prNumber ?: return
        val skipReason = when (decision) {
            GateDecision.SUPPRESS_DRAFT -> SkipReason.DRAFT_PR
            GateDecision.SUPPRESS_BRANCH_PR -> SkipReason.BRANCH_FILTER
            GateDecision.SUPPRESS_METADATA -> SkipReason.METADATA_FILTER
            else -> return
        }
        val headSha = promotion.revisions.firstOrNull()?.revision.orEmpty()
        if (headSha.isBlank()) return
        try {
            draftCheckRunReporter.postSkippedCheckRun(
                buildType = buildType,
                config = config,
                prNumber = prNumber,
                headSha = headSha,
                reason = skipReason,
                headRef = ctx.pr?.headRef,
            )
        } catch (e: Exception) {
            LOG.warn("Failed posting Skipped (${skipReason.name}) for ${buildType.externalId}: ${e.message}")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DraftBuildQueueCleaner::class.java.name)
    }
}
