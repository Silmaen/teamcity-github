package io.github.dlachouette.teamcity.github.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeliveryReplayGuardTest {

    @Test
    fun `first delivery is not a replay, second is`() {
        val guard = DeliveryReplayGuard()
        assertFalse(guard.checkAndRecord("d1"))
        assertTrue(guard.checkAndRecord("d1"))
    }

    @Test
    fun `distinct deliveries are independent`() {
        val guard = DeliveryReplayGuard()
        assertFalse(guard.checkAndRecord("a"))
        assertFalse(guard.checkAndRecord("b"))
        assertTrue(guard.checkAndRecord("a"))
        assertTrue(guard.checkAndRecord("b"))
    }

    @Test
    fun `an id older than the TTL is treated as unseen again`() {
        var now = 1_000L
        val guard = DeliveryReplayGuard().also { it.ttlMs = 100L; it.clock = { now } }
        assertFalse(guard.checkAndRecord("d1"))
        now += 50L
        assertTrue(guard.checkAndRecord("d1")) // within TTL -> replay
        now += 1_000L
        assertFalse(guard.checkAndRecord("d1")) // past TTL -> fresh again
    }

    @Test
    fun `LRU evicts beyond the max size`() {
        val guard = DeliveryReplayGuard().also { it.maxEntries = 2 }
        guard.checkAndRecord("a")
        guard.checkAndRecord("b")
        guard.checkAndRecord("c") // evicts eldest ("a")
        assertEquals(2, guard.size())
        assertFalse(guard.checkAndRecord("a")) // "a" was evicted -> fresh
    }
}
