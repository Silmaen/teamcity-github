package io.github.dlachouette.teamcity.github.enrich

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
import jetbrains.buildServer.serverSide.BuildPromotion
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SQueuedBuild

// Tag the BuildPromotion with "draft" or "ready" the moment it lands
// in the queue, BEFORE DraftAwareBuildFilter holds it. Held builds
// otherwise show no marker in the queue UI because PrBuildEnricher
// only fires on buildStarted, which never happens for held drafts.
class PrPromotionTagger(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
    }

    override fun buildTypeAddedToQueue(queuedBuild: SQueuedBuild) {
        try {
            tag(queuedBuild.buildPromotion)
        } catch (e: Exception) {
            LOG.warn("Failed tagging promotion for ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    private fun tag(promotion: BuildPromotion) {
        val branchName = promotion.branch?.name ?: return
        if (!branchName.startsWith("pull/")) return

        val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return
        val buildType = promotion.buildType ?: return

        if (!isOptedIn(buildType.parameters)) return
        val repoSlug = buildType.parameters[DraftAwareBuildFilter.PARAM_REPO_SLUG] ?: return
        val connectionId = buildType.parameters[DraftAwareBuildFilter.PARAM_CONNECTION_ID] ?: return
        val repo = try {
            RepoCoords.parse(repoSlug)
        } catch (e: IllegalArgumentException) {
            return
        }

        // TokenResolver already logs the cause (rate-limited).
        val access = tokenResolver.resolveAccessToken(buildType.project, connectionId, repo) ?: return

        val pr = prInfoCache.get(repo, prNumber, access.token, access.apiBase)
        if (pr == null) {
            LOG.warn("Cannot fetch PR info for $repoSlug#$prNumber; skipping promotion tag")
            return
        }

        val plan = computePlan(promotion.tags, pr.draft) ?: return
        promotion.setTags(plan.newTags)
        LOG.info("Tagged promotion for $repoSlug#$prNumber as ${plan.appliedTag} (buildType=${buildType.externalId})")
    }

    companion object {
        private val LOG = Logger.getInstance(PrPromotionTagger::class.java.name)

        // Single opt-in for the promotion tagger since v1.2.1: a
        // buildType participates as soon as it carries both the
        // repo slug and the connection ID — the same gate as
        // BuildStatusCheckRunPublisher. The previous version
        // additionally required `teamcity.github.bridge.ignoreDrafts=true`,
        // which silently dropped the draft/ready visual signal on
        // ALL-scope (draft-friendly) builds — exactly the case
        // operators expect to see tagged.
        fun isOptedIn(parameters: Map<String, String>): Boolean {
            val repo = parameters[DraftAwareBuildFilter.PARAM_REPO_SLUG]
            val conn = parameters[DraftAwareBuildFilter.PARAM_CONNECTION_ID]
            return !repo.isNullOrBlank() && !conn.isNullOrBlank()
        }

        // Pure helper - testable without TC SDK mocks.
        // Returns null when the existing tags already carry the desired state tag
        // (idempotent), so callers can skip the setTags call.
        fun computePlan(currentTags: List<String>, isDraft: Boolean): TagPlan? {
            val desired = if (isDraft) TAG_DRAFT else TAG_READY
            val opposite = if (isDraft) TAG_READY else TAG_DRAFT
            if (currentTags.contains(desired) && !currentTags.contains(opposite)) return null
            val withoutOpposite = currentTags.filter { it != opposite }
            val merged = if (withoutOpposite.contains(desired)) withoutOpposite else withoutOpposite + desired
            return TagPlan(newTags = merged, appliedTag = desired)
        }

        const val TAG_DRAFT: String = "draft"
        const val TAG_READY: String = "ready"
    }
}

data class TagPlan(
    val newTags: List<String>,
    val appliedTag: String,
)
