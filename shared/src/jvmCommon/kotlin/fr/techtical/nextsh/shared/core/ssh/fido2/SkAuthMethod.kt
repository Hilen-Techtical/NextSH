// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh.fido2

import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AbstractAuthMethod

private const val TAG = "SkAuthMethod"

/**
 * AuthMethod SSHJ custom pour l'authentification SSH par cle FIDO2 (sk-*).
 *
 * Utilise la methode standard "publickey" avec des types de cle sk-*.
 * La signature est obtenue depuis un authenticator materiel via [challengeHandler].
 *
 * Flux :
 * 1. [request] envoie un feeler non signe (signed=false) avec le type sk-* et le blob de cle publique
 * 2. Le serveur repond [Message.USERAUTH_60] (PK_OK) s'il reconnait la cle
 * 3. [handle] construit les donnees de signature et appelle [challengeHandler]
 * 4. La requete signee est envoyee avec la signature FIDO2 (raw_sig + flags + counter)
 *
 * Les donnees de signature envoyees au challengeHandler suivent le format SSH standard :
 *   string  session_id
 *   byte    SSH_MSG_USERAUTH_REQUEST (50)
 *   string  username
 *   string  service ("ssh-connection")
 *   string  "publickey"
 *   boolean true
 *   string  key_algorithm
 *   string  public_key_blob
 *
 * Securite : les donnees de signature et le session hash sont wipes apres usage.
 */
class SkAuthMethod(
    private val publicKey: SkSshPublicKey,
    private val challengeHandler: suspend (signingData: ByteArray) -> SkSshSignature?,
) : AbstractAuthMethod("publickey") {

    override fun buildReq(): SSHPacket {
        return buildFeelerRequest()
    }

    @Throws(UserAuthException::class, TransportException::class)
    override fun handle(msg: Message, buf: SSHPacket) {
        when (msg) {
            Message.USERAUTH_60 -> {
                Logger.d(TAG, "SK auth: server accepted key (PK_OK), requesting FIDO2 signature")
                sendSignedRequest()
            }
            else -> super.handle(msg, buf)
        }
    }

    override fun shouldRetry(): Boolean = false

    /**
     * Construit la requete feeler (non signee) pour verifier si le serveur accepte la cle.
     */
    private fun buildFeelerRequest(): SSHPacket {
        val req = super.buildReq()
            .putBoolean(false)
            .putString(publicKey.keyType.sshName)
        val pubKeyBlob = publicKey.toBlob()
        try {
            req.putString(pubKeyBlob)
        } finally {
            pubKeyBlob.fill(0)
        }
        return req
    }

    /**
     * Construit et envoie la requete signee apres reception de PK_OK.
     * Appelle le [challengeHandler] pour obtenir la signature FIDO2.
     */
    @Throws(UserAuthException::class, TransportException::class)
    private fun sendSignedRequest() {
        val signingData = buildSigningData()
        var signature: SkSshSignature? = null
        try {
            // runBlocking sur le thread SSH (IO), meme pattern que InteractiveHostKeyVerifier
            // dans SshSessionManager pour attendre la reponse utilisateur
            signature = runBlocking { challengeHandler(signingData) }
                ?: throw UserAuthException("FIDO2 challenge cancelled by user")

            // Construire la requete signee
            val signedReq = super.buildReq()
                .putBoolean(true)
                .putString(publicKey.keyType.sshName)

            val pubKeyBlob = publicKey.toBlob()
            try {
                signedReq.putString(pubKeyBlob)
            } finally {
                pubKeyBlob.fill(0)
            }

            // Encoder la signature : putSignature(keyType, innerData) produit
            // string( string(keyType) + bytes(innerData) )
            val sigInner = signature.encodeInner()
            signedReq.putSignature(publicKey.keyType.sshName, sigInner)
            sigInner.fill(0)

            params.transport.write(signedReq)
        } catch (e: UserAuthException) {
            throw e
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            throw UserAuthException("FIDO2 signing failed", e)
        } finally {
            signingData.fill(0)
            signature?.close()
        }
    }

    /**
     * Construit les donnees a signer pour l'authentification SSH publickey.
     *
     * Format (RFC 4252 section 7) :
     *   string  session_id
     *   byte    SSH_MSG_USERAUTH_REQUEST (50)
     *   string  username
     *   string  service
     *   string  "publickey"
     *   boolean true
     *   string  key_algorithm
     *   string  public_key_blob
     */
    private fun buildSigningData(): ByteArray {
        val sessionId = params.transport.sessionID
        val buf = Buffer.PlainBuffer()
        buf.putString(sessionId)
        buf.putByte(Message.USERAUTH_REQUEST.toByte())
        buf.putString(params.username)
        buf.putString(params.nextServiceName)
        buf.putString("publickey")
        buf.putBoolean(true)
        buf.putString(publicKey.keyType.sshName)
        val keyBlob = publicKey.toBlob()
        try {
            buf.putString(keyBlob)
        } finally {
            keyBlob.fill(0)
        }
        return buf.compactData
    }
}
