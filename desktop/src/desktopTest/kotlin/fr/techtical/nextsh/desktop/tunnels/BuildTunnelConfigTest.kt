// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests of the pure [buildTunnelConfig] builder: an edit must start from
 * the stored tunnel so the fields the Desktop form does not show (today
 * `isFavorite`) are not reset, which would win the CRDT sync and reach Android.
 */
class BuildTunnelConfigTest {

    private fun form(
        tunnelId: String? = null,
        label: String = "Web",
        hostId: String = "h1",
        type: TunnelType = TunnelType.LOCAL_FORWARD,
        localPort: String = "8080",
        remoteHost: String = "localhost",
        remotePort: String = "80",
        autoStart: Boolean = false,
        openBrowserOnConnect: Boolean = false,
        keepAliveAfterBrowserClose: Boolean = true,
    ) = TunnelConfigState(
        tunnelId = tunnelId,
        label = label,
        hostId = hostId,
        type = type,
        localPort = localPort,
        remoteHost = remoteHost,
        remotePort = remotePort,
        autoStart = autoStart,
        openBrowserOnConnect = openBrowserOnConnect,
        keepAliveAfterBrowserClose = keepAliveAfterBrowserClose,
    )

    private fun stored(
        id: String = "t1",
        isFavorite: Boolean = false,
        openBrowserOnConnect: Boolean = false,
        keepAliveAfterBrowserClose: Boolean = false,
    ) = TunnelConfig(
        id = id,
        label = "Old label",
        hostId = "old-host",
        type = TunnelType.REMOTE_FORWARD,
        localPort = 1111,
        remoteHost = "old.example",
        remotePort = 2222,
        autoStart = true,
        openBrowserOnConnect = openBrowserOnConnect,
        keepAliveAfterBrowserClose = keepAliveAfterBrowserClose,
        isFavorite = isFavorite,
    )

    // -- Stored fields preserved ---------------------------------------------

    @Test
    fun `edit keeps the stored favorite flag`() {
        val result = buildTunnelConfig(form(tunnelId = "t1"), stored(isFavorite = true), newId = "new")

        assertTrue(result.isFavorite)
    }

    @Test
    fun `edit keeps a non-favorite flag unchanged`() {
        val result = buildTunnelConfig(form(tunnelId = "t1"), stored(isFavorite = false), newId = "new")

        assertFalse(result.isFavorite)
    }

    @Test
    fun `edit keeps the favorite flag when the type switches to SOCKS`() {
        val result = buildTunnelConfig(
            form(tunnelId = "t1", type = TunnelType.DYNAMIC_SOCKS5),
            stored(isFavorite = true),
            newId = "new",
        )

        assertTrue(result.isFavorite)
        assertEquals(TunnelType.DYNAMIC_SOCKS5, result.type)
    }

    // -- Form fields applied -------------------------------------------------

    @Test
    fun `edit overwrites every field the form owns and trims the strings`() {
        val result = buildTunnelConfig(
            form(
                tunnelId = "t1",
                label = "  Intranet  ",
                hostId = "h42",
                type = TunnelType.LOCAL_FORWARD,
                localPort = "9000",
                remoteHost = "  db.internal  ",
                remotePort = "5432",
                autoStart = false,
                openBrowserOnConnect = true,
                keepAliveAfterBrowserClose = true,
            ),
            stored(),
            newId = "new",
        )

        assertEquals(
            TunnelConfig(
                id = "t1",
                label = "Intranet",
                hostId = "h42",
                type = TunnelType.LOCAL_FORWARD,
                localPort = 9000,
                remoteHost = "db.internal",
                remotePort = 5432,
                autoStart = false,
                openBrowserOnConnect = true,
                keepAliveAfterBrowserClose = true,
                isFavorite = false,
            ),
            result,
        )
    }

    @Test
    fun `SOCKS clears the remote endpoint and ignores a blank remote port`() {
        val result = buildTunnelConfig(
            form(
                tunnelId = "t1",
                type = TunnelType.DYNAMIC_SOCKS5,
                remoteHost = "ignored.example",
                remotePort = "",
                openBrowserOnConnect = true,
                keepAliveAfterBrowserClose = true,
            ),
            stored(),
            newId = "new",
        )

        assertEquals("", result.remoteHost)
        assertEquals(0, result.remotePort)
        assertFalse(result.openBrowserOnConnect)
        assertFalse(result.keepAliveAfterBrowserClose)
    }

    @Test
    fun `non LOCAL_FORWARD types force the browser flags to false`() {
        for (type in listOf(TunnelType.REMOTE_FORWARD, TunnelType.DYNAMIC_SOCKS5)) {
            val result = buildTunnelConfig(
                form(type = type, openBrowserOnConnect = true, keepAliveAfterBrowserClose = true),
                stored = null,
                newId = "new",
            )

            assertFalse(result.openBrowserOnConnect, "openBrowserOnConnect for $type")
            assertFalse(result.keepAliveAfterBrowserClose, "keepAliveAfterBrowserClose for $type")
        }
    }

    @Test
    fun `edit to REMOTE_FORWARD clears browser flags that were set on the stored tunnel`() {
        val result = buildTunnelConfig(
            form(
                tunnelId = "t1",
                type = TunnelType.REMOTE_FORWARD,
                openBrowserOnConnect = true,
                keepAliveAfterBrowserClose = true,
            ),
            stored(openBrowserOnConnect = true, keepAliveAfterBrowserClose = true),
            newId = "new",
        )

        assertFalse(result.openBrowserOnConnect)
        assertFalse(result.keepAliveAfterBrowserClose)
    }

    @Test
    fun `LOCAL_FORWARD passes the browser flags through`() {
        val result = buildTunnelConfig(
            form(type = TunnelType.LOCAL_FORWARD, openBrowserOnConnect = true, keepAliveAfterBrowserClose = false),
            stored = null,
            newId = "new",
        )

        assertTrue(result.openBrowserOnConnect)
        assertFalse(result.keepAliveAfterBrowserClose)
    }

    // -- Id ------------------------------------------------------------------

    @Test
    fun `edit keeps the stored id and ignores the new id`() {
        val result = buildTunnelConfig(form(tunnelId = "t1"), stored(id = "t1"), newId = "should-not-be-used")

        assertEquals("t1", result.id)
    }

    @Test
    fun `new tunnel takes the new id`() {
        val result = buildTunnelConfig(form(), stored = null, newId = "fresh-id")

        assertEquals("fresh-id", result.id)
    }

    // -- New item = historical behaviour -------------------------------------

    @Test
    fun `new tunnel equals the object the previous code built from the form`() {
        val result = buildTunnelConfig(
            form(
                label = " Web ",
                hostId = "h1",
                type = TunnelType.LOCAL_FORWARD,
                localPort = "8080",
                remoteHost = " localhost ",
                remotePort = "80",
                autoStart = true,
                openBrowserOnConnect = true,
                keepAliveAfterBrowserClose = false,
            ),
            stored = null,
            newId = "fresh-id",
        )

        // Model defaults for everything the form does not own (isFavorite = false).
        assertEquals(
            TunnelConfig(
                id = "fresh-id",
                label = "Web",
                hostId = "h1",
                type = TunnelType.LOCAL_FORWARD,
                localPort = 8080,
                remoteHost = "localhost",
                remotePort = 80,
                autoStart = true,
                openBrowserOnConnect = true,
                keepAliveAfterBrowserClose = false,
            ),
            result,
        )
    }
}
