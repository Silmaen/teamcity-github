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
    fun `adds the draft state and the PR tag for a draft PR`() {
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = true))
        assertEquals(listOf("draft", "pr-7"), plan.tagsToAdd)
    }

    @Test
    fun `adds the ready state and the PR tag for a non-draft PR`() {
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = false))
        assertEquals(listOf("ready", "pr-7"), plan.tagsToAdd)
    }

    @Test
    fun `does not duplicate tags that are already present`() {
        assertTrue(PrBuildEnricher.computePlan("42", listOf("draft", "pr-7"), pr(draft = true)).tagsToAdd.isEmpty())
        // A build tagged during the draft phase only needs the state update.
        assertEquals(
            listOf("ready"),
            PrBuildEnricher.computePlan("42", listOf("draft", "pr-7"), pr(draft = false)).tagsToAdd,
        )
    }

    // The PR tag is what makes a build findable by PR number without asking
    // GitHub anything (G12) — including on a plain branch ref.
    @Test
    fun `the PR tag round-trips`() {
        assertEquals("pr-189", PrBuildEnricher.prTag(189))
        assertEquals(189, PrBuildEnricher.prNumberFromTag("pr-189"))
    }

    @Test
    fun `only a well-formed PR tag is read back`() {
        listOf("pr-", "pr-x", "pr-0", "pr--1", "draft", "ready", "prime", "PR-7").forEach {
            assertNull(PrBuildEnricher.prNumberFromTag(it), "tag=$it")
        }
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
