package io.github.dlachouette.teamcity.github.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.retrigger.ReadyForReviewPayload

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

    fun parseReadyForReview(payload: String): ReadyForReviewPayload? {
        return try {
            val node = MAPPER.readTree(payload)
            if (node.path("action").asText() != "ready_for_review") return null

            val pr = node.path("pull_request")
            val number = pr.path("number").asInt(-1)
            if (number < 0) return null

            val repoSlug = node.path("repository").path("full_name").asText("")
            if (repoSlug.isBlank()) return null

            val head = pr.path("head")
            val headSha = head.path("sha").asText("")
            val headRef = head.path("ref").asText("")
            val baseRef = pr.path("base").path("ref").asText("")

            ReadyForReviewPayload(
                repo = RepoCoords.parse(repoSlug),
                prNumber = number,
                headSha = headSha,
                baseRef = baseRef,
                headRef = headRef,
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse pull_request payload: ${e.message}")
            null
        }
    }
}
