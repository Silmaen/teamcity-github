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
    var commitCalls: Int = 0
    var commitResponse: List<PrInfo> = emptyList()

    override fun getPr(accessToken: String, repo: RepoCoords, number: Int, apiBase: String): PrInfo? {
        calls++
        return response
    }

    override fun listPrsForCommit(accessToken: String, repo: RepoCoords, sha: String, apiBase: String): List<PrInfo> {
        commitCalls++
        return commitResponse
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
    fun `stops serving stale once past the grace window`() {
        val client = StubClient(sample)
        var now = 1000L
        val cache = PrInfoCache(client).apply {
            clock = { now }
            ttlMs = 100L
            staleGraceMs = 500L
        }
        cache.get(repo, 7, "tok") // populate at t=1000
        client.response = null

        // Within ttl+grace (1000 + 100 + 500 = 1600): stale still served.
        now = 1500L
        assertNotNull(cache.get(repo, 7, "tok"))

        // Past ttl+grace: stale dropped, returns null.
        now = 5000L
        assertNull(cache.get(repo, 7, "tok"))
    }

    @Test
    fun `returns null when no fetch ever succeeded`() {
        val client = StubClient(null)
        val cache = PrInfoCache(client)
        assertNull(cache.get(repo, 7, "tok"))
    }

    // ----- getForCommit / selectForCommit (branch builds) -----

    @Test
    fun `getForCommit returns the open PR headed by the commit and caches it`() {
        val client = StubClient(null).apply { commitResponse = listOf(sample) }
        val cache = PrInfoCache(client).apply { clock = { 1000L } }

        assertEquals(sample, cache.getForCommit(repo, "f00ba4", "tok"))
        assertEquals(sample, cache.getForCommit(repo, "f00ba4", "tok"))
        assertEquals(1, client.commitCalls)
    }

    @Test
    fun `getForCommit caches the no-PR answer too`() {
        val client = StubClient(null)
        val cache = PrInfoCache(client).apply { clock = { 1000L } }

        assertNull(cache.getForCommit(repo, "f00ba4", "tok"))
        assertNull(cache.getForCommit(repo, "f00ba4", "tok"))
        assertEquals(1, client.commitCalls)
    }

    @Test
    fun `getForCommit re-asks after the ttl`() {
        val client = StubClient(null)
        var now = 1000L
        val cache = PrInfoCache(client).apply {
            clock = { now }
            ttlMs = 100L
        }
        cache.getForCommit(repo, "f00ba4", "tok")
        now += 200L
        cache.getForCommit(repo, "f00ba4", "tok")
        assertEquals(2, client.commitCalls)
    }

    @Test
    fun `getForCommit seeds the number-keyed store`() {
        val client = StubClient(null).apply { commitResponse = listOf(sample) }
        val cache = PrInfoCache(client).apply { clock = { 1000L } }

        cache.getForCommit(repo, "f00ba4", "tok")
        assertEquals(sample, cache.get(repo, 7, "tok"))
        assertEquals(0, client.calls) // served from the seeded entry
    }

    @Test
    fun `getForCommit ignores a blank sha without calling GitHub`() {
        val client = StubClient(null)
        val cache = PrInfoCache(client)
        assertNull(cache.getForCommit(repo, "  ", "tok"))
        assertEquals(0, client.commitCalls)
    }

    @Test
    fun `selectForCommit keeps only open PRs whose head is the commit`() {
        val open = sample
        val closed = sample.copy(number = 8, state = "closed")
        val otherCommit = sample.copy(number = 9, headSha = "cafe01")

        assertEquals(open, PrInfoCache.selectForCommit(listOf(closed, otherCommit, open), "f00ba4"))
        assertNull(PrInfoCache.selectForCommit(listOf(closed, otherCommit), "f00ba4"))
        assertNull(PrInfoCache.selectForCommit(emptyList(), "f00ba4"))
    }

    @Test
    fun `selectForCommit picks the lowest number when several open PRs share the head`() {
        val higher = sample.copy(number = 12)
        val lower = sample.copy(number = 7)
        assertEquals(lower, PrInfoCache.selectForCommit(listOf(higher, lower), "f00ba4"))
    }

    @Test
    fun `selectForCommit matches the sha case-insensitively`() {
        assertEquals(sample, PrInfoCache.selectForCommit(listOf(sample), "F00BA4"))
    }
}
