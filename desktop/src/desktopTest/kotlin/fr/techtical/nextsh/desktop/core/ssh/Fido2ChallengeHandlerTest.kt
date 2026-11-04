// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Signer
import fr.techtical.nextsh.desktop.core.auth.fido2.YubiKitFidoManager
import fr.techtical.nextsh.desktop.core.ssh.fido2.SkKeyHandle
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.userauth.UserAuthException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests de [fido2ChallengeHandler] : le tampon de signature arrive brut au [Fido2Signer],
 * l'assertion est convertie en `SkSshSignature` et les erreurs FIDO sont mappées.
 */
class Fido2ChallengeHandlerTest {

    private val credentialId = ByteArray(16) { (it + 100).toByte() }
    private val handle = SkKeyHandle(
        skPublicKey = SkSshPublicKey(SkKeyType.SK_ED25519, ByteArray(32) { it.toByte() }, "ssh:"),
        credentialId = credentialId,
        rpId = "ssh:",
    )

    private class RecordingSigner(
        private val authData: ByteArray = defaultAuthData(),
        private val failWith: Throwable? = null,
    ) : Fido2Signer {
        val receivedChallenges = mutableListOf<ByteArray>()
        var receivedRpId: String? = null
        var receivedCredentialId: ByteArray? = null
        val signature = ByteArray(64) { (it * 2).toByte() }

        override suspend fun signChallenge(
            rpId: String,
            challenge: ByteArray,
            credentialId: ByteArray,
        ): Fido2Signer.Assertion {
            receivedChallenges += challenge.copyOf()
            receivedRpId = rpId
            receivedCredentialId = credentialId.copyOf()
            failWith?.let { throw it }
            return Fido2Signer.Assertion(authData.copyOf(), signature.copyOf())
        }

        companion object {
            /** 37 octets : flags UP (0x01) à [32], counter 42 big-endian à [33..36]. */
            fun defaultAuthData(): ByteArray = ByteArray(37).also {
                it[32] = 0x01
                it[36] = 42
            }
        }
    }

    @Test
    fun `handler passes the raw signing data to the signer without hashing`() = runTest {
        val signer = RecordingSigner()
        val signingData = ByteArray(150) { (it * 3 + 1).toByte() }

        fido2ChallengeHandler(signer, handle)(signingData)

        assertEquals(1, signer.receivedChallenges.size, "signer must be called exactly once")
        val received = signer.receivedChallenges.single()
        assertContentEquals(signingData, received, "signer must receive the raw RFC 4252 signing buffer")
        assertFalse(
            received.contentEquals(MessageDigest.getInstance("SHA-256").digest(signingData)),
            "signing data must not be pre-hashed by the handler",
        )
    }

    @Test
    fun `handler forwards rpId and credentialId from the key handle`() = runTest {
        val signer = RecordingSigner()

        fido2ChallengeHandler(signer, handle)(byteArrayOf(9, 8, 7))

        assertEquals("ssh:", signer.receivedRpId)
        assertContentEquals(credentialId, signer.receivedCredentialId)
    }

    @Test
    fun `handler builds the SK signature from the assertion`() = runTest {
        val signer = RecordingSigner()

        val sig = assertNotNull(fido2ChallengeHandler(signer, handle)(byteArrayOf(1, 2, 3)))

        assertEquals(SkKeyType.SK_ED25519, sig.keyType)
        assertContentEquals(signer.signature, sig.rawSignature)
        assertEquals(0x01.toByte(), sig.flags)
        assertEquals(42u, sig.counter)
    }

    @Test
    fun `handler rejects a too-short authData`() = runTest {
        val signer = RecordingSigner(authData = ByteArray(36) { 0x01 })

        assertFailsWith<UserAuthException> {
            fido2ChallengeHandler(signer, handle)(byteArrayOf(1))
        }
    }

    @Test
    fun `handler maps FidoError NoDeviceFound to a FIDO2_NO_KEY auth error`() = runTest {
        val signer = RecordingSigner(failWith = YubiKitFidoManager.FidoError.NoDeviceFound)

        val ex = assertFailsWith<UserAuthException> {
            fido2ChallengeHandler(signer, handle)(byteArrayOf(1))
        }

        assertTrue(ex.message.orEmpty().startsWith("FIDO2_NO_KEY"), "got: ${ex.message}")
    }

    @Test
    fun `handler maps FidoError TouchTimeout to a FIDO2_TIMEOUT auth error`() = runTest {
        val signer = RecordingSigner(failWith = YubiKitFidoManager.FidoError.TouchTimeout)

        val ex = assertFailsWith<UserAuthException> {
            fido2ChallengeHandler(signer, handle)(byteArrayOf(1))
        }

        assertTrue(ex.message.orEmpty().startsWith("FIDO2_TIMEOUT"), "got: ${ex.message}")
    }

    @Test
    fun `handler maps FidoError UserCancelled to a FIDO2_USER_CANCELLED auth error`() = runTest {
        val signer = RecordingSigner(failWith = YubiKitFidoManager.FidoError.UserCancelled)

        val ex = assertFailsWith<UserAuthException> {
            fido2ChallengeHandler(signer, handle)(byteArrayOf(1))
        }

        assertTrue(ex.message.orEmpty().startsWith("FIDO2_USER_CANCELLED"), "got: ${ex.message}")
    }

    @Test
    fun `handler maps any other FidoError to a generic FIDO2_ERROR auth error`() = runTest {
        val signer = RecordingSigner(failWith = YubiKitFidoManager.FidoError.CtapError(0x2e, "no credentials"))

        val ex = assertFailsWith<UserAuthException> {
            fido2ChallengeHandler(signer, handle)(byteArrayOf(1))
        }

        assertTrue(ex.message.orEmpty().startsWith("FIDO2_ERROR"), "got: ${ex.message}")
    }
}
