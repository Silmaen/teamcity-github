package io.github.dlachouette.teamcity.github.web

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Bounded ring buffer of recent webhook events. Lives in process
// memory, lost on restart. Sized for an admin page snapshot, not
// for long-term audit; the dedicated log file is the source of
// truth.
class RecentEventsLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val ring = ArrayDeque<RecentEvent>(capacity)
    private val lock = ReentrantLock()

    fun record(
        event: String,
        repo: String?,
        action: String?,
        httpStatus: Int,
        outcome: Outcome,
        detail: String? = null,
    ) {
        val entry = RecentEvent(
            timestampMs = clock(),
            event = event,
            repo = repo,
            action = action,
            httpStatus = httpStatus,
            outcome = outcome,
            detail = detail,
        )
        lock.withLock {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(entry)
        }
    }

    fun snapshot(): List<RecentEvent> = lock.withLock { ring.toList().asReversed() }

    fun size(): Int = lock.withLock { ring.size }

    fun clear() = lock.withLock { ring.clear() }

    companion object {
        const val DEFAULT_CAPACITY: Int = 100
    }
}

data class RecentEvent(
    val timestampMs: Long,
    val event: String,
    val repo: String?,
    val action: String?,
    val httpStatus: Int,
    val outcome: Outcome,
    val detail: String?,
)

enum class Outcome(val displayName: String) {
    ACCEPTED("accepted"),
    SKIPPED("skipped"),
    REJECTED("rejected"),
}
