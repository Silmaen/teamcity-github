package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebhookPayloadParserTest {

    init { LoggerBootstrap.install() }

    private val readyPayload = """
        {
          "action": "ready_for_review",
          "number": 189,
          "pull_request": {
            "number": 189,
            "head": {"ref": "Feature/raycast", "sha": "deadbeef1234"},
            "base": {"ref": "main"}
          },
          "repository": {"full_name": "Silmaen/Owl"}
        }
    """.trimIndent()

    @Test
    fun `parse ready_for_review payload`() {
        val parsed = WebhookPayloadParser.parseReadyForReview(readyPayload)
        assertNotNull(parsed)
        parsed!!
        assertEquals(189, parsed.prNumber)
        assertEquals("Silmaen", parsed.repo.owner)
        assertEquals("Owl", parsed.repo.name)
        assertEquals("deadbeef1234", parsed.headSha)
        assertEquals("Feature/raycast", parsed.headRef)
        assertEquals("main", parsed.baseRef)
    }

    @Test
    fun `parse ignores non-ready actions`() {
        val opened = readyPayload.replace("ready_for_review", "opened")
        assertNull(WebhookPayloadParser.parseReadyForReview(opened))
    }

    @Test
    fun `parse returns null without repo`() {
        val noRepo = """
            {
              "action": "ready_for_review",
              "pull_request": {"number": 1, "head": {}, "base": {}}
            }
        """.trimIndent()
        assertNull(WebhookPayloadParser.parseReadyForReview(noRepo))
    }

    @Test
    fun `parse returns null on garbage`() {
        assertNull(WebhookPayloadParser.parseReadyForReview("not even json"))
    }
}
