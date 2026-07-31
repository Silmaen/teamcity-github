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
    fun `the title of a green build reports the total, and any muted test`() {
        assertEquals("1046 tests passed", TestReport.titleSuffix(counts(total = 1046, passed = 1046)))
        assertEquals("1046 tests passed, 2 muted", TestReport.titleSuffix(counts(total = 1046, passed = 1044, muted = 2)))
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
    fun `the failure text is folded away and truncated`() {
        val long = "boom\n".repeat(500)
        val section = TestReport.section(
            counts(total = 1, failed = 1),
            listOf(FailedTestRun(name = "T.t", newFailure = true, durationMillis = 0, failureText = long)),
        )!!
        assertTrue(section.contains("<details><summary>failure</summary>"), section)
        assertTrue(section.contains("… (truncated)"), section)
        assertTrue(section.length < long.length, "the section must not carry the whole stacktrace")
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
