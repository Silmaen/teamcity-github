package io.github.dlachouette.teamcity.github.web

// Tracks recently-seen GitHub webhook delivery IDs (the
// `X-GitHub-Delivery` header) so a redelivered/replayed payload — which
// the HMAC signature alone cannot distinguish from the original — is
// acknowledged but not re-processed.
//
// Bounded LRU with a TTL: at most `maxEntries` ids are retained, and an
// id older than `ttlMs` is treated as unseen again (GitHub does not
// redeliver that far in the past). Synchronized; call volume is one
// check per webhook so contention is irrelevant.
//
// No-arg constructor so the Spring container instantiates it cleanly;
// the tunables are mutable for tests.
class DeliveryReplayGuard {

    var maxEntries: Int = DEFAULT_MAX_ENTRIES
    var ttlMs: Long = DEFAULT_TTL_MS
    var clock: () -> Long = { System.currentTimeMillis() }

    private val seen = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > maxEntries
    }

    // Returns true if `deliveryId` was already seen within the TTL (i.e.
    // this is a replay). Otherwise records it and returns false.
    @Synchronized
    fun checkAndRecord(deliveryId: String): Boolean {
        val now = clock()
        val previous = seen[deliveryId]
        if (previous != null && now - previous <= ttlMs) {
            return true
        }
        seen[deliveryId] = now
        return false
    }

    @Synchronized
    fun size(): Int = seen.size

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 2_000
        const val DEFAULT_TTL_MS: Long = 24L * 60L * 60L * 1000L
    }
}
