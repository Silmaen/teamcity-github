package io.github.dlachouette.teamcity.github.report

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.CheckRunConclusion
import io.github.dlachouette.teamcity.github.api.CheckRunRequest
import io.github.dlachouette.teamcity.github.api.CheckRunStatus
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
import jetbrains.buildServer.messages.Status
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SRunningBuild

// Publishes a Check Run to GitHub at the two key lifecycle events
// for an opted-in PR build:
//   - buildStarted -> status=in_progress, no conclusion, "Building"
//   - buildFinished -> status=completed, conclusion derived from
//                      TC's Status + isInterrupted, summary carries
//                      the build's statusDescriptor.text
//
// Why this exists: the bundled commitStatusPublisher posts a generic
// "TeamCity build finished" description on commit statuses, dropping
// the build's actual status text. Check Runs let us carry that text
// into the GitHub PR UI.
class BuildStatusCheckRunPublisher(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val gitHubClient: GitHubClient,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
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

    private fun publishInProgress(build: SBuild) {
        val ctx = resolveContext(build) ?: return
        val request = CheckRunRequest(
            name = "TeamCity / ${ctx.buildType.fullName}",
            headSha = ctx.headSha,
            status = CheckRunStatus.IN_PROGRESS,
            conclusion = null,
            outputTitle = "Building",
            outputSummary = "TeamCity build #${build.buildNumber} is running.",
        )
        post(ctx, request, "in-progress")
    }

    private fun publishCompleted(build: SRunningBuild) {
        val ctx = resolveContext(build) ?: return
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
        )
        post(ctx, request, "completed (${mapping.conclusion.apiValue})")
    }

    private fun post(ctx: PrBuildContext, request: CheckRunRequest, label: String) {
        val ok = gitHubClient.postCheckRun(ctx.accessToken, ctx.repo, request, ctx.apiBase)
        if (!ok) {
            LOG.warn("Check Run POST ($label) failed for ${ctx.repo.slug}@${ctx.headSha}")
        } else {
            LOG.info("Published $label Check Run for ${ctx.repo.slug}@${ctx.headSha}")
        }
    }

    private fun resolveContext(build: SBuild): PrBuildContext? {
        val buildType = build.buildType ?: return null
        if (!isOptedIn(buildType.parameters)) return null

        val repoSlug = buildType.parameters[DraftAwareBuildFilter.PARAM_REPO_SLUG] ?: return null
        val connectionId = buildType.parameters[DraftAwareBuildFilter.PARAM_CONNECTION_ID] ?: return null
        val repo = try {
            RepoCoords.parse(repoSlug)
        } catch (e: IllegalArgumentException) {
            return null
        }

        val headSha = build.revisions.firstOrNull()?.revision?.takeIf { it.isNotBlank() } ?: return null
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
        val buildType: jetbrains.buildServer.serverSide.SBuildType,
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
