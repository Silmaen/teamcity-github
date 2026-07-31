package io.github.dlachouette.teamcity.github.queue

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObsoleteBuildPolicyTest {

    private val oldSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val newSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val ref = "Feature/toto"

    // A build of the PR's ref, running on the head the push replaced.
    private fun build(
        personal: Boolean = false,
        triggeredByUser: Boolean = false,
        branchName: String? = ref,
        revisions: List<String> = listOf(oldSha),
    ) = RunningBuildFacts(personal, triggeredByUser, branchName, revisions)

    private fun superseded(
        build: RunningBuildFacts = build(),
        replacementInFlight: Boolean = true,
        cleanupEnabled: Boolean = true,
        featureEnabled: Boolean = true,
    ): Boolean = ObsoleteBuildPolicy.stopsSuperseded(
        build = build,
        prRef = ref,
        newHeadSha = newSha,
        replacementInFlight = replacementInFlight,
        cleanupEnabled = cleanupEnabled,
        featureEnabled = featureEnabled,
    )

    private fun closed(
        build: RunningBuildFacts = build(),
        cleanupEnabled: Boolean = true,
        featureEnabled: Boolean = true,
    ): Boolean = ObsoleteBuildPolicy.stopsForClosedPr(
        build = build,
        prRef = ref,
        cleanupEnabled = cleanupEnabled,
        featureEnabled = featureEnabled,
    )

    // ----- a new push -----

    @Test
    fun `a build of the PR's ref on the previous head is superseded`() {
        assertTrue(superseded())
    }

    @Test
    fun `a build already on the new head is not obsolete`() {
        assertFalse(superseded(build(revisions = listOf(newSha))))
        // Several VCS roots: the PR's revision being among them is enough.
        assertFalse(superseded(build(revisions = listOf("cccccccc", newSha))))
    }

    // TeamCity resolves revisions in the background: a build whose revisions
    // are not known yet is not a build we get to call superseded.
    @Test
    fun `a build with no resolved revision survives a push`() {
        assertFalse(superseded(build(revisions = emptyList())))
    }

    // The invariant that makes an out-of-order webhook delivery harmless, and
    // that keeps a suppressed PR (draft, filtered branch) from losing the only
    // build it had running.
    @Test
    fun `never the last build in flight for the ref`() {
        assertFalse(superseded(replacementInFlight = false))
    }

    // ----- a closed or merged PR -----

    @Test
    fun `a closed PR stops the builds of its ref`() {
        assertTrue(closed())
    }

    // Both push-specific guards are deliberately absent here: nothing will
    // replace these builds, and leaving the ref with nothing running is the
    // desired end state rather than an accident.
    @Test
    fun `a closed PR stops a build whatever it is building and with no replacement`() {
        assertTrue(closed(build(revisions = emptyList())))
        assertTrue(closed(build(revisions = listOf(newSha))))
    }

    // ----- the guards both cases share -----

    @Test
    fun `its own switch turns both off`() {
        assertFalse(superseded(featureEnabled = false))
        assertFalse(closed(featureEnabled = false))
    }

    // The master switch promises the bridge never takes a build away, and
    // stopping a running one is taking it away.
    @Test
    fun `the queue-cleanup master switch turns both off too`() {
        assertFalse(superseded(cleanupEnabled = false))
        assertFalse(closed(cleanupEnabled = false))
    }

    @Test
    fun `never a personal build`() {
        assertFalse(superseded(build(personal = true)))
        assertFalse(closed(build(personal = true)))
    }

    @Test
    fun `never a build somebody started by hand`() {
        assertFalse(superseded(build(triggeredByUser = true)))
        assertFalse(closed(build(triggeredByUser = true)))
    }

    @Test
    fun `never a build of another ref`() {
        assertFalse(superseded(build(branchName = "Feature/other")))
        assertFalse(superseded(build(branchName = null)))
        assertFalse(closed(build(branchName = "Feature/other")))
        assertFalse(closed(build(branchName = null)))
    }
}
