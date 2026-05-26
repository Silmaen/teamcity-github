package io.github.dlachouette.teamcity.github.parameters

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
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
) : AbstractBuildParametersProvider() {

    override fun getParameters(build: SBuild, emulationMode: Boolean): Map<String, String> {
        return try {
            val buildType = build.buildType ?: return emptyMap()
            val params = buildType.parameters
            val repoSlug = params[DraftAwareBuildFilter.PARAM_REPO_SLUG]
            val connectionId = params[DraftAwareBuildFilter.PARAM_CONNECTION_ID]
            if (repoSlug.isNullOrBlank() || connectionId.isNullOrBlank()) return emptyMap()
            val repo = try {
                RepoCoords.parse(repoSlug)
            } catch (e: IllegalArgumentException) {
                return emptyMap()
            }

            computeParams(
                branchName = build.branch?.name,
                resolver = { number ->
                    val access = tokenResolver.resolveAccessToken(buildType.project, connectionId, repo)
                        ?: return@computeParams null
                    prInfoCache.get(repo, number, access.token, access.apiBase)
                },
            )
        } catch (e: Exception) {
            LOG.warn("Failed computing PR parameters for build ${build.buildId}: ${e.message}", e)
            DEFAULT_NON_PR_PARAMS
        }
    }

    override fun getParametersAvailableOnAgent(build: SBuild): Collection<String> {
        val params = build.buildType?.parameters ?: return emptyList()
        return if (params.containsKey(DraftAwareBuildFilter.PARAM_REPO_SLUG) &&
            params.containsKey(DraftAwareBuildFilter.PARAM_CONNECTION_ID)
        ) ALL_KEYS else emptyList()
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
        // - Non-PR branches -> DEFAULT_NON_PR_PARAMS (isPullRequest=false, everything else empty).
        // - PR branches with resolved PrInfo -> all fields populated.
        // - PR branches whose PrInfo could not be resolved -> we still
        //   know isPullRequest=true and the number (parsed from the
        //   branch name); the rest defaults to empty for fail-safety.
        fun computeParams(
            branchName: String?,
            resolver: (Int) -> PrInfo?,
        ): Map<String, String> {
            if (branchName == null || !branchName.startsWith("pull/")) {
                return DEFAULT_NON_PR_PARAMS
            }
            val prNumber = branchName.removePrefix("pull/").toIntOrNull()
                ?: return DEFAULT_NON_PR_PARAMS

            val pr = try {
                resolver(prNumber)
            } catch (_: Throwable) {
                null
            }
            return mapOf(
                PARAM_IS_PULL_REQUEST to "true",
                PARAM_IS_DRAFT to (pr?.draft == true).toString(),
                PARAM_PR_NUMBER to prNumber.toString(),
                PARAM_PR_TITLE to (pr?.title ?: ""),
                PARAM_PR_AUTHOR to (pr?.author ?: ""),
                PARAM_PR_SOURCE_BRANCH to (pr?.headRef ?: ""),
                PARAM_PR_TARGET_BRANCH to (pr?.baseRef ?: ""),
                PARAM_PR_HEAD_SHA to (pr?.headSha ?: ""),
            )
        }
    }
}
