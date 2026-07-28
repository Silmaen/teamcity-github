package io.github.dlachouette.teamcity.github.feature

// The `pull/N` logical branch name TeamCity gives a build of a GitHub PR
// ref. Kept in one place: which ref carries a PR build is a per-project
// choice (see `BridgeFeatureConfig.prBuildRef`), so the prefix must not be
// hard-coded at each site that needs to recognise it.
object BridgeRefs {

    const val PR_REF_PREFIX: String = "pull/"

    fun prRef(prNumber: Int): String = "$PR_REF_PREFIX$prNumber"

    // The PR number a `pull/N` ref encodes, or null for anything else
    // (including `pull/` alone, `pull/x` and null).
    fun prNumberFromRef(branchName: String?): Int? {
        if (branchName == null || !branchName.startsWith(PR_REF_PREFIX)) return null
        return branchName.removePrefix(PR_REF_PREFIX).toIntOrNull()?.takeIf { it > 0 }
    }
}
