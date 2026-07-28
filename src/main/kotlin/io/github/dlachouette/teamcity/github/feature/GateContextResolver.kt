package io.github.dlachouette.teamcity.github.feature

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import jetbrains.buildServer.serverSide.BuildPromotion
import jetbrains.buildServer.serverSide.SProject

// Everything `BridgeGate.decide` needs about one queued build, resolved
// once. `prNumber == null` means "not a PR build".
data class GateContext(
    val branchName: String,
    val prNumber: Int?,
    val pr: PrInfo?,
    val trigger: BridgeTrigger,
)

// Resolves the gate input for the three queue-time sites that used to do it
// (differently) each on their own: `DraftBuildQueueCleaner`,
// `DraftAwareBuildFilter` and `BuildStatusCheckRunPublisher`.
//
// Fail-open contract: `resolve` returns null when the build *is* a PR build
// whose state cannot be fetched (no token, GitHub down). Callers then leave
// the build alone — missing a suppression costs CI minutes, blocking a build
// on a GitHub outage costs a lot more.
class GateContextResolver(
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
) {

    fun resolve(
        promotion: BuildPromotion,
        config: BridgeFeatureConfig,
        triggeredByUser: Boolean,
    ): GateContext? {
        val branchName = promotion.branch?.name ?: return null
        val project = promotion.buildType?.project ?: return null
        val trigger = BridgeTriggerMarker.of(promotion.customParameters, triggeredByUser)

        val prNumber = BridgeRefs.prNumberFromRef(branchName)
        if (prNumber == null) {
            // A plain branch ref. In branch-source mode it may still be the
            // head of an open PR, and the gate must then apply the PR rules.
            val pr = resolvePrFromCommit(promotion, project, config) ?: return GateContext(branchName, null, null, trigger)
            return GateContext(branchName, pr.number, pr, trigger)
        }

        val pr = fetchPr(project, config, prNumber) ?: return null
        return GateContext(branchName, prNumber, pr, trigger)
    }

    private fun fetchPr(project: SProject, config: BridgeFeatureConfig, prNumber: Int): PrInfo? {
        val access = tokenResolver.resolveAccessToken(project, config.connectionId, config.repo) ?: return null
        val pr = prInfoCache.get(config.repo, prNumber, access.token, access.apiBase)
        if (pr == null) LOG.warn("Cannot fetch PR info for ${config.repo.slug}#$prNumber; gate will fail open")
        return pr
    }

    // Only branch-source projects pay for this lookup: elsewhere a plain
    // branch build is a branch build, and asking GitHub about every pushed
    // commit would be a cost (and a behaviour change) nobody asked for.
    private fun resolvePrFromCommit(
        promotion: BuildPromotion,
        project: SProject,
        config: BridgeFeatureConfig,
    ): PrInfo? {
        if (config.prBuildRef != PrBuildRef.BRANCH) return null
        val headSha = promotion.revisions.firstOrNull()?.revision?.takeIf { it.isNotBlank() } ?: return null
        val access = tokenResolver.resolveAccessToken(project, config.connectionId, config.repo) ?: return null
        return prInfoCache.getForCommit(config.repo, headSha, access.token, access.apiBase)
    }

    companion object {
        private val LOG = Logger.getInstance(GateContextResolver::class.java.name)
    }
}
