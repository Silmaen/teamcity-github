package io.github.dlachouette.teamcity.github.enrich

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeRefs
import io.github.dlachouette.teamcity.github.feature.resolvesPrFromCommit
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SRunningBuild

class PrBuildEnricher(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
    private val serverSettings: BridgeServerSettings,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
    }

    override fun buildStarted(build: SRunningBuild) {
        try {
            enrich(build)
        } catch (e: Exception) {
            LOG.warn("Failed to enrich build ${build.buildId}: ${e.message}", e)
        }
    }

    private fun enrich(build: SRunningBuild) {
        val branchName = build.branch?.name ?: return
        val buildType = build.buildType ?: return

        // `pull/N` ref -> the number comes from the branch name. Plain
        // branch ref -> the PR (if any) is looked up from the head commit,
        // so a build launched on `Feature/x` is enriched like the `pull/N`
        // build of the same commit.
        val prNumber = BridgeRefs.prNumberFromRef(branchName)
        val headSha = build.revisions.firstOrNull()?.revision.orEmpty()

        val config = BridgeFeatureReader.read(buildType) ?: return
        if (prNumber == null) {
            if (!config.resolvesPrFromCommit(serverSettings.branchPrLookupEnabled()) || headSha.isBlank()) return
        }

        // TokenResolver already logs the cause (rate-limited).
        val access = tokenResolver.resolveAccessToken(buildType.project, config.connectionId, config.repo) ?: return

        val pr = if (prNumber != null) {
            prInfoCache.get(config.repo, prNumber, access.token, access.apiBase)
        } else {
            prInfoCache.getForCommit(config.repo, headSha, access.token, access.apiBase)
        }
        if (pr == null) {
            if (prNumber != null) {
                LOG.warn("Cannot fetch PR info for ${config.repo.slug}#$prNumber; skipping enrichment")
            } else {
                // The normal case for a branch that has no open PR.
                LOG.debug("No open PR heads ${config.repo.slug}@$headSha; skipping enrichment of $branchName")
            }
            return
        }

        val plan = computePlan(
            currentBuildNumber = build.buildNumber,
            currentTags = build.tags,
            pr = pr,
            prTag = prTagFor(serverSettings, pr.number),
        )
        plan.newBuildNumber?.let { build.buildNumber = it }
        if (plan.tagsToAdd.isNotEmpty()) {
            build.setTags(build.tags + plan.tagsToAdd)
        }
        LOG.info("Enriched build ${buildType.externalId} #${build.buildId} for ${config.repo.slug}#${pr.number} " +
            "(branch=$branchName, draft=${pr.draft})")
    }

    companion object {
        private val LOG = Logger.getInstance(PrBuildEnricher::class.java.name)

        // Pure function — split out so it can be tested without TC SDK mocks.
        fun computePlan(
            currentBuildNumber: String?,
            currentTags: List<String>,
            pr: PrInfo,
            // null = PR tagging disabled; only the state tag is applied.
            prTag: String? = null,
        ): EnrichmentPlan {
            val wanted = listOfNotNull(if (pr.draft) TAG_DRAFT else TAG_READY, prTag)
            val tagsToAdd = wanted.filterNot { currentTags.contains(it) }

            val newBuildNumber = currentBuildNumber
                ?.takeIf { it.isNotBlank() && pr.headRef.isNotBlank() && !it.contains(pr.headRef) }
                ?.let { "$it ${pr.headRef}" }

            return EnrichmentPlan(newBuildNumber, tagsToAdd)
        }

        // The PR number, persisted as a build tag: it survives the build,
        // costs no API call to read back, is searchable with TeamCity's own
        // tag filter, and is what the branch/PR view keys on. Optional, and
        // the prefix is the operator's choice.
        fun prTag(prefix: String, prNumber: Int): String = "$prefix$prNumber"

        // Convenience for the call sites that hold the settings.
        fun prTagFor(settings: BridgeServerSettings, prNumber: Int): String? =
            if (settings.prTagEnabled()) prTag(settings.prTagPrefix(), prNumber) else null

        // The PR number a `<prefix><n>` tag encodes, or null for any other tag.
        fun prNumberFromTag(tag: String, prefix: String): Int? =
            tag.takeIf { prefix.isNotEmpty() && it.startsWith(prefix) }
                ?.removePrefix(prefix)?.toIntOrNull()?.takeIf { it > 0 }

        const val TAG_DRAFT: String = "draft"
        const val TAG_READY: String = "ready"
    }
}

data class EnrichmentPlan(
    val newBuildNumber: String?,
    val tagsToAdd: List<String>,
)
