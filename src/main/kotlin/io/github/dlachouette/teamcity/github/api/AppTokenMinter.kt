package io.github.dlachouette.teamcity.github.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.intellij.openapi.diagnostic.Logger
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
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

        val privateKey = parsePrivateKey(rawPem)
        if (privateKey == null) {
            if (keyDiscoveryLogged.add("pem-$connectionId")) {
                // Diagnostic that does NOT leak the key body. describePemHeader
                // extracts only the BEGIN line (between the opening and closing
                // dashes) which is never sensitive, even when the whole PEM is
                // stored on a single line.
                LOG.warn(
                    "Could not parse the private key stored on connection $connectionId. " +
                        "Diagnostic: ${describePemHeader(rawPem)}. " +
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

        cache.get(installation.id)?.let { return it }

        val created = gitHubClient.createInstallationToken(jwt, installation.id, apiBase) ?: return null
        // Truncate the cached expiry to leave a safety margin under
        // the GitHub-side lifetime so we never hand out a token that
        // is about to expire mid-call.
        val safeExpiresAt = created.expiresAt.minusSeconds(CACHE_SAFETY_MARGIN_SECONDS)
        cache.put(installation.id, created.token, safeExpiresAt)
        LOG.info(
            "Minted fresh installation token for App #$appId " +
                "(installation #${installation.id}, owner=${installation.accountLogin}) " +
                "via the self-mint path."
        )
        return created.token
    }

    private fun signJwt(appId: String, privateKey: RSAPrivateKey): String? {
        return try {
            val now = Instant.now(clock)
            val iat = Date.from(now.minusSeconds(JWT_CLOCK_SKEW_SECONDS))
            val exp = Date.from(now.plusSeconds(JWT_LIFETIME_SECONDS))
            val algorithm = Algorithm.RSA256(null, privateKey)
            JWT.create()
                .withIssuer(appId)
                .withIssuedAt(iat)
                .withExpiresAt(exp)
                .sign(algorithm)
        } catch (e: Exception) {
            LOG.warn("Failed signing App JWT for appId=$appId: ${e.message}")
            null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(AppTokenMinter::class.java.name)

        // GitHub allows up to 10 minutes; we sign for 9 with 60 s
        // clock-skew tolerance to be safe.
        const val JWT_CLOCK_SKEW_SECONDS: Long = 60L
        const val JWT_LIFETIME_SECONDS: Long = 9L * 60L

        // Installation tokens live ~1 hour on GitHub's side. Drop 10 min
        // so we never hand out a token that expires mid-call.
        const val CACHE_SAFETY_MARGIN_SECONDS: Long = 10L * 60L

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

        // Public for testing.
        // Accepts both PKCS#1 ("BEGIN RSA PRIVATE KEY") and PKCS#8
        // ("BEGIN PRIVATE KEY") PEMs. Tolerates:
        //   - literal `\n` escape sequences (when a key is pasted
        //     into a single-line text field)
        //   - actual newlines, CRLF, single CR
        //   - PEMs squashed to a single line with no newlines anywhere
        //     between BEGIN and END (TC's connection-storage format
        //     does this)
        //   - raw base64-encoded PKCS#8 DER with no PEM markers at all
        //
        // Uses index-based extraction (not line-based) so the
        // single-line case is handled identically to the multi-line
        // case.
        fun parsePrivateKey(pem: String): RSAPrivateKey? {
            val normalised = pem
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")

            val (header, body) = extractPemBody(normalised)
                ?: return tryRawBase64Pkcs8(normalised)

            val der = runCatching {
                Base64.getDecoder().decode(body.replace(Regex("\\s+"), ""))
            }.getOrNull() ?: return null

            val keySpec = when {
                header.contains("BEGIN RSA PRIVATE KEY") -> PKCS8EncodedKeySpec(wrapPkcs1AsPkcs8(der))
                header.contains("BEGIN PRIVATE KEY") -> PKCS8EncodedKeySpec(der)
                else -> return null
            }
            return tryGeneratePrivate(keySpec)
        }

        // Locate the first -----BEGIN ...----- marker and the next
        // -----END ...----- marker after it. Returns (header, body)
        // where `header` is the full BEGIN line and `body` is the
        // raw text between the two markers (may contain whitespace
        // that the caller strips). Returns null if no marker pair
        // was found - the caller should then try the raw-base64
        // path.
        private fun extractPemBody(pem: String): Pair<String, String>? {
            val beginIdx = pem.indexOf("-----BEGIN")
            if (beginIdx < 0) return null
            val headerEnd = pem.indexOf("-----", beginIdx + "-----BEGIN".length)
            if (headerEnd < 0) return null
            val header = pem.substring(beginIdx, headerEnd + "-----".length)

            val bodyStart = headerEnd + "-----".length
            val endIdx = pem.indexOf("-----END", bodyStart)
            val bodyEnd = if (endIdx >= 0) endIdx else pem.length
            val body = pem.substring(bodyStart, bodyEnd)
            return header to body
        }

        private fun tryRawBase64Pkcs8(input: String): RSAPrivateKey? {
            val candidate = input.replace(Regex("\\s+"), "")
            if (candidate.isEmpty()) return null
            val der = runCatching { Base64.getDecoder().decode(candidate) }.getOrNull() ?: return null
            return tryGeneratePrivate(PKCS8EncodedKeySpec(der))
        }

        private fun tryGeneratePrivate(spec: PKCS8EncodedKeySpec): RSAPrivateKey? {
            return try {
                KeyFactory.getInstance("RSA").generatePrivate(spec) as? RSAPrivateKey
            } catch (e: Exception) {
                LOG.warn(
                    "KeyFactory rejected the PKCS#8 encoded key spec (length=${spec.encoded.size} bytes): " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                null
            }
        }

        // Returns a short, non-sensitive description of the PEM shape -
        // what BEGIN marker we see, what algorithm, etc. Truncates
        // properly so the body is never logged even when the whole
        // PEM is squashed onto a single line.
        fun describePemHeader(pem: String): String {
            val normalised = pem.replace("\\r\\n", "\n").replace("\\n", "\n")
            val beginIdx = normalised.indexOf("-----BEGIN")
            return when {
                normalised.isBlank() -> "empty value"
                beginIdx < 0 -> "no BEGIN marker found (raw base64?) length=${normalised.length}"
                else -> {
                    val headerEnd = normalised.indexOf("-----", beginIdx + "-----BEGIN".length)
                    val header = if (headerEnd > 0) normalised.substring(beginIdx, headerEnd + 5) else "(truncated header)"
                    when {
                        header.contains("ENCRYPTED PRIVATE KEY") ->
                            "encrypted PKCS#8 PEM ('$header') - the plugin does not support encrypted keys; re-export without passphrase"
                        header.contains("OPENSSH PRIVATE KEY") ->
                            "OpenSSH PEM ('$header') - not PKCS-compatible; convert with `openssl pkcs8 -topk8 -in key -out key.pem -nocrypt`"
                        header.contains("EC PRIVATE KEY") ->
                            "EC PEM ('$header') - GitHub Apps use RSA keys; verify you exported the right file"
                        header.contains("DSA PRIVATE KEY") ->
                            "DSA PEM ('$header') - GitHub Apps use RSA keys; verify you exported the right file"
                        header.contains("BEGIN RSA PRIVATE KEY") || header.contains("BEGIN PRIVATE KEY") ->
                            "header recognised ('$header') but body could not be base64-decoded or DER-parsed - likely the key is truncated or contains stray characters"
                        else ->
                            "unrecognised header: '$header'"
                    }
                }
            }
        }

        // PKCS#1 RSA keys can be loaded as PKCS#8 by wrapping the
        // PKCS#1 body in the standard PrivateKeyInfo ASN.1 structure.
        // Avoids pulling BouncyCastle just to read a PEM header.
        private fun wrapPkcs1AsPkcs8(pkcs1: ByteArray): ByteArray {
            // OID 1.2.840.113549.1.1.1 (rsaEncryption) + NULL params
            val algorithmId = byteArrayOf(
                0x30, 0x0D,
                0x06, 0x09,
                0x2A.toByte(), 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
                0x0D, 0x01, 0x01, 0x01,
                0x05, 0x00,
            )
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val pkcs1Octet = encodeOctetString(pkcs1)
            val seqBody = version + algorithmId + pkcs1Octet
            return encodeSequence(seqBody)
        }

        private fun encodeSequence(content: ByteArray): ByteArray =
            byteArrayOf(0x30) + encodeLength(content.size) + content

        private fun encodeOctetString(content: ByteArray): ByteArray =
            byteArrayOf(0x04) + encodeLength(content.size) + content

        private fun encodeLength(len: Int): ByteArray {
            require(len >= 0) { "DER length cannot be negative: $len" }
            return when {
                len < 0x80 -> byteArrayOf(len.toByte())
                len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
                len < 0x10000 -> byteArrayOf(
                    0x82.toByte(),
                    (len ushr 8).toByte(),
                    (len and 0xFF).toByte(),
                )
                else -> byteArrayOf(
                    0x83.toByte(),
                    (len ushr 16).toByte(),
                    ((len ushr 8) and 0xFF).toByte(),
                    (len and 0xFF).toByte(),
                )
            }
        }
    }
}
