// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.fido2ModeOrDefault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which hosts need the user in front of the phone to connect: a security key to touch, a
 * fingerprint to present. Their prompts are rendered by the activity, so they cannot be answered
 * while the app is not on screen.
 */
class UserPresenceTest {

    private fun host(authType: AuthType, fido2Mode: Fido2Mode? = null) = Host(
        id = "h1",
        label = "Host",
        hostname = "example.org",
        username = "root",
        authType = authType,
        credentialId = "c1",
        fido2Mode = fido2Mode,
    )

    @Test
    fun `a security key host needs the user`() {
        assertTrue(host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY).needsUserPresence())
    }

    @Test
    fun `a FIDO2 host without a mode is a security key host, as the connection reads it`() {
        // A host that came from the Desktop has no mode: the Desktop has no such notion.
        assertTrue(host(AuthType.FIDO2, fido2Mode = null).needsUserPresence())
    }

    @Test
    fun `a passkey host is an ordinary key login and needs nobody`() {
        assertFalse(host(AuthType.FIDO2, Fido2Mode.PASSKEY).needsUserPresence())
    }

    @Test
    fun `a biometric key host needs the user`() {
        assertTrue(host(AuthType.BIOMETRIC_KEY).needsUserPresence())
    }

    @Test
    fun `password, key and certificate hosts need nobody`() {
        assertFalse(host(AuthType.PASSWORD).needsUserPresence())
        assertFalse(host(AuthType.SSH_KEY).needsUserPresence())
        assertFalse(host(AuthType.CERTIFICATE).needsUserPresence())
    }

    @Test
    fun `a stray mode on another kind of host changes nothing`() {
        assertFalse(host(AuthType.SSH_KEY, Fido2Mode.HARDWARE_KEY).needsUserPresence())
        assertTrue(host(AuthType.BIOMETRIC_KEY, Fido2Mode.PASSKEY).needsUserPresence())
    }

    @Test
    fun `the default mode of a FIDO2 host is the security key, the one place of that default`() {
        assertEquals(Fido2Mode.HARDWARE_KEY, host(AuthType.FIDO2, fido2Mode = null).fido2ModeOrDefault())
        assertEquals(Fido2Mode.HARDWARE_KEY, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY).fido2ModeOrDefault())
        assertEquals(Fido2Mode.PASSKEY, host(AuthType.FIDO2, Fido2Mode.PASSKEY).fido2ModeOrDefault())
    }
}