package io.github.dlachouette.teamcity.github.web

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignatureVerifierTest {

    private val secret = "It's a Secret to Everybody"
    private val payload = "Hello, World!".toByteArray()

    @Test
    fun `known vector matches GitHub example`() {
        val expected = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"
        val hex = SignatureVerifier.computeHmacSha256Hex(payload, secret)
        assertTrue(SignatureVerifier.verify(payload, expected, secret))
        assertTrue(expected.endsWith(hex))
    }

    @Test
    fun `verify rejects wrong signature`() {
        val wrong = "sha256=" + "00".repeat(32)
        assertFalse(SignatureVerifier.verify(payload, wrong, secret))
    }

    @Test
    fun `verify rejects missing header`() {
        assertFalse(SignatureVerifier.verify(payload, null, secret))
    }

    @Test
    fun `verify rejects header without prefix`() {
        val hex = SignatureVerifier.computeHmacSha256Hex(payload, secret)
        assertFalse(SignatureVerifier.verify(payload, hex, secret))
    }

    @Test
    fun `constant time equals only true for equal strings`() {
        assertTrue(SignatureVerifier.constantTimeEquals("abc", "abc"))
        assertFalse(SignatureVerifier.constantTimeEquals("abc", "abd"))
        assertFalse(SignatureVerifier.constantTimeEquals("abc", "abcd"))
    }
}
