package io.github.dlachouette.teamcity.github.api

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// Caches GitHub App installation tokens minted by AppTokenMinter.
// Keyed by installationId (Long): a single App may have multiple
// installations (one per org/user), each with its own token.
//
// We compare wall-clock against the truncated expiry stored alongside
// the token. AppTokenMinter is expected to subtract a safety margin
// (~10 min) before calling put(...) so that consumers never see a
// token that is about to expire mid-call.
class AppTokenCache {
    // Settable for tests via property assignment. Spring's
    // constructor autowiring picks the no-arg constructor, so the
    // default systemUTC clock is used in production.
    var clock: Clock = Clock.systemUTC()

    private val entries = ConcurrentHashMap<Long, Entry>()

    fun get(installationId: Long): String? {
        val entry = entries[installationId] ?: return null
        if (!Instant.now(clock).isBefore(entry.expiresAt)) {
            entries.remove(installationId, entry)
            return null
        }
        return entry.token
    }

    fun put(installationId: Long, token: String, expiresAt: Instant) {
        entries[installationId] = Entry(token, expiresAt)
    }

    fun invalidate(installationId: Long) {
        entries.remove(installationId)
    }

    // Drop every cached token, and say how many.
    //
    // An installation token carries the permissions it had **when it was
    // minted**. So granting the App a new permission — and having the
    // installation accept it — changes nothing until the cached token expires:
    // up to the full TTL of calls still failing with 403 for a permission the
    // App visibly has. That is a confusing enough half-hour to be worth a
    // button on the admin page.
    fun clear(): Int {
        val size = entries.size
        entries.clear()
        return size
    }

    private data class Entry(val token: String, val expiresAt: Instant)
}
