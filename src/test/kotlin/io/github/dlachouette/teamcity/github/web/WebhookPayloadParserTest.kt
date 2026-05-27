package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.retrigger.PrAction
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebhookPayloadParserTest {

    init { LoggerBootstrap.install() }

    private fun payload(action: String, draft: Boolean = false): String = """
        {
          "action": "$action",
          "number": 189,
          "pull_request": {
            "number": 189,
            "draft": $draft,
            "head": {"ref": "Feature/raycast", "sha": "deadbeef1234"},
            "base": {"ref": "main"}
          },
          "repository": {"full_name": "acme/widget"}
        }
    """.trimIndent()

    @Test
    fun `parse ready_for_review payload`() {
        val parsed = WebhookPayloadParser.parsePullRequestEvent(payload("ready_for_review"))
        assertNotNull(parsed)
        parsed!!
        assertEquals(PrAction.READY_FOR_REVIEW, parsed.action)
        assertEquals(189, parsed.prNumber)
        assertEquals("acme", parsed.repo.owner)
        assertEquals("widget", parsed.repo.name)
        assertEquals("deadbeef1234", parsed.headSha)
        assertEquals("Feature/raycast", parsed.headRef)
        assertEquals("main", parsed.baseRef)
        assertFalse(parsed.draft)
    }

    @Test
    fun `parse opened payload with draft false`() {
        val parsed = WebhookPayloadParser.parsePullRequestEvent(payload("opened", draft = false))
        assertNotNull(parsed)
        assertEquals(PrAction.OPENED, parsed!!.action)
        assertFalse(parsed.draft)
    }

    @Test
    fun `parse opened payload with draft true`() {
        val parsed = WebhookPayloadParser.parsePullRequestEvent(payload("opened", draft = true))
        assertNotNull(parsed)
        assertEquals(PrAction.OPENED, parsed!!.action)
        assertTrue(parsed.draft)
    }

    @Test
    fun `parse synchronize payload with draft false`() {
        val parsed = WebhookPayloadParser.parsePullRequestEvent(payload("synchronize", draft = false))
        assertNotNull(parsed)
        assertEquals(PrAction.SYNCHRONIZE, parsed!!.action)
        assertFalse(parsed.draft)
    }

    @Test
    fun `parse synchronize payload with draft true`() {
        val parsed = WebhookPayloadParser.parsePullRequestEvent(payload("synchronize", draft = true))
        assertNotNull(parsed)
        assertEquals(PrAction.SYNCHRONIZE, parsed!!.action)
        assertTrue(parsed.draft)
    }

    @Test
    fun `parse ignores unrelated actions`() {
        assertNull(WebhookPayloadParser.parsePullRequestEvent(payload("closed")))
        assertNull(WebhookPayloadParser.parsePullRequestEvent(payload("edited")))
        assertNull(WebhookPayloadParser.parsePullRequestEvent(payload("labeled")))
        assertNull(WebhookPayloadParser.parsePullRequestEvent(payload("reopened")))
    }

    @Test
    fun `parse returns null without repo`() {
        val noRepo = """
            {
              "action": "ready_for_review",
              "pull_request": {"number": 1, "draft": false, "head": {}, "base": {}}
            }
        """.trimIndent()
        assertNull(WebhookPayloadParser.parsePullRequestEvent(noRepo))
    }

    @Test
    fun `parse returns null on garbage`() {
        assertNull(WebhookPayloadParser.parsePullRequestEvent("not even json"))
    }

    @Test
    fun `draft defaults to false when absent from payload`() {
        // ready_for_review payloads from older GitHub deliveries may
        // omit `draft`; the field semantically equals false at that
        // instant since the event marks the draft->ready transition.
        val noDraft = """
            {
              "action": "ready_for_review",
              "pull_request": {
                "number": 1,
                "head": {"ref": "x", "sha": "abc"},
                "base": {"ref": "main"}
              },
              "repository": {"full_name": "acme/widget"}
            }
        """.trimIndent()
        val parsed = WebhookPayloadParser.parsePullRequestEvent(noDraft)
        assertNotNull(parsed)
        assertFalse(parsed!!.draft)
    }
}
