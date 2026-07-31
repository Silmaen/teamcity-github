package io.github.dlachouette.teamcity.github.queue

// What a running build looks like to the obsolete-build rules. Flattened out of
// `SRunningBuild` so the rules are pure functions: the listener does the SDK
// reading, this decides.
data class RunningBuildFacts(
    val personal: Boolean,
    val triggeredByUser: Boolean,
    val branchName: String?,
    val revisions: List<String>,
)

// May the bridge stop a build whose result nobody will read any more?
//
// Two things make a started build pointless, and TeamCity handles neither:
//
//  - **a new push** — the builds running on the previous head keep an agent busy
//    to produce a verdict about a commit nobody will look at again, and leave an
//    `in_progress` Check Run on it. TeamCity drops obsolete *queued* builds by
//    itself; started ones it keeps.
//  - **a closed or merged pull request** — there is no longer anywhere for the
//    verdict to go.
//
// The sibling of `QueueCleanupPolicy`, and it holds the same scope invariant:
// the bridge only ever takes away a build it could have started itself.
object ObsoleteBuildPolicy {

    // A new commit was pushed to the pull request.
    fun stopsSuperseded(
        build: RunningBuildFacts,
        prRef: String,
        newHeadSha: String,
        // Is another build of the same build configuration and ref queued or
        // running for the new head? See the last guard below.
        replacementInFlight: Boolean,
        cleanupEnabled: Boolean,
        featureEnabled: Boolean,
    ): Boolean {
        if (!mayStop(build, prRef, cleanupEnabled, featureEnabled)) return false

        // We do not know what it is building — TeamCity resolves revisions in
        // the background — so we do not get to call it obsolete. (For a closed
        // PR we do: there the whole ref is dead, whatever it is building.)
        if (build.revisions.isEmpty()) return false

        // It IS building the new head. This is the common case for the build
        // this very event just enqueued.
        if (build.revisions.contains(newHeadSha)) return false

        // Last line: never cancel the only build in flight for that ref. It is
        // what keeps an out-of-order webhook delivery from leaving the pull
        // request with nothing running at all — and, since the replacement is
        // normally the build this event enqueued a moment earlier, it also
        // means a suppressed PR (a draft, a filtered branch) keeps whatever it
        // already had running rather than losing it for nothing.
        return replacementInFlight
    }

    // The pull request was closed or merged. No revision test and no
    // replacement test: nothing will replace these builds, and that is the
    // point — leaving the ref with nothing running is the desired end state,
    // not the accident it would be on a push.
    fun stopsForClosedPr(
        build: RunningBuildFacts,
        prRef: String,
        cleanupEnabled: Boolean,
        featureEnabled: Boolean,
    ): Boolean = mayStop(build, prRef, cleanupEnabled, featureEnabled)

    // The guards both cases share: the switches, and the scope invariant.
    private fun mayStop(
        build: RunningBuildFacts,
        prRef: String,
        // The `queueCleanup.enabled` master switch: off means the bridge never
        // takes a build away, whatever else says.
        cleanupEnabled: Boolean,
        // `cancelObsolete.enabled`, the switch for stopping *running* builds.
        featureEnabled: Boolean,
    ): Boolean {
        if (!cleanupEnabled || !featureEnabled) return false

        // A personal build verifies a patch that is not in the repository, so
        // neither a push nor a closed PR speaks for it — and the bridge never
        // enqueued it. Same invariant as everywhere else: personal builds are
        // outside the bridge's queue handling in both directions.
        if (build.personal) return false

        // Somebody pressed Run. Their build is theirs, and neither a push nor a
        // closed PR is a reason for a plugin to stop it: they may well be
        // watching it.
        if (build.triggeredByUser) return false

        // Another ref entirely.
        return build.branchName == prRef
    }
}
