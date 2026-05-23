package io.github.dlachouette.teamcity.github.retrigger

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import jetbrains.buildServer.serverSide.BuildPromotionEx
import jetbrains.buildServer.serverSide.BuildTypeEx
import jetbrains.buildServer.serverSide.ProjectManager

class ReadyForReviewListener(
    private val projectManager: ProjectManager,
    private val prInfoCache: PrInfoCache,
) {

    fun handle(payload: ReadyForReviewPayload) {
        LOG.info("Handling ready_for_review for ${payload.repo.slug}#${payload.prNumber}")
        prInfoCache.invalidate(payload.repo, payload.prNumber)

        val branchName = "pull/${payload.prNumber}"
        val targets = findBuildTypesToRetrigger(payload.repo)
        if (targets.isEmpty()) {
            LOG.info("No build types resolved for ${payload.repo.slug}")
            return
        }

        targets.forEach { bt ->
            enqueue(
                buildType = bt,
                branchName = branchName,
                comment = "Retriggered by teamcity-github-bridge after PR #${payload.prNumber} became ready for review",
            )
        }
    }

    private fun findBuildTypesToRetrigger(repo: RepoCoords): List<BuildTypeEx> {
        return emptyList()
    }

    private fun enqueue(buildType: BuildTypeEx, branchName: String, comment: String) {
        val customizer = buildType.createBuildCustomizer(null)
        customizer.setDesiredBranchName(branchName)
        customizer.setBuildComment(comment)
        val promotion = customizer.createPromotion() as BuildPromotionEx
        buildType.addToQueue(promotion, "teamcity-github-bridge")
    }

    companion object {
        private val LOG = Logger.getInstance(ReadyForReviewListener::class.java.name)
    }
}

data class ReadyForReviewPayload(
    val repo: RepoCoords,
    val prNumber: Int,
    val headSha: String,
    val baseRef: String,
    val headRef: String,
)
