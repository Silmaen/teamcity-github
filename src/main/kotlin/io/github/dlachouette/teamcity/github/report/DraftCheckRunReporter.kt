package io.github.dlachouette.teamcity.github.report

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.CheckRunConclusion
import io.github.dlachouette.teamcity.github.api.CheckRunRequest
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
import jetbrains.buildServer.serverSide.BuildPromotion
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SQueuedBuild
import java.util.concurrent.ConcurrentHashMap

// When a draft PR build hits the queue on an opt-in buildType, publish a
// Check Run with conclusion=skipped to GitHub so the PR shows ⏭️ visibly
// instead of "Expected — Waiting for status to be reported" (which would
// otherwise block the PR).
//
// We do NOT release the StartBuildPrecondition hold — the build stays in
// queue, no agent time is consumed. The Check Run is the user-facing
// signal that the build was deliberately skipped.
class DraftCheckRunReporter(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
    private val gitHubClient: GitHubClient,
) : BuildServerAdapter() {

    // Dedup key: (commitSha, buildTypeExternalId). Bounded but not LRU; for
    // a typical TC server the working set is small (open PRs × opted-in
    // build types) and the cache survives only until server restart.
    private val published = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    init {
        buildServer.addListener(this)
    }

    override fun buildTypeAddedToQueue(queuedBuild: SQueuedBuild) {
        try {
            report(queuedBuild)
        } catch (e: Exception) {
            LOG.warn("Failed reporting draft check run for ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    private fun report(queuedBuild: SQueuedBuild) {
        val promotion = queuedBuild.buildPromotion
        val branchName = promotion.branch?.name ?: return
        if (!branchName.startsWith("pull/")) return

        val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return
        val buildType = promotion.buildType ?: return

        val repoSlug = buildType.parameters[DraftAwareBuildFilter.PARAM_REPO_SLUG] ?: return
        val connectionId = buildType.parameters[DraftAwareBuildFilter.PARAM_CONNECTION_ID] ?: return

        if (buildType.parameters[DraftAwareBuildFilter.PARAM_IGNORE_DRAFTS] != "true") return

        val accessToken = tokenResolver.resolveAccessToken(buildType.project, connectionId)
        if (accessToken == null) {
            LOG.warn("Cannot resolve token for ${buildType.externalId}; skipping check run report")
            return
        }

        val pr = prInfoCache.get(RepoCoords.parse(repoSlug), prNumber, accessToken)
        if (pr == null) {
            LOG.warn("Cannot fetch PR info for $repoSlug#$prNumber; skipping check run report")
            return
        }

        val sha = pr.headSha.takeIf { it.isNotBlank() }
            ?: promotion.revisions.firstOrNull()?.revision
            ?: return

        val request = buildRequest(
            branchName = branchName,
            params = buildType.parameters,
            isDraft = pr.draft,
            headSha = sha,
            buildTypeFullName = buildType.fullName,
            prNumber = prNumber,
        ) ?: return

        val dedupKey = sha to buildType.externalId
        if (!published.add(dedupKey)) return

        val ok = gitHubClient.postCheckRun(accessToken, RepoCoords.parse(repoSlug), request)
        if (!ok) {
            published.remove(dedupKey)
            LOG.warn("Check Run POST failed for $repoSlug@$sha (${buildType.externalId})")
        } else {
            LOG.info("Published skipped Check Run for $repoSlug@$sha (${buildType.externalId})")
        }
    }

    fun invalidateDedupForSha(sha: String) {
        published.removeIf { it.first == sha }
    }

    companion object {
        private val LOG = Logger.getInstance(DraftCheckRunReporter::class.java.name)

        // Pure helper — split out so it can be tested without TC SDK mocks.
        // Returns the request to POST, or null if the build should not be
        // reported (non-PR build, not draft, missing params, etc).
        fun buildRequest(
            branchName: String?,
            params: Map<String, String>,
            isDraft: Boolean,
            headSha: String,
            buildTypeFullName: String,
            prNumber: Int,
        ): CheckRunRequest? {
            if (branchName == null || !branchName.startsWith("pull/")) return null
            if (params[DraftAwareBuildFilter.PARAM_IGNORE_DRAFTS] != "true") return null
            if (!params.containsKey(DraftAwareBuildFilter.PARAM_REPO_SLUG)) return null
            if (!params.containsKey(DraftAwareBuildFilter.PARAM_CONNECTION_ID)) return null
            if (!isDraft) return null
            if (headSha.isBlank()) return null
            return CheckRunRequest(
                name = "TeamCity / $buildTypeFullName",
                headSha = headSha,
                conclusion = CheckRunConclusion.SKIPPED,
                outputTitle = "Skipped: draft PR",
                outputSummary = "PR #$prNumber is in draft state; this build will run automatically when the PR is marked ready for review.",
            )
        }
    }
}
