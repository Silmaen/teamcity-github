package io.github.dlachouette.teamcity.github.api

import com.intellij.openapi.diagnostic.Logger
import java.security.interfaces.RSAPrivateKey
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// Mints GitHub App installation tokens directly from the App's
// private key, bypassing TC's ConnectionCredentialsManager (which
// does not register GitHubApp as a supported provider type on
// TC 2026.1) and the OAuthTokensStorage cache (which is only filled
// when an operator manually clicks "Test connection").
//
// Flow per mint(...) call:
//   1. Read appId + private-key PEM from descriptor.parameters.
//   2. Parse the PEM (supports both PKCS#1 and PKCS#8 headers).
//   3. Sign a short-lived RS256 JWT as the App.
//   4. Find the installation that covers the target repo's owner.
//   5. POST /app/installations/{id}/access_tokens for a fresh
//      ghs_* installation token.
//   6. Cache against the installation id with a safety margin.
class AppTokenMinter(
    private val gitHubClient: GitHubClient,
    private val cache: AppTokenCache,
) {
    // Settable for tests, defaults to system UTC.
    var clock: Clock = Clock.systemUTC()

    // Per-connection one-shot diagnostics so a misconfigured connection
    // does not spam the log on every PR webhook.
    private val keyDiscoveryLogged = ConcurrentHashMap.newKeySet<String>()

    // Learned mapping "appId/owner" -> installation id, so a warm token
    // can be returned without re-listing installations. Cleared only by a
    // server restart; installation ids are stable for an App+owner pair.
    private val ownerInstallationIds = ConcurrentHashMap<String, Long>()

    private fun ownerKey(appId: String, owner: String): String = "$appId/${owner.lowercase()}"

    fun mint(
        connectionId: String,
        connectionDisplayName: String,
        params: Map<String, String>,
        repo: RepoCoords,
        apiBase: String = GitHubClient.DEFAULT_API_BASE,
    ): String? {
        val appId = APP_ID_KEYS.firstNotNullOfOrNull { params[it]?.takeIf { v -> v.isNotBlank() } }
        val rawPem = PRIVATE_KEY_KEYS.firstNotNullOfOrNull { params[it]?.takeIf { v -> v.isNotBlank() } }
        if (appId == null || rawPem == null) {
            if (keyDiscoveryLogged.add(connectionId)) {
                LOG.warn(
                    "Connection $connectionId ($connectionDisplayName) does not expose " +
                        "the GitHub App credentials this plugin needs. " +
                        "Looked for appId in $APP_ID_KEYS and private key in $PRIVATE_KEY_KEYS. " +
                        "Param keys present (values redacted): ${params.keys.sorted()}. " +
                        "Self-mint disabled for this connection."
                )
            }
            return null
        }

        // Fast path: once we have learned this owner's installation id we
        // can serve a still-valid cached token WITHOUT parsing the key,
        // signing a JWT, or calling GET /app/installations. mint() runs on
        // every build-lifecycle event and the queue filter, so skipping
        // that round-trip on the hot path matters.
        val ownerKey = ownerKey(appId, repo.owner)
        ownerInstallationIds[ownerKey]?.let { id -> cache.get(id)?.let { return it } }

        val privateKey = RsaKeyParser.parsePrivateKey(rawPem)
        if (privateKey == null) {
            if (keyDiscoveryLogged.add("pem-$connectionId")) {
                // Diagnostic that does NOT leak the key body. describePemHeader
                // extracts only the BEGIN line (between the opening and closing
                // dashes) which is never sensitive, even when the whole PEM is
                // stored on a single line.
                LOG.warn(
                    "Could not parse the private key stored on connection $connectionId. " +
                        "Diagnostic: ${RsaKeyParser.describePemHeader(rawPem)}. " +
                        "Total length: ${rawPem.length} chars. " +
                        "Accepted formats: -----BEGIN PRIVATE KEY----- (PKCS#8) or -----BEGIN RSA PRIVATE KEY----- (PKCS#1). " +
                        "Self-mint disabled for this connection."
                )
            }
            return null
        }

        val jwt = signJwt(appId, privateKey) ?: return null

        val installations = gitHubClient.listInstallations(jwt, apiBase)
        val installation = installations.firstOrNull { it.accountLogin.equals(repo.owner, ignoreCase = true) }
        if (installation == null) {
            LOG.warn(
                "App #$appId has no installation covering '${repo.owner}'. " +
                    "Installations seen: ${installations.map { "${it.accountLogin} (#${it.id})" }}. " +
                    "Install the App on '${repo.owner}' or correct teamcity.github.bridge.repo on the buildType."
            )
            return null
        }

        // Remember owner -> installation id so subsequent mints for the
        // same owner can take the fast path above.
        ownerInstallationIds[ownerKey] = installation.id

        cache.get(installation.id)?.let { return it }

        val created = gitHubClient.createInstallationToken(jwt, installation.id, apiBase) ?: return null
        // GitHub installation tokens live ~1h; if `expires_at` was absent
        // from the response, fall back to a conservative lifetime computed
        // against the injected clock (never the wall clock — that broke TTL
        // testing). Then truncate by a safety margin so we never hand out a
        // token that is about to expire mid-call.
        val expiresAt = created.expiresAt ?: Instant.now(clock).plusSeconds(DEFAULT_TOKEN_LIFETIME_SECONDS)
        val safeExpiresAt = expiresAt.minusSeconds(CACHE_SAFETY_MARGIN_SECONDS)
        cache.put(installation.id, created.token, safeExpiresAt)
        LOG.info(
            "Minted fresh installation token for App #$appId " +
                "(installation #${installation.id}, owner=${installation.accountLogin}) " +
                "via the self-mint path."
        )
        return created.token
    }

    private fun signJwt(appId: String, privateKey: RSAPrivateKey): String? =
        AppJwt.sign(appId, privateKey, clock)

    companion object {
        private val LOG = Logger.getInstance(AppTokenMinter::class.java.name)

        // Installation tokens live ~1 hour on GitHub's side. Drop 10 min
        // so we never hand out a token that expires mid-call.
        const val CACHE_SAFETY_MARGIN_SECONDS: Long = 10L * 60L

        // Conservative fallback lifetime when GitHub's token response
        // carried no parseable `expires_at` (matches GitHub's ~1h).
        const val DEFAULT_TOKEN_LIFETIME_SECONDS: Long = 60L * 60L

        // Candidate keys for the App credentials in the TC connection
        // descriptor. The bundled github-app provider on TC 2026.1
        // uses the `gitHubApp.` prefix (`gitHubApp.appId`,
        // `secure:gitHubApp.privateKey`); the unprefixed variants are
        // kept for forward/backward compatibility with other TC
        // versions and connection shapes.
        val APP_ID_KEYS: List<String> = listOf(
            "gitHubApp.appId",
            "appId",
            "githubAppId",
            "app.id",
            "secure:appId",
        )
        val PRIVATE_KEY_KEYS: List<String> = listOf(
            "secure:gitHubApp.privateKey",
            "secure:privateKey",
            "secure:privateKey.pem",
            "secure:appPrivateKey",
            "privateKey",
            "secure:secretKey",
        )
    }
}
