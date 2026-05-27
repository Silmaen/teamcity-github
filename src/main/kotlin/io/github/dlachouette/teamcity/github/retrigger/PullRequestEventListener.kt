package io.github.dlachouette.teamcity.github.retrigger

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
import jetbrains.buildServer.serverSide.BuildPromotionEx
import jetbrains.buildServer.serverSide.BuildTypeEx
import jetbrains.buildServer.serverSide.ProjectManager

// Reacts to the three `pull_request` actions whose semantics line up
// with "enqueue opt-in builds on the PR's head":
//   - opened (only when !draft): a brand new ready PR has no
//     prior build history; without this, DSL authors who drop VCS
//     triggers would see zero Check Runs on the initial SHA.
//   - ready_for_review: the draft -> ready transition. By GitHub's
//     contract, draft is always false on this event.
//   - synchronize (only when !draft): every subsequent push to an
//     open PR. Without this, the Check Runs freeze at the SHA from
//     the ready transition.
//
// Drafts are read from the webhook payload itself (`pull_request.draft`)
// — authoritative at the moment of the event and free, no token
// required.
class PullRequestEventListener(
    private val projectManager: ProjectManager,
    private val prInfoCache: PrInfoCache,
) {

    fun handle(payload: PrEventPayload) {
        LOG.info(
            "Handling pull_request.${payload.action.value} for ${payload.repo.slug}#${payload.prNumber} " +
                "(draft=${payload.draft})"
        )

        if (!shouldEnqueue(payload)) {
            LOG.info(
                "Skipping pull_request.${payload.action.value} for ${payload.repo.slug}#${payload.prNumber}: " +
                    "PR is draft"
            )
            return
        }

        // Invalidate before any downstream listener (e.g.
        // DraftBuildQueueCleaner on `buildTypeAddedToQueue`) refetches.
        // Without this, a stale cached PrInfo showing draft=true would
        // cause the cleaner to drop the build we are about to enqueue.
        prInfoCache.invalidate(payload.repo, payload.prNumber)

        val branchName = "pull/${payload.prNumber}"
        val targets = findBuildTypesToRetrigger(payload.repo)
        if (targets.isEmpty()) {
            LOG.info("No build types found for ${payload.repo.slug} - nothing to retrigger")
            return
        }

        LOG.info(
            "Retriggering ${targets.size} build type(s) for ${payload.repo.slug}#${payload.prNumber} " +
                "on pull_request.${payload.action.value}"
        )
        targets.forEach { bt ->
            try {
                enqueue(
                    buildType = bt,
                    branchName = branchName,
                    comment = "Retriggered by teamcity-github-bridge after pull_request.${payload.action.value} on PR #${payload.prNumber}",
                )
            } catch (e: Exception) {
                LOG.warn("Failed to enqueue ${bt.externalId} for PR #${payload.prNumber}: ${e.message}")
            }
        }
    }

    internal fun findBuildTypesToRetrigger(repo: RepoCoords): List<BuildTypeEx> {
        return projectManager.activeBuildTypes
            .filter { it.parameters[DraftAwareBuildFilter.PARAM_REPO_SLUG] == repo.slug }
            .filter { it.parameters[DraftAwareBuildFilter.PARAM_IGNORE_DRAFTS] == "true" }
            .filterIsInstance<BuildTypeEx>()
    }

    private fun enqueue(buildType: BuildTypeEx, branchName: String, comment: String) {
        val customizer = buildType.createBuildCustomizer(null)
        customizer.setDesiredBranchName(branchName)
        customizer.setBuildComment(comment)
        val promotion = customizer.createPromotion() as BuildPromotionEx
        buildType.addToQueue(promotion, "teamcity-github-bridge")
    }

    companion object {
        private val LOG = Logger.getInstance(PullRequestEventListener::class.java.name)

        // Pure decision so tests can exercise the gating without
        // mocking ProjectManager / BuildTypeEx. ready_for_review
        // is unconditional (GitHub only fires it on the draft->ready
        // transition, so draft is always false at that instant);
        // opened and synchronize fire for both states and we filter.
        fun shouldEnqueue(payload: PrEventPayload): Boolean = when (payload.action) {
            PrAction.READY_FOR_REVIEW -> true
            PrAction.OPENED, PrAction.SYNCHRONIZE -> !payload.draft
        }
    }
}

enum class PrAction(val value: String) {
    OPENED("opened"),
    READY_FOR_REVIEW("ready_for_review"),
    SYNCHRONIZE("synchronize");

    companion object {
        fun fromString(s: String): PrAction? = entries.firstOrNull { it.value == s }
    }
}

data class PrEventPayload(
    val action: PrAction,
    val repo: RepoCoords,
    val prNumber: Int,
    val headSha: String,
    val baseRef: String,
    val headRef: String,
    val draft: Boolean,
)
