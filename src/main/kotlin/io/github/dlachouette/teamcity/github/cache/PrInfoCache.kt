package io.github.dlachouette.teamcity.github.cache

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.RepoCoords
import java.util.concurrent.ConcurrentHashMap

class PrInfoCache(
    private val gitHubClient: GitHubClient,
) {
    private data class Key(val repo: RepoCoords, val number: Int)
    private data class Entry(val info: PrInfo, val fetchedAtMs: Long)

    private val store = ConcurrentHashMap<Key, Entry>()

    var ttlMs: Long = DEFAULT_TTL_MS
    // How long PAST the TTL a stale entry may still be served when a
    // refresh fetch fails. Bounds the previous behaviour where a single
    // fetch failure pinned the stale entry forever — a PR that went
    // draft->ready (or got a new head SHA) would otherwise be reported
    // with stale state indefinitely, making the gate decide wrongly.
    var staleGraceMs: Long = DEFAULT_STALE_GRACE_MS
    var clock: () -> Long = { System.currentTimeMillis() }

    fun get(
        repo: RepoCoords,
        number: Int,
        accessToken: String,
        apiBase: String = GitHubClient.DEFAULT_API_BASE,
    ): PrInfo? {
        val key = Key(repo, number)
        val now = clock()
        val cached = store[key]
        if (cached != null && now - cached.fetchedAtMs < ttlMs) {
            return cached.info
        }

        val fresh = gitHubClient.getPr(accessToken, repo, number, apiBase)
        if (fresh != null) {
            store[key] = Entry(fresh, now)
            return fresh
        }

        // Refresh failed (network blip, transient 5xx, rate-limit). Serve
        // the stale entry only inside the grace window; beyond it, drop it
        // and report "unknown" rather than acting on arbitrarily-old state.
        if (cached != null) {
            val age = now - cached.fetchedAtMs
            if (age <= ttlMs + staleGraceMs) {
                LOG.debug("Serving stale PR ${repo.slug}#$number (age=${age}ms) after refresh failure")
                return cached.info
            }
            LOG.warn("Dropping stale PR ${repo.slug}#$number (age=${age}ms exceeds grace) after refresh failure")
            store.remove(key, cached)
        }
        return null
    }

    fun invalidate(repo: RepoCoords, number: Int) {
        store.remove(Key(repo, number))
    }

    fun size(): Int = store.size

    companion object {
        private val LOG = Logger.getInstance(PrInfoCache::class.java.name)
        const val DEFAULT_TTL_MS: Long = 60_000L
        // Five minutes of grace past the TTL for stale-on-failure serving.
        const val DEFAULT_STALE_GRACE_MS: Long = 5L * 60_000L
    }
}
