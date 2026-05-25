package io.github.dlachouette.teamcity.github.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentEventsLogTest {

    @Test
    fun `records up to capacity then evicts oldest`() {
        val log = RecentEventsLog(capacity = 3, clock = { 0L })
        log.record("e1", "r/x", "a", 200, Outcome.ACCEPTED)
        log.record("e2", "r/x", "a", 200, Outcome.ACCEPTED)
        log.record("e3", "r/x", "a", 200, Outcome.ACCEPTED)
        log.record("e4", "r/x", "a", 200, Outcome.ACCEPTED)

        val snap = log.snapshot()
        assertEquals(3, snap.size)
        assertEquals(listOf("e4", "e3", "e2"), snap.map { it.event })
    }

    @Test
    fun `snapshot returns newest first`() {
        var t = 1000L
        val log = RecentEventsLog(capacity = 10, clock = { t })
        log.record("first", null, null, 200, Outcome.ACCEPTED); t += 10
        log.record("second", null, null, 200, Outcome.ACCEPTED); t += 10
        log.record("third", null, null, 200, Outcome.ACCEPTED)

        val snap = log.snapshot()
        assertEquals("third", snap[0].event)
        assertEquals("second", snap[1].event)
        assertEquals("first", snap[2].event)
    }

    @Test
    fun `records carry outcome and detail`() {
        val log = RecentEventsLog(capacity = 5, clock = { 42L })
        log.record("pull_request", "acme/api", "ready_for_review", 200, Outcome.ACCEPTED, "retrigger fired")
        log.record("ping", null, null, 401, Outcome.REJECTED, "bad signature")

        val snap = log.snapshot()
        assertEquals(Outcome.REJECTED, snap[0].outcome)
        assertEquals("bad signature", snap[0].detail)
        assertEquals(401, snap[0].httpStatus)

        assertEquals(Outcome.ACCEPTED, snap[1].outcome)
        assertEquals("acme/api", snap[1].repo)
        assertEquals("ready_for_review", snap[1].action)
        assertEquals(42L, snap[1].timestampMs)
    }

    @Test
    fun `clear empties the buffer`() {
        val log = RecentEventsLog(capacity = 5)
        log.record("a", null, null, 200, Outcome.ACCEPTED)
        log.record("b", null, null, 200, Outcome.ACCEPTED)
        log.clear()
        assertTrue(log.snapshot().isEmpty())
        assertEquals(0, log.size())
    }

    @Test
    fun `concurrent records do not corrupt the buffer`() {
        val log = RecentEventsLog(capacity = 200)
        val threads = (1..10).map { t ->
            Thread {
                repeat(50) { i ->
                    log.record("e-$t-$i", null, null, 200, Outcome.ACCEPTED)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(200, log.size())
    }
}
