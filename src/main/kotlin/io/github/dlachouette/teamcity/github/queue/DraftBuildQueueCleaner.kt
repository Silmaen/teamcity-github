package io.github.dlachouette.teamcity.github.queue

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureConfig
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeGate
import io.github.dlachouette.teamcity.github.feature.BridgeTrigger
import io.github.dlachouette.teamcity.github.feature.GateContext
import io.github.dlachouette.teamcity.github.feature.GateContextResolver
import io.github.dlachouette.teamcity.github.feature.GateDecision
import io.github.dlachouette.teamcity.github.report.DraftCheckRunReporter
import io.github.dlachouette.teamcity.github.report.SkipReason
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SQueuedBuild

// Removes queued builds the bridge is responsible for suppressing, and posts
// the terminal Check Run that replaces them (a "Skipped: …" row, or the
// republished success of a commit that already passed).
//
// What may be removed is deliberately narrow — see `QueueCleanupPolicy`:
// only an AUTOMATIC build excluded by one of the scope filters, or an
// automatic re-build of a commit that already passed. Anything a human, a
// schedule or a GitHub command started is left alone: "the bridge never
// removes a build it did not enqueue itself".
class DraftBuildQueueCleaner(
    buildServer: SBuildServer,
    private val gateContextResolver: GateContextResolver,
    private val checkRunReporter: DraftCheckRunReporter,
    private val serverSettings: BridgeServerSettings,
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
        if (!serverSettings.queueCleanupEnabled()) return
        val promotion = queuedBuild.buildPromotion
        // A personal build is outside queue dedup: the bridge did not enqueue
        // it, so it never takes it out either — neither on a scope filter nor
        // as a duplicate of an already-green commit. (It still resolves its
        // pull request and gets the PR parameters and tags, and it still
        // publishes nothing — those are separate rules; see PrBuildEnricher
        // and BuildStatusCheckRunPublisher.)
        if (promotion.isPersonal) return
        val buildType = promotion.buildType ?: return
        val config = BridgeFeatureReader.read(buildType) ?: return

        val ctx = gateContextResolver.resolve(promotion, config, queuedBuild.triggeredBy.isTriggeredByUser)
            ?: return
        val decision = BridgeGate.decide(
            config, ctx.branchName, ctx.prNumber, ctx.pr?.draft, ctx.pr?.headRef, ctx.trigger,
            ctx.pr?.title.orEmpty(), ctx.pr?.body.orEmpty(), ctx.pr?.labels.orEmpty(),
        )

        // An automatic re-build of a commit that already passed: drop it and
        // republish the success it would have reproduced.
        if (decision == GateDecision.ALLOW) {
            maybeReuseSuccess(queuedBuild, config, ctx)
            return
        }
        if (!QueueCleanupPolicy.removes(decision, ctx.trigger, cleanupEnabled = true)) return

        val reason = when (decision) {
            GateDecision.SUPPRESS_DRAFT -> "PR is draft; this BuildType has triggerOnPrDraft=false"
            GateDecision.SUPPRESS_BRANCH_PR -> "PR source branch '${ctx.pr?.headRef}' is excluded by the BT's PR branch filter"
            GateDecision.SUPPRESS_BRANCH_NON_PR -> "Branch '${ctx.branchName}' is excluded by the BT's branch filter"
            GateDecision.SUPPRESS_METADATA -> "PR metadata (title/body/labels) excluded by the BT's metadata filter"
            GateDecision.SUPPRESS_HARD, GateDecision.ALLOW -> error("unreachable")
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
            checkRunReporter.postSkippedCheckRun(
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

    // `skipIfCommitPassed`: this exact commit already passed in this build
    // configuration, so the queued build would only reproduce a known green.
    // Automatic triggers only — a manual Run, a GitHub command or a Re-run
    // means "do it again", and gets to.
    private fun maybeReuseSuccess(
        queuedBuild: SQueuedBuild,
        config: BridgeFeatureConfig,
        ctx: GateContext,
    ) {
        if (!config.skipIfCommitPassed || ctx.trigger != BridgeTrigger.AUTO) return
        val promotion = queuedBuild.buildPromotion
        val buildType = promotion.buildType ?: return
        val headSha = promotion.revisions.firstOrNull()?.revision?.takeIf { it.isNotBlank() } ?: return

        // Same build configuration, same commit, any ref: GitHub keys a Check
        // Run on (name, sha), so two refs of one commit are one row anyway.
        // A personal build is never that evidence: it passed on a patch that
        // is not in the repository, and republishing it would put a personal
        // build's result on the commit (see BuildStatusCheckRunPublisher —
        // personal builds publish nothing).
        val passed = buildType.history.asSequence()
            .take(HISTORY_SCAN_DEPTH)
            .firstOrNull { build ->
                build.buildId != promotion.associatedBuildId &&
                    !build.buildPromotion.isPersonal &&
                    build.canceledInfo == null &&
                    build.buildStatus.isSuccessful &&
                    build.revisions.any { it.revision == headSha }
            } ?: return

        try {
            queuedBuild.removeFromQueue(
                null,
                "teamcity-github-bridge: commit $headSha already passed in #${passed.buildNumber}",
            )
        } catch (e: Exception) {
            LOG.warn("removeFromQueue threw for ${buildType.externalId} (reuse of #${passed.buildNumber}): ${e.message}")
            return
        }
        LOG.info("Removed ${buildType.externalId} from queue (${ctx.branchName}): " +
            "commit $headSha already passed in #${passed.buildNumber}")
        checkRunReporter.postReusedSuccess(buildType, config, headSha, passed)
    }

    companion object {
        private val LOG = Logger.getInstance(DraftBuildQueueCleaner::class.java.name)

        // How far back to look for a successful build of the same commit.
        private const val HISTORY_SCAN_DEPTH: Int = 50
    }
}

// What the bridge is allowed to take out of the queue. Kept separate (and
// pure) because two sites must agree on it: the cleaner that removes, and the
// Check Run publisher that must not post a "Queued" row for a build about to
// disappear.
//
// SCOPE INVARIANT — do not weaken. Queue cleanup only ever touches a build
// configuration that carries the **GitHub Bridge integration** build feature
// (directly or inherited from a BuildType template) *and* whose project chain
// provides `repo` + `connectionId`. Every removal site therefore opens with
//
//     val config = BridgeFeatureReader.read(buildType) ?: return
//
// so a build configuration that has not opted in is untouchable, whatever the
// branch, the trigger or the settings. There are exactly four such sites: this
// cleaner, `DraftAwareBuildFilter` (which holds rather than removes), and in
// `PullRequestEventListener` both `cancelBuildsForClosedPr` and
// `cancelSupersededBuilds` (which stop running builds — see
// `ObsoleteBuildPolicy`).
//
// Publication (`publishChecks`) is deliberately NOT part of this: silencing a
// build configuration on GitHub does not change which of its automatic builds
// the bridge is allowed to drop.
object QueueCleanupPolicy {

    fun removes(decision: GateDecision, trigger: BridgeTrigger, cleanupEnabled: Boolean): Boolean {
        // Server-wide master switch: when off the bridge never removes nor
        // holds a build, whatever the gate says.
        if (!cleanupEnabled) return false
        return removesWhenEnabled(decision, trigger)
    }

    private fun removesWhenEnabled(decision: GateDecision, trigger: BridgeTrigger): Boolean = when (decision) {
        // A scope filter excluded this build. Only ever applied to automatic
        // builds: the gate already answers ALLOW for explicit ones.
        GateDecision.SUPPRESS_DRAFT,
        GateDecision.SUPPRESS_BRANCH_PR,
        GateDecision.SUPPRESS_BRANCH_NON_PR,
        GateDecision.SUPPRESS_METADATA -> trigger == BridgeTrigger.AUTO
        // "This build configuration is not part of that path" and the
        // project-level mute both mean the bridge does not *start* the build —
        // never that it deletes one somebody else started.
        GateDecision.SUPPRESS_HARD -> false
        GateDecision.ALLOW -> false
    }
}
