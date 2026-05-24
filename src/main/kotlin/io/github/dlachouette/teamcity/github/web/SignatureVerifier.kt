package io.github.dlachouette.teamcity.github.web

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SignatureVerifier {

    fun verify(payload: ByteArray, providedHeader: String?, secret: String): Boolean {
        if (providedHeader == null) return false
        if (!providedHeader.startsWith(PREFIX)) return false
        val providedHex = providedHeader.removePrefix(PREFIX)
        val expectedHex = computeHmacSha256Hex(payload, secret)
        return constantTimeEquals(providedHex, expectedHex)
    }

    fun computeHmacSha256Hex(payload: ByteArray, secret: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
        return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
    }

    internal fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    const val PREFIX: String = "sha256="
    const val ALGORITHM: String = "HmacSHA256"
}
