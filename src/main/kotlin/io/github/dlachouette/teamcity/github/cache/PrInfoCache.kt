package io.github.dlachouette.teamcity.github.cache

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
        val fresh = gitHubClient.getPr(accessToken, repo, number, apiBase) ?: return cached?.info
        store[key] = Entry(fresh, now)
        return fresh
    }

    fun invalidate(repo: RepoCoords, number: Int) {
        store.remove(Key(repo, number))
    }

    fun size(): Int = store.size

    companion object {
        const val DEFAULT_TTL_MS: Long = 60_000L
    }
}
