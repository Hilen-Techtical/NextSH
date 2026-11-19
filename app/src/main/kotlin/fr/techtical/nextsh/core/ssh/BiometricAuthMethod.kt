// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AbstractAuthMethod
import timber.log.Timber
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean

/** Chains are short; this only guards against a cause cycle. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * The authentication ended because the signer failed, or because what it returned could not be
 * turned into a request: not because the server said no. Told apart by `SshSessionManager`, which
 * must not present such a failure as a refusal of the key. [cause] is the signer's own failure.
 */
internal class BiometricSigningException(message: String, cause: Throwable) : UserAuthException(message, cause)

/** The SSH name of the only key the biometric keys are: EC on the NIST P-256 curve. */
internal const val BIOMETRIC_KEY_SSH_ALGORITHM = "ecdsa-sha2-nistp256"

/**
 * True if [key] is an EC P-256 public key, the shape of every biometric key the app generates
 * ([fr.techtical.nextsh.core.crypto.KeystoreManager.generateBiometricSshKey]). The key is read from
 * the Keystore, whose entries come from a database that a backup or a sync can fill: anything else
 * is refused before the server is asked about it.
 */
internal fun isBiometricKeyAlgorithm(key: PublicKey): Boolean = KeyType.fromKey(key) == KeyType.ECDSA256

/**
 * SSHJ authentication method for a key that lives in the Android Keystore and is released by the
 * fingerprint. Modeled on `SkAuthMethod` (`:shared`), the FIDO2 one, and for the same reason: with
 * `client.authPublickey(user, keyProvider)` SSHJ asks for the signature by itself, with a
 * `Signature` it makes and that no `BiometricPrompt` ever authorized, so the Keystore refuses it. Here
 * the signature is not SSHJ's, it comes from [signer].
 *
 * The flow is the standard "publickey" one:
 * 1. [request] sends a feeler (`signed = false`) with the algorithm and the public key blob;
 * 2. the server answers [Message.USERAUTH_60] (PK_OK) if it knows the key. Only then does the
 *    user get asked for a fingerprint: one touch per connection, never for a key the server refuses;
 * 3. [handle] builds the data to sign (RFC 4252 section 7, the same layout as `SkAuthMethod`) and
 *    calls [signer], which shows the prompt, signs and returns the raw DER ECDSA signature the
 *    Keystore produced, or null when the prompt ended without one;
 * 4. the signed request carries `string "ecdsa-sha2-nistp256"` and `string (mpint r || mpint s)`
 *    ([ecdsaDerToSshSignature]).
 *
 * [signer] runs in a `runBlocking` on the SSH transport thread, as the FIDO2 handler does: the
 * connection waits for the user there. Whatever the signer throws, and a null, end the
 * authentication as a [UserAuthException] (SSHJ delivers that to the caller of `client.auth`, any
 * other exception would take the transport down). A failure of the signer, or of the building of
 * the request from what it returned, is a [BiometricSigningException]: the connection tells it
 * from the server refusing the key. An interrupted thread (`runBlocking` woken by a disconnect)
 * gets its interrupt flag back.
 *
 * One signature per method: the server answers PK_OK once. A second PK_OK would otherwise ask for
 * another fingerprint and sign again, so it ends the authentication instead.
 *
 * Security: the signing data and the DER signature are zeroed as soon as the request is built,
 * whatever the outcome. The method never logs any of it.
 *
 * @throws IllegalArgumentException if [publicKey] is not an EC P-256 key
 */
class BiometricAuthMethod(
    publicKey: PublicKey,
    private val signer: suspend (signingData: ByteArray) -> ByteArray?,
) : AbstractAuthMethod("publickey") {

    /** The public key blob of the wire format (public data, so not zeroed after use). */
    private val publicKeyBlob: ByteArray

    /** Set by the first PK_OK, before the signer is asked: the second one finds it taken. */
    private val signatureRequested = AtomicBoolean(false)

    init {
        require(isBiometricKeyAlgorithm(publicKey)) { "A biometric key is an EC P-256 key" }
        publicKeyBlob = Buffer.PlainBuffer().putPublicKey(publicKey).compactData
    }

    override fun buildReq(): SSHPacket = buildFeelerRequest()

    @Throws(UserAuthException::class, TransportException::class)
    override fun handle(msg: Message, buf: SSHPacket) {
        when (msg) {
            Message.USERAUTH_60 -> {
                if (!signatureRequested.compareAndSet(false, true)) {
                    // Nothing sensitive in the text, and no second prompt for a signature already given or refused.
                    throw UserAuthException("Unexpected second PK_OK during the biometric authentication")
                }
                Timber.d("Biometric auth: server accepted the key (PK_OK), asking for the fingerprint")
                sendSignedRequest()
            }
            else -> super.handle(msg, buf)
        }
    }

    /** One attempt only: a second one would be a second fingerprint for a signature the server just refused. */
    override fun shouldRetry(): Boolean = false

    /** The request that asks the server whether it knows the key, without signing anything. */
    private fun buildFeelerRequest(): SSHPacket =
        super.buildReq()
            .putBoolean(false)
            .putString(BIOMETRIC_KEY_SSH_ALGORITHM)
            .putString(publicKeyBlob)

    /** Builds and sends the signed request once the server accepted the key. */
    @Throws(UserAuthException::class, TransportException::class)
    private fun sendSignedRequest() {
        val signingData = buildSigningData()
        var der: ByteArray? = null
        var blob: ByteArray? = null
        try {
            der = runBlocking { signer(signingData) }
                ?: throw UserAuthException("Biometric authentication ended without a signature")
            blob = ecdsaDerToSshSignature(der)

            val signature = Buffer.PlainBuffer()
                .putString(BIOMETRIC_KEY_SSH_ALGORITHM)
                .putString(blob)
                .compactData
            val signedRequest = super.buildReq()
                .putBoolean(true)
                .putString(BIOMETRIC_KEY_SSH_ALGORITHM)
                .putString(publicKeyBlob)
                .putString(signature)
            params.transport.write(signedRequest)
        } catch (e: UserAuthException) {
            throw e
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            // A runBlocking woken by an interrupt (the connection was closed under it) throws
            // InterruptedException and clears the flag: the thread belongs to SSHJ, give it back.
            if (hasInterruptedCause(e)) Thread.currentThread().interrupt()
            throw BiometricSigningException("Biometric signing failed", e)
        } finally {
            signingData.fill(0)
            der?.fill(0)
            blob?.fill(0)
        }
    }

    /** True if [e] or a cause of it (a short chain) is an [InterruptedException]. */
    private fun hasInterruptedCause(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is InterruptedException }

    /**
     * The data the key signs (RFC 4252 section 7):
     *
     *     string    session identifier
     *     byte      SSH_MSG_USERAUTH_REQUEST
     *     string    user name
     *     string    service name
     *     string    "publickey"
     *     boolean   TRUE
     *     string    public key algorithm name
     *     string    public key blob
     */
    private fun buildSigningData(): ByteArray {
        val buffer = Buffer.PlainBuffer()
            .putString(params.transport.sessionID)
            .putByte(Message.USERAUTH_REQUEST.toByte())
            .putString(params.username)
            .putString(params.nextServiceName)
            .putString("publickey")
            .putBoolean(true)
            .putString(BIOMETRIC_KEY_SSH_ALGORITHM)
            .putString(publicKeyBlob)
        return buffer.compactData
    }
}
