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
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = true), prTag = "pr-7")
        assertEquals(listOf("draft", "pr-7"), plan.tagsToAdd)
    }

    @Test
    fun `adds the ready state and the PR tag for a non-draft PR`() {
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = false), prTag = "pr-7")
        assertEquals(listOf("ready", "pr-7"), plan.tagsToAdd)
    }

    @Test
    fun `applies only the state tag when PR tagging is disabled`() {
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = false), prTag = null)
        assertEquals(listOf("ready"), plan.tagsToAdd)
    }

    @Test
    fun `honours a custom PR tag prefix`() {
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = false), prTag = "PR#7")
        assertEquals(listOf("ready", "PR#7"), plan.tagsToAdd)
    }

    @Test
    fun `does not duplicate tags that are already present`() {
        assertTrue(
            PrBuildEnricher.computePlan("42", listOf("draft", "pr-7"), pr(draft = true), prTag = "pr-7")
                .tagsToAdd.isEmpty()
        )
        // A build tagged during the draft phase only needs the state update.
        assertEquals(
            listOf("ready"),
            PrBuildEnricher.computePlan("42", listOf("draft", "pr-7"), pr(draft = false), prTag = "pr-7").tagsToAdd,
        )
    }

    // The PR tag is what makes a build findable by PR number without asking
    // GitHub anything (G12) — including on a plain branch ref.
    @Test
    fun `the PR tag round-trips through any prefix`() {
        listOf("pr-", "PR#", "github-pr-").forEach { prefix ->
            val tag = PrBuildEnricher.prTag(prefix, 189)
            assertEquals(189, PrBuildEnricher.prNumberFromTag(tag, prefix), "prefix=$prefix")
        }
    }

    @Test
    fun `only a well-formed PR tag is read back`() {
        listOf("pr-", "pr-x", "pr-0", "pr--1", "draft", "ready", "prime", "PR-7").forEach {
            assertNull(PrBuildEnricher.prNumberFromTag(it, "pr-"), "tag=$it")
        }
        // A tag written with another prefix is not ours to read.
        assertNull(PrBuildEnricher.prNumberFromTag("pr-189", "PR#"))
        // An empty prefix would match every numeric tag: refuse it.
        assertNull(PrBuildEnricher.prNumberFromTag("189", ""))
    }

    @Test
    fun `appends head ref to build number`() {
        val plan = PrBuildEnricher.computePlan("42", emptyList(), pr(draft = false, headRef = "feature/x"))
        assertEquals("42 feature/x", plan.newBuildNumber)
    }

    // The suffix exists to make a `pull/N` build readable. Once the build runs
    // on the head ref itself (branch-source mode) the Branch column already
    // says it, so repeating it in the build number is noise.
    @Test
    fun `no suffix when the branch already names the head ref`() {
        val plan = PrBuildEnricher.computePlan(
            currentBuildNumber = "42",
            currentTags = emptyList(),
            pr = pr(draft = false, headRef = "Feature/DirectReady"),
            branchName = "Feature/DirectReady",
        )
        assertNull(plan.newBuildNumber)
        // Tags are unaffected by this.
        assertEquals(listOf("ready"), plan.tagsToAdd)
    }

    @Test
    fun `the suffix is still added on a pull ref`() {
        val plan = PrBuildEnricher.computePlan(
            currentBuildNumber = "42",
            currentTags = emptyList(),
            pr = pr(draft = false, headRef = "Feature/DirectReady"),
            branchName = "pull/5",
        )
        assertEquals("42 Feature/DirectReady", plan.newBuildNumber)
    }

    @Test
    fun `an unknown branch keeps the historical behaviour`() {
        val plan = PrBuildEnricher.computePlan(
            currentBuildNumber = "42",
            currentTags = emptyList(),
            pr = pr(draft = false, headRef = "feature/x"),
            branchName = null,
        )
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
