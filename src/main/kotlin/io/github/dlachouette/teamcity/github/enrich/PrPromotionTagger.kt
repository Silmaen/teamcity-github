package io.github.dlachouette.teamcity.github.enrich

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.GateContextResolver
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
    private val gateContextResolver: GateContextResolver,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
    }

    override fun buildTypeAddedToQueue(queuedBuild: SQueuedBuild) {
        try {
            tag(queuedBuild.buildPromotion, queuedBuild.triggeredBy.isTriggeredByUser)
        } catch (e: Exception) {
            LOG.warn("Failed tagging promotion for ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    private fun tag(promotion: BuildPromotion, triggeredByUser: Boolean) {
        val buildType = promotion.buildType ?: return

        // Opt-in = presence of the GitHub Bridge feature on the BT
        // (independent of the draft flags: draft-friendly builds still get
        // the visual draft/ready tag).
        val config = BridgeFeatureReader.read(buildType) ?: return

        // The resolver knows both PR shapes: the `pull/N` ref and — in
        // branch-source mode — a head branch whose commit heads an open PR.
        val ctx = gateContextResolver.resolve(promotion, config, triggeredByUser) ?: return
        val pr = ctx.pr ?: return

        val plan = computePlan(promotion.tags, pr.draft) ?: return
        promotion.setTags(plan.newTags)
        LOG.info("Tagged promotion for ${config.repo.slug}#${pr.number} as ${plan.appliedTag} (buildType=${buildType.externalId})")
    }

    companion object {
        private val LOG = Logger.getInstance(PrPromotionTagger::class.java.name)

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
