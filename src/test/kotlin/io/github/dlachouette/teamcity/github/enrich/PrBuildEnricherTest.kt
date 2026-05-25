package io.github.dlachouette.teamcity.github.enrich

import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrBuildEnricherTest {

    init { LoggerBootstrap.install() }

    private fun pr(draft: Boolean, headRef: String = "feature/x") = PrInfo(
        number = 7,
        title = "WIP",
        author = "bob",
        headRef = headRef,
        baseRef = "main",
        headSha = "f00ba4",
        draft = draft,
        state = "open",
    )

    @Test
    fun `adds draft tag for a draft PR`() {
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = true))
        assertEquals(listOf("draft"), plan.tagsToAdd)
    }

    @Test
    fun `adds ready tag for a non-draft PR`() {
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = false))
        assertEquals(listOf("ready"), plan.tagsToAdd)
    }

    @Test
    fun `does not duplicate the state tag when already present`() {
        val plan = PrBuildEnricher.computePlan("42", listOf("draft"), pr(draft = true))
        assertTrue(plan.tagsToAdd.isEmpty())
    }

    @Test
    fun `appends head ref to build number`() {
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = false, headRef = "feature/x"))
        assertEquals("42 feature/x", plan.newBuildNumber)
    }

    @Test
    fun `does not re-append head ref if already in build number`() {
        val plan = PrBuildEnricher.computePlan("42 feature/x", emptyList(), pr(draft = false, headRef = "feature/x"))
        assertNull(plan.newBuildNumber)
    }

    @Test
    fun `does not modify build number when head ref is blank`() {
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = false, headRef = ""))
        assertNull(plan.newBuildNumber)
    }

    @Test
    fun `does not modify build number when current is null`() {
        val plan = PrBuildEnricher.computePlan(null, emptyList(), pr(draft = false))
        assertNull(plan.newBuildNumber)
    }

    @Test
    fun `does not modify build number when current is blank`() {
        val plan = PrBuildEnricher.computePlan("   ", emptyList(), pr(draft = false))
        assertNull(plan.newBuildNumber)
    }
}
