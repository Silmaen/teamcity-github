package io.github.dlachouette.teamcity.github.cache

import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private class StubClient(var response: PrInfo?) : GitHubClient() {
    var calls: Int = 0
    override fun getPr(accessToken: String, repo: RepoCoords, number: Int, apiBase: String): PrInfo? {
        calls++
        return response
    }
}

class PrInfoCacheTest {

    init { LoggerBootstrap.install() }

    private val repo = RepoCoords("acme", "widget")
    private val sample = PrInfo(
        number = 7,
        title = "WIP",
        author = "bob",
        headRef = "feature/x",
        baseRef = "main",
        headSha = "f00ba4",
        draft = true,
        state = "open",
    )

    @Test
    fun `hits the client on first call and caches subsequent reads`() {
        val client = StubClient(sample)
        val cache = PrInfoCache(client).apply {
            clock = { 1000L }
        }

        val first = cache.get(repo, 7, "tok")
        val second = cache.get(repo, 7, "tok")

        assertEquals(sample, first)
        assertEquals(sample, second)
        assertEquals(1, client.calls)
    }

    @Test
    fun `re-fetches after ttl elapsed`() {
        val client = StubClient(sample)
        var now = 1000L
        val cache = PrInfoCache(client).apply {
            clock = { now }
            ttlMs = 100L
        }

        cache.get(repo, 7, "tok")
        now += 200L
        cache.get(repo, 7, "tok")

        assertEquals(2, client.calls)
    }

    @Test
    fun `invalidate forces a refetch`() {
        val client = StubClient(sample)
        val cache = PrInfoCache(client)
        cache.get(repo, 7, "tok")
        cache.invalidate(repo, 7)
        cache.get(repo, 7, "tok")
        assertEquals(2, client.calls)
    }

    @Test
    fun `returns cached value when fresh fetch fails`() {
        val client = StubClient(sample)
        val cache = PrInfoCache(client).apply {
            clock = { 1000L }
            ttlMs = 1L
        }
        cache.get(repo, 7, "tok")
        client.response = null
        Thread.sleep(5)
        cache.clock = { 2000L }
        val result = cache.get(repo, 7, "tok")
        assertNotNull(result)
    }

    @Test
    fun `returns null when no fetch ever succeeded`() {
        val client = StubClient(null)
        val cache = PrInfoCache(client)
        assertNull(cache.get(repo, 7, "tok"))
    }
}
