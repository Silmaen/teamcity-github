package io.github.dlachouette.teamcity.github.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.intellij.openapi.diagnostic.Logger
import java.security.interfaces.RSAPrivateKey
import java.time.Clock
import java.time.Instant
import java.util.Date

// Signs the short-lived RS256 JWT that authenticates as a GitHub App
// (issuer = appId). Extracted from AppTokenMinter so both the minter and
// the App-management/verification path can produce an App JWT without
// duplicating the signing logic.
object AppJwt {

    private val LOG = Logger.getInstance(AppJwt::class.java.name)

    // GitHub allows up to 10 minutes; we sign for 9 with 60 s clock-skew
    // tolerance to be safe.
    const val CLOCK_SKEW_SECONDS: Long = 60L
    const val LIFETIME_SECONDS: Long = 9L * 60L

    fun sign(appId: String, privateKey: RSAPrivateKey, clock: Clock = Clock.systemUTC()): String? {
        return try {
            val now = Instant.now(clock)
            JWT.create()
                .withIssuer(appId)
                .withIssuedAt(Date.from(now.minusSeconds(CLOCK_SKEW_SECONDS)))
                .withExpiresAt(Date.from(now.plusSeconds(LIFETIME_SECONDS)))
                .sign(Algorithm.RSA256(null, privateKey))
        } catch (e: Exception) {
            LOG.warn("Failed signing App JWT for appId=$appId: ${e.message}")
            null
        }
    }
}
