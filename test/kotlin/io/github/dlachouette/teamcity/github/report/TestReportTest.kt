package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestReportTest {

    init { LoggerBootstrap.install() }

    private fun counts(
        total: Int = 0,
        passed: Int = 0,
        failed: Int = 0,
        ignored: Int = 0,
        muted: Int = 0,
        newFailed: Int = 0,
    ) = TestCounts(total = total, passed = passed, failed = failed, ignored = ignored, muted = muted, newFailed = newFailed)

    @Test
    fun `a build that ran no test says nothing`() {
        assertNull(TestReport.titleSuffix(counts()))
        assertNull(TestReport.section(counts(), emptyList()))
    }

    @Test
    fun `the title separates new failures from pre-existing ones`() {
        assertEquals("3 of 1046 tests failed (2 new)", TestReport.titleSuffix(counts(total = 1046, passed = 1043, failed = 3, newFailed = 2)))
        assertEquals("3 of 1046 tests failed", TestReport.titleSuffix(counts(total = 1046, passed = 1043, failed = 3)))
    }

    @Test
    fun `the title of a green build reports what passed, and any muted test`() {
        assertEquals("1046 tests passed", TestReport.titleSuffix(counts(total = 1046, passed = 1046)))
        assertEquals("1044 tests passed, 2 muted", TestReport.titleSuffix(counts(total = 1046, passed = 1044, muted = 2)))
    }

    // The total counts the ignored and the muted too, so it is not what passed.
    // A build whose only test was skipped used to read "1 tests passed" in the
    // merge box — the one place such a claim is read and not checked.
    @Test
    fun `the title never claims an ignored test passed`() {
        assertEquals("no test passed, 1 ignored", TestReport.titleSuffix(counts(total = 1, ignored = 1)))
        assertEquals("31 tests passed, 12 ignored", TestReport.titleSuffix(counts(total = 43, passed = 31, ignored = 12)))
        assertEquals(
            "31 tests passed, 10 ignored, 2 muted",
            TestReport.titleSuffix(counts(total = 43, passed = 31, ignored = 10, muted = 2)),
        )
    }

    @Test
    fun `the title counts one test in the singular`() {
        assertEquals("1 test passed", TestReport.titleSuffix(counts(total = 1, passed = 1)))
    }

    // Nothing to add to the title for an all-green run: the counts line would
    // repeat it and the body would carry an empty section.
    @Test
    fun `an all-green run needs no section`() {
        assertNull(TestReport.section(counts(total = 10, passed = 10), emptyList()))
    }

    @Test
    fun `a muted failure is reported even though the build is green`() {
        val section = TestReport.section(counts(total = 10, passed = 9, muted = 1), emptyList())!!
        assertTrue(section.contains("1 muted (not failing the build)"), section)
    }

    @Test
    fun `new failures are listed first and marked`() {
        val section = TestReport.section(
            counts(total = 100, passed = 97, failed = 3, newFailed = 1),
            listOf(
                FailedTestRun(name = "a.OldTest.zzz", newFailure = false, durationMillis = 1_000, firstFailedInBuildNumber = "87"),
                FailedTestRun(name = "b.NewTest.aaa", newFailure = true, durationMillis = 1_200),
            ),
        )!!
        assertTrue(section.indexOf("b.NewTest.aaa") < section.indexOf("a.OldTest.zzz"), section)
        assertTrue(section.contains("**new** `b.NewTest.aaa`"), section)
        // A failure this build did not introduce points at the build that did.
        assertTrue(section.contains("first failed in #87"), section)
        assertFalse(section.contains("**new** `a.OldTest.zzz`"), section)
        assertTrue(section.contains("**3 failed (1 new)**"), section)
    }

    @Test
    fun `the failure text is folded away and capped`() {
        val long = "boom\n".repeat(500)
        val section = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0, failureText = long)),
        )!!
        assertTrue(section.contains("<details><summary>boom</summary>"), section)
        assertTrue(section.length < long.length, "the section must not carry the whole stacktrace")
        assertEquals(TestReport.MAX_EXCERPT_LINES, section.lines().count { it.trim() == "boom" })
    }

    // A single line long enough to blow the budget on its own — the excerpt caps
    // lines, this caps characters.
    @Test
    fun `a single enormous line is truncated`() {
        val long = "boom ".repeat(400)
        val section = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0, failureText = long)),
        )!!
        assertTrue(section.contains("… (truncated)"), section)
        assertTrue(section.length < long.length, section.length.toString())
    }

    // The fold-out has to advertise what is inside: "failure" under a row that
    // already reads "failed" is a click for nothing.
    @Test
    fun `the fold-out is labelled with the first line of the failure`() {
        val text = "/src/greeter.cpp:42: Failure\nExpected equality of these values:\n  greet(\"\")\n  \"Hello!\""
        val section = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "Greeter.GreetsEmptyName", newFailure = true, durationMillis = 0, failureText = text)),
        )!!
        // The label drops the leading directory; the body below keeps the full path.
        assertTrue(section.contains("<summary>greeter.cpp:42: Failure</summary>"), section)
    }

    // `<summary>` is raw HTML to GitHub's renderer.
    @Test
    fun `the fold-out label is HTML-escaped and clipped`() {
        val text = "expected <nullptr> & got 3\nsecond line"
        val section = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0, failureText = text)),
        )!!
        assertTrue(section.contains("<summary>expected &lt;nullptr&gt; &amp; got 3</summary>"), section)

        val long = "x".repeat(TestReport.MAX_SUMMARY + 40) + "\nsecond line"
        val clipped = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0, failureText = long)),
        )!!
        assertTrue(clipped.contains("<summary>" + "x".repeat(TestReport.MAX_SUMMARY) + "…</summary>"), clipped)
    }

    // A one-line failure is the common case ("expected X, got Y"). Folding it
    // costs a click and hides the only thing worth reading.
    @Test
    fun `a one-line failure is inlined instead of folded`() {
        val section = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0, failureText = "expected 3, got 4")),
        )!!
        assertTrue(section.contains("- **new** `T.t` — `expected 3, got 4`\n"), section)
        assertFalse(section.contains("<details>"), section)
    }

    @Test
    fun `a one-line failure longer than the inline cap is folded`() {
        val text = "e".repeat(TestReport.MAX_INLINE + 1)
        val section = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0, failureText = text)),
        )!!
        assertTrue(section.contains("<details>"), section)
    }

    // Backticks in the message would end the inline code span early.
    @Test
    fun `an inlined failure cannot break out of its code span`() {
        val section = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0, failureText = "expected `a`, got `b`")),
        )!!
        assertTrue(section.contains("— `expected 'a', got 'b'`\n"), section)
    }

    // What CTest and the plain XML importer actually hand us. Rendering it is
    // a fold-out — or now an inline note — that says nothing.
    @Test
    fun `a failure text that says nothing is dropped`() {
        listOf("Failure", "failure", "FAILED", "failed.", "error", "Exception", "  Assertion failed  ").forEach { noise ->
            assertNull(TestReport.informativeFailure(noise, "T.t"), noise)
            val section = TestReport.section(
                counts(total = 1, failed = 1),
                listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0, failureText = noise)),
            )!!
            assertFalse(section.contains("<details>"), section)
            assertTrue(section.contains("- **new** `T.t`\n"), section)
        }
        assertNull(TestReport.informativeFailure(null, "T.t"))
        assertNull(TestReport.informativeFailure("   ", "T.t"))
    }

    // "(empty): Greeter.GreetsWorld" on twelve consecutive bullets is what a
    // CTest import looks like without this.
    @Test
    fun `an empty suite is not part of the displayed test name`() {
        assertEquals(
            "Greeter.GreetsWorld",
            TestReport.displayName("(empty): Greeter.GreetsWorld", "Greeter.GreetsWorld", "(empty): "),
        )
        assertEquals(
            "Greeter.GreetsWorld",
            TestReport.displayName("Greeter.GreetsWorld", "Greeter.GreetsWorld", null),
        )
        assertEquals(
            "Greeter.GreetsWorld",
            TestReport.displayName("Greeter.GreetsWorld", "Greeter.GreetsWorld", "  "),
        )
    }

    // A suite that says something is what tells two identical test names apart.
    @Test
    fun `a real suite is kept in the displayed test name`() {
        assertEquals(
            "Linux x64: Greeter.GreetsWorld",
            TestReport.displayName("Linux x64: Greeter.GreetsWorld", "Greeter.GreetsWorld", "Linux x64: "),
        )
    }

    // Never render an empty bullet, whatever the SDK hands us.
    @Test
    fun `the full name is the fallback when the suite-less name is empty`() {
        assertEquals("(empty): T.t", TestReport.displayName("(empty): T.t", "  ", "(empty)"))
    }

    // The real thing, as a CTest import hands it over: the assertion is buried
    // in the middle of gtest's own chatter, and it is the only part worth
    // sending to GitHub.
    @Test
    fun `a gtest run is reduced to the assertion that failed`() {
        val raw = """
            Failed

            ------- Stdout: -------
            Running main() from /tmp/conan-cache/p/b/gtest/src/gtest_main.cc
            Note: Google Test filter = Greeter.GreetsEmptyName
            [==========] Running 1 test from 1 test suite.
            [----------] Global test environment set-up.
            [----------] 1 test from Greeter
            [ RUN      ] Greeter.GreetsEmptyName
            /work/tests/test_greeter.cpp:14: Failure
            Expected equality of these values:
              greet("")
                Which is: "Hello, {name}!"
              "Hello, !"
            [  FAILED  ] Greeter.GreetsEmptyName (0 ms)
            [----------] 1 test from Greeter (0 ms total)
            [==========] 1 test from 1 test suite ran. (0 ms total)
            [  PASSED  ] 0 tests.
            [  FAILED  ] 1 test, listed below:
        """.trimIndent()

        val text = TestReport.informativeFailure(raw, "Greeter.GreetsEmptyName")!!
        assertEquals(
            """
            /work/tests/test_greeter.cpp:14: Failure
            Expected equality of these values:
              greet("")
                Which is: "Hello, {name}!"
              "Hello, !"
            """.trimIndent(),
            text,
        )

        // And the fold-out is labelled with where it broke, not with "Failed".
        val section = TestReport.section(
            counts(total = 43, passed = 31, failed = 12),
            listOf(FailedTestRun(name = "Greeter.GreetsEmptyName", newFailure = false, durationMillis = 0, failureText = raw)),
        )!!
        assertTrue(section.contains("<summary>test_greeter.cpp:14: Failure</summary>"), section)
        assertFalse(section.contains("<summary>Failed</summary>"), section)
        assertFalse(section.contains("Running main()"), section)
    }

    // What a Windows agent's own gtest output looks like: the label would spend
    // most of its width on a checkout directory nobody reading the PR can act on.
    @Test
    fun `the fold-out label drops the path to the agent`() {
        val raw = """
            Failed
            D:\BuildAgent\work\test-ci\PR\Windows_x64_Release\tests\test_greeter.cpp(14): error: Expected equality of these values:
              greet("")
              "Hello, !"
        """.trimIndent()
        val section = TestReport.section(
            counts(total = 43, passed = 31, failed = 12),
            listOf(FailedTestRun(name = "Greeter.GreetsEmptyName", newFailure = false, durationMillis = 0, failureText = raw)),
        )!!
        assertTrue(
            section.contains("<summary>test_greeter.cpp(14): error: Expected equality of these values:</summary>"),
            section,
        )
        // The body keeps the full path: that is what somebody copies.
        assertTrue(section.contains("""D:\BuildAgent\work\test-ci\PR\Windows_x64_Release\tests\test_greeter.cpp(14)"""), section)
    }

    @Test
    fun `a POSIX agent path is dropped from the label too, a relative one is kept`() {
        val posix = "/opt/buildagent/work/test-ci/tests/test_greeter.cpp:14: Failure\nExpected equality"
        val relative = "tests/test_greeter.cpp:14: Failure\nExpected equality"
        fun label(text: String) = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0, failureText = text)),
        )!!
        assertTrue(label(posix).contains("<summary>test_greeter.cpp:14: Failure</summary>"), label(posix))
        assertTrue(label(relative).contains("<summary>tests/test_greeter.cpp:14: Failure</summary>"), label(relative))
    }

    // A CTest run concatenates every configuration's output into the failure
    // text of one test run. The excerpt must stop at the first block, or the
    // Release assertion runs into the Debug one.
    @Test
    fun `the excerpt stops at the end of the first failure block`() {
        val raw = """
            Failed

            /opt/buildagent/work/test-ci/PR/Linux_x64_Release/tests/test_greeter.cpp:14: Failure
            Expected equality of these values:
              greet("")
                Which is: "{salutation}, !"
              "Hello, !"

              1 FAILED TEST
            Failed

            /opt/buildagent/work/test-ci/PR/Linux_x64_Debug/tests/test_greeter.cpp:14: Failure
            Expected equality of these values:
              greet("")
        """.trimIndent()

        assertEquals(
            """
            /opt/buildagent/work/test-ci/PR/Linux_x64_Release/tests/test_greeter.cpp:14: Failure
            Expected equality of these values:
              greet("")
                Which is: "{salutation}, !"
              "Hello, !"
            """.trimIndent(),
            TestReport.excerpt(raw),
        )
    }

    // …but a blank line inside an expected-vs-actual block is not an end.
    @Test
    fun `a blank line does not end the excerpt`() {
        val raw = "src/a.cpp:1: Failure\nexpected:\n\n  42\ngot:\n\n  43"
        assertEquals(raw, TestReport.excerpt(raw))
    }

    // MSVC puts the line number in parentheses.
    @Test
    fun `the MSVC anchor shape is recognised`() {
        val raw = "Failed\nsome preamble nobody needs\nsrc\\greeter.cpp(14): error C2065: undeclared identifier"
        assertEquals(
            "src\\greeter.cpp(14): error C2065: undeclared identifier",
            TestReport.excerpt(raw),
        )
    }

    // No anchor: keep what is there, minus the leading "Failed".
    @Test
    fun `a failure with no file reference keeps its own text`() {
        assertEquals("boom: expected 3, got 4", TestReport.excerpt("Failed\n\nboom: expected 3, got 4"))
        assertEquals("expected 3, got 4", TestReport.excerpt("expected 3, got 4"))
    }

    // The filters must never be able to empty the panel.
    @Test
    fun `scaffolding-only output falls back to the raw text`() {
        val raw = "[==========] Running 1 test from 1 test suite.\n[  PASSED  ] 0 tests."
        assertEquals(raw, TestReport.excerpt(raw))
    }

    @Test
    fun `the excerpt is capped in lines`() {
        val raw = (1..40).joinToString("\n") { "line $it" }
        assertEquals(TestReport.MAX_EXCERPT_LINES, TestReport.excerpt(raw).lines().size)
    }

    // Some importers use the test's own name as the failure text.
    @Test
    fun `a failure text that only repeats the test name is dropped`() {
        assertNull(TestReport.informativeFailure("Greeter.GreetsWorld", "Greeter.GreetsWorld"))
        assertEquals("boom", TestReport.informativeFailure("boom", "Greeter.GreetsWorld"))
    }

    @Test
    fun `the list is capped and says how many it left out`() {
        val failures = (1..TestReport.MAX_LISTED + 5).map {
            FailedTestRun(name = "T.test%03d".format(it), newFailure = false, durationMillis = 0)
        }
        val section = TestReport.section(counts(total = 100, failed = failures.size), failures)!!
        assertTrue(section.contains("…and 5 more failing test(s)"), section)
        assertFalse(section.contains("T.test025"), section)
    }

    @Test
    fun `a test with no duration reports no duration`() {
        val section = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0)),
        )!!
        assertFalse(section.contains("—  "), section)
        assertTrue(section.contains("- **new** `T.t`\n"), section)
    }
}
