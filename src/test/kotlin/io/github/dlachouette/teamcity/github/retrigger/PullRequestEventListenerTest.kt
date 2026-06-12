package io.github.dlachouette.teamcity.github.retrigger

import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertTrue(PrAction.fromString("edited") == null)
        assertTrue(PrAction.fromString("labeled") == null)
        assertTrue(PrAction.fromString("assigned") == null)
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
}
