package io.github.dlachouette.teamcity.github.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkipReasonTest {

    @Test
    fun `DRAFT_PR carries the draft template`() {
        val (title, summary) = SkipReason.DRAFT_PR.titleAndSummary(prNumber = 42, headRef = "Feature/foo")
        assertEquals("Skipped: draft PR", title)
        assertTrue(summary.contains("PR #42"))
        assertTrue(summary.contains("draft"))
    }

    @Test
    fun `DRAFT_PR ignores headRef in the summary`() {
        // The PR is the unit; the source branch isn't mentioned.
        val (_, summary) = SkipReason.DRAFT_PR.titleAndSummary(prNumber = 7, headRef = "anything")
        assertTrue(!summary.contains("anything"))
    }

    @Test
    fun `BRANCH_FILTER mentions the source branch name`() {
        val (title, summary) = SkipReason.BRANCH_FILTER.titleAndSummary(prNumber = 99, headRef = "Feature/cascade")
        assertEquals("Skipped: branch out of scope", title)
        assertTrue(summary.contains("Feature/cascade"))
        assertTrue(summary.contains("branch filter"))
    }

    @Test
    fun `BRANCH_FILTER falls back to (unknown) when headRef is blank`() {
        val (_, summary) = SkipReason.BRANCH_FILTER.titleAndSummary(prNumber = 1, headRef = "")
        assertTrue(summary.contains("(unknown)"))
    }

    @Test
    fun `BRANCH_FILTER falls back to (unknown) when headRef is null`() {
        val (_, summary) = SkipReason.BRANCH_FILTER.titleAndSummary(prNumber = 1, headRef = null)
        assertTrue(summary.contains("(unknown)"))
    }
}
