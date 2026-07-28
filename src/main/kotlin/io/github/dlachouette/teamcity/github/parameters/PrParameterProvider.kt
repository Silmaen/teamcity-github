package io.github.dlachouette.teamcity.github.parameters

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.resolvesPrFromCommit
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.parameters.AbstractBuildParametersProvider

// Publishes the full set of PR metadata as build parameters on every
// opted-in build. Replaces what the bundled `pullRequests` build
// feature would publish (which lives under `teamcity.pullRequest.*`),
// so consumers can disable that feature and rely solely on this
// plugin's `teamcity.github.bridge.pullRequest.*` namespace.
//
// All keys are always emitted (with empty values when not applicable),
// so DSL conditions and script-step interpolations never fail with
// "Unresolved parameter".
class PrParameterProvider(
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
    private val serverSettings: BridgeServerSettings,
) : AbstractBuildParametersProvider() {

    override fun getParameters(build: SBuild, emulationMode: Boolean): Map<String, String> {
        return try {
            val buildType = build.buildType ?: return emptyMap()
            val config = BridgeFeatureReader.read(buildType) ?: return emptyMap()

            // Only needed for builds launched on a plain branch ref; null
            // (= no lookup) when the operator disabled the branch->PR
            // lookup or the revision is not resolved.
            val headSha = build.revisions.firstOrNull()?.revision
                ?.takeIf { it.isNotBlank() && config.resolvesPrFromCommit(serverSettings.branchPrLookupEnabled()) }

            fun access() = tokenResolver.resolveAccessToken(buildType.project, config.connectionId, config.repo)

            computeParams(
                branchName = build.branch?.name,
                legacyAliases = serverSettings.legacyAliasesEnabled(),
                headSha = headSha,
                prByCommitResolver = { sha ->
                    access()?.let { prInfoCache.getForCommit(config.repo, sha, it.token, it.apiBase) }
                },
                resolver = { number ->
                    access()?.let { prInfoCache.get(config.repo, number, it.token, it.apiBase) }
                },
            )
        } catch (e: Exception) {
            LOG.warn("Failed computing PR parameters for build ${build.buildId}: ${e.message}", e)
            DEFAULT_NON_PR_PARAMS
        }
    }

    override fun getParametersAvailableOnAgent(build: SBuild): Collection<String> {
        val buildType = build.buildType ?: return emptyList()
        if (BridgeFeatureReader.read(buildType) == null) return emptyList()
        return if (serverSettings.legacyAliasesEnabled()) ALL_KEYS + LEGACY_ALIAS_KEYS else ALL_KEYS
    }

    override fun getPrefix(): String = "teamcity.github.bridge"

    companion object {
        const val PARAM_IS_PULL_REQUEST: String = "teamcity.github.bridge.isPullRequest"
        const val PARAM_IS_DRAFT: String = "teamcity.github.bridge.isDraft"
        const val PARAM_PR_NUMBER: String = "teamcity.github.bridge.pullRequest.number"
        const val PARAM_PR_TITLE: String = "teamcity.github.bridge.pullRequest.title"
        const val PARAM_PR_AUTHOR: String = "teamcity.github.bridge.pullRequest.author"
        const val PARAM_PR_SOURCE_BRANCH: String = "teamcity.github.bridge.pullRequest.sourceBranch"
        const val PARAM_PR_TARGET_BRANCH: String = "teamcity.github.bridge.pullRequest.targetBranch"
        const val PARAM_PR_HEAD_SHA: String = "teamcity.github.bridge.pullRequest.headSha"

        // Aliases under the bundled `pullRequests` feature's namespace,
        // emitted only when the operator opts in (legacyAliases.enabled).
        // Lets teams migrate off the bundled feature without rewriting DSL
        // that still reads `teamcity.pullRequest.*`.
        const val ALIAS_PR_NUMBER: String = "teamcity.pullRequest.number"
        const val ALIAS_PR_TITLE: String = "teamcity.pullRequest.title"
        const val ALIAS_PR_SOURCE_BRANCH: String = "teamcity.pullRequest.sourceBranch"
        const val ALIAS_PR_TARGET_BRANCH: String = "teamcity.pullRequest.targetBranch"

        val LEGACY_ALIAS_KEYS: List<String> = listOf(
            ALIAS_PR_NUMBER,
            ALIAS_PR_TITLE,
            ALIAS_PR_SOURCE_BRANCH,
            ALIAS_PR_TARGET_BRANCH,
        )

        val ALL_KEYS: List<String> = listOf(
            PARAM_IS_PULL_REQUEST,
            PARAM_IS_DRAFT,
            PARAM_PR_NUMBER,
            PARAM_PR_TITLE,
            PARAM_PR_AUTHOR,
            PARAM_PR_SOURCE_BRANCH,
            PARAM_PR_TARGET_BRANCH,
            PARAM_PR_HEAD_SHA,
        )

        val DEFAULT_NON_PR_PARAMS: Map<String, String> = mapOf(
            PARAM_IS_PULL_REQUEST to "false",
            PARAM_IS_DRAFT to "false",
            PARAM_PR_NUMBER to "",
            PARAM_PR_TITLE to "",
            PARAM_PR_AUTHOR to "",
            PARAM_PR_SOURCE_BRANCH to "",
            PARAM_PR_TARGET_BRANCH to "",
            PARAM_PR_HEAD_SHA to "",
        )

        private val LOG = Logger.getInstance(PrParameterProvider::class.java.name)

        // Pure helper - testable without TC SDK fixtures.
        // - PR branches (`pull/N`) with resolved PrInfo -> all fields populated.
        // - PR branches whose PrInfo could not be resolved -> we still
        //   know isPullRequest=true and the number (parsed from the
        //   branch name); the rest defaults to empty for fail-safety.
        // - Plain branch refs with a `headSha`: the PR whose head is that
        //   commit (if any) populates the same fields, so a build launched
        //   on `Feature/x` sees the same parameters as the `pull/N` build
        //   of the same commit. `headSha` is null when the caller has no
        //   revision or the branch->PR lookup is disabled.
        // - Anything else -> DEFAULT_NON_PR_PARAMS (isPullRequest=false,
        //   everything else empty).
        fun computeParams(
            branchName: String?,
            legacyAliases: Boolean = false,
            headSha: String? = null,
            prByCommitResolver: (String) -> PrInfo? = { null },
            resolver: (Int) -> PrInfo?,
        ): Map<String, String> {
            if (branchName == null || !branchName.startsWith("pull/")) {
                val pr = headSha?.takeIf { it.isNotBlank() }?.let { sha ->
                    try {
                        prByCommitResolver(sha)
                    } catch (_: Throwable) {
                        null
                    }
                } ?: return DEFAULT_NON_PR_PARAMS
                return params(pr.number, pr, legacyAliases)
            }
            val prNumber = branchName.removePrefix("pull/").toIntOrNull()
                ?: return DEFAULT_NON_PR_PARAMS

            val pr = try {
                resolver(prNumber)
            } catch (_: Throwable) {
                null
            }
            return params(prNumber, pr, legacyAliases)
        }

        // The PR-context parameter map. `pr` is null when the number is
        // known (from the branch name) but the GitHub lookup failed.
        private fun params(prNumber: Int, pr: PrInfo?, legacyAliases: Boolean): Map<String, String> {
            val params = mutableMapOf(
                PARAM_IS_PULL_REQUEST to "true",
                PARAM_IS_DRAFT to (pr?.draft == true).toString(),
                PARAM_PR_NUMBER to prNumber.toString(),
                PARAM_PR_TITLE to (pr?.title ?: ""),
                PARAM_PR_AUTHOR to (pr?.author ?: ""),
                PARAM_PR_SOURCE_BRANCH to (pr?.headRef ?: ""),
                PARAM_PR_TARGET_BRANCH to (pr?.baseRef ?: ""),
                PARAM_PR_HEAD_SHA to (pr?.headSha ?: ""),
            )
            if (legacyAliases) {
                params[ALIAS_PR_NUMBER] = prNumber.toString()
                params[ALIAS_PR_TITLE] = pr?.title ?: ""
                params[ALIAS_PR_SOURCE_BRANCH] = pr?.headRef ?: ""
                params[ALIAS_PR_TARGET_BRANCH] = pr?.baseRef ?: ""
            }
            return params
        }
    }
}
