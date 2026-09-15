// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EcdhHandshakeTest {

    @Test
    fun `ecdh round trip derives matching secrets`() {
        val salt = ByteArray(32) { it.toByte() }
        val info = "nextsh-lan-sync-v1"

        val alice = EcdhHandshake.generateEphemeralKeypair()
        val bob = EcdhHandshake.generateEphemeralKeypair()

        val aliceSecret = EcdhHandshake.deriveSharedSecret(alice.privateKey(), bob.publicKey, salt, info)
        val bobSecret = EcdhHandshake.deriveSharedSecret(bob.privateKey(), alice.publicKey, salt, info)

        assertTrue(aliceSecret.contentEquals(bobSecret), "Shared secrets must match")
    }

    @Test
    fun `ecdh produces different secrets for different salts`() {
        val salt1 = ByteArray(32) { 0x01 }
        val salt2 = ByteArray(32) { 0x02 }
        val info = "nextsh-lan-sync-v1"

        val alice = EcdhHandshake.generateEphemeralKeypair()
        val bob = EcdhHandshake.generateEphemeralKeypair()

        val secret1 = EcdhHandshake.deriveSharedSecret(alice.privateKey(), bob.publicKey, salt1, info)
        val secret2 = EcdhHandshake.deriveSharedSecret(alice.privateKey(), bob.publicKey, salt2, info)

        assertTrue(!secret1.contentEquals(secret2), "Different salts must produce different secrets")
    }

    @Test
    fun `fingerprint is deterministic`() {
        val kp = EcdhHandshake.generateEphemeralKeypair()
        val fp1 = EcdhHandshake.fingerprint(kp.publicKey)
        val fp2 = EcdhHandshake.fingerprint(kp.publicKey)
        assertEquals(fp1, fp2)
        assertEquals(16, fp1.length)
    }

    @Test
    fun `ephemeral keypair is 32 bytes each side`() {
        val kp = EcdhHandshake.generateEphemeralKeypair()
        assertEquals(32, kp.publicKey.size)
        assertEquals(32, kp.privateKey().size)
    }

    @Test
    fun `wipePrivate zeroes private key`() {
        val kp = EcdhHandshake.generateEphemeralKeypair()
        kp.wipePrivate()
        assertTrue(kp.privateKey().all { it == 0.toByte() }, "Private key must be zeroed after wipe")
    }
}
