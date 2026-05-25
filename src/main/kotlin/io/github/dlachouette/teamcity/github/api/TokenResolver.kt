package io.github.dlachouette.teamcity.github.api

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.connections.credentials.ConnectionCredentialsException
import jetbrains.buildServer.serverSide.connections.credentials.ProjectConnectionCredentialsManager
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.TokenIntent
import jetbrains.buildServer.serverSide.oauth.TokenStoragePageOrder
import jetbrains.buildServer.serverSide.oauth.TokenStorageQuery
import java.util.concurrent.ConcurrentHashMap

// Tokens (PATs, installation tokens, ghs_*, future ~520-char stateless
// JWTs) are treated as opaque strings end-to-end: no length checks, no
// substring, no prefix matching. The opt-in header
// X-GitHub-Stateless-S2S-Token applies to the installation token
// issuance call (POST /app/installations/{id}/access_tokens) which
// TeamCity performs internally - we never see it from here.
//
// User-supplied `teamcity.github.bridge.connectionId` may be either of
// two identifier formats:
//   - PROJECT_EXT_<N>  -> the connection's externalId (TC standard)
//   - CID_<hash>       -> the connection's tokenStorageId
// We try both lookups before giving up, with a per-key rate limit on
// the warning so a misconfigured server does not flood the log.
//
// Token retrieval has two paths:
//   1. `ProjectConnectionCredentialsManager.requestConnectionCredentials`
//      is the high-level entry point. For GitHub App connections it
//      triggers JWT signing + installation-token minting through the
//      bundled github-app plugin and caches the result. This is the
//      same path the bundled commit-status-publisher uses.
//   2. Fallback: `OAuthTokensStorage.getProjectTokens(query)`, which
//      returns any token that has been previously cached. Used only if
//      step 1 errors out (e.g., a future connection type that doesn't
//      go through the credentials manager).
class TokenResolver(
    private val oauthConnectionsManager: OAuthConnectionsManager,
    private val tokensStorage: OAuthTokensStorage,
    private val credentialsManager: ProjectConnectionCredentialsManager,
) {

    private val lastWarnedAtMs = ConcurrentHashMap<String, Long>()
    private val unknownKeyDiscoveryLogged = ConcurrentHashMap.newKeySet<String>()

    // Some connection provider types (e.g. "GitHubApp" on TC 2026.1)
    // do not register a ConnectionCredentialsFactory and always raise
    // "Unsupported Connection Provider type". Cache those so we skip
    // the attempt entirely and go straight to getProjectTokens.
    private val unsupportedProviderTypes = ConcurrentHashMap.newKeySet<String>()

    fun resolveAccessToken(project: SProject, connectionId: String): String? {
        val descriptor = findConnection(project, connectionId)
        if (descriptor == null) {
            if (shouldLog("no-conn", project.externalId, connectionId)) {
                LOG.warn(
                    "No GitHub App connection found for id=$connectionId in project ${project.externalId} or its parents. " +
                        "The value of teamcity.github.bridge.connectionId must be either the connection externalId (PROJECT_EXT_<N>) " +
                        "or its tokenStorageId (CID_<hash>). Visit Project -> Connections to check the available IDs. " +
                        "Further occurrences for the same (project, id) pair will be suppressed for ${WARN_RATE_LIMIT_MS / 1000}s."
                )
            }
            return null
        }
        val accessToken = fetchViaCredentialsManager(project, descriptor)
            ?: fetchViaProjectTokens(project, descriptor)
        if (accessToken == null) {
            if (shouldLog("no-token", project.externalId, connectionId)) {
                LOG.warn(
                    "No installation token available for connection ${descriptor.id} (storageId=${descriptor.tokenStorageId}) in project ${project.externalId}. " +
                        "The connection exists but TeamCity could not produce a token. Most common causes: " +
                        "the GitHub App is not installed on the target repository, the App has no permission for it, or the connection " +
                        "has never been used. Try opening Project -> Connections -> Edit and saving once to force token issuance."
                )
            }
            return null
        }
        clearWarnCooldowns(project.externalId, connectionId)
        return accessToken
    }

    private fun findConnection(project: SProject, id: String): OAuthConnectionDescriptor? {
        return oauthConnectionsManager.findConnectionByTokenStorageId(project, id)
            ?: oauthConnectionsManager.findConnectionById(project, id)
    }

    // Primary path: high-level credentials manager. Triggers token
    // minting via the connection's provider when supported. On TC
    // 2026.1 the bundled GitHubApp provider is NOT registered with
    // this framework, so the call raises "Unsupported Connection
    // Provider type: GitHubApp" - we remember that and stop trying
    // for the same provider type.
    private fun fetchViaCredentialsManager(
        project: SProject,
        descriptor: OAuthConnectionDescriptor,
    ): String? {
        val providerType = descriptor.providerType ?: ""
        if (providerType.isNotBlank() && unsupportedProviderTypes.contains(providerType)) {
            return null
        }
        return try {
            val credentials = credentialsManager.requestConnectionCredentials(project, descriptor.id)
            val props = credentials.properties
            val token = ACCESS_TOKEN_KEYS.firstNotNullOfOrNull { props[it] }
            if (token == null) {
                val key = "${project.externalId}|${descriptor.id}"
                if (unknownKeyDiscoveryLogged.add(key)) {
                    LOG.warn(
                        "ConnectionCredentials for ${descriptor.id} in ${project.externalId} did not expose any known access-token key. " +
                            "Tried: $ACCESS_TOKEN_KEYS. Keys present: ${props.keys.sorted()}. " +
                            "Open an issue with this key list so the plugin can support this credential shape."
                    )
                }
            }
            token
        } catch (e: ConnectionCredentialsException) {
            val msg = e.message.orEmpty()
            if (msg.startsWith("Unsupported Connection Provider type")) {
                // Known TC SDK limitation: the github-app plugin does
                // not plug into ConnectionCredentialsFactory. The
                // cache-only fallback is the correct path; no action
                // for the operator. Log once at INFO so we leave a
                // breadcrumb without polluting WARN.
                if (providerType.isNotBlank() && unsupportedProviderTypes.add(providerType)) {
                    LOG.info(
                        "ConnectionCredentialsFactory does not handle provider type '$providerType' on this TC version - using cached tokens only. " +
                            "This is normal for GitHub App connections on TC 2026.1 and is not actionable."
                    )
                }
            } else {
                val key = "creds-failed|${project.externalId}|${descriptor.id}"
                if (shouldLogOnce(key)) {
                    LOG.warn(
                        "credentialsManager.requestConnectionCredentials(${descriptor.id}) refused in project ${project.externalId}: " +
                            "[${e.javaClass.simpleName}] $msg. Falling back to getProjectTokens. " +
                            "Further occurrences for the same (project, id) will be suppressed for ${WARN_RATE_LIMIT_MS / 1000}s."
                    )
                }
            }
            null
        } catch (e: Throwable) {
            LOG.warn("credentialsManager threw unexpectedly for ${descriptor.id} in ${project.externalId}: [${e.javaClass.simpleName}] ${e.message}", e)
            null
        }
    }

    // Fallback: look up tokens that have already been cached for this
    // connection in TC's project-scoped token storage.
    private fun fetchViaProjectTokens(
        project: SProject,
        descriptor: OAuthConnectionDescriptor,
    ): String? {
        val builder = ProjectTokenQueryBuilder(project)
            .withConnectionId(descriptor.id)
            .withRefreshIfNecessary(true)
            .withTokenIntent(TokenIntent.ANY)
        val query = builder.build()
        // TC's getProjectTokens treats pageNumber as 1-indexed and
        // computes skip=(pageNumber-1)*pageSize internally. Passing 0
        // makes it call stream.skip(-pageSize) which throws.
        val order = TokenStoragePageOrder(
            1,
            10,
            TokenStoragePageOrder.OrderBy.RECORD_CREATION_DATE,
            TokenStoragePageOrder.Direction.DESC,
        )
        val result = try {
            tokensStorage.getProjectTokens(query, order)
        } catch (e: Throwable) {
            LOG.warn("getProjectTokens threw for connection ${descriptor.id} in project ${project.externalId}: ${e.message}", e)
            return null
        }
        return result.items.firstOrNull()?.token?.accessToken
    }

    private fun shouldLog(reason: String, projectId: String, connectionId: String): Boolean =
        shouldLogOnce(warnKey(reason, projectId, connectionId))

    private fun shouldLogOnce(key: String): Boolean {
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

    private fun clearWarnCooldowns(projectId: String, connectionId: String) {
        lastWarnedAtMs.remove(warnKey("no-conn", projectId, connectionId))
        lastWarnedAtMs.remove(warnKey("no-token", projectId, connectionId))
    }

    private fun warnKey(reason: String, projectId: String, connectionId: String): String =
        "$reason:$projectId:$connectionId"

    // TokenStorageQuery.Builder is generic on its own subtype with a
    // protected constructor (self-typed builder pattern). Concrete
    // subclass so we can instantiate it from Kotlin.
    private class ProjectTokenQueryBuilder(project: SProject) :
        TokenStorageQuery.Builder<ProjectTokenQueryBuilder>(project) {
        override fun self(): ProjectTokenQueryBuilder = this
    }

    companion object {
        private val LOG = Logger.getInstance(TokenResolver::class.java.name)

        const val WARN_RATE_LIMIT_MS: Long = 60_000L

        // Known property keys under which TC connection providers
        // expose the access token. `secure:accessToken` is the format
        // documented in the bundled TfsConstants; GitHub-App follows
        // the same convention. Listed in priority order; first non-null
        // value wins.
        private val ACCESS_TOKEN_KEYS: List<String> = listOf(
            "secure:accessToken",
            "accessToken",
            "secure:oauth.accessToken",
            "oauth.accessToken",
            "secure:token",
            "token",
        )
    }
}
