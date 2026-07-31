package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.api.CheckRunConclusion
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import jetbrains.buildServer.messages.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BuildStatusCheckRunPublisherTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `maps normal status to success`() {
        val m = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.NORMAL, isInterrupted = false)
        assertEquals(CheckRunConclusion.SUCCESS, m.conclusion)
        assertEquals("Build passed", m.title)
    }

    @Test
    fun `maps warning to success (warnings do not fail builds in TC)`() {
        val m = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.WARNING, isInterrupted = false)
        assertEquals(CheckRunConclusion.SUCCESS, m.conclusion)
    }

    @Test
    fun `maps failure to failure`() {
        val m = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.FAILURE, isInterrupted = false)
        assertEquals(CheckRunConclusion.FAILURE, m.conclusion)
        assertEquals("Build failed", m.title)
    }

    @Test
    fun `maps error to failure`() {
        val m = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.ERROR, isInterrupted = false)
        assertEquals(CheckRunConclusion.FAILURE, m.conclusion)
    }

    @Test
    fun `interrupted overrides any status to cancelled`() {
        val m1 = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.NORMAL, isInterrupted = true)
        val m2 = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.FAILURE, isInterrupted = true)
        assertEquals(CheckRunConclusion.CANCELLED, m1.conclusion)
        assertEquals(CheckRunConclusion.CANCELLED, m2.conclusion)
        assertEquals("Build cancelled", m1.title)
    }

    @Test
    fun `unknown status maps to neutral`() {
        val m = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.UNKNOWN, isInterrupted = false)
        assertEquals(CheckRunConclusion.NEUTRAL, m.conclusion)
    }

    // ----- infrastructure failure vs broken build -----

    private val infra = FailureClassification(FailureKind.INFRASTRUCTURE, "Unable to collect changes")
    private val code = FailureClassification(FailureKind.CODE)
    private val failed = BuildOutcomeMapping(CheckRunConclusion.FAILURE, "Build failed")

    @Test
    fun `a code failure is reported exactly as before`() {
        val m = BuildStatusCheckRunPublisher.refineForFailureCause(failed, code, infraNeutral = true)
        assertEquals(CheckRunConclusion.FAILURE, m.conclusion)
        assertEquals("Build failed", m.title)
    }

    @Test
    fun `an infrastructure failure concludes neutral and names its cause`() {
        val m = BuildStatusCheckRunPublisher.refineForFailureCause(failed, infra, infraNeutral = true)
        assertEquals(CheckRunConclusion.NEUTRAL, m.conclusion)
        assertEquals("Infrastructure failure: Unable to collect changes", m.title)
    }

    // The flag is a merge policy, not a statement of fact: with it off the
    // title still says what broke, the conclusion just stays red.
    @Test
    fun `with the flag off an infrastructure failure stays red but keeps the cause`() {
        val m = BuildStatusCheckRunPublisher.refineForFailureCause(failed, infra, infraNeutral = false)
        assertEquals(CheckRunConclusion.FAILURE, m.conclusion)
        assertEquals("Infrastructure failure: Unable to collect changes", m.title)
    }

    @Test
    fun `a failed snapshot dependency is named but stays red whatever the flag says`() {
        val dep = FailureClassification(FailureKind.DEPENDENCY, "Snapshot dependency failure")
        listOf(true, false).forEach { flag ->
            val m = BuildStatusCheckRunPublisher.refineForFailureCause(failed, dep, infraNeutral = flag)
            assertEquals(CheckRunConclusion.FAILURE, m.conclusion)
            assertEquals("Build failed: Snapshot dependency failure", m.title)
        }
    }

    // A build can report an infrastructural problem and still be green — that
    // is what TeamCity's SNAPSHOT_DEPENDENCY_ERROR_BUILD_PROCEEDS is for — and
    // a cancelled build's conclusion is not up for debate.
    @Test
    fun `only a failure is refined`() {
        listOf(
            CheckRunConclusion.SUCCESS,
            CheckRunConclusion.CANCELLED,
            CheckRunConclusion.NEUTRAL,
        ).forEach { conclusion ->
            val original = BuildOutcomeMapping(conclusion, "Build passed")
            val m = BuildStatusCheckRunPublisher.refineForFailureCause(original, infra, infraNeutral = true)
            assertEquals(original, m)
        }
    }

    @Test
    fun `the note tells a reviewer the commit was not judged`() {
        val note = BuildStatusCheckRunPublisher.infrastructureNote(infra, CheckRunConclusion.NEUTRAL)
        assertNotNull(note)
        assertTrue(note!!.contains("not a problem with the code"))
        assertTrue(note.contains("Unable to collect changes"))
        assertTrue(note.contains("does not block the merge"))
    }

    @Test
    fun `the note says so when the failure is kept red`() {
        val note = BuildStatusCheckRunPublisher.infrastructureNote(infra, CheckRunConclusion.FAILURE)
        assertNotNull(note)
        assertFalse(note!!.contains("does not block the merge"))
    }

    @Test
    fun `no note for a code failure`() {
        assertNull(BuildStatusCheckRunPublisher.infrastructureNote(code, CheckRunConclusion.FAILURE))
        assertNull(
            BuildStatusCheckRunPublisher.infrastructureNote(
                FailureClassification(FailureKind.DEPENDENCY, "Snapshot dependency failure"),
                CheckRunConclusion.FAILURE,
            ),
        )
    }

    @Test
    fun `titles stay within GitHub's limit`() {
        val long = FailureClassification(FailureKind.INFRASTRUCTURE, "x".repeat(BuildStatusCheckRunPublisher.TITLE_MAX * 2))
        val m = BuildStatusCheckRunPublisher.refineForFailureCause(failed, long, infraNeutral = true)
        assertTrue(m.title.length <= BuildStatusCheckRunPublisher.TITLE_MAX)
    }

    @Test
    fun `truncates summary above the limit`() {
        val long = "a".repeat(BuildStatusCheckRunPublisher.SUMMARY_MAX + 100)
        val truncated = BuildStatusCheckRunPublisher.truncateSummary(long)
        assertTrue(truncated.length <= BuildStatusCheckRunPublisher.SUMMARY_MAX + 20)
        assertTrue(truncated.endsWith("(truncated)"))
    }

    @Test
    fun `keeps short summary intact`() {
        val short = "Build OK, no warnings"
        assertEquals(short, BuildStatusCheckRunPublisher.truncateSummary(short))
    }

    @Test
    fun `queued action publishes as soon as the revision is resolved`() {
        assertEquals(QueuedAction.PUBLISH, BuildStatusCheckRunPublisher.decideQueuedAction(revisionReady = true, attempt = 1))
        // A resolved revision publishes even on the last attempt.
        assertEquals(
            QueuedAction.PUBLISH,
            BuildStatusCheckRunPublisher.decideQueuedAction(revisionReady = true, attempt = BuildStatusCheckRunPublisher.MAX_QUEUED_ATTEMPTS),
        )
    }

    // A personal build verifies a patch that is not in the repository, so no
    // Check Run may describe it. Before 1.10.0 one published like any other
    // build, and a manually-triggered personal build left a "Queued" row
    // stuck on the PR for good.
    @Test
    fun `personal builds never publish`() {
        assertFalse(BuildStatusCheckRunPublisher.publishesFor(personal = true))
        assertTrue(BuildStatusCheckRunPublisher.publishesFor(personal = false))
    }

    @Test
    fun `queued action retries while the revision is not resolved yet`() {
        assertEquals(QueuedAction.RETRY, BuildStatusCheckRunPublisher.decideQueuedAction(revisionReady = false, attempt = 1))
        assertEquals(
            QueuedAction.RETRY,
            BuildStatusCheckRunPublisher.decideQueuedAction(revisionReady = false, attempt = BuildStatusCheckRunPublisher.MAX_QUEUED_ATTEMPTS - 1),
        )
    }

    @Test
    fun `queued action gives up once the attempt budget is exhausted`() {
        assertEquals(
            QueuedAction.GIVE_UP,
            BuildStatusCheckRunPublisher.decideQueuedAction(revisionReady = false, attempt = BuildStatusCheckRunPublisher.MAX_QUEUED_ATTEMPTS),
        )
        assertEquals(
            QueuedAction.GIVE_UP,
            BuildStatusCheckRunPublisher.decideQueuedAction(revisionReady = false, attempt = BuildStatusCheckRunPublisher.MAX_QUEUED_ATTEMPTS + 5),
        )
    }

    // The opt-in gate moved from buildType.parameters to the
    // "GitHub Bridge integration" BuildFeature in v1.5.0. The
    // equivalent of the previous `isOptedIn` tests now lives in
    // BridgeFeatureConfigTest exercising BridgeFeatureReader.fromParams.

    // G14: output.text stitches the optional Markdown sections together.
    // The merge box shows one line; it should say whether the reviewer's own
    // diff is implicated.
    @Test
    fun `the title carries the test verdict when tests ran`() {
        val failed = TestCounts(total = 1046, passed = 1043, failed = 3, ignored = 0, muted = 0, newFailed = 2)
        assertEquals(
            "Build failed — 3 of 1046 tests failed (2 new)",
            BuildStatusCheckRunPublisher.titleWithTests("Build failed", failed),
        )
        // No tests, or the feature off: the title is untouched.
        assertEquals("Build failed", BuildStatusCheckRunPublisher.titleWithTests("Build failed", null))
        assertEquals(
            "Build passed",
            BuildStatusCheckRunPublisher.titleWithTests("Build passed", TestCounts(0, 0, 0, 0, 0, 0)),
        )
    }

    @Test
    fun `the title stays within GitHub's limit`() {
        val huge = TestCounts(total = 1, passed = 0, failed = 1, ignored = 0, muted = 0, newFailed = 1)
        val title = BuildStatusCheckRunPublisher.titleWithTests("x".repeat(300), huge)
        assertEquals(BuildStatusCheckRunPublisher.TITLE_MAX, title.length)
    }

    // A finished build whose descriptor still says "Running" (TeamCity has not
    // recomputed it when buildFinished fires) must not publish that as its
    // summary — a green Check Run summarised "Running" is simply wrong.
    @Test
    fun `the stale running status text is not published`() {
        assertFalse(BuildStatusCheckRunPublisher.isInformativeStatusText("Running"))
        assertFalse(BuildStatusCheckRunPublisher.isInformativeStatusText("  running  "))
        assertFalse(BuildStatusCheckRunPublisher.isInformativeStatusText("Running: step 2 of 5"))
        assertFalse(BuildStatusCheckRunPublisher.isInformativeStatusText("   "))
    }

    // Relaying TeamCity's real status text instead of GitHub's canned wording is
    // the point of the plugin, so everything else goes through.
    @Test
    fun `a real status text is published`() {
        assertTrue(BuildStatusCheckRunPublisher.isInformativeStatusText("Tests passed: 5"))
        assertTrue(BuildStatusCheckRunPublisher.isInformativeStatusText("Exit code 1"))
        assertTrue(BuildStatusCheckRunPublisher.isInformativeStatusText("Tests failed: 3 (1 new), passed: 1043"))
    }

    @Test
    fun `joinSections keeps the non-blank sections in order`() {
        assertEquals(
            "### Failure details\n\n- boom\n\n### Artifacts\n\n- [app.zip](u)\n",
            BuildStatusCheckRunPublisher.joinSections("### Failure details\n\n- boom\n", "### Artifacts\n\n- [app.zip](u)\n"),
        )
    }

    @Test
    fun `joinSections yields null when there is nothing to say`() {
        assertNull(BuildStatusCheckRunPublisher.joinSections(null, null))
        assertNull(BuildStatusCheckRunPublisher.joinSections(null, "   "))
    }

    @Test
    fun `joinSections passes a single section through`() {
        assertEquals("only\n", BuildStatusCheckRunPublisher.joinSections(null, "only\n"))
    }
}
