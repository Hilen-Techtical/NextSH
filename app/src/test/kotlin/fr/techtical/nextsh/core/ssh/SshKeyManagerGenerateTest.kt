// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.shared.core.backup.SshPublicKeyLine
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDerivation
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.security.Security
import java.util.Base64

/**
 * The Ed25519 key a user generates: written by the writer shared with Desktop, the label typed by the
 * user stays on the key row and never enters the private part of the file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshKeyManagerGenerateTest {

    private val manager = SshKeyManager()

    private fun bodyOf(pem: String): ByteArray = Base64.getDecoder().decode(
        pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString(""),
    )

    @Test
    fun `the label typed by the user does not enter the private part of the generated key`() = runTest {
        val label = "Server of the accountant 2026"

        val generated = manager.generateKeyPair(label, SshKeyType.ED25519)

        val body = String(bodyOf(generated.privateKeyPem), Charsets.ISO_8859_1)
        assertFalse(body.contains(label), "the label must not be written in the private key file")
        assertTrue(body.contains("nextsh-generated"), "the private part carries the constant comment")
        // The label stays what names the key in the app.
        assertEquals(label, generated.sshKey.label)
    }

    @Test
    fun `the generated private key gives back the public key of the row`() = runTest {
        val generated = manager.generateKeyPair("Any label", SshKeyType.ED25519)

        val derivation = SshPublicKeyDeriver.derive(generated.privateKeyPem, null)

        assertTrue(derivation is SshPublicKeyDerivation.Derived, "a clear OpenSSH v1 Ed25519 key must be derivable")
        assertEquals(
            SshPublicKeyLine.blobKey(generated.sshKey.publicKey),
            (derivation as SshPublicKeyDerivation.Derived).blobKey,
        )
        assertEquals("nextsh-generated", SshPublicKeyLine.comment(generated.sshKey.publicKey))
    }

    @Test
    fun `a blank or odd label gives the same constant comment`() = runTest {
        for (label in listOf("   ", "ligne\nde\ttest")) {
            val generated = manager.generateKeyPair(label, SshKeyType.ED25519)

            val body = String(bodyOf(generated.privateKeyPem), Charsets.ISO_8859_1)
            assertTrue(body.contains("nextsh-generated"))
        }
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun registerEd25519Provider() {
            // Registered at app start in production (App.setupBouncyCastleProvider).
            if (Security.getProvider(EdDSASecurityProvider.PROVIDER_NAME) == null) {
                Security.addProvider(EdDSASecurityProvider())
            }
        }
    }
}
