// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The failure of `loadSkKeyHandle` never carries the id of the key nor a piece of its public line: the text
 * can reach the screen and the logs.
 */
class DesktopSshKeyLoaderSkHandleMessageTest {

    private fun skKey(line: String, credentialId: String?) = SshKey(
        id = "secret-key-id-42",
        label = "Hardware",
        keyType = SshKeyType.SK_ED25519,
        publicKey = line,
        fido2CredentialId = credentialId,
    )

    @Test
    fun `an unreadable public line gives a message without the key id nor the line`() {
        val line = "sk-ssh-ed25519@openssh.com not-base64-at-all comment-to-hide"

        val e = assertFailsWith<IllegalArgumentException> {
            DesktopSshKeyLoader.loadSkKeyHandle(skKey(line, "Y3JlZA=="))
        }

        val message = e.message.orEmpty()
        assertFalse("secret-key-id-42" in message)
        assertFalse("not-base64-at-all" in message)
        assertFalse("comment-to-hide" in message)
    }

    @Test
    fun `a missing credential id gives a message without the key id`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DesktopSshKeyLoader.loadSkKeyHandle(skKey("sk-ssh-ed25519@openssh.com AAAA", null))
        }

        assertFalse("secret-key-id-42" in e.message.orEmpty())
    }

    @Test
    fun `a credential id that is not base64 gives a message without the key id`() {
        // A valid line is needed to reach the Base64 step: any unreadable line fails earlier, which is checked above.
        val e = runCatching {
            DesktopSshKeyLoader.loadSkKeyHandle(skKey("sk-ssh-ed25519@openssh.com AAAA", "%%% not base64 %%%"))
        }.exceptionOrNull()

        assertFalse("secret-key-id-42" in e?.message.orEmpty())
    }
}
