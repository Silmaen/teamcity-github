package io.github.dlachouette.teamcity.github.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.retrigger.CommentCommandPayload
import io.github.dlachouette.teamcity.github.retrigger.PrAction
import io.github.dlachouette.teamcity.github.retrigger.PrEventPayload
import io.github.dlachouette.teamcity.github.retrigger.RerunRequestPayload
import io.github.dlachouette.teamcity.github.retrigger.ReviewApprovedPayload

object WebhookPayloadParser {
    private val LOG = Logger.getInstance(WebhookPayloadParser::class.java.name)
    private val MAPPER = ObjectMapper()

    // Cheap peek used by the events log to record the action and repo
    // for any pull_request payload, regardless of whether it triggers
    // a retrigger or not.
    fun peekActionAndRepo(payload: String): Pair<String?, String?> {
        return try {
            val node = MAPPER.readTree(payload)
            val action = node.path("action").asText("").takeIf { it.isNotBlank() }
            val repo = node.path("repository").path("full_name").asText("").takeIf { it.isNotBlank() }
            action to repo
        } catch (e: Exception) {
            null to null
        }
    }

    // Parses `pull_request` events whose action is one of the three we
    // act on (opened, ready_for_review, synchronize). Returns null for
    // any other action or for malformed payloads. The `draft` flag is
    // read directly from the payload — authoritative at the moment of
    // the event, no cache lookup or API call required.
    fun parsePullRequestEvent(payload: String): PrEventPayload? {
        return try {
            val node = MAPPER.readTree(payload)
            val actionStr = node.path("action").asText("")
            val action = PrAction.fromString(actionStr) ?: return null

            val pr = node.path("pull_request")
            val number = pr.path("number").asInt(-1)
            if (number < 0) return null

            val repoSlug = node.path("repository").path("full_name").asText("")
            if (repoSlug.isBlank()) return null

            val head = pr.path("head")
            val headSha = head.path("sha").asText("")
            val headRef = head.path("ref").asText("")
            val baseRef = pr.path("base").path("ref").asText("")
            val draft = pr.path("draft").asBoolean(false)
            val merged = pr.path("merged").asBoolean(false)

            PrEventPayload(
                action = action,
                repo = RepoCoords.parse(repoSlug),
                prNumber = number,
                headSha = headSha,
                baseRef = baseRef,
                headRef = headRef,
                draft = draft,
                merged = merged,
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse pull_request payload: ${e.message}")
            null
        }
    }

    // Parses a `pull_request_review` event, returning a payload only when
    // the review was submitted with state=approved (the case run-on-
    // approval cares about). Null otherwise.
    fun parseReviewApproved(payload: String): ReviewApprovedPayload? {
        return try {
            val node = MAPPER.readTree(payload)
            if (node.path("action").asText("") != "submitted") return null
            if (!node.path("review").path("state").asText("").equals("approved", ignoreCase = true)) return null

            val pr = node.path("pull_request")
            val number = pr.path("number").asInt(-1)
            if (number < 0) return null
            val repoSlug = node.path("repository").path("full_name").asText("")
            if (repoSlug.isBlank()) return null
            val head = pr.path("head")

            ReviewApprovedPayload(
                repo = RepoCoords.parse(repoSlug),
                prNumber = number,
                headSha = head.path("sha").asText(""),
                headRef = head.path("ref").asText(""),
                draft = pr.path("draft").asBoolean(false),
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse pull_request_review payload: ${e.message}")
            null
        }
    }

    // Parses an `issue_comment` event, returning a payload only when a
    // comment was CREATED on a pull request (issues that aren't PRs, and
    // edit/delete actions, are ignored). Authorization (author
    // association) and command matching happen downstream.
    fun parseIssueComment(payload: String): CommentCommandPayload? {
        return try {
            val node = MAPPER.readTree(payload)
            if (node.path("action").asText("") != "created") return null
            val issue = node.path("issue")
            // A comment is on a PR iff the issue carries a `pull_request` object.
            if (issue.path("pull_request").isMissingNode) return null
            val number = issue.path("number").asInt(-1)
            if (number < 0) return null
            val repoSlug = node.path("repository").path("full_name").asText("")
            if (repoSlug.isBlank()) return null
            val comment = node.path("comment")

            CommentCommandPayload(
                repo = RepoCoords.parse(repoSlug),
                prNumber = number,
                body = comment.path("body").asText(""),
                authorAssociation = comment.path("author_association").asText(""),
                commenter = comment.path("user").path("login").asText(""),
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse issue_comment payload: ${e.message}")
            null
        }
    }

    // Parses a `check_run` event, returning a payload only for the
    // `rerequested` action (the "re-run" button in the GitHub Checks UI).
    fun parseRerunRequest(payload: String): RerunRequestPayload? {
        return try {
            val node = MAPPER.readTree(payload)
            if (node.path("action").asText("") != "rerequested") return null
            val checkRun = node.path("check_run")
            val name = checkRun.path("name").asText("").takeIf { it.isNotBlank() } ?: return null
            val repoSlug = node.path("repository").path("full_name").asText("")
            if (repoSlug.isBlank()) return null
            // Prefer the associated PR number; fall back to the suite's head branch.
            val prNumber = checkRun.path("pull_requests").firstOrNull()?.path("number")?.asInt(-1)
                ?.takeIf { it >= 0 }
            val headBranch = checkRun.path("check_suite").path("head_branch").asText("")
                .takeIf { it.isNotBlank() }

            RerunRequestPayload(
                repo = RepoCoords.parse(repoSlug),
                checkRunName = name,
                headSha = checkRun.path("head_sha").asText(""),
                prNumber = prNumber,
                headBranch = headBranch,
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse check_run payload: ${e.message}")
            null
        }
    }
}
