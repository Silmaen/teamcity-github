package io.github.dlachouette.teamcity.github.queue

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeGate
import io.github.dlachouette.teamcity.github.feature.GateDecision
import io.github.dlachouette.teamcity.github.report.DraftCheckRunReporter
import io.github.dlachouette.teamcity.github.report.SkipReason
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SQueuedBuild

// Removes queued builds that the gate says should be suppressed.
// Posts Skipped Check Runs for PR contexts (DRAFT_PR / BRANCH_FILTER).
// Stays silent for non-PR branch contexts (no Check Run there).
class DraftBuildQueueCleaner(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
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
        val branchName = promotion.branch?.name ?: return
        val buildType = promotion.buildType ?: return
        val config = BridgeFeatureReader.read(buildType) ?: return

        val isManual = queuedBuild.triggeredBy.isTriggeredByUser
        val isPr = branchName.startsWith("pull/")

        // Fetch PR info up-front if PR; the gate needs draft + headRef.
        val prDraft: Boolean?
        val prHeadRef: String?
        val prNumber: Int?
        var prTitle = ""
        var prBody = ""
        var prLabels = emptyList<String>()
        if (isPr) {
            prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return
            val access = tokenResolver.resolveAccessToken(buildType.project, config.connectionId, config.repo) ?: return
            val pr = prInfoCache.get(config.repo, prNumber, access.token, access.apiBase) ?: return
            prDraft = pr.draft
            prHeadRef = pr.headRef
            prTitle = pr.title
            prBody = pr.body
            prLabels = pr.labels
        } else {
            prNumber = null
            prDraft = null
            prHeadRef = null
        }

        val decision = BridgeGate.decide(config, branchName, prDraft, prHeadRef, isManual, prTitle, prBody, prLabels)
        if (decision == GateDecision.ALLOW) return

        val reason = when (decision) {
            GateDecision.SUPPRESS_HARD -> "BT excluded by GitHub Bridge feature (HARD)"
            GateDecision.SUPPRESS_DRAFT -> "PR is draft; this BuildType has triggerOnPrDraft=false"
            GateDecision.SUPPRESS_BRANCH_PR -> "PR source branch '$prHeadRef' is excluded by the BT's PR branch filter"
            GateDecision.SUPPRESS_BRANCH_NON_PR -> "Branch '$branchName' is excluded by the BT's branch filter"
            GateDecision.SUPPRESS_METADATA -> "PR metadata (title/body/labels) excluded by the BT's metadata filter"
            GateDecision.ALLOW -> error("unreachable")
        }

        try {
            queuedBuild.removeFromQueue(null, reason)
            LOG.info("Removed ${buildType.externalId} from queue ($branchName): $reason")
        } catch (e: Exception) {
            LOG.warn("removeFromQueue threw for ${buildType.externalId} ($branchName): ${e.message}. " +
                "The DraftAwareBuildFilter safety net will hold the build instead.", e)
            return
        }

        // Post the user-visible Skipped Check Run for PR contexts only.
        // SUPPRESS_HARD on PR is silent by design (the BT just doesn't
        // participate in PRs at all; a CR would be noise).
        if (prNumber != null) {
            val skipReason = when (decision) {
                GateDecision.SUPPRESS_DRAFT -> SkipReason.DRAFT_PR
                GateDecision.SUPPRESS_BRANCH_PR -> SkipReason.BRANCH_FILTER
                else -> null
            }
            if (skipReason != null) {
                val headSha = promotion.revisions.firstOrNull()?.revision.orEmpty()
                if (headSha.isNotBlank()) {
                    try {
                        draftCheckRunReporter.postSkippedCheckRun(
                            buildType = buildType,
                            config = config,
                            prNumber = prNumber,
                            headSha = headSha,
                            reason = skipReason,
                            headRef = prHeadRef,
                        )
                    } catch (e: Exception) {
                        LOG.warn("Failed posting Skipped (${skipReason.name}) for ${buildType.externalId}: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DraftBuildQueueCleaner::class.java.name)
    }
}
