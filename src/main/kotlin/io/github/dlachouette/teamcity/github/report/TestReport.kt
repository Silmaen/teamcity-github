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
        if (counts.failed > 0) {
            val new = if (counts.newFailed > 0) " (${counts.newFailed} new)" else ""
            return "${counts.failed} of ${counts.total} tests failed$new"
        }
        // Nothing failed — but the total is not the number that passed. It also
        // counts the ignored and the muted, so a run whose only test was
        // skipped used to read "1 tests passed" in the merge box, which is the
        // one place a claim like that is read and not checked.
        val bits = mutableListOf<String>()
        bits += if (counts.passed > 0) "${counts.passed} ${tests(counts.passed)} passed" else "no test passed"
        if (counts.ignored > 0) bits += "${counts.ignored} ignored"
        if (counts.muted > 0) bits += "${counts.muted} muted"
        return bits.joinToString(", ")
    }

    private fun tests(n: Int): String = if (n == 1) "test" else "tests"

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

    // How a test is named on its bullet.
    //
    // TeamCity prefixes a test name with its suite and substitutes the literal
    // "(empty)" when the report carries none — a CTest or plain-XML import puts
    // "(empty): Greeter.GreetsWorld" on every single line. Drop the prefix in
    // that case; keep it when the suite says something (a matrix leg, a test
    // target), because then it is what tells two identical test names apart.
    fun displayName(full: String, withoutSuite: String, suite: String?): String {
        val s = suite?.trim().orEmpty().removeSuffix(":").trim()
        if (s.isNotEmpty() && s != EMPTY_SUITE) return full
        return withoutSuite.trim().ifEmpty { full }
    }

    // The failure text of a run, or null when it says nothing the line above
    // does not already say.
    //
    // TeamCity's XML importers — CTest among them — routinely report a bare
    // "Failure" as both the status text and the short stacktrace of a failing
    // test. A fold-out whose entire content is that one word is worse than no
    // fold-out at all: it invites a click that answers nothing. Same for a
    // text that only repeats the test's own name.
    //
    // Public because the publisher uses it to decide whether the cheap SDK
    // reads were worth anything before paying for the expensive one.
    fun informativeFailure(raw: String?, testName: String): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Every line noise covers both the bare "Failure" and the "Failed\n\n"
        // that a gtest run puts in front of its real output.
        if (text.lines().all { isNoise(it) }) return null
        if (normalise(text) == normalise(testName)) return null
        return excerpt(text)
    }

    // The part of a test's output a reviewer needs, out of the framework's own
    // chatter.
    //
    // A gtest run reports "Failed", a `------- Stdout: -------` banner, its
    // `[==========]` bracket lines, the environment set-up and tear-down, and
    // only somewhere in the middle the `file:line` and the expected-vs-actual
    // block that says what went wrong. Spending the character budget on the
    // scaffolding is what left the fold-out useless even once it had the real
    // output in it.
    //
    // So: drop the lines that are the framework talking about itself, then
    // start at the `file:line` anchor when there is one. Conservative by
    // construction — the anchor is optional and, if the filters leave nothing,
    // the text comes back untouched.
    fun excerpt(raw: String): String {
        val lines = raw.lines().filterNot { isScaffolding(it) }
        val anchor = lines.indexOfFirst { ANCHOR.containsMatchIn(it) }
        val from = if (anchor >= 0) anchor else lines.indexOfFirst { !isNoise(it) }.coerceAtLeast(0)
        // Stop at the end of the first failure block. A CTest run concatenates
        // every configuration's output into one text, so without this the
        // excerpt of the Release failure runs into the Debug one — two
        // assertions and a "1 FAILED TEST" tail where one assertion was asked
        // for.
        val rest = lines.drop(from)
        val kept = (rest.take(1) + rest.drop(1).takeWhile { !isBlockEnd(it) })
            .dropLastWhile { it.isBlank() }
            .take(MAX_EXCERPT_LINES)
        return kept.joinToString("\n").trim().ifEmpty { raw.trim() }
    }

    private fun normalise(text: String): String = text.lowercase().trim { !it.isLetterOrDigit() }

    private fun isNoise(line: String): Boolean = normalise(line).let { it.isEmpty() || it in NOISE }

    // The line that closes a failure block: a runner's tally ("1 FAILED TEST")
    // or the bare "Failed" that starts the next one. Blank lines are **not**
    // block ends — an expected-vs-actual block may contain them.
    private fun isBlockEnd(line: String): Boolean =
        normalise(line) in NOISE || TALLY.containsMatchIn(line)

    private val TALLY: Regex = Regex("""^\s*\d+\s+FAILED\s+TESTS?\b""", RegexOption.IGNORE_CASE)

    // A line that only exists because a test framework printed it. Matching is
    // deliberately narrow: a line that is not recognised is kept, and the worst
    // case of a miss is the fold-out being longer than it needed to be.
    private fun isScaffolding(line: String): Boolean = SCAFFOLDING.any { it.containsMatchIn(line) }

    private fun testEntry(test: FailedTestRun): String = buildString {
        append("- ")
        if (test.newFailure) append("**new** ")
        append('`').append(test.name).append('`')
        if (test.durationMillis > 0) append(" — ").append(BuildTimeline.human(test.durationMillis))
        test.firstFailedInBuildNumber?.takeIf { !test.newFailure && it.isNotBlank() }
            ?.let { append(", first failed in #").append(it) }

        val text = informativeFailure(test.failureText, test.name)
        // One short line is not worth folding: it reads on the bullet itself,
        // which is one click less for the common "expected X, got Y".
        val oneLiner = text?.takeIf { !it.contains('\n') && it.length <= MAX_INLINE }
        if (oneLiner != null) append(" — `").append(oneLiner.replace('`', '\'')).append('`')
        append('\n')
        if (text == null || oneLiner != null) return@buildString

        // Longer than a line: fold it, but say what is inside. A fold-out
        // labelled "failure" under a row that already reads "failed" tells a
        // reviewer nothing about whether clicking is worth it.
        append("  <details><summary>").append(summaryLabel(text)).append("</summary>\n\n")
        append("  ```\n")
        text.take(MAX_FAILURE_TEXT).lineSequence().forEach { append("  ").append(it).append('\n') }
        if (text.length > MAX_FAILURE_TEXT) append("  … (truncated)\n")
        append("  ```\n\n  </details>\n")
    }

    // The first meaningful line of the failure, clipped and HTML-escaped:
    // `<summary>` content is raw HTML to GitHub's renderer, not markdown.
    private fun summaryLabel(text: String): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "failure"
        val short = withoutAgentPath(first)
        val clipped = if (short.length > MAX_SUMMARY) short.take(MAX_SUMMARY).trimEnd() + "…" else short
        return clipped.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    // The label names the file, not the path to the agent's disk.
    //
    // `D:\BuildAgent\work\test-ci\PR\Windows_x64_Release\tests\test_greeter.cpp(14): error: Expected…`
    // spends sixty of its hundred characters on a directory nobody reading the
    // pull request can act on, and pushes the message itself out of the frame.
    // Only the fold-out's label is shortened — the body keeps the full path, so
    // the location stays copy-pasteable.
    private fun withoutAgentPath(line: String): String {
        val m = LEADING_ABSOLUTE_PATH.find(line) ?: return line
        return line.removeRange(m.range).ifBlank { line }
    }

    // A leading absolute path, Windows or POSIX, up to its last separator. A
    // relative path (`src/greeter.cpp:14`) is left alone: it is short, and it is
    // where the file actually lives in the repository.
    private val LEADING_ABSOLUTE_PATH = Regex("""^(?:[A-Za-z]:)?[\\/](?:[^\\/\s]+[\\/])+""")

    // Normalised failure texts that carry no information. Kept deliberately
    // short: the cost of a false positive is a reviewer opening TeamCity, the
    // cost of a false negative is a useless fold-out on every failing test.
    // What TeamCity displays for a test whose report carries no suite.
    private const val EMPTY_SUITE: String = "(empty)"

    // Where the failure actually is: `/src/greeter.cpp:14: Failure` (gcc,
    // clang, gtest, catch2, pytest) or `src\greeter.cpp(14): error` (MSVC).
    // The most informative line of any failure, and the label the fold-out
    // deserves.
    private val ANCHOR: Regex = Regex("""^\s*\S+(:\d+(:\d+)?|\(\d+(,\d+)?\)):\s*\S""")

    // Test-framework scaffolding: TeamCity's stdout banner, gtest's bracket
    // lines (`[==========]`, `[ RUN      ]`, `[  FAILED  ]`), and the two lines
    // gtest_main prints before any test runs.
    private val SCAFFOLDING: List<Regex> = listOf(
        Regex("""^\s*-{3,}\s*std(out|err)\s*:\s*-{3,}\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*\[\s*(=+|-+|RUN|OK|FAILED|PASSED|SKIPPED|DISABLED)\s*\]"""),
        Regex("""^\s*Running main\(\) from """),
        Regex("""^\s*Note: Google Test filter ="""),
    )

    private val NOISE: Set<String> = setOf(
        "failure", "failures", "failed", "test failed", "fail",
        "error", "errors", "exception", "assertion failed", "assertion failure",
    )

    // How many failing tests are listed before deferring to TeamCity, and how
    // much of each failure text is kept. GitHub allows 65535 characters of
    // `output.text` in total; these caps leave room for the other sections.
    const val MAX_LISTED: Int = 20
    const val MAX_FAILURE_TEXT: Int = 800

    // A failure that fits here goes on the bullet; anything longer is folded.
    const val MAX_INLINE: Int = 120

    // How many lines of a test's output survive the excerpt. Enough for an
    // expected-vs-actual block or a short stack, not for a whole run log.
    const val MAX_EXCERPT_LINES: Int = 12

    // How much of the first line is used to label a fold-out.
    const val MAX_SUMMARY: Int = 100
}
