package io.github.dlachouette.teamcity.github.report

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.CheckRunConclusion
import io.github.dlachouette.teamcity.github.api.CheckRunRequest
import io.github.dlachouette.teamcity.github.api.CheckRunStatus
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureConfig
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeGate
import io.github.dlachouette.teamcity.github.feature.GateDecision
import jetbrains.buildServer.messages.Status
import jetbrains.buildServer.serverSide.BuildPromotion
import jetbrains.buildServer.serverSide.BuildRevision
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SQueuedBuild
import jetbrains.buildServer.serverSide.SRunningBuild
import jetbrains.buildServer.serverSide.WebLinks
import jetbrains.buildServer.users.User

// Publishes a Check Run to GitHub at every lifecycle transition for
// an opted-in PR build:
//   - buildTypeAddedToQueue   -> status=queued, "Queued"
//   - buildStarted            -> status=in_progress, "Building"
//   - buildInterrupted        -> status=completed, conclusion=cancelled
//   - buildFinished           -> status=completed, conclusion derived
//   - buildRemovedFromQueue   -> status=completed, conclusion=cancelled
//
// GitHub dedups Check Runs by (name, head_sha), so every event
// transitions the same row: queued -> in_progress -> completed.
class BuildStatusCheckRunPublisher(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val gitHubClient: GitHubClient,
    private val webLinks: WebLinks,
    private val prInfoCache: PrInfoCache,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
    }

    override fun buildTypeAddedToQueue(queuedBuild: SQueuedBuild) {
        try {
            publishQueued(queuedBuild)
        } catch (e: Exception) {
            LOG.warn("Failed publishing queued Check Run for ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    override fun buildStarted(build: SRunningBuild) {
        try {
            publishInProgress(build)
        } catch (e: Exception) {
            LOG.warn("Failed publishing in-progress Check Run for ${build.buildTypeExternalId} #${build.buildId}: ${e.message}", e)
        }
    }

    override fun buildFinished(build: SRunningBuild) {
        try {
            publishCompleted(build)
        } catch (e: Exception) {
            LOG.warn("Failed publishing completed Check Run for ${build.buildTypeExternalId} #${build.buildId}: ${e.message}", e)
        }
    }

    override fun buildInterrupted(build: SRunningBuild) {
        try {
            publishCompleted(build)
        } catch (e: Exception) {
            LOG.warn("Failed publishing cancelled Check Run for interrupted ${build.buildTypeExternalId} #${build.buildId}: ${e.message}", e)
        }
    }

    // `buildRemovedFromQueue` fires for EVERY exit from the queue —
    // the SDK documents it as "started, deleted, optimized, etc.", so
    // a null user does NOT mean "cancelled", and a non-null associated
    // build does NOT always mean the build is running (a build that
    // "failed to start" on a failed snapshot dependency also leaves a
    // finished record). `publishQueueRemoved` sorts the cases out so the
    // "Queued" row always reaches a terminal state.
    //
    // The previous `if (user == null) return` guard was meant to skip
    // the build-started case, but it also swallowed system removals
    // such as a failed snapshot dependency — leaving their "Queued"
    // Check Run stuck forever on GitHub.
    override fun buildRemovedFromQueue(queuedBuild: SQueuedBuild, user: User?, comment: String) {
        try {
            publishQueueRemoved(queuedBuild, user, comment)
        } catch (e: Exception) {
            LOG.warn("Failed publishing Check Run for queue removal of ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    private fun publishQueued(queuedBuild: SQueuedBuild) {
        val promotion = queuedBuild.buildPromotion
        val ctx = resolveContext(promotion.buildType, promotion.revisions) ?: return
        if (willBeSuppressed(queuedBuild, promotion, ctx)) {
            LOG.debug("Skipping queued Check Run for ${ctx.buildType.externalId}; gate says SUPPRESS, cleaner will own the row")
            return
        }
        val request = CheckRunRequest(
            name = "TeamCity / ${ctx.buildType.fullName}",
            headSha = ctx.headSha,
            status = CheckRunStatus.QUEUED,
            conclusion = null,
            outputTitle = "Queued",
            outputSummary = "TeamCity has queued this build; waiting for a compatible agent.",
            detailsUrl = safeUrl { webLinks.getQueuedBuildUrl(queuedBuild) },
        )
        post(ctx, request, "queued")
    }

    // True iff `DraftBuildQueueCleaner` is going to remove this
    // queued build (so we should not post the "Queued" Check Run —
    // the cleaner will post Skipped, or stay silent for non-PR /
    // HARD-block cases). Mirrors the cleaner's decision via
    // `BridgeGate.decide`.
    private fun willBeSuppressed(queuedBuild: SQueuedBuild, promotion: BuildPromotion, ctx: PrBuildContext): Boolean {
        val branchName = promotion.branch?.name ?: return false
        val isManual = queuedBuild.triggeredBy.isTriggeredByUser
        val prDraft: Boolean?
        val prHeadRef: String?
        if (branchName.startsWith("pull/")) {
            val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return false
            val pr = prInfoCache.get(ctx.repo, prNumber, ctx.accessToken, ctx.apiBase) ?: return false
            prDraft = pr.draft
            prHeadRef = pr.headRef
        } else {
            prDraft = null
            prHeadRef = null
        }
        return BridgeGate.decide(ctx.config, branchName, prDraft, prHeadRef, isManual) != GateDecision.ALLOW
    }

    private fun publishInProgress(build: SBuild) {
        val ctx = resolveContext(build.buildType, build.revisions) ?: return
        val request = CheckRunRequest(
            name = "TeamCity / ${ctx.buildType.fullName}",
            headSha = ctx.headSha,
            status = CheckRunStatus.IN_PROGRESS,
            conclusion = null,
            outputTitle = "Building",
            outputSummary = "TeamCity build #${build.buildNumber} is running.",
            detailsUrl = safeUrl { webLinks.getViewResultsUrl(build) },
        )
        post(ctx, request, "in-progress")
    }

    private fun publishCompleted(build: SRunningBuild) {
        // A build that "failed to start" (e.g. a failed snapshot
        // dependency) may carry no revisions of its own; fall back to the
        // promotion's revisions (resolved at enqueue) so we still resolve
        // the head SHA and report it instead of leaving the row stuck.
        val revisions = build.revisions.ifEmpty { build.buildPromotion.revisions }
        val ctx = resolveContext(build.buildType, revisions) ?: return
        val mapping = mapBuildOutcome(build.buildStatus, build.isInterrupted)
        val summary = build.statusDescriptor.text.orEmpty()
            .takeIf { it.isNotBlank() }
            ?: "TeamCity build finished with status ${build.buildStatus}"

        val request = CheckRunRequest(
            name = "TeamCity / ${ctx.buildType.fullName}",
            headSha = ctx.headSha,
            status = CheckRunStatus.COMPLETED,
            conclusion = mapping.conclusion,
            outputTitle = mapping.title,
            outputSummary = truncateSummary(summary),
            detailsUrl = safeUrl { webLinks.getViewResultsUrl(build) },
        )
        post(ctx, request, "completed (${mapping.conclusion.apiValue})")
    }

    private fun publishQueueRemoved(queuedBuild: SQueuedBuild, user: User?, comment: String) {
        val promotion = queuedBuild.buildPromotion
        val associated = promotion.associatedBuild

        // The build left the queue to actually RUN: a running SBuild
        // exists and buildStarted / buildFinished own the Check Run row.
        if (associated != null && !associated.isFinished) return

        val ctx = resolveContext(promotion.buildType, promotion.revisions) ?: return

        if (associated != null) {
            // A FINISHED build is attached to this promotion.
            if (associated.buildPromotion.id != promotion.id) {
                // Queue optimization: this promotion was satisfied by an
                // EQUIVALENT build (different promotion) that owns its own
                // Check Run row. Nothing to post.
                return
            }
            // This promotion's OWN build is finished although it never ran
            // to completion normally (it left the queue): it "failed to
            // start", typically because a snapshot dependency failed.
            // Report its real outcome — the same conclusion buildFinished
            // reports — so the row reaches a terminal state (a failed
            // dependency => "Build failed", red, blocks the merge).
            // `isInterrupted` is SRunningBuild-only; on a finished SBuild
            // a non-null canceledInfo is the equivalent signal.
            val mapping = mapBuildOutcome(associated.buildStatus, associated.canceledInfo != null)
            val summary = associated.statusDescriptor.text.orEmpty().takeIf { it.isNotBlank() }
                ?: comment.takeIf { it.isNotBlank() }
                ?: "Build did not run to completion (a snapshot dependency likely failed)."
            val request = CheckRunRequest(
                name = "TeamCity / ${ctx.buildType.fullName}",
                headSha = ctx.headSha,
                status = CheckRunStatus.COMPLETED,
                conclusion = mapping.conclusion,
                outputTitle = mapping.title,
                outputSummary = truncateSummary(summary),
                detailsUrl = safeUrl { webLinks.getViewResultsUrl(associated) },
            )
            post(ctx, request, "queue-removed/finished (${mapping.conclusion.apiValue})")
            return
        }

        // No associated build at all. A user removing a queued build is a
        // genuine cancellation — report it so the row does not stay stuck.
        if (user != null) {
            val summary = comment.takeIf { it.isNotBlank() }
                ?: "Build was cancelled before it started."
            val request = CheckRunRequest(
                name = "TeamCity / ${ctx.buildType.fullName}",
                headSha = ctx.headSha,
                status = CheckRunStatus.COMPLETED,
                conclusion = CheckRunConclusion.CANCELLED,
                outputTitle = "Cancelled before start",
                outputSummary = truncateSummary(summary),
                detailsUrl = safeUrl { webLinks.getConfigurationHomePageUrl(ctx.buildType) },
            )
            post(ctx, request, "queue-removed/cancelled")
            return
        }

        // System removal with no associated build. In a dependency fan-out
        // the plugin (and TeamCity's chain optimization) create duplicate
        // queued promotions of the shared dependency; tearing those down
        // fires this event with no record — and the REAL build already
        // reported via buildFinished or its own finished record above.
        // Posting a generic status here would clobber that real result
        // under the same Check Run name (this is what flipped a
        // genuinely-failed build to "Build could not start"). The same is
        // true for gate-suppressed builds our queue cleaner removed (it
        // owns their Skipped row). So stay silent: a build that genuinely
        // failed to start keeps its own finished record, handled above.
        LOG.debug("Ignoring system queue removal with no associated build for ${ctx.buildType.externalId} (${promotion.branch?.name})")
    }

    private fun safeUrl(block: () -> String?): String? {
        return try {
            block()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            LOG.debug("WebLinks URL build failed: ${e.message}")
            null
        }
    }

    private fun post(ctx: PrBuildContext, request: CheckRunRequest, label: String) {
        val ok = gitHubClient.postCheckRun(ctx.accessToken, ctx.repo, request, ctx.apiBase)
        if (!ok) {
            LOG.warn("Check Run POST ($label) failed for ${ctx.repo.slug}@${ctx.headSha}")
        } else {
            LOG.info("Published $label Check Run for ${ctx.repo.slug}@${ctx.headSha}")
        }
    }

    private fun resolveContext(buildType: SBuildType?, revisions: List<BuildRevision>): PrBuildContext? {
        if (buildType == null) return null
        val config = BridgeFeatureReader.read(buildType) ?: return null

        val headSha = revisions.firstOrNull()?.revision?.takeIf { it.isNotBlank() } ?: return null
        // TokenResolver already logs the cause (rate-limited).
        val access = tokenResolver.resolveAccessToken(buildType.project, config.connectionId, config.repo) ?: return null
        return PrBuildContext(
            repo = config.repo,
            buildType = buildType,
            config = config,
            headSha = headSha,
            accessToken = access.token,
            apiBase = access.apiBase,
        )
    }

    private data class PrBuildContext(
        val repo: RepoCoords,
        val buildType: SBuildType,
        val config: BridgeFeatureConfig,
        val headSha: String,
        val accessToken: String,
        val apiBase: String,
    )

    companion object {
        private val LOG = Logger.getInstance(BuildStatusCheckRunPublisher::class.java.name)

        // GitHub limits output.summary to 65535 characters. Truncate
        // conservatively to leave headroom for the ellipsis.
        const val SUMMARY_MAX: Int = 60_000

        fun truncateSummary(text: String): String =
            if (text.length <= SUMMARY_MAX) text
            else text.take(SUMMARY_MAX) + "\n\n... (truncated)"

        // Pure mapping helper — testable without TC SDK fixtures.
        // Encodes the policy: warning is still successful in TC's
        // model (it does not fail the build by default), so we report
        // SUCCESS. Interrupted builds map to CANCELLED regardless of
        // the underlying status.
        fun mapBuildOutcome(status: Status, isInterrupted: Boolean): BuildOutcomeMapping {
            if (isInterrupted) {
                return BuildOutcomeMapping(CheckRunConclusion.CANCELLED, "Build cancelled")
            }
            return when {
                status.isSuccessful -> BuildOutcomeMapping(CheckRunConclusion.SUCCESS, "Build passed")
                status.isFailed -> BuildOutcomeMapping(CheckRunConclusion.FAILURE, "Build failed")
                else -> BuildOutcomeMapping(CheckRunConclusion.NEUTRAL, "Build status: ${status.text}")
            }
        }
    }
}

data class BuildOutcomeMapping(
    val conclusion: CheckRunConclusion,
    val title: String,
)
