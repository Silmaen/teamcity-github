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

    // G14: QA reaches the installer from the pull request, so the cell holds
    // **direct download** links — one per artifact — and they must survive the
    // JSON round-trip.
    @Test
    fun `renders one direct link per artifact and parses them back`() {
        val links = listOf(
            PrSummaryCommenter.ArtifactLink("setup.exe", "https://tc/repository/download/Bt/42:id/setup.exe"),
            PrSummaryCommenter.ArtifactLink("sha256.txt", "https://tc/repository/download/Bt/42:id/sha256.txt"),
        )
        val body = commenter.render(
            mapOf("TeamCity / A" to PrSummaryCommenter.Row("✅", "Build passed", "https://tc/a", links))
        )
        assertTrue(body.contains("[setup.exe](https://tc/repository/download/Bt/42:id/setup.exe)"))
        assertTrue(body.contains("[sha256.txt](https://tc/repository/download/Bt/42:id/sha256.txt)"))
        assertEquals(links, commenter.parseState(body)["TeamCity / A"]?.artifacts)
    }

    @Test
    fun `a row without artifacts renders an empty cell`() {
        val body = commenter.render(mapOf("TeamCity / A" to PrSummaryCommenter.Row("✅", "Build passed", "https://tc/a")))
        assertTrue(!body.contains("[setup.exe]"))
        assertEquals(emptyList<PrSummaryCommenter.ArtifactLink>(), commenter.parseState(body)["TeamCity / A"]?.artifacts)
    }

    @Test
    fun `a legacy artifactsUrl is read back as a single link`() {
        // Written by 1.9.0-rc versions, which pointed at the artifacts tab.
        // Upgrading must not blank the cell.
        val legacy = """
            ${PrSummaryCommenter.MARKER_BEGIN}
            {"TeamCity / A":{"emoji":"✅","text":"Build passed","url":"https://tc/a","artifactsUrl":"https://tc/a/artifacts"}}
            ${PrSummaryCommenter.MARKER_END}
        """.trimIndent()
        assertEquals(
            listOf(PrSummaryCommenter.ArtifactLink("artifacts", "https://tc/a/artifacts")),
            commenter.parseState(legacy)["TeamCity / A"]?.artifacts,
        )
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
        assertEquals(emptyList<PrSummaryCommenter.ArtifactLink>(), row.artifacts)
    }

    // The download URL contract: `/repository/download/<bt>/<buildId>:id/<path>`,
    // the same one artifact dependencies use — a link that downloads the file
    // rather than opening a TeamCity page.
    @Test
    fun `artifact paths are percent-encoded per segment`() {
        assertEquals(
            "dist/my%20app%20setup.exe",
            BuildStatusCheckRunPublisher.encodeArtifactPath("dist/my app setup.exe"),
        )
        assertEquals(
            "reports/index.html",
            BuildStatusCheckRunPublisher.encodeArtifactPath("reports/index.html"),
        )
        // A '+' in a file name must survive as %2B, not be read back as a space.
        assertEquals("libc%2B%2B.zip", BuildStatusCheckRunPublisher.encodeArtifactPath("libc++.zip"))
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
