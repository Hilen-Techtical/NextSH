// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh.fido2

import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Signer
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.signature.Signature
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Impl SSHJ [Signature] pour le type "sk-ssh-ed25519@openssh.com".
 *
 * Flux de signature :
 * 1. [initSign] recoit un [SkFido2PrivateKey] et memorise la ref vers le YubiKitFidoManager.
 * 2. [update] accumule les bytes du challenge SSH (format RFC 4252 §7).
 * 3. [sign] :
 *    a. Calcule SHA-256 sur les bytes accumules → clientDataHash (32 bytes).
 *    b. Appelle YubiKitFidoManager.signChallenge(rpId, clientDataHash, credentialId).
 *    c. Extrait flags (byte 32 de authData, index 32) et counter (bytes 33-36 BE).
 *    d. Construit la signature wire SSH SK :
 *         string raw_signature (64 bytes ed25519)
 *         byte   flags
 *         uint32 counter
 * 4. [encode] encapsule dans le format SSH wire complet :
 *         string key_type ("sk-ssh-ed25519@openssh.com")
 *         string inner_blob
 * 5. [verify] leve UnsupportedOperationException (verification cote serveur uniquement).
 *
 * Thread safety : [sign] appelle [runBlocking] sur le thread SSH IO : meme pattern
 * que [fr.techtical.nextsh.shared.core.ssh.fido2.SkAuthMethod]. L'UI doit avoir
 * attache ses callbacks onTouchRequired/onTouchDone au YubiKitFidoManager AVANT
 * l'appel a [initSign].
 *
 * Format authData CTAP2 (FIDO2 spec §6.1) :
 *   rpIdHash     [0..31]  SHA-256 du rpId
 *   flags        [32]     bit 0 = UP, bit 2 = UV, ...
 *   counter      [33..36] uint32 big-endian
 *   (extensions et attestedCredentialData optionnels apres byte 37)
 */
class SkFido2Signature(
    private val keyType: SkKeyType,
    private val onTouchRequired: (deviceLabel: String) -> Unit = {},
    private val onTouchDone: () -> Unit = {},
) : Signature {

    private val buffer = ByteArrayOutputStream()
    private var fido2PrivateKey: SkFido2PrivateKey? = null

    override fun getSignatureName(): String = keyType.sshName

    override fun initVerify(pubkey: PublicKey) {
        // Verification non supportee cote client : le serveur verifie la signature
        throw UnsupportedOperationException("FIDO2 signature verification not supported on client side")
    }

    override fun initSign(prvkey: PrivateKey) {
        fido2PrivateKey = prvkey as? SkFido2PrivateKey
            ?: throw SSHRuntimeException("Expected SkFido2PrivateKey, got ${prvkey::class.java.name}")
        buffer.reset()
    }

    override fun update(H: ByteArray) {
        buffer.write(H)
    }

    override fun update(H: ByteArray, off: Int, len: Int) {
        buffer.write(H, off, len)
    }

    /**
     * Signe le challenge SSH via YubiKey CTAP2 GetAssertion.
     * Bloque jusqu'au touch de l'utilisateur (timeout configure dans YubiKitFidoManager).
     *
     * @return bytes bruts de la signature wire SK (avant encapsulation dans [encode])
     */
    override fun sign(): ByteArray {
        val key = fido2PrivateKey
            ?: throw SSHRuntimeException("SkFido2Signature.sign() called before initSign()")

        // SHA-256 du challenge SSH accumule = clientDataHash
        val challengeBytes = buffer.toByteArray()
        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(challengeBytes)

        // Appel CTAP2 GetAssertion (bloquant)
        val assertion = runBlocking {
            key.fido2Signer.signChallenge(
                rpId = key.publicKey.application, // "ssh:" par defaut
                clientDataHash = clientDataHash,
                credentialId = key.credentialId,
            )
        }

        // Extraire flags et counter de authData
        // authData format : rpIdHash[0..31] | flags[32] | counter[33..36] | ...
        val authData = assertion.authData
        if (authData.size < 37) {
            throw SSHRuntimeException("CTAP2 authData too short (${authData.size} bytes, expected >= 37)")
        }
        val flags = authData[32]
        val counter = ((authData[33].toInt() and 0xFF) shl 24) or
                ((authData[34].toInt() and 0xFF) shl 16) or
                ((authData[35].toInt() and 0xFF) shl 8) or
                (authData[36].toInt() and 0xFF)

        // Construire la signature wire SK via SkSshSignature (shared)
        val skSig = SkSshSignature.create(
            keyType = keyType,
            rawSignature = assertion.signature,
            flags = flags,
            counter = counter.toUInt(),
        )
        val inner = skSig.encodeInner()
        skSig.close()
        return inner
    }

    /**
     * Encapsule les bytes bruts dans le format SSH wire complet pour les signatures sk-* :
     *   string key_type (ex. "sk-ssh-ed25519@openssh.com")
     *   string inner_blob (raw_signature || flags || uint32_be(counter))
     *
     * Ce format est ce que [SSHPacket.putSignature(keyAlgorithmName, sigBytes)] produit,
     * mais ici on l'implemente directement pour controler l'encodage sk-*.
     */
    override fun encode(signature: ByteArray): ByteArray {
        val buf = Buffer.PlainBuffer()
        buf.putString(keyType.sshName)
        buf.putString(signature)
        return buf.compactData
    }

    override fun verify(sig: ByteArray): Boolean {
        throw UnsupportedOperationException("FIDO2 signature verification not supported on client side")
    }

}
