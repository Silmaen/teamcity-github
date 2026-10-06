package io.github.dlachouette.teamcity.github.report

// The summary of a `queued` Check Run: where the build stands, so a reviewer can
// tell "busy agents, four minutes out" from "stuck".
//
// From `SQueuedBuild`: the position (`orderNumber`) and TeamCity's own estimate
// (`buildEstimates` — the start in seconds from now, and why it is waiting, in
// the wording of the queue page). Every part is optional: a build whose
// dependencies are unresolved has no estimate at all, and then only the
// position is said.
object QueueEstimate {

    const val FALLBACK: String = "TeamCity has queued this build."

    // `startInSeconds`: relative start, null when unknown or never.
    fun summary(position: Int?, startInSeconds: Long?, waitReason: String?): String {
        val where = position?.takeIf { it > 0 }?.let { "${ordinal(it)} in queue" }
        val whenText = startInSeconds?.let { if (it <= 0) "about to start" else "~${duration(it)} to start" }
        val head = listOfNotNull(where, whenText).joinToString(", ")
        val reason = waitReason?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
        return when {
            head.isEmpty() && reason == null -> FALLBACK
            head.isEmpty() -> "Queued: $reason."
            reason == null -> "${head.replaceFirstChar { it.uppercase() }}."
            else -> "${head.replaceFirstChar { it.uppercase() }} — $reason."
        }
    }

    // True when the summary says more than the fallback: no follow-up needed.
    fun hasEstimate(startInSeconds: Long?, waitReason: String?): Boolean =
        startInSeconds != null || !waitReason.isNullOrBlank()

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    // Coarse on purpose: an estimate, not a countdown.
    fun duration(seconds: Long): String = when {
        seconds < 60 -> "<1m"
        seconds < 3600 -> "${(seconds + 30) / 60}m"
        else -> "${seconds / 3600}h %02dm".format((seconds % 3600) / 60)
    }
}
