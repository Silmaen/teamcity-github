package io.github.dlachouette.teamcity.github.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class AppTokenMinterTest {

    init { LoggerBootstrap.install() }

    companion object {
        // A real 2048-bit RSA keypair generated once per test class. The
        // PKCS#8 PEM string mirrors what an operator would paste into the
        // TC connection edit form.
        private lateinit var publicKey: RSAPublicKey
        private lateinit var privateKey: RSAPrivateKey
        private lateinit var pkcs8Pem: String

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            publicKey = keyPair.public as RSAPublicKey
            privateKey = keyPair.private as RSAPrivateKey
            val base64 = Base64.getEncoder().encodeToString(privateKey.encoded)
            pkcs8Pem = buildString {
                appendLine("-----BEGIN PRIVATE KEY-----")
                base64.chunked(64).forEach { appendLine(it) }
                appendLine("-----END PRIVATE KEY-----")
            }
        }
    }

    private fun fixedClock(at: Instant): Clock = Clock.fixed(at, ZoneOffset.UTC)

    private val repo = RepoCoords("acme", "widget")

    @Test
    fun `parsePrivateKey accepts a fresh PKCS8 PEM`() {
        val parsed = RsaKeyParser.parsePrivateKey(pkcs8Pem)
        assertNotNull(parsed)
        assertEquals(privateKey.modulus, parsed!!.modulus)
    }

    @Test
    fun `parsePrivateKey tolerates literal backslash-n escape sequences`() {
        val singleLine = pkcs8Pem.replace("\n", "\\n")
        val parsed = RsaKeyParser.parsePrivateKey(singleLine)
        assertNotNull(parsed)
    }

    @Test
    fun `parsePrivateKey rejects garbage input`() {
        assertNull(RsaKeyParser.parsePrivateKey("not a pem at all"))
        assertNull(RsaKeyParser.parsePrivateKey(""))
        assertNull(RsaKeyParser.parsePrivateKey("-----BEGIN PRIVATE KEY-----\n!!not-base64!!\n-----END PRIVATE KEY-----"))
    }

    @Test
    fun `parsePrivateKey accepts a PKCS1 PEM produced manually`() {
        val crt = privateKey as RSAPrivateCrtKey
        val pkcs1Pem = pkcs1Pem(crt)
        val parsed = RsaKeyParser.parsePrivateKey(pkcs1Pem)
        assertNotNull(parsed)
        assertEquals(crt.modulus, parsed!!.modulus)
        assertEquals(crt.privateExponent, parsed.privateExponent)
    }

    // Regression for the format TC's connection storage produces:
    // BEGIN, body, and END all on the same line with no newlines.
    @Test
    fun `parsePrivateKey accepts a PKCS1 PEM squashed onto a single line`() {
        val crt = privateKey as RSAPrivateCrtKey
        val pemMultiLine = pkcs1Pem(crt)
        val pemSingleLine = pemMultiLine.replace("\n", "").replace("\r", "")
        val parsed = RsaKeyParser.parsePrivateKey(pemSingleLine)
        assertNotNull(parsed)
        assertEquals(crt.modulus, parsed!!.modulus)
    }

    @Test
    fun `parsePrivateKey accepts a PKCS8 PEM squashed onto a single line`() {
        val pemSingleLine = pkcs8Pem.replace("\n", "").replace("\r", "")
        val parsed = RsaKeyParser.parsePrivateKey(pemSingleLine)
        assertNotNull(parsed)
        assertEquals(privateKey.modulus, parsed!!.modulus)
    }

    // Manually ASN.1-encode an RSAPrivateCrtKey into a PKCS#1 PEM.
    // Mirrors what `openssl genrsa` produces and matches what GitHub
    // Apps hand operators when they generate a new private key.
    private fun pkcs1Pem(crt: RSAPrivateCrtKey): String {
        val pkcs1Der = encodePkcs1(crt)
        val base64 = Base64.getEncoder().encodeToString(pkcs1Der)
        return buildString {
            appendLine("-----BEGIN RSA PRIVATE KEY-----")
            base64.chunked(64).forEach { appendLine(it) }
            appendLine("-----END RSA PRIVATE KEY-----")
        }
    }

    private fun encodePkcs1(crt: RSAPrivateCrtKey): ByteArray {
        fun intTlv(v: BigInteger): ByteArray {
            val bytes = v.toByteArray()
            return byteArrayOf(0x02) + asn1Length(bytes.size) + bytes
        }
        val body = intTlv(BigInteger.ZERO) +
            intTlv(crt.modulus) +
            intTlv(crt.publicExponent) +
            intTlv(crt.privateExponent) +
            intTlv(crt.primeP) +
            intTlv(crt.primeQ) +
            intTlv(crt.primeExponentP) +
            intTlv(crt.primeExponentQ) +
            intTlv(crt.crtCoefficient)
        return byteArrayOf(0x30) + asn1Length(body.size) + body
    }

    private fun asn1Length(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        len < 0x10000 -> byteArrayOf(0x82.toByte(), (len ushr 8).toByte(), (len and 0xFF).toByte())
        else -> error("asn1 length too long: $len")
    }

    @Test
    fun `mint returns null when appId is missing from params`() {
        val minter = AppTokenMinter(StubGitHubClient(), AppTokenCache())
        val result = minter.mint(
            connectionId = "PROJECT_EXT_1",
            connectionDisplayName = "Test",
            params = mapOf("secure:privateKey" to pkcs8Pem),
            repo = repo,
        )
        assertNull(result)
    }

    @Test
    fun `mint returns null when private key is missing from params`() {
        val minter = AppTokenMinter(StubGitHubClient(), AppTokenCache())
        val result = minter.mint(
            connectionId = "PROJECT_EXT_1",
            connectionDisplayName = "Test",
            params = mapOf("appId" to "123"),
            repo = repo,
        )
        assertNull(result)
    }

    @Test
    fun `mint returns null when the PEM cannot be parsed`() {
        val minter = AppTokenMinter(StubGitHubClient(), AppTokenCache())
        val result = minter.mint(
            connectionId = "PROJECT_EXT_1",
            connectionDisplayName = "Test",
            params = mapOf(
                "appId" to "123",
                "secure:privateKey" to "not a pem",
            ),
            repo = repo,
        )
        assertNull(result)
    }

    @Test
    fun `mint returns null when no installation matches the repo owner`() {
        val stub = StubGitHubClient(
            installations = listOf(
                InstallationInfo(id = 1L, accountLogin = "other-org"),
            ),
        )
        val minter = AppTokenMinter(stub, AppTokenCache())
        val result = minter.mint(
            connectionId = "PROJECT_EXT_1",
            connectionDisplayName = "Test",
            params = mapOf(
                "appId" to "123",
                "secure:privateKey" to pkcs8Pem,
            ),
            repo = RepoCoords("acme", "widget"),
        )
        assertNull(result)
        assertEquals(1, stub.listCalls.get())
        assertEquals(0, stub.createCalls.get())
    }

    @Test
    fun `mint signs an RS256 JWT and produces a token`() {
        // Use the real now so the verifier's wall-clock check passes.
        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)
        val stub = StubGitHubClient(
            installations = listOf(InstallationInfo(id = 42L, accountLogin = "acme")),
            createToken = CreatedToken(token = "ghs_minted", expiresAt = expiresAt),
        )
        val minter = AppTokenMinter(stub, AppTokenCache()).also { it.clock = fixedClock(now) }

        val token = minter.mint(
            connectionId = "PROJECT_EXT_1",
            connectionDisplayName = "Test",
            params = mapOf(
                "appId" to "999",
                "secure:privateKey" to pkcs8Pem,
            ),
            repo = repo,
        )

        assertEquals("ghs_minted", token)
        // Verify the signed JWT shape via the captured value.
        val jwt = stub.lastJwt
        assertNotNull(jwt)
        val decoded = JWT.require(Algorithm.RSA256(publicKey, null)).build().verify(jwt)
        assertEquals("999", decoded.issuer)
        assertEquals("RS256", decoded.algorithm)
        // iat=now-60s (clock-skew tolerance), exp=now+540s (lifetime).
        // Total iat->exp span = 600s.
        val iat = decoded.issuedAt.toInstant()
        val exp = decoded.expiresAt.toInstant()
        assertTrue(!iat.isAfter(now), "iat must be at or before now")
        assertEquals(600L, java.time.Duration.between(iat, exp).seconds)
    }

    @Test
    fun `mint serves cached token on second call within TTL`() {
        val now = Instant.parse("2026-05-26T12:00:00Z")
        val expiresAt = now.plusSeconds(3600)
        val stub = StubGitHubClient(
            installations = listOf(InstallationInfo(id = 42L, accountLogin = "acme")),
            createToken = CreatedToken(token = "ghs_first", expiresAt = expiresAt),
        )
        val cache = AppTokenCache().also { it.clock = fixedClock(now) }
        val minter = AppTokenMinter(stub, cache).also { it.clock = fixedClock(now) }
        val params = mapOf("appId" to "999", "secure:privateKey" to pkcs8Pem)

        val first = minter.mint("c", "Test", params, repo)
        val second = minter.mint("c", "Test", params, repo)

        assertEquals("ghs_first", first)
        assertEquals("ghs_first", second)
        // First call: list + create. Second call takes the fast path —
        // the learned owner -> installation-id mapping plus the warm token
        // cache satisfy it without re-listing installations or minting.
        assertEquals(1, stub.listCalls.get())
        assertEquals(1, stub.createCalls.get())
    }

    @Test
    fun `mint goes back to network when cache TTL expires`() {
        val createdAt = Instant.parse("2026-05-26T12:00:00Z")
        val expiresAt = createdAt.plusSeconds(3600)
        // Cache safety margin is 10 min, so cache expiry is t+50 min.
        val afterCacheExpiry = createdAt.plusSeconds(AppTokenMinter.CACHE_SAFETY_MARGIN_SECONDS).plusSeconds(2_500L)
        val stub = StubGitHubClient(
            installations = listOf(InstallationInfo(id = 42L, accountLogin = "acme")),
            createToken = CreatedToken(token = "ghs_first", expiresAt = expiresAt),
        )
        val cache = AppTokenCache()
        val minter = AppTokenMinter(stub, cache)
        val params = mapOf("appId" to "999", "secure:privateKey" to pkcs8Pem)

        cache.clock = fixedClock(createdAt)
        minter.clock = fixedClock(createdAt)
        val first = minter.mint("c", "Test", params, repo)

        // Move past cache safety margin.
        cache.clock = fixedClock(afterCacheExpiry)
        minter.clock = fixedClock(afterCacheExpiry)
        stub.createToken = CreatedToken(token = "ghs_second", expiresAt = afterCacheExpiry.plusSeconds(3600))
        val second = minter.mint("c", "Test", params, repo)

        assertEquals("ghs_first", first)
        assertEquals("ghs_second", second)
        assertEquals(2, stub.createCalls.get())
    }

    // Minimal stub: captures the last JWT presented, returns canned
    // installation + token data.
    private class StubGitHubClient(
        val installations: List<InstallationInfo> = emptyList(),
        var createToken: CreatedToken? = null,
    ) : GitHubClient() {
        val listCalls = AtomicInteger(0)
        val createCalls = AtomicInteger(0)
        var lastJwt: String? = null
            private set

        override fun listInstallations(jwt: String, apiBase: String): List<InstallationInfo> {
            lastJwt = jwt
            listCalls.incrementAndGet()
            return installations
        }

        override fun createInstallationToken(
            jwt: String,
            installationId: Long,
            apiBase: String,
            repositoryNames: List<String>?,
        ): CreatedToken? {
            lastJwt = jwt
            createCalls.incrementAndGet()
            return createToken
        }
    }
}
