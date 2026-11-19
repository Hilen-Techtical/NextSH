// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshKeyType
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The label typed by the user never reaches the generated key: the comment is a constant. */
class DesktopSshKeyCommentTest {

    private val keyManager = DesktopSshKeyManager()

    init {
        if (java.security.Security.getProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider())
        }
    }

    private val hostileLabels = listOf(
        "a\nssh-rsa AAAAinjected evil",
        "a\r\nb",
        "x" + Char(0x202E) + "y",
        "tab\there",
        "",
        "  ",
        "my laptop key",
    )

    @Test
    fun `an Ed25519 public key is one line that ends with the constant comment`() = runTest {
        for (label in hostileLabels) {
            val (_, publicKey) = keyManager.generateKeyPair(SshKeyType.ED25519, label)
            assertSingleLineWithConstantComment(publicKey, "ssh-ed25519")
        }
    }

    @Test
    fun `an ECDSA public key is one line that ends with the constant comment`() = runTest {
        val (_, publicKey) = keyManager.generateKeyPair(SshKeyType.ECDSA_256, "a\nb")
        assertSingleLineWithConstantComment(publicKey, "ecdsa-sha2-nistp256")
    }

    @Test
    fun `the Ed25519 private section carries the constant comment and never the label`() = runTest {
        val label = "secret-label-" + "zq9"
        val (pem, _) = keyManager.generateKeyPair(SshKeyType.ED25519, label)
        val body = pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
        val raw = String(Base64.getDecoder().decode(body), Charsets.ISO_8859_1)
        assertTrue(raw.contains(DesktopSshKeyManager.GENERATED_KEY_COMMENT), "constant comment missing")
        assertFalse(raw.contains(label), "the typed label leaked into the private key")
    }

    private fun assertSingleLineWithConstantComment(publicKey: String, expectedType: String) {
        assertFalse(publicKey.contains('\n') || publicKey.contains('\r'), "line break in the public key")
        assertTrue(publicKey.none { it.isISOControl() }, "control character in the public key")
        assertTrue(publicKey.none { it.code == 0x202E }, "bidi control in the public key")
        assertTrue(publicKey.startsWith("$expectedType "), "unexpected key type: $publicKey")
        assertTrue(publicKey.endsWith(" nextsh-generated"), "comment is not the constant")
        assertEquals(3, publicKey.split(' ').size, "expected exactly type, key and comment")
    }
}
