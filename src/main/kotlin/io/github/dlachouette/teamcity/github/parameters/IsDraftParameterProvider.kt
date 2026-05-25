package io.github.dlachouette.teamcity.github.parameters

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.parameters.AbstractBuildParametersProvider

// Exposes `teamcity.github.bridge.isdraft` to every opted-in build. Value
// is "true" exactly when the build runs on a pull/N branch and the
// PR's `draft` field is true; "false" in every other case (not a PR,
// PR not draft, PR could not be resolved, missing token, etc.).
//
// Closes section 5.1 of the knowledge base (`teamcity.pullRequest.isDraft`
// was never published by TC's bundled `pullRequests` feature, even
// though the docs hint at it). Now buildSteps and DSL conditions
// can use it directly:
//
//     ##teamcity[buildStatus status='SUCCESS']
//     conditions { equals("teamcity.github.bridge.isdraft", "false") }
//
// The parameter is published on the server side (visible in TC's
// build params view) AND exposed to the agent (build steps can read
// it via %teamcity.github.bridge.isdraft%).
class IsDraftParameterProvider(
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
) : AbstractBuildParametersProvider() {

    override fun getParameters(build: SBuild, emulationMode: Boolean): Map<String, String> {
        return try {
            val buildType = build.buildType ?: return emptyMap()
            val params = buildType.parameters
            val repoSlug = params[DraftAwareBuildFilter.PARAM_REPO_SLUG]
            val connectionId = params[DraftAwareBuildFilter.PARAM_CONNECTION_ID]
            // Opt-in: only emit the parameter for buildTypes that have
            // the plugin's repo+connection wiring. Other buildTypes do
            // not know about the plugin and should not see the var,
            // otherwise downstream conditions would have ambiguous
            // semantics.
            if (repoSlug.isNullOrBlank() || connectionId.isNullOrBlank()) return emptyMap()

            val isDraft = computeIsDraft(
                branchName = build.branch?.name,
                resolver = { repo, prNumber ->
                    val token = tokenResolver.resolveAccessToken(buildType.project, connectionId) ?: return@computeIsDraft null
                    prInfoCache.get(repo, prNumber, token)
                },
                repoCoordsParse = { RepoCoords.parse(repoSlug) },
            )
            mapOf(PARAM_NAME to isDraft.toString())
        } catch (e: Exception) {
            LOG.warn("Failed computing $PARAM_NAME for build ${build.buildId}: ${e.message}", e)
            // Fail safe: emit "false" rather than nothing, so DSL
            // conditions that reference the var continue to evaluate.
            mapOf(PARAM_NAME to "false")
        }
    }

    override fun getParametersAvailableOnAgent(build: SBuild): Collection<String> {
        // Cheap pre-check: only expose to the agent when the buildType
        // is opted in. Avoids leaking a noop parameter to every build.
        val params = build.buildType?.parameters ?: return emptyList()
        return if (params.containsKey(DraftAwareBuildFilter.PARAM_REPO_SLUG) &&
            params.containsKey(DraftAwareBuildFilter.PARAM_CONNECTION_ID)
        ) listOf(PARAM_NAME) else emptyList()
    }

    override fun getPrefix(): String = "teamcity.github.bridge"

    companion object {
        const val PARAM_NAME: String = "teamcity.github.bridge.isdraft"
        private val LOG = Logger.getInstance(IsDraftParameterProvider::class.java.name)

        // Pure helper - testable without TC SDK fixtures.
        // - Non-PR branches → false (the parameter is meaningful only
        //   for PR builds; main, tags etc. are never "draft").
        // - PR branch but PR couldn't be resolved → false (fail-safe).
        // - PR found and draft=true → true.
        // - PR found and draft=false → false.
        fun computeIsDraft(
            branchName: String?,
            resolver: (RepoCoords, Int) -> PrInfo?,
            repoCoordsParse: () -> RepoCoords,
        ): Boolean {
            if (branchName == null || !branchName.startsWith("pull/")) return false
            val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return false
            val pr = try {
                resolver(repoCoordsParse(), prNumber)
            } catch (_: Throwable) {
                null
            }
            return pr?.draft == true
        }
    }
}
