package io.github.dlachouette.teamcity.github.api

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import java.util.concurrent.ConcurrentHashMap

// Tokens (PATs, installation tokens, ghs_*, future ~520-char stateless
// JWTs) are treated as opaque strings end-to-end: no length checks, no
// substring, no prefix matching. The opt-in header
// X-GitHub-Stateless-S2S-Token applies to the installation token
// issuance call (POST /app/installations/{id}/access_tokens) which
// TeamCity performs via OAuthTokensStorage - we never see it from here.
//
// User-supplied `tcgh.github.connectionId` may be either of two
// identifier formats:
//   - PROJECT_EXT_<N>  -> the connection's externalId (TC standard)
//   - CID_<hash>       -> the connection's tokenStorageId (what the
//                         Owl knowledge base documented)
// We try both lookups before giving up, with a per-key rate limit on
// the warning so a misconfigured server does not flood the log on
// every queue scheduling cycle.
class TokenResolver(
    private val oauthConnectionsManager: OAuthConnectionsManager,
    private val tokensStorage: OAuthTokensStorage,
) {

    private val lastWarnedAtMs = ConcurrentHashMap<String, Long>()

    fun resolveAccessToken(project: SProject, connectionId: String): String? {
        val descriptor = findConnection(project, connectionId)
        if (descriptor == null) {
            if (shouldLog("no-conn", project.externalId, connectionId)) {
                LOG.warn(
                    "No GitHub App connection found for id=$connectionId in project ${project.externalId} or its parents. " +
                        "The value of tcgh.github.connectionId must be either the connection externalId (PROJECT_EXT_<N>) " +
                        "or its tokenStorageId (CID_<hash>). Visit Project -> Connections to check the available IDs. " +
                        "Further occurrences for the same (project, id) pair will be suppressed for ${WARN_RATE_LIMIT_MS / 1000}s."
                )
            }
            return null
        }
        val storageId = descriptor.tokenStorageId
        if (storageId.isNullOrBlank()) {
            if (shouldLog("no-storage-id", project.externalId, connectionId)) {
                LOG.warn("Connection $connectionId in project ${project.externalId} has no tokenStorageId; cannot resolve an installation token.")
            }
            return null
        }
        val token = tokensStorage.getToken(project, storageId, true, true)
        if (token == null) {
            if (shouldLog("no-token", project.externalId, connectionId)) {
                LOG.warn("No stored token resolved for connection $connectionId (storageId=$storageId) in project ${project.externalId}. The App connection exists but TeamCity could not issue an installation token; check that the App is installed on the target repository.")
            }
            return null
        }
        // Reset the warning cooldown for this key on success so the
        // next failure logs promptly.
        lastWarnedAtMs.remove(warnKey("no-conn", project.externalId, connectionId))
        lastWarnedAtMs.remove(warnKey("no-storage-id", project.externalId, connectionId))
        lastWarnedAtMs.remove(warnKey("no-token", project.externalId, connectionId))
        return token.accessToken
    }

    private fun findConnection(project: SProject, id: String): OAuthConnectionDescriptor? {
        // Try TokenStorageId first (CID_<hash>) — that is the format the
        // existing knowledge base recommended, so most users will type
        // that. Fall back to externalId (PROJECT_EXT_<N>).
        return oauthConnectionsManager.findConnectionByTokenStorageId(project, id)
            ?: oauthConnectionsManager.findConnectionById(project, id)
    }

    private fun shouldLog(reason: String, projectId: String, connectionId: String): Boolean {
        val key = warnKey(reason, projectId, connectionId)
        val now = System.currentTimeMillis()
        var should = false
        lastWarnedAtMs.compute(key) { _, last ->
            if (last == null || now - last > WARN_RATE_LIMIT_MS) {
                should = true
                now
            } else {
                last
            }
        }
        return should
    }

    private fun warnKey(reason: String, projectId: String, connectionId: String): String =
        "$reason:$projectId:$connectionId"

    companion object {
        private val LOG = Logger.getInstance(TokenResolver::class.java.name)

        // Drop further warnings for the same (reason, project, connectionId)
        // for this long. Plenty of time to notice the first one without
        // drowning the log.
        const val WARN_RATE_LIMIT_MS: Long = 60_000L
    }
}
