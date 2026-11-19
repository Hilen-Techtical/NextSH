// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import com.jediterm.terminal.TtyConnector
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch

/**
 * A relaxed [TtyConnector] mock that behaves like a live shell: `read` blocks until `close()` and
 * then reports the end of the stream (-1). A plain relaxed mock answers 0 to `read`, which JediTerm
 * takes for an end of stream: the terminal session would then report the shell as exited
 * (`ComposeTerminalSession.onShellExited`) and the manager would end and dispose the pane, which a
 * real connector never does while its shell lives.
 */
internal fun liveShellConnector(): TtyConnector {
    val closed = CountDownLatch(1)
    val connector = mockk<TtyConnector>(relaxed = true)
    every { connector.read(any(), any(), any()) } answers {
        closed.await()
        -1
    }
    every { connector.close() } answers { closed.countDown() }
    return connector
}
