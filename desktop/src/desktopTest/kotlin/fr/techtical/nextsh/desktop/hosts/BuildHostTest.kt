// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests of the pure [buildHost] builder: an edit must start from the
 * stored host so the fields the Desktop form does not show (favorite,
 * keep-alive, auto-reconnect, FIDO2 mode) are not reset, which would win the
 * CRDT sync and, for a FIDO2 host, make Android refuse to connect.
 */
class BuildHostTest {

    private fun form(
        hostId: String? = null,
        label: String = "Prod",
        hostname: String = "prod.example",
        port: String = "22",
        username: String = "root",
        authType: AuthType = AuthType.PASSWORD,
        terminalTheme: String = "TECHTICAL_DARK",
        credentialId: String = "cred-1",
        group: String = "",
    ) = HostDetailFormState(
        hostId = hostId,
        label = label,
        hostname = hostname,
        port = port,
        username = username,
        authType = authType,
        terminalTheme = terminalTheme,
        credentialId = credentialId,
        group = group,
    )

    private fun stored(
        id: String = "h1",
        authType: AuthType = AuthType.PASSWORD,
        credentialId: String = "old-cred",
        fido2Mode: Fido2Mode? = null,
        isFavorite: Boolean = false,
        keepAliveSeconds: Int = 30,
        autoReconnect: Boolean = true,
    ) = Host(
        id = id,
        label = "Old label",
        hostname = "old.example",
        port = 2222,
        username = "olduser",
        authType = authType,
        credentialId = credentialId,
        group = "old-group",
        keepAliveSeconds = keepAliveSeconds,
        autoReconnect = autoReconnect,
        terminalTheme = "OLD_THEME",
        fido2Mode = fido2Mode,
        isFavorite = isFavorite,
    )

    // -- Stored fields preserved ---------------------------------------------

    @Test
    fun `edit keeps the stored favorite flag`() {
        val result = buildHost(form(hostId = "h1"), stored(isFavorite = true), newId = "new")

        assertTrue(result.isFavorite)
    }

    @Test
    fun `edit keeps the stored keep-alive and auto-reconnect settings`() {
        val result = buildHost(
            form(hostId = "h1"),
            stored(keepAliveSeconds = 90, autoReconnect = false),
            newId = "new",
        )

        assertEquals(90, result.keepAliveSeconds)
        assertFalse(result.autoReconnect)
    }

    // -- Form fields applied -------------------------------------------------

    @Test
    fun `edit overwrites every field the form owns and trims the strings`() {
        val result = buildHost(
            form(
                hostId = "h1",
                label = "  Prod  ",
                hostname = "  prod.example  ",
                port = "2200",
                username = "  admin  ",
                authType = AuthType.SSH_KEY,
                terminalTheme = "TECHTICAL_LIGHT",
                credentialId = "key-9",
                group = "  Clients  ",
            ),
            stored(isFavorite = true, keepAliveSeconds = 60, autoReconnect = false),
            newId = "new",
        )

        assertEquals(
            Host(
                id = "h1",
                label = "Prod",
                hostname = "prod.example",
                port = 2200,
                username = "admin",
                authType = AuthType.SSH_KEY,
                credentialId = "key-9",
                group = "Clients",
                keepAliveSeconds = 60,
                autoReconnect = false,
                terminalTheme = "TECHTICAL_LIGHT",
                fido2Mode = null,
                isFavorite = true,
            ),
            result,
        )
    }

    @Test
    fun `blank group becomes null`() {
        val result = buildHost(form(hostId = "h1", group = "   "), stored(), newId = "new")

        assertNull(result.group)
    }

    // -- Id ------------------------------------------------------------------

    @Test
    fun `edit keeps the stored id and ignores the new id`() {
        val result = buildHost(form(hostId = "h1"), stored(id = "h1"), newId = "should-not-be-used")

        assertEquals("h1", result.id)
    }

    @Test
    fun `new host takes the new id`() {
        val result = buildHost(form(), stored = null, newId = "fresh-id")

        assertEquals("fresh-id", result.id)
    }

    // -- New item = historical behaviour -------------------------------------

