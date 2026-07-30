package io.github.dlachouette.teamcity.github.report

// The test outcome of a build, as GitHub should read it.
//
// Deliberately a plain data shape rather than TeamCity's `ShortStatistics`:
// the formatting below is the interesting part and it is testable without a
// build fixture. The publisher does the SDK reading.
data class TestCounts(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val ignored: Int,
    val muted: Int,
    val newFailed: Int,
) {
    val ran: Boolean get() = total > 0
}

// One failing test run, flattened out of `STestRun`.
//
// `newFailure` is TeamCity's own judgement (this test was green in the
// previous build): it is the single most useful bit for a reviewer, because
// it separates "you broke this" from "this was already red".
data class FailedTestRun(
    val name: String,
    val newFailure: Boolean,
    val durationMillis: Long,
    val firstFailedInBuildNumber: String? = null,
    val failureText: String? = null,
)

object TestReport {

    // Appended to the Check Run title, which is the one line GitHub shows in
    // the PR's merge box. "Build failed" tells a reviewer nothing they can
    // act on; "Build failed — 3 of 1046 tests failed (2 new)" tells them
    // whether to look at their own diff.
    //
    // Null when the build ran no tests: most build configurations don't, and
    // "0 tests" in the merge box is worse than nothing.
    fun titleSuffix(counts: TestCounts): String? {
        if (!counts.ran) return null
        if (counts.failed <= 0) {
            val muted = if (counts.muted > 0) ", ${counts.muted} muted" else ""
            return "${counts.total} tests passed$muted"
        }
        val new = if (counts.newFailed > 0) " (${counts.newFailed} new)" else ""
        return "${counts.failed} of ${counts.total} tests failed$new"
    }

    // The `### Tests` section of the Check Run body: the counts, then the
    // failing tests newest-breakage-first, with their failure text folded
    // into a <details> block so the panel stays readable.
    //
    // Null when no test ran, or when everything passed and there is nothing
    // to say beyond the title.
    fun section(counts: TestCounts, failed: List<FailedTestRun>): String? {
        if (!counts.ran) return null
        if (counts.failed <= 0 && counts.muted <= 0 && counts.ignored <= 0) return null

        return buildString {
            append("### Tests\n\n")
            append(countsLine(counts)).append("\n")
            if (failed.isEmpty()) return@buildString

            append("\n")
            // New failures first: they are what the PR most likely caused.
            val ordered = failed.sortedWith(compareByDescending<FailedTestRun> { it.newFailure }.thenBy { it.name })
            ordered.take(MAX_LISTED).forEach { test -> append(testEntry(test)) }
            if (ordered.size > MAX_LISTED) {
                append("\n_…and ${ordered.size - MAX_LISTED} more failing test(s) (see the TeamCity build)._\n")
            }
        }.takeIf { it.isNotBlank() }
    }

    private fun countsLine(counts: TestCounts): String {
        val parts = mutableListOf<String>()
        if (counts.failed > 0) {
            val new = if (counts.newFailed > 0) " (${counts.newFailed} new)" else ""
            parts += "**${counts.failed} failed$new**"
        }
        if (counts.passed > 0) parts += "${counts.passed} passed"
        if (counts.ignored > 0) parts += "${counts.ignored} ignored"
        // Muted failures are deliberate: say so, so nobody reads the green
        // conclusion as "nothing is broken".
        if (counts.muted > 0) parts += "${counts.muted} muted (not failing the build)"
        return parts.joinToString(" · ")
    }

    private fun testEntry(test: FailedTestRun): String = buildString {
        append("- ")
        if (test.newFailure) append("**new** ")
        append('`').append(test.name).append('`')
        if (test.durationMillis > 0) append(" — ").append(BuildTimeline.human(test.durationMillis))
        test.firstFailedInBuildNumber?.takeIf { !test.newFailure && it.isNotBlank() }
            ?.let { append(", first failed in #").append(it) }
        append('\n')
        val text = test.failureText?.trim()?.takeIf { it.isNotEmpty() } ?: return@buildString
        append("  <details><summary>failure</summary>\n\n")
        append("  ```\n")
        text.take(MAX_FAILURE_TEXT).lineSequence().forEach { append("  ").append(it).append('\n') }
        if (text.length > MAX_FAILURE_TEXT) append("  … (truncated)\n")
        append("  ```\n\n  </details>\n")
    }

    // How many failing tests are listed before deferring to TeamCity, and how
    // much of each failure text is kept. GitHub allows 65535 characters of
    // `output.text` in total; these caps leave room for the other sections.
    const val MAX_LISTED: Int = 20
    const val MAX_FAILURE_TEXT: Int = 800
}
