// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

/**
 * Encrypts/decrypts [CredentialEntry] payloads with a key derived from the ECDH
 * shared secret via HKDF-SHA256.
 *
 * Why a separate key rather than reusing the transport key directly:
 * - **Defense in depth**: if the outer [SyncBundle] envelope key leaks (e.g.
 *   accidental plaintext logging of a decrypted bundle), credentials stay
 *   protected by a distinct derived key.
 * - **Future-proof for Team mode**: the inner key can later be replaced by a
 *   group key distributed via a different channel without touching the wire
 *   envelope.
 *
 * Cryptographic parameters:
 * - KDF: HKDF-SHA256 (RFC 5869)
 * - Salt: constant per-protocol string (domain separation from other HKDF uses)
 * - Info: constant per-protocol string (ensures keys derived for different
 *   purposes from the same ECDH secret never collide)
 * - Cipher: AES-256-GCM, per-entry random 12-byte IV, 16-byte tag appended to
 *   ciphertext (JVM GCM default)
 *
 * Implementations live in [platform-specific jvmCommon impls][CredentialCodecImpl]
 * because HKDF is provided by BouncyCastle on JVM and the Cipher calls are
 * JVM-specific. commonMain exposes only the object facade.
 */
expect object CredentialCodec {
    /**
     * Derives the 32-byte inner credential key from [sharedSecret] via HKDF-SHA256.
     * Deterministic: same input ⇒ same output, so both peers derive identical keys.
     */
    fun deriveInnerKey(sharedSecret: ByteArray): ByteArray

    /**
     * Encrypts [plaintext] with the inner key. Returns (ivBase64, ciphertextBase64).
     * Caller is responsible for wiping [plaintext] and [innerKey] after use.
     */
    fun encrypt(plaintext: ByteArray, innerKey: ByteArray): Pair<String, String>

    /**
     * Decrypts a credential payload. Throws [SyncCodecException] on MAC failure
     * or malformed Base64. Returns a fresh ByteArray: caller zeroes it after use.
     */
    fun decrypt(ivBase64: String, ciphertextBase64: String, innerKey: ByteArray): ByteArray

    /** Constants exposed for domain separation documentation. */
    val HKDF_CRED_SALT: String    // "nextsh-cred-v1"
    val HKDF_CRED_INFO: String    // "credential-sync"
}
