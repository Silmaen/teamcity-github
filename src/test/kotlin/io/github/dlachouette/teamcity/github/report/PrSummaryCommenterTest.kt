package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrSummaryCommenterTest {

    init { LoggerBootstrap.install() }

    private val commenter = PrSummaryCommenter(GitHubClient())

    @Test
    fun `render then parse round-trips the state map`() {
        val rows = mapOf(
            "TeamCity / A" to PrSummaryCommenter.Row("✅", "Build passed", "https://tc/a"),
            "TeamCity / B" to PrSummaryCommenter.Row("❌", "Build failed", null),
        )
        val body = commenter.render(rows)
        val parsed = commenter.parseState(body)
        assertEquals(rows, parsed)
    }

    @Test
    fun `render embeds the marker and a table row per check`() {
        val body = commenter.render(mapOf("TeamCity / A" to PrSummaryCommenter.Row("✅", "Build passed", "https://tc/a")))
        assertTrue(body.contains(PrSummaryCommenter.MARKER_BEGIN))
        assertTrue(body.contains("TeamCity / A"))
        assertTrue(body.contains("[details](https://tc/a)"))
    }

    @Test
    fun `parseState returns empty for a body without the marker`() {
        assertTrue(commenter.parseState("just a normal comment").isEmpty())
        assertTrue(commenter.parseState(null).isEmpty())
    }

    // G14: QA reaches the installer from the PR, so the artefacts link must
    // survive the JSON round-trip and stay optional for comments written by
    // earlier versions.
    @Test
    fun `renders and parses back the artifacts link`() {
        val body = commenter.render(
            mapOf("TeamCity / A" to PrSummaryCommenter.Row("✅", "Build passed", "https://tc/a", "https://tc/a/artifacts"))
        )
        assertTrue(body.contains("[artifacts](https://tc/a/artifacts)"))
        assertEquals("https://tc/a/artifacts", commenter.parseState(body)["TeamCity / A"]?.artifactsUrl)
    }

    @Test
    fun `a row without artifacts renders an empty cell and parses back as null`() {
        val body = commenter.render(mapOf("TeamCity / A" to PrSummaryCommenter.Row("✅", "Build passed", "https://tc/a")))
        assertTrue(!body.contains("[artifacts]"))
        assertNull(commenter.parseState(body)["TeamCity / A"]?.artifactsUrl)
    }

    @Test
    fun `state written before artifacts existed still parses`() {
        val legacy = """
            ${PrSummaryCommenter.MARKER_BEGIN}
            {"TeamCity / A":{"emoji":"✅","text":"Build passed","url":"https://tc/a"}}
            ${PrSummaryCommenter.MARKER_END}
            ### TeamCity build summary
        """.trimIndent()
        val row = commenter.parseState(legacy)["TeamCity / A"]!!
        assertEquals("Build passed", row.text)
        assertNull(row.artifactsUrl)
    }

    @Test
    fun `upsert merges a new check into existing state`() {
        // Simulate an existing comment, then merge a second check via parse+render.
        val first = commenter.render(mapOf("TeamCity / A" to PrSummaryCommenter.Row("✅", "Build passed", null)))
        val merged = commenter.parseState(first).toMutableMap()
        merged["TeamCity / B"] = PrSummaryCommenter.Row("❌", "Build failed", null)
        val body = commenter.render(merged)
        val parsed = commenter.parseState(body)
        assertEquals(2, parsed.size)
        assertTrue(parsed.containsKey("TeamCity / A"))
        assertTrue(parsed.containsKey("TeamCity / B"))
    }
}
