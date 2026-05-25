package io.github.dlachouette.teamcity.github.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URL

open class GitHubClient {

    open fun getPr(accessToken: String, repo: RepoCoords, number: Int): PrInfo? {
        val url = URL("$apiBase/repos/${repo.slug}/pulls/$number")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", apiVersion)
            connectTimeout = 5000
            readTimeout = 10000
        }

        return try {
            val code = conn.responseCode
            when (code) {
                200 -> parsePrInfo(conn.inputStream.bufferedReader().readText())
                else -> {
                    LOG.warn("GitHub returned $code for ${repo.slug}#$number")
                    null
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed querying ${repo.slug}#$number: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    open fun postCheckRun(
        accessToken: String,
        repo: RepoCoords,
        request: CheckRunRequest,
    ): Boolean {
        val url = URL("$apiBase/repos/${repo.slug}/check-runs")
        val body = encodeCheckRunPayload(request)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", apiVersion)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 10000
            doOutput = true
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // 201 Created on success per GitHub Check Runs API.
            if (code in 200..299) {
                true
            } else {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                LOG.warn("GitHub Check Run POST returned $code for ${repo.slug}@${request.headSha}: $err")
                false
            }
        } catch (e: Exception) {
            LOG.warn("Failed POST check-run for ${repo.slug}@${request.headSha}: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    private val apiBase: String = "https://api.github.com"
    private val apiVersion: String = "2022-11-28"

    companion object {
        private val LOG = Logger.getInstance(GitHubClient::class.java.name)
        private val MAPPER = ObjectMapper()

        fun parsePrInfo(json: String): PrInfo? {
            return try {
                val node = MAPPER.readTree(json)
                parsePrInfo(node)
            } catch (e: Exception) {
                LOG.warn("Failed to parse PR JSON: ${e.message}")
                null
            }
        }

        fun parsePrInfo(node: JsonNode): PrInfo? {
            val number = node.path("number").asInt(-1).takeIf { it >= 0 } ?: return null
            val head = node.path("head")
            val base = node.path("base")
            return PrInfo(
                number = number,
                title = node.path("title").asText(""),
                author = node.path("user").path("login").asText(""),
                headRef = head.path("ref").asText(""),
                baseRef = base.path("ref").asText(""),
                headSha = head.path("sha").asText(""),
                draft = node.path("draft").asBoolean(false),
                state = node.path("state").asText("open"),
            )
        }

        // Public for testing — verifies we build the exact JSON shape GitHub expects.
        fun encodeCheckRunPayload(request: CheckRunRequest): String {
            val root: ObjectNode = MAPPER.createObjectNode()
            root.put("name", request.name)
            root.put("head_sha", request.headSha)
            root.put("status", "completed")
            root.put("conclusion", request.conclusion.apiValue)
            val output: ObjectNode = root.putObject("output")
            output.put("title", request.outputTitle)
            output.put("summary", request.outputSummary)
            return MAPPER.writeValueAsString(root)
        }
    }
}

data class CheckRunRequest(
    val name: String,
    val headSha: String,
    val conclusion: CheckRunConclusion,
    val outputTitle: String,
    val outputSummary: String,
)

enum class CheckRunConclusion(val apiValue: String) {
    SUCCESS("success"),
    FAILURE("failure"),
    NEUTRAL("neutral"),
    SKIPPED("skipped"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out"),
    ACTION_REQUIRED("action_required"),
}
