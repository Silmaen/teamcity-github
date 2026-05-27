package io.github.dlachouette.teamcity.github.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BranchSpecMatcherTest {

    @Test
    fun `empty spec matches everything (default-open)`() {
        val matcher = BranchSpecMatcher.parse("")
        assertTrue(matcher.isEmpty())
        assertTrue(matcher.matches("pull/42"))
        assertTrue(matcher.matches("any/branch"))
        assertTrue(matcher.matches("main"))
    }

    @Test
    fun `null spec matches everything`() {
        val matcher = BranchSpecMatcher.parse(null)
        assertTrue(matcher.isEmpty())
        assertTrue(matcher.matches("anything"))
    }

    @Test
    fun `blank-only spec matches everything`() {
        val matcher = BranchSpecMatcher.parse("   \n  \n\t\n")
        assertTrue(matcher.isEmpty())
        assertTrue(matcher.matches("x"))
    }

    @Test
    fun `only exclude rules match everything except excluded`() {
        val matcher = BranchSpecMatcher.parse("-:scratch/*")
        assertTrue(matcher.matches("Feature/foo"))
        assertTrue(matcher.matches("main"))
        assertFalse(matcher.matches("scratch/poc"))
    }

    @Test
    fun `matches with pull branch uses headRef`() {
        val matcher = BranchSpecMatcher.parse("+:Feature/*")
        // pull/42 with headRef "Feature/foo" -> match
        assertTrue(matcher.matches("pull/42", headRefIfPr = "Feature/foo"))
        // pull/42 with headRef "bug/x" -> no match
        assertFalse(matcher.matches("pull/42", headRefIfPr = "bug/x"))
        // Non-PR branch uses branch name directly
        assertTrue(matcher.matches("Feature/foo", headRefIfPr = null))
        // pull/* with no headRef falls back to literal "pull/N"
        assertFalse(matcher.matches("pull/42", headRefIfPr = null))
    }

    @Test
    fun `single include glob matches`() {
        val matcher = BranchSpecMatcher.parse("+:Feature/*")
        assertTrue(matcher.matches("Feature/foo"))
        assertTrue(matcher.matches("Feature/sub/path"))
        assertFalse(matcher.matches("feature/lowercase"))
        assertFalse(matcher.matches("other/Feature/foo"))
    }

    @Test
    fun `bare line is treated as include`() {
        val matcher = BranchSpecMatcher.parse("Feature/*")
        assertTrue(matcher.matches("Feature/foo"))
        assertFalse(matcher.matches("bug/x"))
    }

    @Test
    fun `multiple include lines union`() {
        val matcher = BranchSpecMatcher.parse(
            """
            +:Feature/*
            +:hotfix/*
            +:release-train/*
            """.trimIndent()
        )
        assertTrue(matcher.matches("Feature/x"))
        assertTrue(matcher.matches("hotfix/123"))
        assertTrue(matcher.matches("release-train/2026-Q2"))
        assertFalse(matcher.matches("main"))
    }

    @Test
    fun `exclude overrides include`() {
        val matcher = BranchSpecMatcher.parse(
            """
            +:Feature/*
            -:Feature/temp-*
            """.trimIndent()
        )
        assertTrue(matcher.matches("Feature/foo"))
        assertFalse(matcher.matches("Feature/temp-debug"))
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val matcher = BranchSpecMatcher.parse(
            """
            # automation branches
            +:Feature/*cascade-merge*

            +:release-train/*
            # exclude scratch sub-paths
            -:Feature/scratch-*
            """.trimIndent()
        )
        assertTrue(matcher.matches("Feature/cascade-merge-2026Q2"))
        assertTrue(matcher.matches("release-train/main"))
        assertFalse(matcher.matches("Feature/scratch-poc"))
    }

    @Test
    fun `question mark matches single character`() {
        val matcher = BranchSpecMatcher.parse("+:bug-?")
        assertTrue(matcher.matches("bug-1"))
        assertTrue(matcher.matches("bug-X"))
        assertFalse(matcher.matches("bug-12"))
        assertFalse(matcher.matches("bug-"))
    }

    @Test
    fun `regex form uses delimiters`() {
        val matcher = BranchSpecMatcher.parse("+:/release-\\d+\\.\\d+/")
        assertTrue(matcher.matches("release-1.0"))
        assertTrue(matcher.matches("release-2026.5"))
        assertFalse(matcher.matches("release-foo"))
    }

    @Test
    fun `dot in pattern is literal`() {
        val matcher = BranchSpecMatcher.parse("+:bug.123")
        assertTrue(matcher.matches("bug.123"))
        assertFalse(matcher.matches("bugX123"))
    }

    @Test
    fun `validate returns null for valid specs`() {
        assertNull(BranchSpecMatcher.validate(null))
        assertNull(BranchSpecMatcher.validate(""))
        assertNull(BranchSpecMatcher.validate("+:Feature/*"))
        assertNull(BranchSpecMatcher.validate("+:foo\n-:bar"))
        assertNull(BranchSpecMatcher.validate("+:/release-\\d+/"))
    }

    @Test
    fun `validate flags empty patterns`() {
        val err = BranchSpecMatcher.validate("+:\n-:")
        assertNotNull(err)
        assertTrue(err!!.contains("empty pattern"))
    }

    @Test
    fun `validate flags invalid regex`() {
        val err = BranchSpecMatcher.validate("+:/[unclosed/")
        assertNotNull(err)
        assertTrue(err!!.contains("invalid regex"))
    }

    @Test
    fun `asString round-trips the original spec`() {
        val spec = "+:Feature/*\n-:Feature/temp-*"
        assertEquals(spec, BranchSpecMatcher.parse(spec).asString())
    }
}
