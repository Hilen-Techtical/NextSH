// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges an SSHJ interactive shell to JediTerm's [TtyConnector].
 * JediTerm drives its own reader thread off [read] and pushes user keystrokes
 * through [write]; we just hand it the shell streams.
 *
 * [close] only tears down the **shell channel**: it does NOT terminate the
 * parent `SSHClient`. That's intentional: in a Terminal+SFTP split the
 * SFTP pane uses the same `SSHClient`, so disposing the terminal widget
 * must not kill SFTP. Full session teardown (shell + `SSHClient`) is done
 * explicitly via [DesktopSshTerminalSession.shutdown].
 */
class SshTtyConnector(
    private val shell: Session.Shell,
) : TtyConnector {

    private val reader = InputStreamReader(shell.inputStream, StandardCharsets.UTF_8)
    private val output = shell.outputStream
    private val connected = AtomicBoolean(true)

    override fun init(q: com.jediterm.terminal.Questioner?): Boolean = true

    override fun close() {
        if (!connected.compareAndSet(true, false)) return
        try { shell.close() } catch (_: Exception) {}
    }

    override fun getName(): String = "SSH"

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val n = reader.read(buf, offset, length)
        if (n > 0) downgradeAltScreenMode(buf, offset, n)
        return n
    }

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun write(string: String) {
        write(string.toByteArray(StandardCharsets.UTF_8))
    }

    override fun isConnected(): Boolean = connected.get()

    override fun waitFor(): Int = 0

    override fun ready(): Boolean = reader.ready()

    override fun resize(size: TermSize) {
        try {
            shell.changeWindowDimensions(size.columns, size.rows, 0, 0)
        } catch (_: Exception) {
            // Server may refuse: non-fatal.
        }
    }

    /**
     * Rewrites DEC private mode **1049** (save-cursor + alternate screen) to
     * **1047** (alternate screen only) in the incoming stream, patched in place
     * (length-preserving: only the final digit `9` -> `7`).
     *
     * Why: the bundled JediTerm 3.40 mis-restores the cursor on `CSI ? 1049 l`
     * (alt-screen exit). It returns the cursor to the position saved at
     * `CSI ? 1049 h`, which: for a full-screen app launched after the main
     * buffer scrolled (nano / vim / less): is several rows too high, so the
     * next shell prompt overwrites a stale line instead of landing at the
     * bottom (bug B1). `1047` still swaps to a fresh alternate screen buffer
     * on entry, so the app gets a clean canvas.
     *
     * Bare 1047 drops 1049's cursor save/restore entirely, which is NOT
     * enough on its own: with the cursor left wherever the app parked it
     * (bottom row via `endwin()`), a barely-filled screen got its next prompt
     * at the very bottom with a blank gap above. The save/restore is
     * re-implemented cleanly (without JediTerm's buggy `StoredCursor` path)
     * at the alt-screen transition edges in `ComposeTerminalSession`: see
     * `preAltCursor` there.
     *
     * Only `CSI ? 1049 h` / `CSI ? 1049 l` are matched. A sequence split across
     * two reads (rare: observed atomic in practice) is left untouched: at worst
     * the original off-by-rows glitch shows once, never corruption.
     */
    private fun downgradeAltScreenMode(buf: CharArray, offset: Int, n: Int) {
        var i = offset
        val last = offset + n - 8 // ESC '[' '?' '1' '0' '4' '9' ('h'|'l')
        while (i <= last) {
            if (buf[i].code == ESC && buf[i + 1] == '[' && buf[i + 2] == '?' &&
                buf[i + 3] == '1' && buf[i + 4] == '0' && buf[i + 5] == '4' && buf[i + 6] == '9' &&
                (buf[i + 7] == 'h' || buf[i + 7] == 'l')
            ) {
                buf[i + 6] = '7' // 1049 -> 1047
                i += 8
            } else {
                i++
            }
        }
    }

    private companion object {
        private const val ESC = 0x1b
    }
}
