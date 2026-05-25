package io.github.dlachouette.teamcity.github.enrich

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
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
}
