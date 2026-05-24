package io.github.dlachouette.teamcity.github.api

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage

// Tokens (PATs, installation tokens, ghs_*, future ~520-char stateless
// JWTs) are treated as opaque strings end-to-end: no length checks, no
// substring, no prefix matching. The opt-in header
// X-GitHub-Stateless-S2S-Token applies to the installation token
// issuance call (POST /app/installations/{id}/access_tokens) which
// TeamCity performs via OAuthTokensStorage - we never see it from here.
class TokenResolver(
    private val oauthConnectionsManager: OAuthConnectionsManager,
    private val tokensStorage: OAuthTokensStorage,
) {

    fun resolveAccessToken(project: SProject, connectionId: String): String? {
        val descriptor = oauthConnectionsManager.findConnectionById(project, connectionId)
        if (descriptor == null) {
            LOG.warn("No GitHub App connection found for id=$connectionId in project ${project.externalId}")
            return null
        }
        val storageId = descriptor.tokenStorageId
        if (storageId.isNullOrBlank()) {
            LOG.warn("Connection $connectionId in project ${project.externalId} has no tokenStorageId")
            return null
        }
        val token = tokensStorage.getToken(project, storageId, true, true)
        if (token == null) {
            LOG.warn("No stored token resolved for connection $connectionId (storageId=$storageId)")
            return null
        }
        return token.accessToken
    }

    companion object {
        private val LOG = Logger.getInstance(TokenResolver::class.java.name)
    }
}
