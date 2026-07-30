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

    // G10: output.annotations, in the exact shape GitHub documents.
    @Test
    fun `annotations are encoded under output`() {
        val json = GitHubClient.encodeCheckRunPayload(
            CheckRunRequest(
                name = "TeamCity / Build",
                headSha = "abc",
                status = CheckRunStatus.COMPLETED,
                conclusion = CheckRunConclusion.FAILURE,
                outputTitle = "Build failed",
                outputSummary = "1 error",
                annotations = listOf(
                    CheckRunAnnotation(
                        path = "src/a.cpp",
                        startLine = 42,
                        endLine = 42,
                        level = CheckRunAnnotationLevel.FAILURE,
                        message = "no member named 'trace'",
                        title = "C2065",
                    ),
                ),
            )
        )
        val node = ObjectMapper().readTree(json).path("output").path("annotations")
        assertTrue(node.isArray)
        assertEquals(1, node.size())
        assertEquals("src/a.cpp", node[0].path("path").asText())
        assertEquals(42, node[0].path("start_line").asInt())
        assertEquals(42, node[0].path("end_line").asInt())
        assertEquals("failure", node[0].path("annotation_level").asText())
        assertEquals("no member named 'trace'", node[0].path("message").asText())
        assertEquals("C2065", node[0].path("title").asText())
    }

    @Test
    fun `no annotations means no annotations field at all`() {
        val json = GitHubClient.encodeCheckRunPayload(
            CheckRunRequest(
                name = "TeamCity / Build",
                headSha = "abc",
                status = CheckRunStatus.IN_PROGRESS,
                conclusion = null,
                outputTitle = "Building",
                outputSummary = "",
            )
        )
        assertTrue(ObjectMapper().readTree(json).path("output").path("annotations").isMissingNode)
    }

    @Test
    fun `more annotations than GitHub accepts are truncated`() {
        val many = (1..80).map {
            CheckRunAnnotation("src/a.cpp", it, it, CheckRunAnnotationLevel.WARNING, "w$it")
        }
        val json = GitHubClient.encodeCheckRunPayload(
            CheckRunRequest(
                name = "n", headSha = "abc", status = CheckRunStatus.COMPLETED,
                conclusion = CheckRunConclusion.SUCCESS, outputTitle = "t", outputSummary = "s",
                annotations = many,
            )
        )
        assertEquals(
            GitHubClient.MAX_ANNOTATIONS_PER_REQUEST,
            ObjectMapper().readTree(json).path("output").path("annotations").size(),
        )
    }

    // GitHub renders "Successful in 7m" from these two, which it cannot do
    // from the delivery time of our request — a Check Run posted late would
    // otherwise look instantaneous.
    @Test
    fun `carries the build's own start and finish instants`() {
        val request = CheckRunRequest(
            name = "TeamCity / Build",
            headSha = "abc",
            status = CheckRunStatus.COMPLETED,
            conclusion = CheckRunConclusion.SUCCESS,
            outputTitle = "Build passed",
            outputSummary = "ok",
            startedAt = "2026-07-30T14:19:07Z",
            completedAt = "2026-07-30T14:26:19Z",
        )
        val node = mapper.readTree(GitHubClient.encodeCheckRunPayload(request))
        assertEquals("2026-07-30T14:19:07Z", node.path("started_at").asText())
        assertEquals("2026-07-30T14:26:19Z", node.path("completed_at").asText())
    }

    @Test
    fun `an in-progress row claims a start but never a finish`() {
        val request = CheckRunRequest(
            name = "TeamCity / Build",
            headSha = "abc",
            status = CheckRunStatus.IN_PROGRESS,
            conclusion = null,
            outputTitle = "Building",
            outputSummary = "running",
            startedAt = "2026-07-30T14:19:07Z",
            completedAt = "2026-07-30T14:26:19Z",
        )
        val node = mapper.readTree(GitHubClient.encodeCheckRunPayload(request))
        assertEquals("2026-07-30T14:19:07Z", node.path("started_at").asText())
        assertTrue(node.path("completed_at").isMissingNode)
    }

    @Test
    fun `omits both instants when the build dates are unknown`() {
        val request = CheckRunRequest(
            name = "TeamCity / Build",
            headSha = "abc",
            status = CheckRunStatus.QUEUED,
            conclusion = null,
            outputTitle = "Queued",
            outputSummary = "waiting",
        )
        val node = mapper.readTree(GitHubClient.encodeCheckRunPayload(request))
        assertTrue(node.path("started_at").isMissingNode)
        assertTrue(node.path("completed_at").isMissingNode)
    }
}
