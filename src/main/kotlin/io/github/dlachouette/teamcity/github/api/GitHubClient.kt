package io.github.dlachouette.teamcity.github.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
    }
}
