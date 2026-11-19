// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import net.schmizz.sshj.common.Buffer
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Converts known-host keys between the local storage form of each platform and the portable form of
 * the backup.
 *
 * Local form: `(algorithm, encoded)` where `algorithm` is `PublicKey.getAlgorithm()` (a JCA name
 * that is NOT guaranteed identical across platforms and providers) and `encoded` is the X.509 DER.
 * Portable form: `keyType` (SSH wire name) plus the base64 of the SSH public key blob.
 *
 * Import goes through `Buffer.PlainBuffer(blob).readPublicKey()`, the same SSHJ code that reads the
 * server key during the handshake, and stores the `(algorithm, encoded)` of the key SSHJ built. The
 * imported entry therefore compares equal, by construction, to what the verifier will see later.
 *
 * Nothing here logs and no failure carries a message: invalid input returns null.
 */
object KnownHostPortable {

    /** An entry in portable form. */
    class Portable(val keyType: String, val publicKey: String) {
        override fun toString(): String = "Portable"
    }

    /** An entry in local storage form. */
    class LocalKey(val algorithm: String, val encoded: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is LocalKey && algorithm == other.algorithm && encoded.contentEquals(other.encoded)

        override fun hashCode(): Int = 31 * algorithm.hashCode() + encoded.contentHashCode()

        override fun toString(): String = "LocalKey"
    }

    // X.509 SubjectPublicKeyInfo header of an Ed25519 key (OID 1.3.101.112), followed by 32 raw bytes.
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
    private const val ED25519_KEY_BYTES = 32
    private const val ED25519_TYPE = "ssh-ed25519"

    /**
     * Local to portable. [algorithm] is the JCA algorithm name and [x509] the X.509 DER of the key.
     * Returns null for an unsupported key type or a key that cannot be rebuilt (the caller skips
     * the entry and counts it).
     */
    fun toPortable(algorithm: String, x509: ByteArray): Portable? = try {
        val blob = when (algorithm) {
            // Ed25519 is parsed by hand: its provider name differs (i2p, JDK, BouncyCastle) and a
            // provider is not always registered at this point.
            "EdDSA", "Ed25519" -> ed25519Blob(x509)
            "RSA" -> sshBlob(KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(x509)))
            "EC", "ECDSA" -> sshBlob(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509)))
            else -> null
        }
        if (blob == null) {
            null
        } else {
            val keyType = Buffer.PlainBuffer(blob).readString()
            if (keyType in BackupFormat.KNOWN_HOST_KEY_TYPES && blob.size <= BackupLimits.MAX_PUBLIC_KEY_BLOB_BYTES) {
                Portable(keyType, Base64.getEncoder().encodeToString(blob))
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Portable to local. Returns null unless: [keyType] is in the allow-list, [publicKeyBase64] is
     * valid base64 of at most 8 KiB, SSHJ reads exactly one public key from it, and the type of that
     * key equals [keyType].
     */
    fun fromPortable(keyType: String, publicKeyBase64: String): LocalKey? = try {
        if (keyType !in BackupFormat.KNOWN_HOST_KEY_TYPES) {
            null
        } else if (BackupValidator.decodedBase64Size(publicKeyBase64) !in 1..BackupLimits.MAX_PUBLIC_KEY_BLOB_BYTES) {
            null
        } else {
            val blob = Base64.getDecoder().decode(publicKeyBase64)
            // SECURITY: SSHJ 0.38 allocates `new byte[length]` from a length it does not check against the
            // bytes left (Ed25519, ECDSA), so a 19 byte blob can ask for 2 GiB and throw an OutOfMemoryError
            // (an Error, not an Exception). The exact structure is checked BEFORE SSHJ sees the blob.
            if (!isWellFormedBlob(keyType, blob)) {
                null
            } else {
                val buffer = Buffer.PlainBuffer(blob)
                val key: PublicKey = buffer.readPublicKey()
                if (buffer.available() != 0) null else LocalKey(key.algorithm, key.encoded)
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private val CURVE_POINT_BYTES = mapOf(
        "ecdsa-sha2-nistp256" to ("nistp256" to 65),
        "ecdsa-sha2-nistp384" to ("nistp384" to 97),
        "ecdsa-sha2-nistp521" to ("nistp521" to 133),
    )
    private const val RSA_MIN_MODULUS_BYTES = 64
    private const val RSA_MAX_MODULUS_BYTES = 1025 // 8192 bits plus the sign byte
    private const val RSA_MAX_EXPONENT_BYTES = 8

    /**
     * Exact structure of an SSH public key blob, checked with plain byte arithmetic: every length is
     * bounded by the bytes that remain, nothing may follow the key. Ed25519: 32 bytes. ECDSA: the curve
     * name of the type, then an uncompressed point (0x04, 65, 97 or 133 bytes). RSA: positive exponent and
     * modulus of a reasonable size.
     */
    private fun isWellFormedBlob(keyType: String, blob: ByteArray): Boolean {
        val cursor = BlobCursor(blob)
        val type = cursor.readString() ?: return false
        if (type.decodeToString() != keyType) return false
        when (keyType) {
            ED25519_TYPE -> {
                val key = cursor.readString() ?: return false
                if (key.size != ED25519_KEY_BYTES) return false
            }
            "ssh-rsa" -> {
                val e = cursor.readString() ?: return false
                val n = cursor.readString() ?: return false
                if (e.isEmpty() || e.size > RSA_MAX_EXPONENT_BYTES || (e[0].toInt() and 0x80) != 0) return false
                if (n.size < RSA_MIN_MODULUS_BYTES || n.size > RSA_MAX_MODULUS_BYTES || (n[0].toInt() and 0x80) != 0) return false
            }
            else -> {
                val (curveName, pointBytes) = CURVE_POINT_BYTES[keyType] ?: return false
                val curve = cursor.readString() ?: return false
                if (curve.decodeToString() != curveName) return false
                val point = cursor.readString() ?: return false
                if (point.size != pointBytes || point[0].toInt() != 0x04) return false
            }
        }
        return cursor.remaining() == 0
    }

    /** Reads SSH `string` fields (uint32 big endian length, then bytes) without ever trusting a length. */
    private class BlobCursor(private val bytes: ByteArray) {
        private var pos = 0

        fun remaining(): Int = bytes.size - pos

        fun readString(): ByteArray? {
            if (remaining() < 4) return null
            val length = ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            if (length < 0 || length > remaining()) return null
            val value = bytes.copyOfRange(pos, pos + length)
            pos += length
            return value
        }
    }

    private fun ed25519Blob(x509: ByteArray): ByteArray? {
        if (x509.size != ED25519_SPKI_PREFIX.size + ED25519_KEY_BYTES) return null
        for (i in ED25519_SPKI_PREFIX.indices) if (x509[i] != ED25519_SPKI_PREFIX[i]) return null
        val raw = x509.copyOfRange(ED25519_SPKI_PREFIX.size, x509.size)
        return Buffer.PlainBuffer().putString(ED25519_TYPE).putString(raw).compactData
    }

    private fun sshBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData
}
