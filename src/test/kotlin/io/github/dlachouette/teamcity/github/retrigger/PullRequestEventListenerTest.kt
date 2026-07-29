package io.github.dlachouette.teamcity.github.retrigger

import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PullRequestEventListenerTest {

    init { LoggerBootstrap.install() }

    // The per-BT gating decision moved to `BridgeGate.decide` in
    // v1.5.0. See BridgeGateTest for the full action × draft ×
    // trigger-flag × branch-filter matrix.
    //
    // The tests below only cover the action parser + the static
    // helpers exposed on the listener (matchesRepo,
    // matchesBranchAndSha).

    @Test
    fun `PrAction fromString maps the supported actions`() {
        assertTrue(PrAction.fromString("opened") == PrAction.OPENED)
        assertTrue(PrAction.fromString("ready_for_review") == PrAction.READY_FOR_REVIEW)
        assertTrue(PrAction.fromString("synchronize") == PrAction.SYNCHRONIZE)
        assertTrue(PrAction.fromString("closed") == PrAction.CLOSED)
    }

    @Test
    fun `PrAction fromString returns null for unrelated actions`() {
        // `edited` and `labeled` became actionable in 1.8.3 — see the
        // re-evaluation tests below.
        assertTrue(PrAction.fromString("assigned") == null)
        assertTrue(PrAction.fromString("converted_to_draft") == null)
        assertTrue(PrAction.fromString("") == null)
    }

    @Test
    fun `matchesRepo accepts exact slug`() {
        val repo = RepoCoords.parse("acme/widget")
        assertTrue(PullRequestEventListener.matchesRepo(RepoCoords.parse("acme/widget"), repo))
    }

    @Test
    fun `matchesRepo accepts slug with different casing`() {
        val repo = RepoCoords.parse("acme/widget")
        assertTrue(PullRequestEventListener.matchesRepo(RepoCoords.parse("Acme/Widget"), repo))
        assertTrue(PullRequestEventListener.matchesRepo(RepoCoords.parse("ACME/WIDGET"), repo))
    }

    @Test
    fun `matchesRepo rejects different repo`() {
        val repo = RepoCoords.parse("acme/widget")
        assertFalse(PullRequestEventListener.matchesRepo(RepoCoords.parse("acme/gadget"), repo))
        assertFalse(PullRequestEventListener.matchesRepo(RepoCoords.parse("other/widget"), repo))
    }

    @Test
    fun `matchesBranchAndSha returns true when branch and sha match`() {
        assertTrue(
            PullRequestEventListener.matchesBranchAndSha(
                buildBranch = "pull/42",
                buildRevisions = listOf("deadbeef"),
                targetBranch = "pull/42",
                targetSha = "deadbeef",
            )
        )
    }

    @Test
    fun `matchesBranchAndSha returns false on different branch or sha`() {
        assertFalse(
            PullRequestEventListener.matchesBranchAndSha(
                buildBranch = "pull/43",
                buildRevisions = listOf("deadbeef"),
                targetBranch = "pull/42",
                targetSha = "deadbeef",
            )
        )
        assertFalse(
            PullRequestEventListener.matchesBranchAndSha(
                buildBranch = "pull/42",
                buildRevisions = listOf("cafebabe"),
                targetBranch = "pull/42",
                targetSha = "deadbeef",
            )
        )
    }

    @Test
    fun `matchesBranchAndSha returns false on empty revisions or null branch`() {
        assertFalse(
            PullRequestEventListener.matchesBranchAndSha(
                buildBranch = "pull/42",
                buildRevisions = emptyList(),
                targetBranch = "pull/42",
                targetSha = "deadbeef",
            )
        )
        assertFalse(
            PullRequestEventListener.matchesBranchAndSha(
                buildBranch = null,
                buildRevisions = listOf("deadbeef"),
                targetBranch = "pull/42",
                targetSha = "deadbeef",
            )
        )
    }

    // --- G1 / G3 / G4: labels and edits become triggers, not only filters

    @Test
    fun `the re-evaluation actions are recognised`() {
        mapOf(
            "reopened" to PrAction.REOPENED,
            "labeled" to PrAction.LABELED,
            "unlabeled" to PrAction.UNLABELED,
            "edited" to PrAction.EDITED,
        ).forEach { (raw, expected) -> assertEquals(expected, PrAction.fromString(raw)) }
    }

    @Test
    fun `actions we do not act on stay unmapped`() {
        listOf("assigned", "converted_to_draft", "review_requested", "locked", "").forEach {
            assertNull(PrAction.fromString(it), "action=$it")
        }
    }

    @Test
    fun `only the actions that change the commit report skips`() {
        // A new commit (or a fresh PR) may legitimately publish a "Skipped"
        // row: nothing else has reported for that commit yet.
        listOf(PrAction.OPENED, PrAction.REOPENED, PrAction.READY_FOR_REVIEW, PrAction.SYNCHRONIZE, PrAction.CLOSED)
            .forEach { assertTrue(it.reportsSkips, "action=$it") }

        // A label or a title edit re-evaluates the SAME commit. A Check Run is
        // keyed on (name, commit), so posting "Skipped" here would overwrite
        // the result an earlier build published for it.
        listOf(PrAction.LABELED, PrAction.UNLABELED, PrAction.EDITED)
            .forEach { assertFalse(it.reportsSkips, "action=$it") }
    }
}
