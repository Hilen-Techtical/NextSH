// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.Serializable

/**
 * Wire-format entry for a vault credential being synced between peers.
 *
 * The credential bytes themselves are **always already encrypted** when packaged
 * into this type, with a key derived via HKDF-SHA256 from the ECDH shared
 * secret, using the dedicated info string [CredentialCodec.HKDF_CRED_INFO].
 * This is the inner layer of the double-encryption model: credentials are
 * readable only by peers that also derived the same inner key, even if the
 * outer [SyncBundle] envelope is somehow decrypted outside the protocol.
 *
 * CRDT-compatible: wrapped in [SyncEntry] with the standard vector clock and
 * soft-delete machinery. On conflict, the UI surfaces only [type] + [credentialId]
 * + timestamps: **the encrypted payload is never decrypted in the UI**.
 */
@Serializable
class CredentialEntry(
    /** UUID referencing a [Host.credentialId] (for HOST_PASSWORD / CERTIFICATE) or [SshKey.id] (for SSH_PRIVATE_KEY). */
    val credentialId: String,
    val type: CredentialType,
    /** Base64 URL-safe (no padding): per-entry AES-GCM IV, 12 bytes. Fresh per encryption. */
    val iv: String,
    /** Base64 URL-safe (no padding): AES-256-GCM ciphertext with 16-byte tag appended. */
    val encryptedPayload: String,
    /** SHA-256 of the plaintext payload (Base64 URL-safe). Stable across peers for same plaintext. */
    val contentHashBase64: String,
) {
    /**
     * Equality compares only (credentialId, type, contentHashBase64): [iv] and
     * [encryptedPayload] differ between peers encrypting the same plaintext
     * (fresh IV each time), but [contentHashBase64] is stable. Letting [CrdtEngine]
     * detect "concurrent same-content" edits avoids false-positive conflicts
     * when the same password is typed on both devices offline.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialEntry) return false
        return credentialId == other.credentialId &&
            type == other.type &&
            contentHashBase64 == other.contentHashBase64
    }

    override fun hashCode(): Int {
        var h = credentialId.hashCode()
        h = 31 * h + type.hashCode()
        h = 31 * h + contentHashBase64.hashCode()
        return h
    }

    override fun toString(): String =
        "CredentialEntry(id=${credentialId.take(8)}…, type=$type, hash=${contentHashBase64.take(8)}…)"
}

@Serializable
enum class CredentialType {
    /** UTF-8 encoded password bytes (from the CharArray in the vault, wiped immediately after encryption). */
    HOST_PASSWORD,

    /** OpenSSH/PEM-encoded private key bytes. Only non-biometric, non-FIDO2 keys are ever synced. */
    SSH_PRIVATE_KEY,

    /** Passphrase protecting an encrypted SSH private key, keyed by the same credentialId as the private key. */
    SSH_PRIVATE_KEY_PASSPHRASE,

    /** OpenSSH certificate (-cert.pub) contents. */
    CERTIFICATE,
}
