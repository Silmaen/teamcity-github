package io.github.dlachouette.teamcity.github.feature

// How a build came to exist, from the bridge's point of view.
//
// The distinction matters for the SOFT gates (branch list, PR-metadata
// filters): they exist to keep *automatic* triggers narrow, and must never
// silence an explicit request. Without it, every filter that keeps a build
// configuration off the automatic path also killed its on-demand build —
// the bridge enqueued it and `DraftBuildQueueCleaner` removed it again.
enum class BridgeTrigger {
    // A `pull_request` webhook, a VCS trigger, a schedule: every gate applies.
    AUTO,

    // An explicit GitHub-side command: a PR comment carrying the trigger
    // phrase, a review approval on a run-on-approval build, the "Re-run"
    // button, or `POST /api/trigger`. Treated exactly like MANUAL.
    COMMAND,

    // A user clicked "Run" in the TeamCity UI.
    MANUAL;

    // True for anything a human (or a human's command) asked for
    // explicitly. HARD blocks still apply; SOFT filters do not.
    val isExplicit: Boolean get() = this != AUTO
}

// The bridge stamps command-triggered builds with a custom build parameter
// so the queue-time sites (cleaner, start precondition, Check Run
// publisher) can tell them apart from an automatic PR event. A parameter
// rather than an internal attribute: it survives into the finished build,
// where it is both auditable and usable from a build script
// (`%teamcity.github.bridge.triggerSource%`).
object BridgeTriggerMarker {

    const val PARAM: String = "teamcity.github.bridge.triggerSource"

    // Parameter map to stamp on a promotion the bridge enqueues itself.
    fun parametersFor(trigger: BridgeTrigger): Map<String, String> =
        mapOf(PARAM to trigger.name.lowercase())

    // Classify a queued/running build. `triggeredByUser` comes from
    // TeamCity (`TriggeredBy.isTriggeredByUser`); the marker only ever
    // upgrades AUTO to COMMAND, so a real user trigger always wins.
    fun of(customParameters: Map<String, String>, triggeredByUser: Boolean): BridgeTrigger = when {
        triggeredByUser -> BridgeTrigger.MANUAL
        customParameters[PARAM].equals(BridgeTrigger.COMMAND.name, ignoreCase = true) -> BridgeTrigger.COMMAND
        else -> BridgeTrigger.AUTO
    }
}
