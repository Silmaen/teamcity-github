package io.github.dlachouette.teamcity.github.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.RepoCoords

// Maintains a single "sticky" summary comment on a PR, one row per
// TeamCity check, refreshed as builds complete.
//
// The comment's authoritative state lives as a JSON map embedded inside
// an HTML-comment marker block at the top of the comment body; the
// human-readable Markdown table below it is regenerated from that map on
// every update. This makes the round-trip robust to table-format changes
// and to other text in the comment.
//
// Because java.net.HttpURLConnection cannot issue PATCH, "update" is
// delete-then-create (the comment moves to the bottom of the thread,
// which is fine for a single rolling summary).
class PrSummaryCommenter(
    private val gitHubClient: GitHubClient,
) {

    data class Row(val emoji: String, val text: String, val url: String?)

    fun upsert(
        accessToken: String,
        repo: RepoCoords,
        prNumber: Int,
        apiBase: String,
        checkName: String,
        row: Row,
    ) {
        try {
            val existing = gitHubClient.listIssueComments(accessToken, repo, prNumber, apiBase)
                .firstOrNull { it.body.contains(MARKER_BEGIN) }
            val rows = parseState(existing?.body).toMutableMap()
            rows[checkName] = row
            val body = render(rows)

            if (existing != null) {
                gitHubClient.deleteIssueComment(accessToken, repo, existing.id, apiBase)
            }
            gitHubClient.createIssueComment(accessToken, repo, prNumber, body, apiBase)
        } catch (e: Exception) {
            LOG.warn("Failed upserting PR summary comment for ${repo.slug}#$prNumber: ${e.message}")
        }
    }

    // Public for testing — extract the embedded JSON state map.
    fun parseState(body: String?): Map<String, Row> {
        if (body == null) return emptyMap()
        val start = body.indexOf(MARKER_BEGIN)
        if (start < 0) return emptyMap()
        val jsonStart = start + MARKER_BEGIN.length
        val jsonEnd = body.indexOf(MARKER_END, jsonStart)
        if (jsonEnd < 0) return emptyMap()
        val json = body.substring(jsonStart, jsonEnd).trim()
        return try {
            val node = MAPPER.readTree(json)
            node.fields().asSequence().associate { (name, v) ->
                name to Row(
                    emoji = v.path("emoji").asText(""),
                    text = v.path("text").asText(""),
                    url = v.path("url").asText("").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            LOG.debug("Could not parse PR summary state, starting fresh: ${e.message}")
            emptyMap()
        }
    }

    // Public for testing — render the full comment body.
    fun render(rows: Map<String, Row>): String {
        val json = MAPPER.writeValueAsString(rows)
        return buildString {
            append(MARKER_BEGIN).append('\n').append(json).append('\n').append(MARKER_END).append('\n')
            append("### TeamCity build summary\n\n")
            append("| Check | Result | |\n|---|---|---|\n")
            rows.toSortedMap().forEach { (name, r) ->
                val link = r.url?.let { "[details]($it)" } ?: ""
                append("| ").append(name).append(" | ").append(r.emoji).append(' ').append(r.text)
                    .append(" | ").append(link).append(" |\n")
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PrSummaryCommenter::class.java.name)
        private val MAPPER = ObjectMapper()
        const val MARKER_BEGIN: String = "<!-- teamcity-github-bridge:pr-summary"
        const val MARKER_END: String = "-->"
    }
}
