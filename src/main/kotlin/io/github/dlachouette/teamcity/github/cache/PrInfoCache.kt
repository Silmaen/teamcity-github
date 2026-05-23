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
    private val ttlMs: Long = 60_000L

    fun get(repo: RepoCoords, number: Int, connectionId: String): PrInfo? {
        val key = Key(repo, number)
        val now = System.currentTimeMillis()
        val cached = store[key]
        if (cached != null && now - cached.fetchedAtMs < ttlMs) {
            return cached.info
        }
        val fresh = gitHubClient.getPr(repo, number, connectionId) ?: return cached?.info
        store[key] = Entry(fresh, now)
        return fresh
    }

    fun invalidate(repo: RepoCoords, number: Int) {
        store.remove(Key(repo, number))
    }
}
