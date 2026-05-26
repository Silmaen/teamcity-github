package io.github.dlachouette.teamcity.github.enrich

import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrPromotionTaggerTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `adds draft tag when none present and PR is draft`() {
        val plan = PrPromotionTagger.computePlan(emptyList(), isDraft = true)
        assertEquals(listOf("draft"), plan?.newTags)
        assertEquals("draft", plan?.appliedTag)
    }

    @Test
    fun `adds ready tag when none present and PR is ready`() {
        val plan = PrPromotionTagger.computePlan(emptyList(), isDraft = false)
        assertEquals(listOf("ready"), plan?.newTags)
        assertEquals("ready", plan?.appliedTag)
    }

    @Test
    fun `is idempotent when desired tag is already present`() {
        val plan = PrPromotionTagger.computePlan(listOf("draft", "nightly"), isDraft = true)
        assertNull(plan)
    }

    @Test
    fun `replaces ready with draft when PR transitions to draft`() {
        val plan = PrPromotionTagger.computePlan(listOf("ready", "nightly"), isDraft = true)
        assertEquals("draft", plan?.appliedTag)
        assertEquals(listOf("nightly", "draft"), plan?.newTags)
    }

    @Test
    fun `replaces draft with ready when PR transitions to ready`() {
        val plan = PrPromotionTagger.computePlan(listOf("nightly", "draft"), isDraft = false)
        assertEquals("ready", plan?.appliedTag)
        assertEquals(listOf("nightly", "ready"), plan?.newTags)
    }

    @Test
    fun `preserves unrelated tags`() {
        val plan = PrPromotionTagger.computePlan(listOf("nightly", "experimental"), isDraft = true)
        assertTrue(plan!!.newTags.contains("nightly"))
        assertTrue(plan.newTags.contains("experimental"))
        assertTrue(plan.newTags.contains("draft"))
    }

    // Opt-in gate: same shape as BuildStatusCheckRunPublisher.isOptedIn.
    // Locks in the v1.2.1 fix that drops the previous ignoreDrafts=true
    // requirement so ALL-scope (draft-friendly) PR builds also get tagged.

    @Test
    fun `isOptedIn requires repo and connection id`() {
        assertTrue(
            PrPromotionTagger.isOptedIn(
                mapOf(
                    DraftAwareBuildFilter.PARAM_REPO_SLUG to "acme/widget",
                    DraftAwareBuildFilter.PARAM_CONNECTION_ID to "CID_abc",
                )
            )
        )
    }

    @Test
    fun `isOptedIn returns false when repo is missing`() {
        assertFalse(
            PrPromotionTagger.isOptedIn(
                mapOf(DraftAwareBuildFilter.PARAM_CONNECTION_ID to "CID_abc")
            )
        )
    }

    @Test
    fun `isOptedIn returns false when connection id is missing`() {
        assertFalse(
            PrPromotionTagger.isOptedIn(
                mapOf(DraftAwareBuildFilter.PARAM_REPO_SLUG to "acme/widget")
            )
        )
    }

    @Test
    fun `isOptedIn returns false on blank values`() {
        assertFalse(
            PrPromotionTagger.isOptedIn(
                mapOf(
                    DraftAwareBuildFilter.PARAM_REPO_SLUG to "",
                    DraftAwareBuildFilter.PARAM_CONNECTION_ID to "CID_abc",
                )
            )
        )
    }

    @Test
    fun `isOptedIn does NOT require ignoreDrafts=true`() {
        // The previous version required this to be "true"; v1.2.1
        // drops the requirement so ALL-scope PR builds (those run on
        // draft PRs with ignoreDrafts=false) also get the
        // draft / ready visual signal.
        assertTrue(
            PrPromotionTagger.isOptedIn(
                mapOf(
                    DraftAwareBuildFilter.PARAM_REPO_SLUG to "acme/widget",
                    DraftAwareBuildFilter.PARAM_CONNECTION_ID to "CID_abc",
                    DraftAwareBuildFilter.PARAM_IGNORE_DRAFTS to "false",
                )
            )
        )
        assertTrue(
            PrPromotionTagger.isOptedIn(
                mapOf(
                    DraftAwareBuildFilter.PARAM_REPO_SLUG to "acme/widget",
                    DraftAwareBuildFilter.PARAM_CONNECTION_ID to "CID_abc",
                )
            )
        )
    }
}
