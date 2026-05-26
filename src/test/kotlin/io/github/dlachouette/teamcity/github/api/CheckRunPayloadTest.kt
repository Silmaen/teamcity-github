package io.github.dlachouette.teamcity.github.api

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckRunPayloadTest {

    init { LoggerBootstrap.install() }

    private val mapper = ObjectMapper()

    @Test
    fun `encodes the minimum fields GitHub expects for a skipped check run`() {
        val request = CheckRunRequest(
            name = "TeamCity / Build / Linux x64 / Clang",
            headSha = "abc123def456",
            status = CheckRunStatus.COMPLETED,
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
    fun `omits conclusion when status is in_progress`() {
        val request = CheckRunRequest(
            name = "TeamCity / Build",
            headSha = "deadbeef",
            status = CheckRunStatus.IN_PROGRESS,
            conclusion = null,
            outputTitle = "Building",
            outputSummary = "Build #42 is running.",
        )

        val json = GitHubClient.encodeCheckRunPayload(request)
        val node = mapper.readTree(json)

        assertEquals("in_progress", node.path("status").asText())
        assertTrue(node.path("conclusion").isMissingNode)
    }

    @Test
    fun `emits queued status without conclusion`() {
        val request = CheckRunRequest(
            name = "TeamCity / Build",
            headSha = "abcd",
            status = CheckRunStatus.QUEUED,
            conclusion = null,
            outputTitle = "Queued",
            outputSummary = "Waiting in queue.",
        )

        val json = GitHubClient.encodeCheckRunPayload(request)
        val node = mapper.readTree(json)

        assertEquals("queued", node.path("status").asText())
        assertTrue(node.path("conclusion").isMissingNode)
    }

    @Test
    fun `rejects completed status without conclusion at encode time`() {
        val request = CheckRunRequest(
            name = "x",
            headSha = "y",
            status = CheckRunStatus.COMPLETED,
            conclusion = null,
            outputTitle = "t",
            outputSummary = "s",
        )

        assertThrows(IllegalStateException::class.java) {
            GitHubClient.encodeCheckRunPayload(request)
        }
    }

    @Test
    fun `emits details_url when provided`() {
        val request = CheckRunRequest(
            name = "TeamCity / Build",
            headSha = "abcd",
            status = CheckRunStatus.COMPLETED,
            conclusion = CheckRunConclusion.SUCCESS,
            outputTitle = "Build passed",
            outputSummary = "All green.",
            detailsUrl = "https://tc.example.com/buildConfiguration/MyProject_Build/42",
        )

        val json = GitHubClient.encodeCheckRunPayload(request)
        val node = mapper.readTree(json)

        assertEquals("https://tc.example.com/buildConfiguration/MyProject_Build/42", node.path("details_url").asText())
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

    @Test
    fun `status enum maps to GitHub API value`() {
        assertEquals("queued", CheckRunStatus.QUEUED.apiValue)
        assertEquals("in_progress", CheckRunStatus.IN_PROGRESS.apiValue)
        assertEquals("completed", CheckRunStatus.COMPLETED.apiValue)
    }
}
