package io.github.dlachouette.teamcity.github.enrich

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.GateContextResolver
import jetbrains.buildServer.serverSide.BuildPromotion
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SQueuedBuild

// Tag the BuildPromotion with "draft"/"ready" **and its PR number** the
// moment it lands in the queue, BEFORE DraftAwareBuildFilter holds it. Held
// builds otherwise show no marker in the queue UI because PrBuildEnricher
// only fires on buildStarted, which never happens for held drafts.
//
// The PR tag belongs here and not only in the listener's retro-association
// pass: that pass tags the builds sitting at the pull request's *current*
// head, so a build of an earlier commit — every build in the history, five
// minutes later — never got one. In branch-source mode there is no `pull/N`
// ref to fall back on either, so the PR number was simply lost: the
// "Branches & PRs" tab showed an empty PR column and a search by number
// found nothing. Here the number is already resolved, one line away.
class PrPromotionTagger(
    buildServer: SBuildServer,
    private val gateContextResolver: GateContextResolver,
    private val serverSettings: BridgeServerSettings,
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

        val prTag = PrBuildEnricher.prTagFor(serverSettings, pr.number)
        val plan = computePlan(promotion.tags, pr.draft, prTag) ?: return
        promotion.setTags(plan.newTags)
        LOG.info("Tagged promotion for ${config.repo.slug}#${pr.number} as ${plan.appliedTags.joinToString(" + ")} (buildType=${buildType.externalId})")
    }

    companion object {
        private val LOG = Logger.getInstance(PrPromotionTagger::class.java.name)

        // Pure helper - testable without TC SDK mocks.
        //
        // Returns null when nothing would change (the state tag is already the
        // right one and the PR tag is already there), so callers can skip the
        // setTags call. `prTag` is null when PR tagging is switched off.
        fun computePlan(currentTags: List<String>, isDraft: Boolean, prTag: String? = null): TagPlan? {
            val desired = if (isDraft) TAG_DRAFT else TAG_READY
            val opposite = if (isDraft) TAG_READY else TAG_DRAFT
            val stateSettled = currentTags.contains(desired) && !currentTags.contains(opposite)
            val prSettled = prTag == null || currentTags.contains(prTag)
            if (stateSettled && prSettled) return null

            val merged = currentTags.filter { it != opposite }.toMutableList()
            val applied = mutableListOf<String>()
            if (!merged.contains(desired)) {
                merged += desired
                applied += desired
            }
            if (prTag != null && !merged.contains(prTag)) {
                merged += prTag
                applied += prTag
            }
            // The state tag flipped (draft -> ready) without anything being
            // added: still a change, and still worth naming in the log.
            if (applied.isEmpty()) applied += desired
            return TagPlan(newTags = merged, appliedTags = applied)
        }

        const val TAG_DRAFT: String = "draft"
        const val TAG_READY: String = "ready"
    }
}

data class TagPlan(
    val newTags: List<String>,
    val appliedTags: List<String>,
)
