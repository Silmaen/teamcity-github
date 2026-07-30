package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildTimelineTest {

    init { LoggerBootstrap.install() }

    private val t0 = 1_800_000_000_000L // arbitrary epoch millis; only deltas matter

    @Test
    fun `splits the wait between the dependencies and what TeamCity blames on agents`() {
        val timings = BuildTimeline.compute(
            queuedAt = t0,
            // 3 minutes waiting on the chain, then 1 minute TeamCity attributes
            // to there being no free compatible agent.
            lastDependencyFinishedAt = t0 + 180_000,
            startedAt = t0 + 240_000,
            finishedAt = t0 + 240_000 + 60_000,
            agentWaitHintMillis = 60_000,
        )!!
        assertEquals(180_000, timings.dependencyWaitMillis)
        assertEquals(60_000, timings.agentWaitMillis)
        assertEquals(0, timings.miscWaitMillis)
        assertEquals(240_000, timings.queueMillis)
        assertEquals(60_000, timings.runMillis)
        assertEquals(300_000, timings.totalMillis)
    }

    // The heart of the matter: the dates say HOW LONG a build waited past its
    // dependencies, never WHY. Without TeamCity's own attribution that time is
    // unattributed — never blamed on the agent pool, which is what the first
    // version of this got wrong (it called "checking for changes" an agent
    // shortage).
    @Test
    fun `without TeamCity's attribution the rest of the wait stays unattributed`() {
        val timings = BuildTimeline.compute(
            queuedAt = t0,
            lastDependencyFinishedAt = null,
            startedAt = t0 + 45_000,
            finishedAt = t0 + 45_000 + 1_000,
            agentWaitHintMillis = null,
        )!!
        assertEquals(0, timings.dependencyWaitMillis)
        assertEquals(0, timings.agentWaitMillis)
        assertEquals(45_000, timings.miscWaitMillis)
        // Unattributed or not, it is still part of the wait the report states.
        assertEquals(45_000, timings.queueMillis)
    }

    // A hint bigger than the wait it is a part of — or one whose unit we
    // guessed wrong — must not inflate the report.
    @Test
    fun `the agent hint is clamped to the wait that remains`() {
        val timings = BuildTimeline.compute(
            queuedAt = t0,
            lastDependencyFinishedAt = t0 + 30_000,
            startedAt = t0 + 40_000,
            finishedAt = t0 + 40_000,
            agentWaitHintMillis = 999_000,
        )!!
        assertEquals(30_000, timings.dependencyWaitMillis)
        assertEquals(10_000, timings.agentWaitMillis)
        assertEquals(0, timings.miscWaitMillis)
    }

    // A dependency reused from an earlier chain finished before this build was
    // even queued: it cost this build nothing.
    @Test
    fun `a dependency that finished before the build was queued costs nothing`() {
        val timings = BuildTimeline.compute(
            queuedAt = t0,
            lastDependencyFinishedAt = t0 - 600_000,
            startedAt = t0 + 20_000,
            finishedAt = t0 + 20_000,
        )!!
        assertEquals(0, timings.dependencyWaitMillis)
        assertEquals(20_000, timings.miscWaitMillis)
    }

    // Server/agent clock skew, or a dependency that reports a finish date past
    // our start: the wait cannot exceed the queue time it is a part of.
    @Test
    fun `a dependency finishing after the start is clamped to the queue time`() {
        val timings = BuildTimeline.compute(
            queuedAt = t0,
            lastDependencyFinishedAt = t0 + 999_000,
            startedAt = t0 + 30_000,
            finishedAt = t0 + 30_000,
        )!!
        assertEquals(30_000, timings.dependencyWaitMillis)
        assertEquals(0, timings.agentWaitMillis)
        assertEquals(0, timings.miscWaitMillis)
    }

    @Test
    fun `a build that never started has no timeline`() {
        assertNull(BuildTimeline.compute(queuedAt = t0, startedAt = null, finishedAt = null, lastDependencyFinishedAt = null))
    }

    // The run time comes from the dates, and a null finish date is exactly what
    // buildFinished hands us — the publisher substitutes "now" there, because
    // reading zero made every build report "Ran <1s".
    @Test
    fun `a running build reports its wait but no run time yet`() {
        val timings = BuildTimeline.compute(
            queuedAt = t0,
            lastDependencyFinishedAt = null,
            startedAt = t0 + 5_000,
            finishedAt = null,
        )!!
        assertEquals(5_000, timings.queueMillis)
        assertEquals(0, timings.runMillis)
    }

    @Test
    fun `formats durations compactly`() {
        assertEquals("<1s", BuildTimeline.human(0))
        assertEquals("<1s", BuildTimeline.human(999))
        assertEquals("42s", BuildTimeline.human(42_000))
        assertEquals("7m", BuildTimeline.human(420_000))
        assertEquals("7m 12s", BuildTimeline.human(432_000))
        assertEquals("1h 04m", BuildTimeline.human(3_840_000))
    }

    // The block that opens the Check Run body: three fixed lines, so the eye
    // always finds the same number in the same place.
    @Test
    fun `the timing block states the total, the run and the wait`() {
        val timings = BuildTimings(
            dependencyWaitMillis = 180_000,
            agentWaitMillis = 60_000,
            miscWaitMillis = 1_000,
            runMillis = 432_000,
            hasDependencies = true,
        )
        assertEquals(
            """
            - **Total** — 11m 13s
            - **Run** — 7m 12s on `agent-3`
            - **Wait** — 4m 1s (dependencies 3m, free agent 1m, other 1s)
            """.trimIndent() + "\n",
            BuildTimeline.describeBlock(timings, "agent-3"),
        )
    }

    // The free-agent share answers "was the pool the problem?", so it is stated
    // even when the answer is "no" — silence there reads as missing data.
    @Test
    fun `the free-agent share is always stated, even sub-second`() {
        val timings = BuildTimings(
            dependencyWaitMillis = 0,
            agentWaitMillis = 200,
            miscWaitMillis = 300,
            runMillis = 22_000,
            hasDependencies = false,
        )
        assertEquals(
            """
            - **Total** — 22s
            - **Run** — 22s on `AGENT-510`
            - **Wait** — <1s (free agent <1s)
            """.trimIndent() + "\n",
            BuildTimeline.describeBlock(timings, "AGENT-510"),
        )
    }

    // A dependency that cost nothing still gets its line: it tells the reader
    // the chain was accounted for rather than ignored.
    @Test
    fun `a build with dependencies shows their share even when it is zero`() {
        val timings = BuildTimings(
            dependencyWaitMillis = 0,
            agentWaitMillis = 30_000,
            miscWaitMillis = 0,
            runMillis = 5_000,
            hasDependencies = true,
        )
        assertTrue(
            BuildTimeline.describeBlock(timings, null).contains("(dependencies <1s, free agent 30s)"),
            BuildTimeline.describeBlock(timings, null),
        )
    }

    // "other" names no cause, so it only earns a mention once it is big enough
    // to explain something.
    @Test
    fun `the unattributed remainder is mentioned only when it is worth a second`() {
        val noisy = BuildTimings(
            dependencyWaitMillis = 0, agentWaitMillis = 0, miscWaitMillis = 900, runMillis = 1_000,
        )
        assertFalse(BuildTimeline.describeBlock(noisy, null).contains("other"))

        val real = noisy.copy(miscWaitMillis = 9_000)
        assertTrue(BuildTimeline.describeBlock(real, null).contains("other 9s"))
    }

    @Test
    fun `the agent is named only when there is one`() {
        val timings = BuildTimings(dependencyWaitMillis = 0, agentWaitMillis = 0, miscWaitMillis = 0, runMillis = 12_000)
        assertTrue(BuildTimeline.describeBlock(timings, null).contains("- **Run** — 12s\n"))
        assertTrue(BuildTimeline.describeBlock(timings, "  ").contains("- **Run** — 12s\n"))
    }

    // GitHub renders the elapsed time from these, so the format has to be the
    // ISO-8601 instant it documents — seconds precision, UTC, trailing Z.
    @Test
    fun `formats instants as ISO-8601 UTC truncated to the second`() {
        assertEquals("2026-07-30T14:26:19Z", BuildTimeline.iso(1785421579_123))
    }
}
