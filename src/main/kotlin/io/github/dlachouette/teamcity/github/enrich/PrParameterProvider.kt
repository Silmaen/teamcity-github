package io.github.dlachouette.teamcity.github.enrich

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.api.PrInfoCache
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

        // --- what the pull request is and what it changes (1.10.0) ---

        // The PR's page on GitHub: the one value that lets a build, a script or
        // a notification point back at what it is judging.
        const val PARAM_PR_URL: String = "teamcity.github.bridge.pullRequest.url"

        // The base branch's head at the time of the event. Kept distinct from
        // the merge base on purpose — see below.
        const val PARAM_PR_BASE_SHA: String = "teamcity.github.bridge.pullRequest.baseSha"

        // Where the branches diverged. **This** is what a diff-scoped step must
        // compare against: `git diff <mergeBase>..<headSha>` is the pull
        // request's own change, while diffing against the base branch's head
        // also shows everything that landed on the base since. Empty when
        // `mergeBase.enabled` is off or the lookup failed — a step that needs it
        // should say so rather than silently diff the wrong range.
        const val PARAM_PR_MERGE_BASE: String = "teamcity.github.bridge.pullRequest.mergeBase"

        // Size of the change, for a gate that does not want the file list.
        const val PARAM_PR_CHANGED_FILES: String = "teamcity.github.bridge.pullRequest.changedFiles"
        const val PARAM_PR_ADDITIONS: String = "teamcity.github.bridge.pullRequest.additions"
        const val PARAM_PR_DELETIONS: String = "teamcity.github.bridge.pullRequest.deletions"
        const val PARAM_PR_COMMITS: String = "teamcity.github.bridge.pullRequest.commits"

        // Comma-separated label names, in GitHub's order. Same list the
        // metadata gate filters on, exposed so a build step can act on it too.
        const val PARAM_PR_LABELS: String = "teamcity.github.bridge.pullRequest.labels"

        // Aliases under the bundled `pullRequests` feature's namespace,
        // emitted only when the operator opts in (legacyAliases.enabled).
        // Lets teams migrate off the bundled feature without rewriting DSL
        // that still reads `teamcity.pullRequest.*`.
        //
        // The two branch names are emitted **twice**, because the bundled
        // feature spells them with dots — `teamcity.pullRequest.source.branch`
        // — and this plugin's own namespace spells them camelCase. Only the
        // dotted spelling makes existing DSL work unchanged, which is the whole
        // point of the flag; the camelCase pair shipped first (1.7.0) and is
        // kept so a team that already reads it does not break on an upgrade.
        const val ALIAS_PR_NUMBER: String = "teamcity.pullRequest.number"
        const val ALIAS_PR_TITLE: String = "teamcity.pullRequest.title"
        const val ALIAS_PR_SOURCE_BRANCH: String = "teamcity.pullRequest.sourceBranch"
        const val ALIAS_PR_TARGET_BRANCH: String = "teamcity.pullRequest.targetBranch"
        const val ALIAS_PR_SOURCE_BRANCH_DOTTED: String = "teamcity.pullRequest.source.branch"
        const val ALIAS_PR_TARGET_BRANCH_DOTTED: String = "teamcity.pullRequest.target.branch"

        val LEGACY_ALIAS_KEYS: List<String> = listOf(
            ALIAS_PR_NUMBER,
            ALIAS_PR_TITLE,
            ALIAS_PR_SOURCE_BRANCH,
            ALIAS_PR_TARGET_BRANCH,
            ALIAS_PR_SOURCE_BRANCH_DOTTED,
            ALIAS_PR_TARGET_BRANCH_DOTTED,
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
            PARAM_PR_URL,
            PARAM_PR_BASE_SHA,
            PARAM_PR_MERGE_BASE,
            PARAM_PR_CHANGED_FILES,
            PARAM_PR_ADDITIONS,
            PARAM_PR_DELETIONS,
            PARAM_PR_COMMITS,
            PARAM_PR_LABELS,
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
            PARAM_PR_URL to "",
            PARAM_PR_BASE_SHA to "",
            PARAM_PR_MERGE_BASE to "",
            PARAM_PR_CHANGED_FILES to "",
            PARAM_PR_ADDITIONS to "",
            PARAM_PR_DELETIONS to "",
            PARAM_PR_COMMITS to "",
            PARAM_PR_LABELS to "",
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

        private fun count(n: Int?): String = if (n == null || n <= 0) "" else n.toString()

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
                PARAM_PR_URL to (pr?.htmlUrl ?: ""),
                PARAM_PR_BASE_SHA to (pr?.baseSha ?: ""),
                PARAM_PR_MERGE_BASE to (pr?.mergeBaseSha ?: ""),
                // A count is emitted only when GitHub actually sent one: the
                // objects in `GET /commits/{sha}/pulls` carry no counts, and "0"
                // would read as "this PR changes nothing".
                PARAM_PR_CHANGED_FILES to count(pr?.changedFiles),
                PARAM_PR_ADDITIONS to count(pr?.additions),
                PARAM_PR_DELETIONS to count(pr?.deletions),
                PARAM_PR_COMMITS to count(pr?.commits),
                PARAM_PR_LABELS to (pr?.labels?.joinToString(",") ?: ""),
            )
            if (legacyAliases) {
                params[ALIAS_PR_NUMBER] = prNumber.toString()
                params[ALIAS_PR_TITLE] = pr?.title ?: ""
                params[ALIAS_PR_SOURCE_BRANCH] = pr?.headRef ?: ""
                params[ALIAS_PR_TARGET_BRANCH] = pr?.baseRef ?: ""
                params[ALIAS_PR_SOURCE_BRANCH_DOTTED] = pr?.headRef ?: ""
                params[ALIAS_PR_TARGET_BRANCH_DOTTED] = pr?.baseRef ?: ""
            }
            return params
        }
    }
}
