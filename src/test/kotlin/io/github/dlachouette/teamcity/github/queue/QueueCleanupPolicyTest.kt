package io.github.dlachouette.teamcity.github.queue

import io.github.dlachouette.teamcity.github.feature.BridgeTrigger
import io.github.dlachouette.teamcity.github.feature.GateDecision
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// What the bridge is allowed to take out of the queue. The rule is narrow on
// purpose: "the bridge never removes a build it did not enqueue itself",
// except an automatic build a scope filter excluded.
class QueueCleanupPolicyTest {

    private val filters = listOf(
        GateDecision.SUPPRESS_DRAFT,
        GateDecision.SUPPRESS_BRANCH_PR,
        GateDecision.SUPPRESS_BRANCH_NON_PR,
        GateDecision.SUPPRESS_METADATA,
    )

    @Test
    fun `an automatic build excluded by a scope filter is removed`() {
        filters.forEach {
            assertTrue(QueueCleanupPolicy.removes(it, BridgeTrigger.AUTO), "decision=$it")
        }
    }

    @Test
    fun `nothing a human or a command started is ever removed`() {
        filters.forEach { decision ->
            listOf(BridgeTrigger.MANUAL, BridgeTrigger.COMMAND).forEach { trigger ->
                assertFalse(
                    QueueCleanupPolicy.removes(decision, trigger),
                    "decision=$decision trigger=$trigger",
                )
            }
        }
    }

    @Test
    fun `a HARD block never removes anything`() {
        // "Not part of that path" and the project-level mute mean the bridge
        // does not *start* the build — not that it deletes one.
        BridgeTrigger.entries.forEach { trigger ->
            assertFalse(QueueCleanupPolicy.removes(GateDecision.SUPPRESS_HARD, trigger), "trigger=$trigger")
        }
    }

    @Test
    fun `an allowed build is left alone`() {
        BridgeTrigger.entries.forEach { trigger ->
            assertFalse(QueueCleanupPolicy.removes(GateDecision.ALLOW, trigger), "trigger=$trigger")
        }
    }
}
