package io.github.dlachouette.teamcity.github.api

import com.intellij.openapi.diagnostic.Logger
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

// PEM/PKCS parsing for GitHub App private keys, extracted from
// AppTokenMinter so the byte-level ASN.1/DER plumbing lives apart from
// the JWT-minting orchestration. Pure and self-contained — no GitHub or
// TeamCity dependencies — which is why it carries its own test surface.
//
// Accepts both PKCS#1 ("BEGIN RSA PRIVATE KEY") and PKCS#8
// ("BEGIN PRIVATE KEY") PEMs. Tolerates:
//   - literal `\n` escape sequences (key pasted into a single-line field)
//   - actual newlines, CRLF, single CR
//   - PEMs squashed to a single line with no newlines between BEGIN/END
//     (TC's connection-storage format does this)
//   - raw base64-encoded PKCS#8 DER with no PEM markers at all
object RsaKeyParser {

    private val LOG = Logger.getInstance(RsaKeyParser::class.java.name)

    // Uses index-based extraction (not line-based) so the single-line
    // case is handled identically to the multi-line case.
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
    // -----END ...----- marker after it. Returns (header, body) where
    // `header` is the full BEGIN line and `body` is the raw text between
    // the two markers (may contain whitespace the caller strips).
    // Returns null if no marker pair was found — the caller should then
    // try the raw-base64 path.
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

    // Returns a short, non-sensitive description of the PEM shape — what
    // BEGIN marker we see, what algorithm, etc. Truncates properly so the
    // body is never logged even when the whole PEM is on a single line.
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

    // PKCS#1 RSA keys can be loaded as PKCS#8 by wrapping the PKCS#1 body
    // in the standard PrivateKeyInfo ASN.1 structure. Avoids pulling
    // BouncyCastle just to read a PEM header.
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
