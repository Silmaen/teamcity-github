package io.github.dlachouette.teamcity.github.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.retrigger.PrAction
import io.github.dlachouette.teamcity.github.retrigger.PrEventPayload

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

            PrEventPayload(
                action = action,
                repo = RepoCoords.parse(repoSlug),
                prNumber = number,
                headSha = headSha,
                baseRef = baseRef,
                headRef = headRef,
                draft = draft,
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse pull_request payload: ${e.message}")
            null
        }
    }
}
