// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh.fido2

import fr.techtical.nextsh.shared.util.Logger
import net.schmizz.sshj.common.Buffer
import java.io.Closeable

private const val TAG = "SkSshSignature"

/**
 * Encapsule une signature SSH FIDO2 (sk-*) au format wire.
 *
 * La signature SSH complete est :
 *   string key_type (e.g., "sk-ecdsa-sha2-nistp256@openssh.com")
 *   string signature_inner
 *
 * Ou signature_inner contient :
 *   string raw_signature (DER-encoded ECDSA ou 64 bytes Ed25519)
 *   byte   flags (bit 0 = user present, bit 2 = user verified)
 *   uint32 counter (authenticator signature counter)
 *
 * L'encodage outer (key_type + wrapping) est gere par [Buffer.putSignature]
 * dans [SkAuthMethod]. Cette classe n'encode que la partie inner.
 *
 * Securite : implemente [Closeable] pour wiper la signature brute.
 */
class SkSshSignature private constructor(
    val keyType: SkKeyType,
    val rawSignature: ByteArray,
    val flags: Byte,
    val counter: UInt,
) : Closeable {

    /**
     * Encode les donnees internes de la signature (sans le wrapper key_type).
     * Ce resultat est passe a [Buffer.putSignature] comme sigData.
     *
     * Format :
     *   string raw_signature
     *   byte   flags
     *   uint32 counter
     */
    fun encodeInner(): ByteArray {
        val buf = Buffer.PlainBuffer()
        buf.putString(rawSignature)
        buf.putByte(flags)
        buf.putUInt32(counter.toLong())
        return buf.compactData
    }

    override fun close() {
        rawSignature.fill(0)
    }

    companion object {
        /** Bit 0 : l'utilisateur etait physiquement present */
        const val FLAG_USER_PRESENT: Byte = 0x01
        /** Bit 2 : l'utilisateur a ete verifie (biometrie/PIN) */
        const val FLAG_USER_VERIFIED: Byte = 0x04

        /**
         * Cree une signature FIDO2 validee.
         *
         * @param keyType Type de cle sk-*
         * @param rawSignature Signature brute de l'authenticator (DER ECDSA ou Ed25519 64 bytes)
         * @param flags Byte de flags FIDO2 (user_present doit etre set)
         * @param counter Compteur de signatures de l'authenticator
         * @throws IllegalArgumentException si le flag user_present n'est pas set
         */
        fun create(
            keyType: SkKeyType,
            rawSignature: ByteArray,
            flags: Byte,
            counter: UInt,
        ): SkSshSignature {
            require(flags.toInt() and FLAG_USER_PRESENT.toInt() != 0) {
                "FIDO2 signature must have user_present flag set"
            }
            if (counter == 0u) {
                Logger.w(TAG, "FIDO2 signature counter is 0: authenticator may not support counters or is newly reset")
            }
            return SkSshSignature(keyType, rawSignature, flags, counter)
        }
    }
}
