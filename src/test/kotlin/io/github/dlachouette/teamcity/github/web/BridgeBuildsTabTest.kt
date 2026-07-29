package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.enrich.PrBuildEnricher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// G12: the unified branch/PR view — one list, either key finds a build.
class BridgeBuildsTabTest {

    private fun row(
        branch: String,
        prNumber: Int? = null,
        buildTypeName: String = "Build_Linux",
        startedAt: Long = 0L,
    ) = BridgeBuildRow(
        buildTypeId = "Proj_$buildTypeName",
        buildTypeName = buildTypeName,
        branch = branch,
        prNumber = prNumber,
        state = "Build passed",
        level = "ok",
        buildNumber = "42",
        url = null,
        artifactsUrl = null,
        draft = null,
        startedAt = startedAt,
    )

    // --- key resolution

    @Test
    fun `the PR tag wins over the ref, so branch builds are keyed too`() {
        assertEquals(189, BridgeBuildsTab.prNumberOf("Feature/x", listOf("ready", "pr-189"), "pr-"))
        assertEquals(7, BridgeBuildsTab.prNumberOf("pull/7", emptyList(), "pr-"))
        // The tag is authoritative: a `pull/N` build tagged by the enricher
        // agrees with its ref anyway.
        assertEquals(7, BridgeBuildsTab.prNumberOf("pull/7", listOf("pr-7"), "pr-"))
    }

    @Test
    fun `a custom prefix is honoured, the default one is then ignored`() {
        assertEquals(189, BridgeBuildsTab.prNumberOf("Feature/x", listOf("PR#189"), "PR#"))
        assertNull(BridgeBuildsTab.prNumberOf("Feature/x", listOf("pr-189"), "PR#"))
    }

    @Test
    fun `with PR tagging off the ref is the only key left`() {
        // Empty prefix = disabled: a tagged branch build loses its PR column,
        // a pull ref keeps it.
        assertNull(BridgeBuildsTab.prNumberOf("Feature/x", listOf("pr-189"), ""))
        assertEquals(7, BridgeBuildsTab.prNumberOf("pull/7", listOf("pr-7"), ""))
    }

    @Test
    fun `a plain branch build with no PR has no PR key`() {
        assertNull(BridgeBuildsTab.prNumberOf("master", listOf("ready"), "pr-"))
        assertNull(BridgeBuildsTab.prNumberOf(null, emptyList(), "pr-"))
        // Not a PR tag.
        assertNull(BridgeBuildsTab.prNumberOf("master", listOf("pr-", "pr-x", "prefixed"), "pr-"))
    }

    @Test
    fun `the draft state comes from the tags`() {
        assertEquals(true, BridgeBuildsTab.draftOf(listOf(PrBuildEnricher.TAG_DRAFT)))
        assertEquals(false, BridgeBuildsTab.draftOf(listOf(PrBuildEnricher.TAG_READY)))
        assertNull(BridgeBuildsTab.draftOf(listOf("nightly")))
    }

    // --- search

    @Test
    fun `a number searches the PR key`() {
        val rows = listOf(row("Feature/x", 189), row("Feature/y", 42), row("master"))
        listOf("189", "#189").forEach { q ->
            val found = BridgeBuildsTab.filterAndSort(rows, q, BridgeBuildsTab.SORT_TIME)
            assertEquals(listOf("Feature/x"), found.map { it.branch }, "q=$q")
        }
    }

    @Test
    fun `text searches the branch and the build configuration`() {
        val rows = listOf(row("Feature/raycast", 189), row("Bugfix/z", 42), row("master", null, "Nightly_All"))
        assertEquals(
            listOf("Feature/raycast"),
            BridgeBuildsTab.filterAndSort(rows, "feature/", BridgeBuildsTab.SORT_TIME).map { it.branch },
        )
        assertEquals(
            listOf("master"),
            BridgeBuildsTab.filterAndSort(rows, "nightly", BridgeBuildsTab.SORT_TIME).map { it.branch },
        )
    }

    @Test
    fun `an empty query keeps everything`() {
        val rows = listOf(row("Feature/x", 189), row("master"))
        assertEquals(2, BridgeBuildsTab.filterAndSort(rows, "", BridgeBuildsTab.SORT_TIME).size)
    }

    @Test
    fun `a branch that looks numeric still matches as text`() {
        // "42" is read as a PR number first, but a branch containing it also
        // matches — the view must not hide a legitimate row.
        val rows = listOf(row("Release/42", null), row("Feature/x", 42))
        assertEquals(
            setOf("Release/42", "Feature/x"),
            BridgeBuildsTab.filterAndSort(rows, "42", BridgeBuildsTab.SORT_TIME).map { it.branch }.toSet(),
        )
    }

    // --- sorting

    @Test
    fun `default sort is newest first`() {
        val rows = listOf(row("a", startedAt = 100), row("b", startedAt = 300), row("c", startedAt = 200))
        assertEquals(
            listOf("b", "c", "a"),
            BridgeBuildsTab.filterAndSort(rows, "", BridgeBuildsTab.SORT_TIME).map { it.branch },
        )
    }

    @Test
    fun `branch sort groups by branch, newest first inside a branch`() {
        val rows = listOf(
            row("Feature/b", startedAt = 100),
            row("Feature/a", startedAt = 100),
            row("Feature/a", startedAt = 500),
        )
        val sorted = BridgeBuildsTab.filterAndSort(rows, "", BridgeBuildsTab.SORT_BRANCH)
        assertEquals(listOf("Feature/a", "Feature/a", "Feature/b"), sorted.map { it.branch })
        assertEquals(listOf(500L, 100L, 100L), sorted.map { it.startedAt })
    }

    @Test
    fun `PR sort puts the highest PR first and branch builds last`() {
        val rows = listOf(row("master", null), row("Feature/x", 12), row("Feature/y", 200))
        assertEquals(
            listOf(200, 12, null),
            BridgeBuildsTab.filterAndSort(rows, "", BridgeBuildsTab.SORT_PR).map { it.prNumber },
        )
    }
}
