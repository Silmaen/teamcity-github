package io.github.dlachouette.teamcity.github.api

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.connections.credentials.ConnectionCredentialsException
import jetbrains.buildServer.serverSide.connections.credentials.ProjectConnectionCredentialsManager
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import java.util.concurrent.ConcurrentHashMap

// Pair of (token, apiBase) returned by TokenResolver. The apiBase
// matches the GitHub host the token was minted against, so callers
// must use it on every subsequent REST call (PR queries, Check Runs,
// /rate_limit selftest). Without this, a token minted against a
// GitHub Enterprise host (e.g. github.acme.com/api/v3) sent to
// github.com would 401 - this is exactly the failure mode an
// earlier dev build hit on a GHE sandbox.
data class ResolvedAccess(
    val token: String,
    val apiBase: String,
)

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
// Token retrieval has two paths, tried in order:
//   1. `AppTokenMinter.mint(...)` - the plugin signs its own JWT with
//      the App's private key (read from the connection descriptor),
//      finds the installation matching the target repo's owner, and
//      mints a fresh installation token via the GitHub REST API.
//      This is the authoritative source: tokens are guaranteed
//      fresh (cached locally with a safety margin under the 60 min
//      GitHub-side lifetime) and scoped to the right installation.
//   2. `ProjectConnectionCredentialsManager.requestConnectionCredentials`
//      is the high-level TC entry point. For GitHub App connections
//      on TC 2026.1 it raises `Unsupported Connection Provider type`
//      and never returns a token. Kept as a forward-compat fallback
//      so a future TC fix is honoured automatically.
//
// The `OAuthTokensStorage.getProjectTokens` cache path that earlier
// versions used as a fallback has been dropped: TC's "refresh if
// necessary" flag does not refresh GitHub App tokens reliably on
// 2026.1, so the cache ends up handing out 401-rejected stale tokens
// that mask the real configuration. Self-mint replaces it cleanly.
class TokenResolver(
    private val oauthConnectionsManager: OAuthConnectionsManager,
    private val credentialsManager: ProjectConnectionCredentialsManager,
    private val appTokenMinter: AppTokenMinter,
) {

    private val lastWarnedAtMs = ConcurrentHashMap<String, Long>()
    private val unknownKeyDiscoveryLogged = ConcurrentHashMap.newKeySet<String>()

    // Some connection provider types (e.g. "GitHubApp" on TC 2026.1)
    // do not register a ConnectionCredentialsFactory and always raise
    // "Unsupported Connection Provider type". Cache those so we skip
    // the attempt entirely and go straight to getProjectTokens.
    private val unsupportedProviderTypes = ConcurrentHashMap.newKeySet<String>()

    fun resolveAccessToken(project: SProject, connectionId: String, repo: RepoCoords): ResolvedAccess? {
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
        val apiBase = apiBaseFromDescriptor(descriptor)
        val accessToken = fetchViaSelfMint(descriptor, repo, apiBase)
            ?: fetchViaCredentialsManager(project, descriptor)
        if (accessToken == null) {
            if (shouldLog("no-token", project.externalId, connectionId)) {
                LOG.warn(
                    "No installation token available for connection ${descriptor.id} (storageId=${descriptor.tokenStorageId}) in project ${project.externalId}. " +
                        "Self-mint and the credentials-manager fallback both returned null. " +
                        "apiBase used: $apiBase. " +
                        "Most common causes: the GitHub App is not installed on ${repo.slug}, the App has no permission for it, " +
                        "the connection descriptor does not expose appId + private key under the expected keys (see AppTokenMinter logs), " +
                        "or the connection's GitHub URL points at a different host than the App was actually registered on."
                )
            }
            return null
        }
        clearWarnCooldowns(project.externalId, connectionId)
        return ResolvedAccess(token = accessToken, apiBase = apiBase)
    }

    // Computes the REST apiBase for the connection. Tries the
    // candidate descriptor keys first, falls back to api.github.com
    // if none are present.
    private fun apiBaseFromDescriptor(descriptor: OAuthConnectionDescriptor): String {
        val raw = GITHUB_URL_KEYS.firstNotNullOfOrNull {
            descriptor.parameters[it]?.takeIf { v -> v.isNotBlank() }
        }
        return GitHubClient.apiBaseFromGitHubUrl(raw)
    }

    // Public diagnostic helper - the self-tester uses it to display
    // which apiBase the plugin would use for a given (project,
    // connection) pair, even when resolveAccessToken fails. Returns
    // null only if the connection cannot be found.
    fun computeApiBase(project: SProject, connectionId: String): String? {
        val descriptor = findConnection(project, connectionId) ?: return null
        return apiBaseFromDescriptor(descriptor)
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

    // Third path: self-mint the installation token directly from the
    // App's credentials stored on the connection descriptor. This is
    // the only path that works on a vanilla TC 2026.1 sandbox where
    // the OAuthTokensStorage cache has never been populated.
    private fun fetchViaSelfMint(
        descriptor: OAuthConnectionDescriptor,
        repo: RepoCoords,
        apiBase: String,
    ): String? {
        return try {
            appTokenMinter.mint(
                connectionId = descriptor.id,
                connectionDisplayName = descriptor.connectionDisplayName,
                params = descriptor.parameters,
                repo = repo,
                apiBase = apiBase,
            )
        } catch (e: Throwable) {
            LOG.warn(
                "AppTokenMinter.mint threw unexpectedly for ${descriptor.id} (repo=${repo.slug}): " +
                    "[${e.javaClass.simpleName}] ${e.message}",
                e,
            )
            null
        }
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

        // Candidate keys for the GitHub host URL on TC connection
        // descriptors. On TC 2026.1 the bundled github-app provider
        // exposes `gitHubApp.ownerUrl`, which is the URL of the owner
        // (org or user) the App was registered against - close enough
        // to derive the API base (host + /api/v3 on GHE).
        val GITHUB_URL_KEYS: List<String> = listOf(
            "gitHubApp.ownerUrl",
            "gitHubUrl",
            "githubUrl",
            "github.url",
            "serverUrl",
            "url",
        )
    }
}
