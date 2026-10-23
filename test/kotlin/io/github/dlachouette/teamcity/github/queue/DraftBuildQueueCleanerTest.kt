package io.github.dlachouette.teamcity.github.queue

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The cleaner's decision is delegated to BridgeGate in v1.5.0+; see
// BridgeGateTest for the per-branch / per-trigger matrix. The old
// `shouldRemove(pr)` helper is gone (the gate now decides based on
// the full BridgeFeatureConfig instead of just the PR's draft flag).
//
// Integration-level behaviour (queue removal + Skipped Check Run
// posting) requires TC SDK fixtures and is currently exercised
// manually on the Test_CI sandbox per CHANGELOG.
class DraftBuildQueueCleanerTest {

    // The re-check the start precondition asks for runs later, on another
    // thread: by then the build may have started or left the queue.
    @Test
    fun `a re-check acts only on a build still waiting in the queue`() {
        assertTrue(DraftBuildQueueCleaner.stillQueued(inQueue = true, started = false))
        assertFalse(DraftBuildQueueCleaner.stillQueued(inQueue = true, started = true))
        assertFalse(DraftBuildQueueCleaner.stillQueued(inQueue = false, started = false))
        assertFalse(DraftBuildQueueCleaner.stillQueued(inQueue = false, started = true))
    }
}
