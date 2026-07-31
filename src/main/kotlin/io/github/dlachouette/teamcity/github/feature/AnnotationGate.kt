package io.github.dlachouette.teamcity.github.feature

// May the bridge annotate the pull request's diff for this build?
//
// Three levels can each say no — the server, every project in the chain, and
// the build configuration's feature — and the answer is the **AND** of all of
// them. One `false` anywhere wins.
//
// The asymmetry is deliberate. A level that says nothing abstains, and a level
// that says `true` does not overrule an ancestor's `false`: turning
// annotations off for a project tree has to be enforceable in *one* place, or
// it is not enforceable at all. Somebody who wants them back removes the `no`
// where it was written.
//
// Only annotations work this way. The other Check Run content is a server-wide
// flag, because it costs nothing and offends nobody; annotations write on the
// reviewer's diff, which is the one place a team may legitimately want quiet.
object AnnotationGate {

    // The verdict. `projectChain` carries each project's **own** value, from
    // the root down — own and not resolved, because a resolved read collapses
    // the chain to its nearest definition and the whole point here is that a
    // `no` further up still counts.
    fun enabled(serverEnabled: Boolean, projectChain: List<String?>, feature: String?): Boolean =
        serverEnabled && projectChain.none { isFalse(it) } && !isFalse(feature)

    // The name of the outermost project that says no, for the settings form: a
    // checkbox rendering "on" while an ancestor holds it off is a lie, and the
    // user then goes looking for a bug in the plugin.
    fun vetoingProject(chain: List<Pair<String, String?>>): String? =
        chain.firstOrNull { isFalse(it.second) }?.first

    // Only the literal `false` decides. Anything else — absent, blank, `true`,
    // a typo — abstains: a misspelt value must not silently turn a reporting
    // feature off.
    private fun isFalse(raw: String?): Boolean = raw?.trim().equals("false", ignoreCase = true)
}
