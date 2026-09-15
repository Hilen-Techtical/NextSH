// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests unitaires pour [Fido2EnrollManager].
 *
 * Note : la découverte matérielle (USB/NFC) et l'appel CTAP2 réel ne peuvent pas
 * être testés en unit test. Ils nécessitent un device physique ou un émulateur.
 * Le parsing CBOR est délégué à YubiKit et n'est pas re-testé ici.
 *
 * Ces tests couvrent :
 *   - Le round-trip publicKey → SkSshPublicKey → authorized_keys format
 *   - Le wipe sécurisé de [EnrollResult]
 */
class Fido2EnrollManagerTest {

    // ── Test : round-trip publicKey → authorized_keys ─────────────────────────

    @Test
    fun `EnrollResult publicKey produces valid SkSshPublicKey authorized_keys line`() {
        val rawPubKey = ByteArray(32) { it.toByte() }
        val credentialId = ByteArray(16) { 0xAA.toByte() }

        val enrollResult = Fido2EnrollManager.EnrollResult(
            credentialId = credentialId,
            application = "ssh:",
            publicKey = rawPubKey,
            keyType = SkKeyType.SK_ED25519,
            transport = Fido2EnrollManager.Transport.USB,
        )

        val skPubKey = SkSshPublicKey(
            keyType = SkKeyType.SK_ED25519,
            rawKeyData = enrollResult.publicKey,
            application = enrollResult.application,
        )
        val line = skPubKey.toAuthorizedKeysLine()

        assertTrue(line.startsWith("sk-ssh-ed25519@openssh.com "), "Line should start with sk type: $line")

        // Round-trip : parse la ligne en SkSshPublicKey
        val parsed = SkSshPublicKey.fromAuthorizedKeysLine(line)
        assertNotNull(parsed)
        assertEquals(SkKeyType.SK_ED25519, parsed!!.keyType)
        assertArrayEquals(rawPubKey, parsed.rawKeyData)
        assertEquals("ssh:", parsed.application)

        skPubKey.close()
        parsed.close()
    }

    @Test
    fun `EnrollResult wipe fills credentialId and publicKey with zeros`() {
        val credId = ByteArray(16) { 0xFF.toByte() }
        val pubKey = ByteArray(32) { 0xAB.toByte() }
        val result = Fido2EnrollManager.EnrollResult(
            credentialId = credId,
            application = "ssh:",
            publicKey = pubKey,
            keyType = SkKeyType.SK_ED25519,
            transport = Fido2EnrollManager.Transport.USB,
        )

        result.wipe()

        assertTrue(credId.all { it == 0.toByte() }, "credentialId should be zeroed")
        assertTrue(pubKey.all { it == 0.toByte() }, "publicKey should be zeroed")
    }
}
