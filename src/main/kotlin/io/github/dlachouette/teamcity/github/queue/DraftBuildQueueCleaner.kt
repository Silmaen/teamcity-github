package io.github.dlachouette.teamcity.github.queue

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SQueuedBuild

// When a build for an opted-in PR buildType lands in the queue and
// the PR is in draft state, remove the build from the queue
// immediately. The "skipped" Check Run is still posted to GitHub by
// `DraftCheckRunReporter` and the draft/ready tag still ends up on
// the promotion via `PrPromotionTagger`; both listeners run on the
// same `buildTypeAddedToQueue` event.
//
// The build will be re-enqueued automatically by
// `ReadyForReviewListener` when the PR transitions to "ready for
// review" via the App-level webhook.
//
// Why remove instead of hold:
//   - Held builds accumulate in the queue and clutter the UI. With
//     5 opted-in build types and 3 draft PRs open you immediately
//     have 15 "held" entries; with the queue scheduler hitting
//     `canStart` each cycle the noise compounds.
//   - GitHub already surfaces the deliberate skip via the Check Run
//     ("Skipped: draft PR") so visibility is preserved.
//   - The retrigger flow is unchanged, so the user experience on
//     "ready for review" stays identical.
//
// `DraftAwareBuildFilter` remains in place as a safety net: if the
// cleaner fails (token outage, PR info unresolvable), the filter
// holds the build with a wait reason instead of letting a draft
// slip through to an agent.
class DraftBuildQueueCleaner(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
    }

    override fun buildTypeAddedToQueue(queuedBuild: SQueuedBuild) {
        try {
            maybeRemove(queuedBuild)
        } catch (e: Exception) {
            LOG.warn("Draft queue cleanup failed for ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    private fun maybeRemove(queuedBuild: SQueuedBuild) {
        val promotion = queuedBuild.buildPromotion
        val branchName = promotion.branch?.name ?: return
        val buildType = promotion.buildType ?: return
        val params = buildType.parameters

        if (params[DraftAwareBuildFilter.PARAM_IGNORE_DRAFTS] != "true") return
        val repoSlug = params[DraftAwareBuildFilter.PARAM_REPO_SLUG]?.takeIf { it.isNotBlank() } ?: return
        val connectionId = params[DraftAwareBuildFilter.PARAM_CONNECTION_ID]?.takeIf { it.isNotBlank() } ?: return
        if (!branchName.startsWith("pull/")) return
        val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return

        val token = tokenResolver.resolveAccessToken(buildType.project, connectionId) ?: return
        val pr = prInfoCache.get(RepoCoords.parse(repoSlug), prNumber, token) ?: return

        if (!shouldRemove(pr)) return

        val reason = "PR #$prNumber on $repoSlug is in draft state. " +
            "It will be re-enqueued automatically when marked ready for review."
        try {
            queuedBuild.removeFromQueue(null, reason)
            LOG.info("Removed draft build from queue: ${buildType.externalId} (pull/$prNumber on $repoSlug)")
        } catch (e: Exception) {
            LOG.warn("removeFromQueue threw for ${buildType.externalId} (pull/$prNumber): ${e.message}. The DraftAwareBuildFilter safety net will hold the build instead.", e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DraftBuildQueueCleaner::class.java.name)

        // Pure helper, testable without TC SDK fixtures.
        // Returns true exactly when the resolved PR is draft, false
        // otherwise. Lifted to a function so the same decision logic
        // can be exercised by unit tests.
        fun shouldRemove(pr: PrInfo): Boolean = pr.draft
    }
}
