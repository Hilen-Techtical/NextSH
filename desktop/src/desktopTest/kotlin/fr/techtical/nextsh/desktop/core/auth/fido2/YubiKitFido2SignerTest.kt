// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2

import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests de [YubiKitFido2Signer] : la couche CTAP reçoit `SHA-256(challenge brut)`, une seule
 * fois (contrat : [Fido2Signer.signChallenge]). [YubiKitFidoManager] est mocké ; le
 * GetAssertion YubiKit lui-même se valide sur matériel réel.
 */
class YubiKitFido2SignerTest {

    private val rpId = "ssh:"
    private val credentialId = ByteArray(16) { (it + 100).toByte() }

    private val cannedAssertion = YubiKitFidoManager.SkAssertion(
        authData = ByteArray(37) { it.toByte() },
        signature = ByteArray(64) { (it * 3).toByte() },
    )

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

    /**
     * Mock du manager qui capture le clientDataHash reçu par la couche CTAP.
     */
    private fun managerCapturing(hashSlot: CapturingSlot<ByteArray>): YubiKitFidoManager {
        val manager = mockk<YubiKitFidoManager>()
        coEvery {
            manager.signChallenge(
                rpId = any(),
                clientDataHash = capture(hashSlot),
                credentialId = any(),
                requireUv = any(),
                timeoutMs = any(),
            )
        } returns cannedAssertion
        return manager
    }

    @Test
    fun `signChallenge sends SHA-256 of the raw challenge to the CTAP layer exactly once`() = runTest {
        val hashSlot = slot<ByteArray>()
        val manager = managerCapturing(hashSlot)
        val signer = YubiKitFido2Signer(manager)
        val rawChallenge = "ssh-userauth-signing-buffer".toByteArray()

        signer.signChallenge(rpId = rpId, challenge = rawChallenge, credentialId = credentialId)

        coVerify(exactly = 1) {
            manager.signChallenge(
                rpId = any(),
                clientDataHash = any(),
                credentialId = any(),
                requireUv = any(),
                timeoutMs = any(),
            )
        }
        assertContentEquals(
            sha256(rawChallenge),
            hashSlot.captured,
            "CTAP clientDataHash must be SHA-256 of the raw challenge",
        )
    }

    @Test
    fun `clientDataHash is a single SHA-256 pass (published test vector for abc)`() = runTest {
        val hashSlot = slot<ByteArray>()
        val signer = YubiKitFido2Signer(managerCapturing(hashSlot))

        signer.signChallenge(rpId, "abc".toByteArray(), credentialId)

        // FIPS 180-2 / NIST : SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(hashSlot.captured),
        )
    }

    @Test
    fun `signChallenge forwards rpId and credentialId and returns the authenticator assertion untouched`() = runTest {
        val manager = mockk<YubiKitFidoManager>()
        val rpIdSlot = slot<String>()
        val credSlot = slot<ByteArray>()
        coEvery {
            manager.signChallenge(
                rpId = capture(rpIdSlot),
                clientDataHash = any(),
                credentialId = capture(credSlot),
                requireUv = any(),
                timeoutMs = any(),
            )
        } returns cannedAssertion
        val signer = YubiKitFido2Signer(manager)

        val result = signer.signChallenge(rpId, "data".toByteArray(), credentialId)

        assertEquals(rpId, rpIdSlot.captured)
        assertContentEquals(credentialId, credSlot.captured)
        assertContentEquals(cannedAssertion.authData, result.authData)
        assertContentEquals(cannedAssertion.signature, result.signature)
    }

    @Test
    fun `signChallenge does not modify the caller challenge array`() = runTest {
        val signer = YubiKitFido2Signer(managerCapturing(slot<ByteArray>()))
        val rawChallenge = ByteArray(40) { (it + 7).toByte() }
        val snapshot = rawChallenge.copyOf()

        signer.signChallenge(rpId, rawChallenge, credentialId)

        assertTrue(snapshot.contentEquals(rawChallenge), "caller challenge must stay intact")
    }
}
