package io.github.dlachouette.teamcity.github.web

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Tiny in-process counter registry, exposed in Prometheus text format by
// MetricsController. No external dependency; counters are created on
// first increment. Process-lifetime totals (reset on restart).
class BridgeMetrics {

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun inc(name: String, delta: Long = 1L) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(delta)
    }

    fun snapshot(): Map<String, Long> = counters.mapValues { it.value.get() }

    // Prometheus text exposition. Every counter is rendered as a
    // `bridge_<name>_total` counter with a HELP/TYPE preamble.
    fun renderPrometheus(): String = buildString {
        snapshot().toSortedMap().forEach { (name, value) ->
            val metric = "bridge_${name}_total"
            append("# TYPE ").append(metric).append(" counter\n")
            append(metric).append(' ').append(value).append('\n')
        }
    }

    companion object {
        // Webhook outcomes.
        const val WEBHOOKS_RECEIVED = "webhooks_received"
        const val WEBHOOKS_REJECTED = "webhooks_rejected"
        const val WEBHOOKS_REPLAYED = "webhooks_replayed"
        const val WEBHOOKS_TOO_LARGE = "webhooks_too_large"

        // Events dropped because the PR head lives in a fork (the bridge is
        // attached to one repository, never to its forks).
        const val FORK_EVENTS_IGNORED = "fork_events_ignored"

        // Check Run publication.
        const val CHECK_RUNS_POSTED = "check_runs_posted"
        const val CHECK_RUNS_FAILED = "check_runs_failed"

        // Enqueue / cancellation activity.
        const val BUILDS_ENQUEUED = "builds_enqueued"
        const val BUILDS_CANCELLED = "builds_cancelled"
    }
}
