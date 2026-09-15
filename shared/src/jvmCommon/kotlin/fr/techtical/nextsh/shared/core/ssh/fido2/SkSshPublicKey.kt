// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh.fido2

import net.schmizz.sshj.common.Buffer
import java.io.Closeable
import java.util.Base64

/**
 * Represente une cle publique SSH FIDO2 (sk-ecdsa-sha2-nistp256 ou sk-ssh-ed25519).
 *
 * Wire format sk-ecdsa-sha2-nistp256:
 *   string "sk-ecdsa-sha2-nistp256@openssh.com"
 *   string "nistp256"
 *   string ec_point (uncompressed public key point)
 *   string application (e.g., "ssh:")
 *
 * Wire format sk-ssh-ed25519:
 *   string "sk-ssh-ed25519@openssh.com"
 *   string ed25519_public_key (32 bytes)
 *   string application
 *
 * Securite : implemente [Closeable] pour wiper les donnees de cle en memoire.
 */
class SkSshPublicKey(
    val keyType: SkKeyType,
    val rawKeyData: ByteArray,
    val application: String = "ssh:",
) : Closeable {

    /**
     * Serialise la cle publique au format wire SSH (blob).
     */
    fun toBlob(): ByteArray {
        val buf = Buffer.PlainBuffer()
        buf.putString(keyType.sshName)
        if (keyType == SkKeyType.SK_ECDSA_256) {
            buf.putString(keyType.curveName!!)
        }
        buf.putString(rawKeyData)
        buf.putString(application)
        return buf.compactData
    }

    /**
     * Formate la cle pour le fichier authorized_keys OpenSSH.
     */
    fun toAuthorizedKeysLine(): String {
        val blob = toBlob()
        try {
            return "${keyType.sshName} ${Base64.getEncoder().encodeToString(blob)}"
        } finally {
            blob.fill(0)
        }
    }

    override fun close() {
        rawKeyData.fill(0)
    }

    companion object {

        /**
         * Encodes a raw Ed25519 public key (32 bytes) into the SSH wire format for sk-ssh-ed25519.
         *
         * Wire format:
         *   string "sk-ssh-ed25519@openssh.com"
         *   string ed25519_public_key (32 bytes)
         *   string application (e.g., "ssh:")
         *
         * The result can be Base64-encoded and prefixed with the key type to form a complete
         * OpenSSH public key line (as stored in [fr.techtical.nextsh.shared.domain.model.SshKey.publicKey]).
         *
         * @param application The FIDO2 RP-ID used as the application string (e.g., "ssh:").
         * @param ed25519PubKeyRaw Raw 32-byte Ed25519 public key extracted from the COSE key.
         * @return SSH wire format bytes.
         * @throws IllegalArgumentException if [ed25519PubKeyRaw] is not exactly 32 bytes.
         */
        fun toOpenSshWireFormat(application: String, ed25519PubKeyRaw: ByteArray): ByteArray {
            require(ed25519PubKeyRaw.size == 32) {
                "Ed25519 public key must be exactly 32 bytes, got ${ed25519PubKeyRaw.size}"
            }
            val buf = Buffer.PlainBuffer()
            buf.putString(SkKeyType.SK_ED25519.sshName)
            buf.putString(ed25519PubKeyRaw)
            buf.putString(application)
            return buf.compactData
        }

        /**
         * Converts a wire format blob + application string into the OpenSSH public key string
         * format suitable for storage in [fr.techtical.nextsh.shared.domain.model.SshKey.publicKey]:
         *   "sk-ssh-ed25519@openssh.com <base64> nextsh-fido2"
         *
         * @param application The FIDO2 application string.
         * @param ed25519PubKeyRaw Raw 32-byte Ed25519 public key.
         * @param comment Optional comment (default "nextsh-fido2").
         * @return OpenSSH public key string.
         */
        fun toOpenSshPublicKeyString(
            application: String,
            ed25519PubKeyRaw: ByteArray,
            comment: String = "nextsh-fido2",
        ): String {
            val blob = toOpenSshWireFormat(application, ed25519PubKeyRaw)
            return "${SkKeyType.SK_ED25519.sshName} ${Base64.getEncoder().encodeToString(blob)} $comment"
        }

        /**
         * Encodes a raw ECDSA P-256 public key (65 bytes SEC1 uncompressed = 0x04 || x || y)
         * into the SSH wire format for sk-ecdsa-sha2-nistp256.
         *
         * Wire format:
         *   string "sk-ecdsa-sha2-nistp256@openssh.com"
         *   string "nistp256"
         *   string ec_point (65-byte SEC1 uncompressed point)
         *   string application (e.g., "ssh:")
         *
         * @param application The FIDO2 RP-ID used as the application string (e.g., "ssh:").
         * @param ecdsaP256PubKeyRaw Raw 65-byte SEC1 uncompressed ECDSA P-256 public key.
         * @return SSH wire format bytes.
         * @throws IllegalArgumentException if [ecdsaP256PubKeyRaw] is not exactly 65 bytes
         *         or does not start with 0x04.
         */
        fun toOpenSshWireFormatEcdsaP256(application: String, ecdsaP256PubKeyRaw: ByteArray): ByteArray {
            require(ecdsaP256PubKeyRaw.size == 65) {
                "ECDSA P-256 SEC1 uncompressed key must be exactly 65 bytes, got ${ecdsaP256PubKeyRaw.size}"
            }
            require(ecdsaP256PubKeyRaw[0] == 0x04.toByte()) {
                "ECDSA P-256 key must start with 0x04 (uncompressed), got 0x${ecdsaP256PubKeyRaw[0].toUByte().toString(16)}"
            }
            val buf = Buffer.PlainBuffer()
            buf.putString(SkKeyType.SK_ECDSA_256.sshName)
            buf.putString(SkKeyType.SK_ECDSA_256.curveName!!)
            buf.putString(ecdsaP256PubKeyRaw)
            buf.putString(application)
            return buf.compactData
        }

        /**
         * Converts a wire format blob + application string into the OpenSSH public key string
         * for sk-ecdsa-sha2-nistp256, suitable for storage in SshKey.publicKey:
         *   "sk-ecdsa-sha2-nistp256@openssh.com <base64> nextsh-fido2"
         *
         * @param application The FIDO2 application string.
         * @param ecdsaP256PubKeyRaw Raw 65-byte SEC1 uncompressed ECDSA P-256 public key.
         * @param comment Optional comment (default "nextsh-fido2").
         * @return OpenSSH public key string.
         */
        fun toOpenSshPublicKeyStringEcdsaP256(
            application: String,
            ecdsaP256PubKeyRaw: ByteArray,
            comment: String = "nextsh-fido2",
        ): String {
            val blob = toOpenSshWireFormatEcdsaP256(application, ecdsaP256PubKeyRaw)
            return "${SkKeyType.SK_ECDSA_256.sshName} ${Base64.getEncoder().encodeToString(blob)} $comment"
        }

        /**
         * Parse une cle publique depuis un blob wire SSH.
         * Retourne null si le format est invalide ou le type inconnu.
         */
        fun fromBlob(blob: ByteArray): SkSshPublicKey? {
            return try {
                val buf = Buffer.PlainBuffer(blob)
                val typeName = buf.readString()
                val keyType = SkKeyType.entries.find { it.sshName == typeName }
                    ?: return null

                val rawKeyData = if (keyType == SkKeyType.SK_ECDSA_256) {
                    buf.readString() // curve name "nistp256", skip
                    buf.readStringAsBytes()
                } else {
                    buf.readStringAsBytes()
                }
                val application = buf.readString()

                SkSshPublicKey(keyType, rawKeyData, application)
            } catch (e: Buffer.BufferException) {
                null
            }
        }

        /**
         * Parse une cle publique depuis une ligne authorized_keys OpenSSH.
         * Format attendu : "sk-*@openssh.com AAAA... [comment]"
         */
        fun fromAuthorizedKeysLine(line: String): SkSshPublicKey? {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 2) return null
            val typeName = parts[0]
            if (SkKeyType.entries.none { it.sshName == typeName }) return null
            return try {
                val blob = Base64.getDecoder().decode(parts[1])
                fromBlob(blob)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