    @Test
    fun `new host equals the object the previous code built from the form`() {
        val result = buildHost(
            form(
                label = " Prod ",
                hostname = " prod.example ",
                port = "22",
                username = " root ",
                authType = AuthType.PASSWORD,
                terminalTheme = "TECHTICAL_DARK",
                credentialId = "cred-1",
                group = " ",
            ),
            stored = null,
            newId = "fresh-id",
        )

        // Model defaults for everything the form does not own.
        assertEquals(
            Host(
                id = "fresh-id",
                label = "Prod",
                hostname = "prod.example",
                port = 22,
                username = "root",
                authType = AuthType.PASSWORD,
                credentialId = "cred-1",
                group = null,
                terminalTheme = "TECHTICAL_DARK",
            ),
            result,
        )
    }

    // -- FIDO2 mode ----------------------------------------------------------

    @Test
    fun `new FIDO2 host gets HARDWARE_KEY`() {
        val result = buildHost(form(authType = AuthType.FIDO2), stored = null, newId = "fresh-id")

        assertEquals(Fido2Mode.HARDWARE_KEY, result.fido2Mode)
    }

    @Test
    fun `stored PASSKEY mode is kept when the FIDO2 credential is unchanged`() {
        val result = buildHost(
            form(hostId = "h1", authType = AuthType.FIDO2, credentialId = "key-1"),
            stored(authType = AuthType.FIDO2, credentialId = "key-1", fido2Mode = Fido2Mode.PASSKEY),
            newId = "new",
        )

        assertEquals(Fido2Mode.PASSKEY, result.fido2Mode)
    }

    @Test
    fun `stored PASSKEY mode becomes HARDWARE_KEY when another credential is picked`() {
        val result = buildHost(
            form(hostId = "h1", authType = AuthType.FIDO2, credentialId = "sk-2"),
            stored(authType = AuthType.FIDO2, credentialId = "key-1", fido2Mode = Fido2Mode.PASSKEY),
            newId = "new",
        )

        assertEquals(Fido2Mode.HARDWARE_KEY, result.fido2Mode)
    }

    @Test
    fun `stored HARDWARE_KEY mode is kept when the FIDO2 credential is unchanged`() {
        val result = buildHost(
            form(hostId = "h1", authType = AuthType.FIDO2, credentialId = "sk-1"),
            stored(authType = AuthType.FIDO2, credentialId = "sk-1", fido2Mode = Fido2Mode.HARDWARE_KEY),
            newId = "new",
        )

        assertEquals(Fido2Mode.HARDWARE_KEY, result.fido2Mode)
    }

    @Test
    fun `stored FIDO2 host without mode gets HARDWARE_KEY`() {
        val result = buildHost(
            form(hostId = "h1", authType = AuthType.FIDO2, credentialId = "sk-1"),
            stored(authType = AuthType.FIDO2, credentialId = "sk-1", fido2Mode = null),
            newId = "new",
        )

        assertEquals(Fido2Mode.HARDWARE_KEY, result.fido2Mode)
    }

    @Test
    fun `stored PASSWORD host switched to FIDO2 gets HARDWARE_KEY`() {
        val result = buildHost(
            form(hostId = "h1", authType = AuthType.FIDO2, credentialId = "sk-1"),
            stored(authType = AuthType.PASSWORD, credentialId = "sk-1", fido2Mode = null),
            newId = "new",
        )

        assertEquals(Fido2Mode.HARDWARE_KEY, result.fido2Mode)
    }

    @Test
    fun `stale mode on a non FIDO2 stored host is not carried over to FIDO2`() {
        val result = buildHost(
            form(hostId = "h1", authType = AuthType.FIDO2, credentialId = "key-1"),
            stored(authType = AuthType.SSH_KEY, credentialId = "key-1", fido2Mode = Fido2Mode.PASSKEY),
            newId = "new",
        )

        assertEquals(Fido2Mode.HARDWARE_KEY, result.fido2Mode)
    }

    @Test
    fun `switching away from FIDO2 clears the mode`() {
        for (authType in listOf(AuthType.PASSWORD, AuthType.SSH_KEY, AuthType.CERTIFICATE)) {
            val result = buildHost(
                form(hostId = "h1", authType = authType),
                stored(authType = AuthType.FIDO2, fido2Mode = Fido2Mode.PASSKEY),
                newId = "new",
            )

            assertNull(result.fido2Mode, "fido2Mode for $authType")
        }
    }

    @Test
    fun `new non FIDO2 host has no mode`() {
        val result = buildHost(form(authType = AuthType.SSH_KEY), stored = null, newId = "fresh-id")

        assertNull(result.fido2Mode)
    }
}
