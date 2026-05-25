package io.github.dlachouette.teamcity.github.api

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CheckRunPayloadTest {

    init { LoggerBootstrap.install() }

    private val mapper = ObjectMapper()

    @Test
    fun `encodes the minimum fields GitHub expects for a skipped check run`() {
        val request = CheckRunRequest(
            name = "TeamCity / Build / Linux x64 / Clang",
            headSha = "abc123def456",
            conclusion = CheckRunConclusion.SKIPPED,
            outputTitle = "Skipped: draft PR",
            outputSummary = "PR #42 is in draft state; this build will run automatically when the PR is marked ready for review.",
        )

        val json = GitHubClient.encodeCheckRunPayload(request)
        val node = mapper.readTree(json)

        assertEquals("TeamCity / Build / Linux x64 / Clang", node.path("name").asText())
        assertEquals("abc123def456", node.path("head_sha").asText())
        assertEquals("completed", node.path("status").asText())
        assertEquals("skipped", node.path("conclusion").asText())
        assertEquals("Skipped: draft PR", node.path("output").path("title").asText())
        assertEquals(
            "PR #42 is in draft state; this build will run automatically when the PR is marked ready for review.",
            node.path("output").path("summary").asText(),
        )
    }

    @Test
    fun `conclusion enum maps to the GitHub API value`() {
        assertEquals("success", CheckRunConclusion.SUCCESS.apiValue)
        assertEquals("failure", CheckRunConclusion.FAILURE.apiValue)
        assertEquals("neutral", CheckRunConclusion.NEUTRAL.apiValue)
        assertEquals("skipped", CheckRunConclusion.SKIPPED.apiValue)
        assertEquals("cancelled", CheckRunConclusion.CANCELLED.apiValue)
        assertEquals("timed_out", CheckRunConclusion.TIMED_OUT.apiValue)
        assertEquals("action_required", CheckRunConclusion.ACTION_REQUIRED.apiValue)
    }
}
