// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.Signal
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
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
 *
 * The incoming stream is also scanned for OSC 52 (remote clipboard write)
 * sequences before JediTerm sees it: they are stripped and the decoded text is
 * passed to [onRemoteClipboardWrite], see [Osc52Filter].
 */
class SshTtyConnector(
    private val shell: Session.Shell,
    onRemoteClipboardWrite: (String) -> Unit = {},
) : TtyConnector, ShellEndInfo {

    private val reader = InputStreamReader(shell.inputStream, StandardCharsets.UTF_8)
    private val output = shell.outputStream
    private val connected = AtomicBoolean(true)
    private val osc52Filter = Osc52Filter(onRemoteClipboardWrite)

    // Raw (unfiltered) characters read from the shell, reused across calls.
    private var rawBuf = CharArray(0)

    // Overflow for the degenerate case where the caller's buffer is no larger
    // than the characters the filter holds back (see [read]). Never used with
    // a realistic read size.
    private val spill = CharArray(Osc52Filter.MAX_HELD_CHARS + 1)
    private var spillStart = 0
    private var spillEnd = 0

    override fun init(q: com.jediterm.terminal.Questioner?): Boolean = true

    @Volatile
    private var closedByUs = false

    override val closedLocally: Boolean get() = closedByUs

    override fun exitCode(): Int? {
        val command = shell as? Session.Command ?: return null
        // The stream ends at CHANNEL_EOF, but the server sends exit-status AFTER it and before CLOSE (SSHJ
        // fills it in on the transport thread): wait for the channel to close, bounded. Called from the
        // emulator thread, never from the UI.
        try {
            command.join(EXIT_STATUS_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Timeout or broken transport: read what is there.
        }
        command.exitStatus?.let { return it }
        val signal = command.exitSignal ?: return null
        // Shell convention: a process killed by signal N reports 128 + N.
        return SIGNAL_NUMBERS[signal]?.let { 128 + it }
    }

    override fun close() {
        closedByUs = true
        if (!connected.compareAndSet(true, false)) return
        try { shell.close() } catch (_: Exception) {}
    }

    override fun getName(): String = "SSH"

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (spillEnd > spillStart) return drainSpill(buf, offset, length)
        if (rawBuf.size < length) rawBuf = CharArray(length)
        while (true) {
            // The filter may release up to heldCount characters it kept from the
            // previous chunk, so read that much less to keep the output within
            // `length`.
            val room = length - osc52Filter.heldCount
            val n = reader.read(rawBuf, 0, if (room >= 1) room else 1)
            // End of stream: characters the filter still holds (an unfinished
            // OSC 52 prefix or payload) are dropped, as JediTerm would never
            // have rendered an unterminated sequence either.
            if (n < 0) return -1
            if (n == 0) continue
            if (room >= 1) {
                val produced = osc52Filter.filter(rawBuf, 0, n, buf, offset)
                if (produced > 0) {
                    downgradeAltScreenMode(buf, offset, produced)
                    return produced
                }
            } else {
                val produced = osc52Filter.filter(rawBuf, 0, n, spill, 0)
                if (produced > 0) {
                    spillStart = 0
                    spillEnd = produced
                    return drainSpill(buf, offset, length)
                }
            }
            // The whole chunk was swallowed by an OSC 52 sequence (or held
            // back as a prefix candidate): never report 0, read again.
        }
    }

    private fun drainSpill(buf: CharArray, offset: Int, length: Int): Int {
        val count = minOf(length, spillEnd - spillStart)
        System.arraycopy(spill, spillStart, buf, offset, count)
        spillStart += count
        downgradeAltScreenMode(buf, offset, count)
        return count
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

    override fun ready(): Boolean = spillEnd > spillStart || reader.ready()

    override fun resize(size: TermSize) {
        // Never on a closed or ended channel: sshd answers a request on a channel it no longer knows by
        // dropping the whole connection ("unknown channel"), SFTP and every other channel with it. A resize
        // posted just before the end of the shell is applied up to 500 ms later (JediTerm debounce).
        if (!connected.get() || !shell.isOpen || shell.isEOF) return
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
        private const val EXIT_STATUS_WAIT_SECONDS = 2L

        // Linux signal numbers, the only ones a server reports by name.
        private val SIGNAL_NUMBERS = mapOf(
            Signal.HUP to 1, Signal.INT to 2, Signal.QUIT to 3, Signal.ILL to 4, Signal.ABRT to 6,
            Signal.FPE to 8, Signal.KILL to 9, Signal.USR1 to 10, Signal.SEGV to 11, Signal.USR2 to 12,
            Signal.PIPE to 13, Signal.ALRM to 14, Signal.TERM to 15,
        )
    }
}

/**
 * What a terminal session needs to know when the shell stream ends: whether WE closed the shell
 * (user, watchdog shutdown, app exit), in which case the end is not a normal `exit`, and the exit
 * code the server reported, when there is one.
 */
interface ShellEndInfo {
    /** True once this side closed the shell, whatever the reason. */
    val closedLocally: Boolean

    /** Exit status (or 128 + signal number) reported by the server, null when unknown. */
    fun exitCode(): Int?
}
