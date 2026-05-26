package io.github.dlachouette.teamcity.github.api

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AppTokenCacheTest {

    init { LoggerBootstrap.install() }

    private fun fixedClock(at: Instant): Clock =
        Clock.fixed(at, ZoneOffset.UTC)

    @Test
    fun `get returns stored token before expiry`() {
        val now = Instant.parse("2026-05-26T12:00:00Z")
        val cache = AppTokenCache().also { it.clock = fixedClock(now) }
        cache.put(installationId = 42L, token = "ghs_abc", expiresAt = now.plusSeconds(60))

        assertEquals("ghs_abc", cache.get(42L))
    }

    @Test
    fun `get returns null when wall-clock has reached expiry`() {
        val expiresAt = Instant.parse("2026-05-26T12:00:00Z")
        val cache = AppTokenCache()
        cache.clock = fixedClock(expiresAt.minusSeconds(60))
        cache.put(installationId = 42L, token = "ghs_abc", expiresAt = expiresAt)
        assertEquals("ghs_abc", cache.get(42L))

        cache.clock = fixedClock(expiresAt)
        assertNull(cache.get(42L))
    }

    @Test
    fun `get returns null after the wall-clock has passed the expiry`() {
        val expiresAt = Instant.parse("2026-05-26T12:00:00Z")
        val cache = AppTokenCache()
        cache.clock = fixedClock(expiresAt.plusSeconds(1))
        cache.put(installationId = 42L, token = "ghs_abc", expiresAt = expiresAt)

        assertNull(cache.get(42L))
    }

    @Test
    fun `get returns null when nothing was stored`() {
        val cache = AppTokenCache()
        assertNull(cache.get(99L))
    }

    @Test
    fun `invalidate drops the entry`() {
        val now = Instant.parse("2026-05-26T12:00:00Z")
        val cache = AppTokenCache().also { it.clock = fixedClock(now) }
        cache.put(installationId = 7L, token = "ghs_xyz", expiresAt = now.plusSeconds(3600))
        assertEquals("ghs_xyz", cache.get(7L))

        cache.invalidate(7L)

        assertNull(cache.get(7L))
    }

    @Test
    fun `put overwrites existing entry`() {
        val now = Instant.parse("2026-05-26T12:00:00Z")
        val cache = AppTokenCache().also { it.clock = fixedClock(now) }
        cache.put(installationId = 1L, token = "ghs_old", expiresAt = now.plusSeconds(60))
        cache.put(installationId = 1L, token = "ghs_new", expiresAt = now.plusSeconds(3600))

        assertEquals("ghs_new", cache.get(1L))
    }

    @Test
    fun `entries are isolated per installation id`() {
        val now = Instant.parse("2026-05-26T12:00:00Z")
        val cache = AppTokenCache().also { it.clock = fixedClock(now) }
        cache.put(installationId = 1L, token = "ghs_one", expiresAt = now.plusSeconds(3600))
        cache.put(installationId = 2L, token = "ghs_two", expiresAt = now.plusSeconds(3600))

        assertEquals("ghs_one", cache.get(1L))
        assertEquals("ghs_two", cache.get(2L))
    }
}
