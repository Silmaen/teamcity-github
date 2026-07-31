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
        assertEquals(listOf("draft"), plan?.appliedTags)
    }

    @Test
    fun `adds ready tag when none present and PR is ready`() {
        val plan = PrPromotionTagger.computePlan(emptyList(), isDraft = false)
        assertEquals(listOf("ready"), plan?.newTags)
        assertEquals(listOf("ready"), plan?.appliedTags)
    }

    @Test
    fun `is idempotent when desired tag is already present`() {
        val plan = PrPromotionTagger.computePlan(listOf("draft", "nightly"), isDraft = true)
        assertNull(plan)
    }

    @Test
    fun `replaces ready with draft when PR transitions to draft`() {
        val plan = PrPromotionTagger.computePlan(listOf("ready", "nightly"), isDraft = true)
        assertEquals(listOf("draft"), plan?.appliedTags)
        assertEquals(listOf("nightly", "draft"), plan?.newTags)
    }

    @Test
    fun `replaces draft with ready when PR transitions to ready`() {
        val plan = PrPromotionTagger.computePlan(listOf("nightly", "draft"), isDraft = false)
        assertEquals(listOf("ready"), plan?.appliedTags)
        assertEquals(listOf("nightly", "ready"), plan?.newTags)
    }

    @Test
    fun `preserves unrelated tags`() {
        val plan = PrPromotionTagger.computePlan(listOf("nightly", "experimental"), isDraft = true)
        assertTrue(plan!!.newTags.contains("nightly"))
        assertTrue(plan.newTags.contains("experimental"))
        assertTrue(plan.newTags.contains("draft"))
    }

    // The bug this fixes: only the listener's retro-association pass wrote the
    // PR tag, and only onto builds sitting at the pull request's current head.
    // Every older build lost its PR number — and in branch-source mode there is
    // no `pull/N` ref to recover it from, so the "Branches & PRs" tab showed an
    // empty PR column and a search by number found nothing.
    @Test
    fun `tags the PR number alongside the state`() {
        val plan = PrPromotionTagger.computePlan(emptyList(), isDraft = false, prTag = "pr-189")!!
        assertEquals(listOf("ready", "pr-189"), plan.newTags)
        assertEquals(listOf("ready", "pr-189"), plan.appliedTags)
    }

    @Test
    fun `adds a missing PR tag to a build that already carries its state`() {
        val plan = PrPromotionTagger.computePlan(listOf("nightly", "ready"), isDraft = false, prTag = "pr-189")!!
        assertEquals(listOf("nightly", "ready", "pr-189"), plan.newTags)
        assertEquals(listOf("pr-189"), plan.appliedTags)
    }

    @Test
    fun `does nothing when both the state and the PR tag are settled`() {
        assertNull(PrPromotionTagger.computePlan(listOf("ready", "pr-189"), isDraft = false, prTag = "pr-189"))
    }

    // PR tagging switched off (`prTag.enabled`): the state tag still applies.
    @Test
    fun `a null PR tag leaves the state tagging alone`() {
        val plan = PrPromotionTagger.computePlan(listOf("pr-189"), isDraft = true, prTag = null)!!
        assertEquals(listOf("pr-189", "draft"), plan.newTags)
        assertEquals(listOf("draft"), plan.appliedTags)
    }

    // The opt-in gate moved from buildType.parameters to the
    // "GitHub Bridge integration" BuildFeature in v1.5.0. The
    // equivalent of the previous `isOptedIn` tests now lives in
    // BridgeFeatureConfigTest exercising BridgeFeatureReader.fromParams.
}
