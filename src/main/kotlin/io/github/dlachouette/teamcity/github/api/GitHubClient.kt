package io.github.dlachouette.teamcity.github.api

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import java.net.HttpURLConnection
import java.net.URL

class GitHubClient(
    private val tokensStorage: OAuthTokensStorage,
    private val projectManager: ProjectManager,
) {

    fun getPr(repo: RepoCoords, number: Int, connectionId: String): PrInfo? {
        val token = getInstallationToken(connectionId) ?: run {
            LOG.warn("No installation token resolved for connection $connectionId")
            return null
        }

        val url = URL("$apiBase/repos/${repo.slug}/pulls/$number")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", apiVersion)
            connectTimeout = 5000
            readTimeout = 10000
        }

        return try {
            when (val code = conn.responseCode) {
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

    private fun getInstallationToken(connectionId: String): String? {
        return null
    }

    private fun parsePrInfo(json: String): PrInfo? {
        return null
    }

    private val apiBase: String = "https://api.github.com"
    private val apiVersion: String = "2022-11-28"

    companion object {
        private val LOG = Logger.getInstance(GitHubClient::class.java.name)
    }
}
