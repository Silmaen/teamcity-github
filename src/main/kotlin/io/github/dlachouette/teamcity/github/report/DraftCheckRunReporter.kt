package io.github.dlachouette.teamcity.github.report

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.CheckRunConclusion
import io.github.dlachouette.teamcity.github.api.CheckRunRequest
import io.github.dlachouette.teamcity.github.api.CheckRunStatus
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureConfig
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.WebLinks
import java.util.concurrent.ConcurrentHashMap

// Posts "Skipped: …" Check Runs to GitHub. No longer a build-server
// listener: the call sites (PullRequestEventListener,
// DraftBuildQueueCleaner) explicitly invoke postSkippedCheckRun when
// the centralized gate says SUPPRESS_DRAFT or SUPPRESS_BRANCH_PR.
//
// Idempotent via a per-(sha, BT) dedup set, so multiple webhook
// retries or the cleaner+listener firing for the same SHA emit at
// most one Check Run per BT.
class DraftCheckRunReporter(
    private val tokenResolver: TokenResolver,
    private val gitHubClient: GitHubClient,
    private val webLinks: WebLinks,
    private val serverSettings: io.github.dlachouette.teamcity.github.config.BridgeServerSettings,
) {

    private val published = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    fun postSkippedCheckRun(
        buildType: SBuildType,
        config: BridgeFeatureConfig,
        prNumber: Int,
        headSha: String,
        reason: SkipReason,
        headRef: String? = null,
    ): Boolean {
        if (!config.publishChecks || config.repo.slug.isBlank()) return false
        if (!config.prTriggerEnabled) return false
        if (headSha.isBlank()) return false
        if (!serverSettings.isRepoAllowed(config.repo.slug)) return false
        if (serverSettings.dryRun()) {
            LOG.info("[dry-run] would post Skipped (${reason.name}) Check Run for ${config.repo.slug}@$headSha")
            return false
        }

        val access = tokenResolver.resolveAccessToken(buildType.project, config.connectionId, config.repo) ?: return false

        val detailsUrl = safeUrl { webLinks.getConfigurationHomePageUrl(buildType) }

        val (title, summary) = reason.titleAndSummary(prNumber, headRef)
        val request = CheckRunRequest(
            name = checkRunName(buildType),
            headSha = headSha,
            status = CheckRunStatus.COMPLETED,
            conclusion = CheckRunConclusion.SKIPPED,
            outputTitle = title,
            outputSummary = summary,
            detailsUrl = detailsUrl,
        )

        val dedupKey = headSha to buildType.externalId
        if (!published.add(dedupKey)) return false

        val ok = gitHubClient.postCheckRun(access.token, config.repo, request, access.apiBase)
        if (!ok) {
            published.remove(dedupKey)
            LOG.warn("Skipped Check Run POST failed for ${config.repo.slug}@$headSha (${buildType.externalId})")
        } else {
            LOG.info("Posted Skipped Check Run for ${config.repo.slug}@$headSha (${buildType.externalId}) — reason=${reason.name}")
        }
        return ok
    }

    // Republish the success of a build that already covered this commit, for
    // a queued build that was dropped as redundant. Same Check Run name and
    // same commit as the original build, so GitHub updates that one row; the
    // details link points at the build that actually ran.
    fun postReusedSuccess(
        buildType: SBuildType,
        config: BridgeFeatureConfig,
        headSha: String,
        reused: SBuild,
    ): Boolean {
        if (!config.publishChecks || config.repo.slug.isBlank() || headSha.isBlank()) return false
        if (!serverSettings.isRepoAllowed(config.repo.slug)) return false
        if (serverSettings.dryRun()) {
            LOG.info("[dry-run] would republish success of #${reused.buildNumber} for ${config.repo.slug}@$headSha")
            return false
        }
        val access = tokenResolver.resolveAccessToken(buildType.project, config.connectionId, config.repo) ?: return false

        val request = CheckRunRequest(
            name = checkRunName(buildType),
            headSha = headSha,
            status = CheckRunStatus.COMPLETED,
            conclusion = CheckRunConclusion.SUCCESS,
            outputTitle = "Build passed (reused #${reused.buildNumber})",
            outputSummary = "This commit already passed in build #${reused.buildNumber}; " +
                "the queued build was dropped instead of reproducing a known result.",
            detailsUrl = safeUrl { webLinks.getViewResultsUrl(reused) },
        )
        val ok = gitHubClient.postCheckRun(access.token, config.repo, request, access.apiBase)
        if (ok) LOG.info("Republished success of #${reused.buildNumber} for ${config.repo.slug}@$headSha (${buildType.externalId})")
        else LOG.warn("Reused-success Check Run POST failed for ${config.repo.slug}@$headSha (${buildType.externalId})")
        return ok
    }

    fun invalidateDedupForSha(sha: String) {
        published.removeIf { it.first == sha }
    }

    companion object {
        private val LOG = Logger.getInstance(DraftCheckRunReporter::class.java.name)
    }
}

// Reasons surfaced by `postSkippedCheckRun`. Each maps to a fixed
// title + summary template so the GitHub UI's "Checks" panel shows
// a consistent message and the user can tell apart draft-deferred
// skips from branch-out-of-scope skips at a glance.
enum class SkipReason {
    DRAFT_PR,
    BRANCH_FILTER,
    PATH_FILTER,
    METADATA_FILTER;

    fun titleAndSummary(prNumber: Int, headRef: String?): Pair<String, String> = when (this) {
        DRAFT_PR -> "Skipped: draft PR" to
            "PR #$prNumber is in draft state and this BuildType is configured to skip drafts. " +
                "It will run automatically once the PR is marked ready for review."
        BRANCH_FILTER -> {
            val branch = headRef?.takeIf { it.isNotBlank() } ?: "(unknown)"
            "Skipped: branch out of scope" to
                "PR source branch '$branch' does not match this BuildType's branch filter; " +
                    "no build was triggered for this revision."
        }
        PATH_FILTER -> "Skipped: paths out of scope" to
            "None of the files changed in PR #$prNumber match this BuildType's path filter; " +
                "no build was triggered for this revision."
        METADATA_FILTER -> "Skipped: PR metadata out of scope" to
            "PR #$prNumber did not satisfy this BuildType's title/body or label filter; " +
                "no build was triggered for this revision."
    }
}
