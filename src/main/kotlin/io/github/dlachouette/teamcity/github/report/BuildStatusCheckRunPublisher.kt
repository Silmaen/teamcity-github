package io.github.dlachouette.teamcity.github.report

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.CheckRunConclusion
import io.github.dlachouette.teamcity.github.api.CheckRunRequest
import io.github.dlachouette.teamcity.github.api.CheckRunStatus
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
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
//                                (early signal; buildFinished may not
//                                always enchain cleanly)
//   - buildFinished           -> status=completed, conclusion derived
//                                from TC's Status + isInterrupted,
//                                summary carries statusDescriptor.text
//   - buildRemovedFromQueue   -> status=completed, conclusion=cancelled
//                                (only when user!=null — see comment
//                                on the handler)
//
// GitHub dedups Check Runs by (name, head_sha), so every event
// transitions the same row: queued -> in_progress -> completed.
//
// Why this exists: the bundled commitStatusPublisher posts a generic
// "TeamCity build finished" description on commit statuses, dropping
// the build's actual status text. Check Runs let us carry that text
// into the GitHub PR UI, and let us advertise "TC has seen this
// build" while it sits in the queue waiting for an agent (instead of
// the GitHub UI showing "Expected — Waiting for status to be
// reported", which is indistinguishable from "TC silently dropped
// the build").
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

    // TC fires buildInterrupted before buildFinished when a build is
    // stopped by the user. Field reports show buildFinished sometimes
    // not enchaining (agent disconnect during interrupt, or the build
    // never reaching the finished state cleanly), leaving the Check
    // Run stuck at "in_progress" on the GitHub side. Publishing on
    // interrupt makes the cancellation visible immediately; if
    // buildFinished does run afterwards, GitHub dedups by
    // (name, head_sha) and the row stays "completed/cancelled".
    override fun buildInterrupted(build: SRunningBuild) {
        try {
            publishCompleted(build)
        } catch (e: Exception) {
            LOG.warn("Failed publishing cancelled Check Run for interrupted ${build.buildTypeExternalId} #${build.buildId}: ${e.message}", e)
        }
    }

    // Catches the "user cancels a build still in the queue" case: no
    // SRunningBuild ever exists, so buildFinished never fires. Without
    // this handler the row would stay at "Queued" forever on GitHub.
    //
    // We skip when user==null: that matches DraftBuildQueueCleaner's
    // automated removal, where DraftCheckRunReporter has already
    // posted "Skipped" and we don't want to overwrite it.
    override fun buildRemovedFromQueue(queuedBuild: SQueuedBuild, user: User?, comment: String) {
        if (user == null) return
        try {
            publishQueueCancelled(queuedBuild, comment)
        } catch (e: Exception) {
            LOG.warn("Failed publishing cancelled Check Run for queue removal of ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    private fun publishQueued(queuedBuild: SQueuedBuild) {
        val promotion = queuedBuild.buildPromotion
        val ctx = resolveContext(promotion.buildType, promotion.revisions) ?: return
        // DraftCheckRunReporter and DraftBuildQueueCleaner also fire
        // on this event for draft-suppressed builds. If we post
        // "Queued" here, GitHub's (name, head_sha) dedup lets whichever
        // listener runs last win — and field testing shows our queued
        // row keeps winning, leaving the user with a "Queued" status
        // and a stale TC queue link for a build that was actually
        // removed and reported as "Skipped: draft PR".
        //
        // Cheap path: ignoreDrafts gate + branch shape; only consult
        // PrInfoCache when both match. The cache is populated by the
        // DraftAwareBuildFilter / DraftCheckRunReporter / cleaner that
        // run on this same event, so the lookup is essentially free.
        if (willBeDraftSuppressed(queuedBuild, promotion, ctx)) {
            LOG.debug("Skipping queued Check Run for draft-suppressed ${ctx.buildType.externalId}; DraftCheckRunReporter owns the row")
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

    private fun willBeDraftSuppressed(queuedBuild: SQueuedBuild, promotion: BuildPromotion, ctx: PrBuildContext): Boolean {
        val params = ctx.buildType.parameters
        if (params[DraftAwareBuildFilter.PARAM_IGNORE_DRAFTS] != "true") return false
        val branchName = promotion.branch?.name ?: return false
        if (!branchName.startsWith("pull/")) return false
        val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return false
        // A manual user trigger bypasses suppression (see
        // DraftAwareBuildFilter) — the build will run, so we want the
        // queued Check Run posted normally.
        if (queuedBuild.triggeredBy.isTriggeredByUser) return false
        val pr = prInfoCache.get(ctx.repo, prNumber, ctx.accessToken, ctx.apiBase) ?: return false
        return pr.draft
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
        val ctx = resolveContext(build.buildType, build.revisions) ?: return
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

    private fun publishQueueCancelled(queuedBuild: SQueuedBuild, comment: String) {
        val promotion = queuedBuild.buildPromotion
        val ctx = resolveContext(promotion.buildType, promotion.revisions) ?: return
        val summary = comment.takeIf { it.isNotBlank() }
            ?: "Build was removed from the queue before it started."
        val request = CheckRunRequest(
            name = "TeamCity / ${ctx.buildType.fullName}",
            headSha = ctx.headSha,
            status = CheckRunStatus.COMPLETED,
            conclusion = CheckRunConclusion.CANCELLED,
            outputTitle = "Cancelled before start",
            outputSummary = truncateSummary(summary),
            detailsUrl = safeUrl { webLinks.getConfigurationHomePageUrl(ctx.buildType) },
        )
        post(ctx, request, "cancelled (queue removed)")
    }

    // WebLinks needs serverRootUrl to be configured. Treat a blank
    // or throwing call as "no detailsUrl" rather than crashing the
    // publish path — GitHub falls back to its own per-Check-Run page.
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
        if (!isOptedIn(buildType.parameters)) return null

        val repoSlug = buildType.parameters[DraftAwareBuildFilter.PARAM_REPO_SLUG] ?: return null
        val connectionId = buildType.parameters[DraftAwareBuildFilter.PARAM_CONNECTION_ID] ?: return null
        val repo = try {
            RepoCoords.parse(repoSlug)
        } catch (e: IllegalArgumentException) {
            return null
        }

        val headSha = revisions.firstOrNull()?.revision?.takeIf { it.isNotBlank() } ?: return null
        // TokenResolver already logs the cause (rate-limited).
        val access = tokenResolver.resolveAccessToken(buildType.project, connectionId, repo) ?: return null
        return PrBuildContext(
            repo = repo,
            buildType = buildType,
            headSha = headSha,
            accessToken = access.token,
            apiBase = access.apiBase,
        )
    }

    private data class PrBuildContext(
        val repo: RepoCoords,
        val buildType: SBuildType,
        val headSha: String,
        val accessToken: String,
        val apiBase: String,
    )

    companion object {
        private val LOG = Logger.getInstance(BuildStatusCheckRunPublisher::class.java.name)

        // Single opt-in for the status publisher since v0.7.0: a
        // buildType participates as soon as it carries both the
        // repo slug and the connection ID. The previous version
        // also required `teamcity.github.bridge.ignoreDrafts=true` and a `pull/N`
        // branch (Gap A4 in the roadmap), which scoped the
        // publisher to a subset of builds and forced consumers to
        // keep the bundled commitStatusPublisher around for the
        // rest. Lifting both guards gives the plugin full
        // lifecycle coverage (main + opt-out PR builds + opt-in PR
        // builds), so consumers can disable the bundled publisher
        // without losing rows in the GitHub PR UI.
        fun isOptedIn(parameters: Map<String, String>): Boolean {
            val repo = parameters[DraftAwareBuildFilter.PARAM_REPO_SLUG]
            val conn = parameters[DraftAwareBuildFilter.PARAM_CONNECTION_ID]
            return !repo.isNullOrBlank() && !conn.isNullOrBlank()
        }

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
