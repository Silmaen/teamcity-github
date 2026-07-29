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
        listOf("assigned", "converted_to_draft", "review_requested", "locked").forEach {
            assertNull(WebhookPayloadParser.parsePullRequestEvent(payload(it)), "action=$it")
        }
    }

    @Test
    fun `parse accepts the re-evaluation actions`() {
        mapOf(
            "reopened" to PrAction.REOPENED,
            "labeled" to PrAction.LABELED,
            "unlabeled" to PrAction.UNLABELED,
            "edited" to PrAction.EDITED,
        ).forEach { (raw, expected) ->
            assertEquals(expected, WebhookPayloadParser.parsePullRequestEvent(payload(raw))?.action, "action=$raw")
        }
    }

    @Test
    fun `parseIssueComment accepts a created comment on a PR`() {
        val json = """
            {"action":"created",
             "issue":{"number":7,"pull_request":{"url":"x"}},
             "comment":{"body":"please /rebuild now","author_association":"MEMBER","user":{"login":"alice"}},
             "repository":{"full_name":"acme/widget"}}
        """.trimIndent()
        val parsed = WebhookPayloadParser.parseIssueComment(json)
        assertNotNull(parsed)
        assertTrue(parsed!!.prNumber == 7)
        assertTrue(parsed.body.contains("/rebuild"))
        assertTrue(parsed.authorAssociation == "MEMBER")
        assertTrue(parsed.commenter == "alice")
    }

    @Test
    fun `parsePullRequestReviewComment accepts a created inline diff comment`() {
        val json = """
            {"action":"created",
             "pull_request":{"number":12},
             "comment":{"body":"/rebuild please","author_association":"COLLABORATOR","user":{"login":"bob"}},
             "repository":{"full_name":"acme/widget"}}
        """.trimIndent()
        val parsed = WebhookPayloadParser.parsePullRequestReviewComment(json)
        assertNotNull(parsed)
        assertTrue(parsed!!.prNumber == 12)
        assertTrue(parsed.body.contains("/rebuild"))
        assertTrue(parsed.commenter == "bob")
    }

    @Test
    fun `parsePullRequestReviewComment ignores non-created actions`() {
        assertNull(
            WebhookPayloadParser.parsePullRequestReviewComment(
                """{"action":"edited","pull_request":{"number":1},"comment":{"body":"x"},"repository":{"full_name":"a/b"}}"""
            )
        )
    }

    @Test
    fun `parseIssueComment ignores non-PR issues and non-created actions`() {
        // Issue without a pull_request object.
        assertNull(
            WebhookPayloadParser.parseIssueComment(
                """{"action":"created","issue":{"number":7},"comment":{"body":"x"},"repository":{"full_name":"a/b"}}"""
            )
        )
        // Edited action on a PR comment.
        assertNull(
            WebhookPayloadParser.parseIssueComment(
                """{"action":"edited","issue":{"number":7,"pull_request":{}},"comment":{"body":"x"},"repository":{"full_name":"a/b"}}"""
            )
        )
    }

    @Test
    fun `parse accepts closed action`() {
        val parsed = WebhookPayloadParser.parsePullRequestEvent(payload("closed"))
        assertNotNull(parsed)
        assertTrue(parsed!!.action == PrAction.CLOSED)
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
