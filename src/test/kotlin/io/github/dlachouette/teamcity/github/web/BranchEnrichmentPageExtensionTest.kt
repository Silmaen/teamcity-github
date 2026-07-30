package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BranchEnrichmentPageExtensionTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `keeps the characters a tag prefix legitimately uses`() {
        assertEquals("pr-", BranchEnrichmentPageExtension.sanitizeTagPrefix("pr-"))
        assertEquals("github/pr_", BranchEnrichmentPageExtension.sanitizeTagPrefix("github/pr_"))
        assertEquals("PR.", BranchEnrichmentPageExtension.sanitizeTagPrefix("PR."))
    }

    // The result is interpolated into a JS string literal in the page
    // fragment, so nothing that could close it may survive.
    @Test
    fun `strips anything that could break out of a JS string literal`() {
        assertEquals("pr-", BranchEnrichmentPageExtension.sanitizeTagPrefix("pr-'"))
        assertEquals("pr-", BranchEnrichmentPageExtension.sanitizeTagPrefix("pr-\\"))
        // The `/` survives on purpose — it is legitimate in a tag prefix. What
        // matters is that the angle brackets, quotes and parens are gone, so
        // the result cannot be anything but an inert string.
        assertEquals("prscriptalert1/script", BranchEnrichmentPageExtension.sanitizeTagPrefix("pr<script>alert(1)</script>"))
        assertEquals("pr", BranchEnrichmentPageExtension.sanitizeTagPrefix("pr\n"))
        assertEquals("pr", BranchEnrichmentPageExtension.sanitizeTagPrefix("pr with spaces".take(2)))
    }

    @Test
    fun `a prefix with nothing usable disables the PR pill`() {
        assertEquals("", BranchEnrichmentPageExtension.sanitizeTagPrefix(""))
        assertEquals("", BranchEnrichmentPageExtension.sanitizeTagPrefix("'\"\\<>"))
    }
}
