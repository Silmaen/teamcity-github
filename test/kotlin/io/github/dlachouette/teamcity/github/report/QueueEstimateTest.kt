package io.github.dlachouette.teamcity.github.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// "Busy agents, four minutes out" versus "stuck": the queued row says which.
class QueueEstimateTest {

    @Test
    fun `position, start and reason make one line`() {
        assertEquals(
            "26th in queue, ~4m to start — There are no idle compatible agents which can run this build.",
            QueueEstimate.summary(26, 252, "There are no idle compatible agents which can run this build"),
        )
    }

    @Test
    fun `each part is optional`() {
        assertEquals("3rd in queue.", QueueEstimate.summary(3, null, null))
        assertEquals("About to start.", QueueEstimate.summary(null, 0, " "))
        assertEquals("Queued: Waiting for snapshot dependencies.", QueueEstimate.summary(0, null, "Waiting for snapshot dependencies."))
        assertEquals(QueueEstimate.FALLBACK, QueueEstimate.summary(null, null, null))
    }

    @Test
    fun `ordinals and durations`() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "112th"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 112).map(QueueEstimate::ordinal))
        assertEquals("<1m", QueueEstimate.duration(40))
        assertEquals("4m", QueueEstimate.duration(252))
        assertEquals("1h 05m", QueueEstimate.duration(3_900))
    }

    @Test
    fun `a position alone is no estimate`() {
        assertFalse(QueueEstimate.hasEstimate(null, ""))
        assertTrue(QueueEstimate.hasEstimate(120, null))
        assertTrue(QueueEstimate.hasEstimate(null, "No idle agent"))
    }
}
