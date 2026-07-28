package io.github.dlachouette.teamcity.github.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// G13: "Re-run all checks" arrives as check_suite.rerequested.
class CheckSuitePayloadTest {

    private fun suite(
        action: String = "rerequested",
        headSha: String = "deadbeef",
        headBranch: String = "Feature/x",
        pullRequests: String = """[{"number":42}]""",
    ) = """
        {"action":"$action",
         "repository":{"full_name":"acme/widget"},
         "check_suite":{"head_sha":"$headSha","head_branch":"$headBranch","pull_requests":$pullRequests}}
    """.trimIndent()

    @Test
    fun `parses a rerequested suite of a PR`() {
        val parsed = WebhookPayloadParser.parseCheckSuiteRerequest(suite())!!
        assertEquals("acme/widget", parsed.repo.slug)
        assertEquals("deadbeef", parsed.headSha)
        assertEquals("Feature/x", parsed.headBranch)
        assertEquals(42, parsed.prNumber)
    }

    @Test
    fun `parses a suite with no pull request (branch build)`() {
        val parsed = WebhookPayloadParser.parseCheckSuiteRerequest(suite(pullRequests = "[]"))!!
        assertNull(parsed.prNumber)
        assertEquals("Feature/x", parsed.headBranch)
    }

    @Test
    fun `ignores every other action`() {
        listOf("completed", "requested").forEach {
            assertNull(WebhookPayloadParser.parseCheckSuiteRerequest(suite(action = it)), "action=$it")
        }
    }

    @Test
    fun `rejects a payload without a head sha`() {
        assertNull(WebhookPayloadParser.parseCheckSuiteRerequest(suite(headSha = "")))
    }

    @Test
    fun `a blank head branch becomes null rather than empty`() {
        val parsed = WebhookPayloadParser.parseCheckSuiteRerequest(suite(headBranch = ""))!!
        assertNull(parsed.headBranch)
        // The PR number still names the ref, so the event stays actionable.
        assertNotNull(parsed.prNumber)
    }

    @Test
    fun `malformed json is ignored`() {
        assertNull(WebhookPayloadParser.parseCheckSuiteRerequest("{not json"))
    }
}
