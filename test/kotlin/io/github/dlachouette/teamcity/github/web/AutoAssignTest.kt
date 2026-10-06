package io.github.dlachouette.teamcity.github.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// A pull request opened with nobody assigned goes to its author — once, and
// never against a human's choice.
class AutoAssignTest {

    private fun assignee(
        action: PrAction = PrAction.OPENED,
        enabled: Boolean = true,
        author: String = "alice",
        bot: Boolean = false,
        assignees: List<String> = emptyList(),
    ) = AutoAssign.assignee(action, enabled, author, bot, assignees)

    @Test
    fun `an unassigned pull request is assigned to its author on opened`() {
        assertEquals("alice", assignee())
    }

    @Test
    fun `nothing happens when off, already assigned, or not on opened`() {
        assertNull(assignee(enabled = false))
        assertNull(assignee(assignees = listOf("bob")))
        PrAction.entries.filter { it != PrAction.OPENED }.forEach { assertNull(assignee(action = it), "action=$it") }
    }

    @Test
    fun `bots and unknown authors are skipped`() {
        assertNull(assignee(author = "dependabot[bot]", bot = true))
        assertNull(assignee(author = ""))
    }

    @Test
    fun `the webhook payload carries the author, its kind and the assignees`() {
        val json = """
            {"action":"opened","repository":{"full_name":"acme/widget"},
             "pull_request":{"number":7,"draft":false,
               "user":{"login":"renovate[bot]","type":"Bot"},
               "assignees":[{"login":"bob"}],
               "head":{"sha":"deadbeef","ref":"Feature/x"},"base":{"ref":"main"}}}
        """.trimIndent()
        val p = WebhookPayloadParser.parsePullRequestEvent(json)!!
        assertEquals("renovate[bot]", p.author)
        assertEquals(true, p.authorIsBot)
        assertEquals(listOf("bob"), p.assignees)
    }
}
