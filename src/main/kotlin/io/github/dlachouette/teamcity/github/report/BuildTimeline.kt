package io.github.dlachouette.teamcity.github.report

import java.time.Instant
import java.time.temporal.ChronoUnit

// Where a build's wall-clock went, split into the three intervals a
// reviewer on a pull request can act on:
//
//   queuedAt ──── dependencyWait ────┬─── agentWait ───┬── run ──── finishedAt
//                                    │                 │
//                        last dependency            startedAt
//                        finished
//
// The split matters because the two waits have different owners. Time spent
// waiting for a dependency is the build chain's shape (and usually someone
// else's build being slow); time spent waiting for a free agent is the pool
// being too small. "Queued 14 minutes" alone does not say which, and that is
// the number people act on.
// `miscWaitMillis` is the queue time we cannot attribute to either cause —
// TeamCity spends it collecting changes, synchronising the queue, running the
// distribution process. It is accounted for (it is part of the total wait) but
// never named in the report: claiming a cause we did not observe is how the
// first version of this line ended up calling "checking for changes" an agent
// shortage.
data class BuildTimings(
    val dependencyWaitMillis: Long,
    val agentWaitMillis: Long,
    val miscWaitMillis: Long,
    val runMillis: Long,
    // Whether the build HAS snapshot dependencies at all, which is not the same
    // question as whether it waited for them: a dependency that finished long
    // before this build was queued costs nothing yet still deserves its line,
    // so a reader knows the chain was accounted for and not ignored.
    val hasDependencies: Boolean = false,
) {
    val queueMillis: Long get() = dependencyWaitMillis + agentWaitMillis + miscWaitMillis
    val totalMillis: Long get() = queueMillis + runMillis
}

object BuildTimeline {

    // Below this, an interval is noise: TeamCity's own queue breakdown is full
    // of sub-second steps ("queue synchronised <1s", "waiting for the
    // distribution process <1s") and repeating them adds nothing.
    const val SIGNIFICANT_MS: Long = 1_000L

    // All inputs are epoch millis, so this stays a pure function of four
    // numbers and needs no TeamCity fixture to test.
    //
    // `lastDependencyFinishedAt` is the moment the LAST snapshot dependency
    // finished, i.e. the instant this build became eligible for an agent.
    // Everything before it, since the build entered the queue, was spent
    // waiting on the chain; everything after it, until the build started, was
    // spent waiting for an agent.
    //
    // `agentWaitHintMillis` is TeamCity's OWN attribution of part of the wait to
    // agent availability (see `BuildStatusCheckRunPublisher.agentWaitHint`).
    // Only that is reported as an agent shortage; whatever is left over after
    // the dependencies and the hint becomes `misc`. Null (no evidence) puts the
    // whole remainder in `misc`, which the report keeps quiet about — the point
    // being that "we don't know" must not read as "no free agent".
    //
    // Returns null for a build that never started: there is no timeline to
    // report, and a fabricated one would be worse than silence.
    fun compute(
        queuedAt: Long?,
        startedAt: Long?,
        finishedAt: Long?,
        lastDependencyFinishedAt: Long?,
        agentWaitHintMillis: Long? = null,
        hasDependencies: Boolean = lastDependencyFinishedAt != null,
    ): BuildTimings? {
        if (startedAt == null) return null
        val queue = if (queuedAt == null) 0L else (startedAt - queuedAt).coerceAtLeast(0L)
        // A dependency that finished BEFORE this build was even queued cost it
        // nothing, hence the lower clamp; one that "finished" after the build
        // started (clock skew between server and agent, a dependency reused
        // from another chain) cannot have cost more than the whole wait, hence
        // the upper one.
        val dependencyWait = when {
            queuedAt == null || lastDependencyFinishedAt == null -> 0L
            else -> (lastDependencyFinishedAt - queuedAt).coerceIn(0L, queue)
        }
        val run = if (finishedAt == null) 0L else (finishedAt - startedAt).coerceAtLeast(0L)
        val remainder = queue - dependencyWait
        // Clamped, so a hint larger than the wait it is part of (or expressed in
        // a unit we guessed wrong) cannot inflate the report. Erring low sends
        // the difference to `misc`, i.e. to silence.
        val agentWait = (agentWaitHintMillis ?: 0L).coerceIn(0L, remainder)
        return BuildTimings(
            dependencyWaitMillis = dependencyWait,
            agentWaitMillis = agentWait,
            miscWaitMillis = remainder - agentWait,
            runMillis = run,
            hasDependencies = hasDependencies,
        )
    }

    // The timing block that OPENS the Check Run body — three lines, always the
    // same three, so the numbers sit where the eye already knows to look:
    //
    //     - **Total** — 1m 12s
    //     - **Run** — 21s on `AGENT-513`
    //     - **Wait** — 51s (dependencies 38s, free agent 13s, other 1s)
    //
    // Total is wait + run. "Run" is the time the build actually WORKED, never
    // the waiting — conflating the two is what makes a CI report useless.
    //
    // The wait detail always names the free-agent share, even sub-second, since
    // "was the pool the problem?" is a question you answer with a number and not
    // with silence; the dependency share appears whenever the build has
    // dependencies at all, even if they cost it nothing. The unattributed
    // remainder ("other") is only mentioned once it is worth a second — it names
    // no cause, so it earns its place only when it is big enough to explain
    // something.
    fun describeBlock(timings: BuildTimings, agentName: String?): String = buildString {
        append("- **Total** — ").append(human(timings.totalMillis)).append('\n')
        append("- **Run** — ").append(human(timings.runMillis))
        if (!agentName.isNullOrBlank()) append(" on `").append(agentName).append('`')
        append('\n')
        append("- **Wait** — ").append(human(timings.queueMillis))
        val detail = waitDetail(timings)
        if (detail.isNotEmpty()) append(" (").append(detail.joinToString(", ")).append(')')
        append('\n')
    }

    private fun waitDetail(timings: BuildTimings): List<String> = buildList {
        if (timings.hasDependencies) add("dependencies ${human(timings.dependencyWaitMillis)}")
        add("free agent ${human(timings.agentWaitMillis)}")
        if (timings.miscWaitMillis >= SIGNIFICANT_MS) add("other ${human(timings.miscWaitMillis)}")
    }

    // Compact human duration: "42s", "7m 12s", "1h 04m". Sub-second is
    // reported as "<1s" rather than "0s", which reads like a missing value —
    // though `describe` drops such intervals instead of printing them.
    fun human(millis: Long): String {
        if (millis < 0) return "<1s"
        val totalSeconds = millis / 1000
        if (totalSeconds < 1) return "<1s"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h %02dm".format(minutes)
            minutes > 0 && seconds > 0 -> "${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    // GitHub wants ISO-8601 for `started_at` / `completed_at`, and uses them
    // to render the elapsed time itself in the Checks panel. Truncated to the
    // second: sub-second precision is noise GitHub does not display.
    fun iso(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).toString()
}
