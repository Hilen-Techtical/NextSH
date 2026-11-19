// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `shutdown()` (the watchdog's way to end a dead session) must close the shell THROUGH the connector, so the
 * terminal sees the end of its stream as ours and does not report a remote `exit`.
 */
class DesktopSshTerminalSessionShutdownTest {

    @Test
    fun `shutdown closes the shell through the connector and marks the end as ours`() {
        val shell = mockk<Session.Shell>(relaxed = true)
        every { shell.inputStream } returns ByteArrayInputStream(ByteArray(0))
        every { shell.outputStream } returns ByteArrayOutputStream()
        var exits = 0
        val terminal = DesktopSshTerminalSession(
            sshClient = mockk<SSHClient>(relaxed = true),
            session = mockk<Session>(relaxed = true),
            shell = shell,
            onExit = { exits++ },
        )
        assertFalse(terminal.connector.closedLocally)

        terminal.shutdown()

        assertTrue(terminal.connector.closedLocally)
        verify { shell.close() }
        assertEquals(1, exits)
    }
}
