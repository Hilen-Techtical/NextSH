// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalModelListener
import com.jediterm.terminal.model.TerminalTextBuffer
import fr.techtical.nextsh.desktop.sessions.TechticalTerminalSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless JediTerm session, a Compose-friendly drop-in replacement for
 * `com.jediterm.terminal.ui.JediTermWidget` that **only owns the data model
 * and parser**, leaving rendering and input to a Compose `Composable` layer.
 *
 * This holder assembles the four core components JediTerm needs to operate
 * a live terminal:
 *
 *  1. [TerminalTextBuffer]: screen + scrollback storage. Mutated by the
 *     emulator thread; read by the renderer Composable under [textBuffer.lock].
 *  2. [JediTerminal]: `Terminal` interface impl, central state machine
 *     (cursor, scroll regions, modes, key encoding).
 *  3. [ComposeTerminalDisplay]: our impl of `TerminalDisplay` that exposes
 *     observable state (cursor, alt screen, blink, render trigger) for the
 *     renderer Composable to collectAsState on.
 *  4. [TerminalStarter]: drives the parser (`JediEmulator`) loop on a
 *     dedicated worker thread, reads from [TtyConnector], writes user input
 *     back via `sendBytes`/`sendString`.
 *
 * Lifecycle parity with `JediTermWidget`:
 *  - construction → [start] (spawns emulator thread)
 *  - send keystrokes → [sendBytes] / [sendString] (matching `widget.ttyConnector.write`)
 *  - resize → [resize] (matches `JediTerminal.resize` + `TtyConnector.resize`)
 *  - dispose → [close] (stops emulator + executors, closes connector)
 *
 * Theme switching is handled by the existing [TechticalTerminalSettings] (the
 * mutable palette is shared with the renderer through `defaultStyle`).
 *
 * @param sessionId stable id of the SSH session this terminal binds to;
 *                  used to name worker threads for diagnostics.
 * @param connector the [TtyConnector] (typically `SshTtyConnector`) that
 *                  bridges the SSH shell. **Not** owned by this class: the
 *                  caller decides when to close the SSH client.
 * @param settings  shared [TechticalTerminalSettings], palette and default
 *                  style. Live theme swaps propagate via its
 *                  supplier-backed `TerminalColor` instances (see that
 *                  class's doc), no session recreation needed.
 * @param initialColumns initial terminal width in characters; the renderer
 *                       calls [resize] once it knows its real Compose-measured size.
 * @param initialRows    initial terminal height in characters.
 * @param historyLines   scrollback line capacity; defaults to 5000 (same as
 *                       JediTermWidget's internal default).
 */
class ComposeTerminalSession(
    val sessionId: String,
    val connector: TtyConnector,
    val settings: TechticalTerminalSettings,
    initialColumns: Int = 80,
    initialRows: Int = 24,
    historyLines: Int = 5000,
) {
    val styleState: StyleState = StyleState().apply {
        // setDefaultStyle adopts our palette-backed TextStyle so every
        // "default-styled" cell resolves its colour at paint time through
        // the supplier: theme swaps then re-tint past output without
        // recreating the buffer. We call the public override `getDefaultStyle()`
        // explicitly to avoid Kotlin property-resolution shadowing the
        // private backing field of the same name in TechticalTerminalSettings.
        setDefaultStyle(settings.getDefaultStyle())
    }

    val textBuffer: TerminalTextBuffer = TerminalTextBuffer(
        initialColumns,
        initialRows,
        styleState,
        historyLines,
        /* textProcessing = */ null,
    )

    val display: ComposeTerminalDisplay = ComposeTerminalDisplay()

    val terminal: JediTerminal = JediTerminal(display, textBuffer, styleState)

    private val executorMgr = ComposeExecutorServiceManager(sessionId)

    private val typeAheadManager = TerminalTypeAheadManager(NoOpTypeAheadModel())

    private val starter = TerminalStarter(
        terminal,
        connector,
        TtyBasedArrayDataStream(connector) { typeAheadManager.onTerminalStateChanged() },
        typeAheadManager,
        executorMgr,
    )

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * Optional broadcast hook, installed by [fr.techtical.nextsh.desktop.sessions.DesktopSessionManager]
     * once at session creation and kept for the session's whole lifetime. The
     * manager's handler decides on every invocation, from the live tab tree,
     * whether this session's tab is in broadcast ("synchronize-panes",
     * Ctrl+Shift+B) mode and who the sibling panes are, so a pane that
     * connects (or reconnects, which mints a new session id) AFTER the toggle
     * relays just like the panes that were already there.
     * Invoked with the raw bytes of every USER-originated
     * send, i.e. every [sendBytes] / [sendString] call made with
     * `userInput = true`, which is exactly how [ComposeTerminalRenderer]
     * forwards real keystrokes and pastes (see its `session.sendBytes(...,
     * userInput = true)` call sites). This is the only place left where
     * "did a human just type this" is still known once the bytes leave the
     * renderer.
     *
     * The manager's relay calls the sibling panes' [sendBytes] with
     * `userInput = false`, since the hook only fires on `userInput = true`,
     * a mirrored delivery can never itself trigger another mirror (anti-loop).
     * A session whose tab is not broadcasting pays one set-emptiness check per
     * keystroke inside the manager's handler, no tree walk. Cleared in [close].
     *
     * **Contract for callers of [sendBytes] / [sendString].** `userInput =
     * true` means "a human typed or pasted this in THIS pane": it is the flag
     * that opts the bytes into the broadcast relay. Machine-generated traffic
     * that merely happens to be triggered by a human gesture must use
     * `userInput = false` when it is only meaningful for the pane it came
     * from. The canonical case is xterm mouse reporting: reports carry cell
     * coordinates that mean nothing in a sibling pane, and relaying them would
     * let a compromised remote host that enables mouse tracking inject bytes
     * into the OTHER hosts of the broadcast group. [ComposeTerminalRenderer]
     * therefore sends them with `userInput = false` (see the CONTRACT comment
     * at its mouse-reporting call site).
     */
    @Volatile
    var onUserInput: ((ByteArray) -> Unit)? = null

    /**
     * Cursor position (1-based, [JediTerminal] convention) captured on the
     * main buffer at the alt-screen *entry* edge, restored at the *exit*
     * edge. Replaces the cursor save/restore that mode 1049 performed before
     * the 1049→1047 downgrade in `SshTtyConnector`: with bare 1047 the cursor
     * keeps its in-alt position on exit (typically parked at the bottom row
     * by `endwin()`), so on a barely-filled screen the next prompt landed at
     * the very bottom with a blank gap above it. Restoring the entry position
     * here is safe for apps that reposition explicitly after the exit
     * sequence: their move arrives later in the stream and overrides ours.
     */
    private var preAltCursor: Pair<Int, Int>? = null

    /** Edge detector: JediTerm re-invokes the display callback on every
     *  `CSI ?1047 h/l`, even without an actual transition. */
    private var inAltScreen = false

    init {
        // Bridge buffer mutations to the Compose render trigger. Every call
        // to scrollArea / writeString / addLine etc. on the buffer fires this
        // listener on the emulator thread, which bumps the StateFlow counter
        // and triggers Compose recomposition of the rendered Canvas.
        textBuffer.addModelListener(TerminalModelListener {
            display.forceInvalidate()
        })
        // Alt-screen transitions (emulator thread, synchronous with the
        // sequence parse, before the next bytes are processed):
        //  - entry: capture the main-buffer cursor position (see preAltCursor);
        //  - exit:  reset the SGR style to the palette default (an app that
        //    exits with an attribute still active, e.g. vim leaving underline
        //    on, would otherwise bleed it into the next shell prompt), then
        //    restore the captured cursor position. Both mirror what mode 1049
        //    did before the SshTtyConnector downgrade, minus JediTerm 3.40's
        //    buggy StoredCursor path.
        display.onAlternateScreenChanged = { inAlt ->
            if (inAlt != inAltScreen) {
                inAltScreen = inAlt
                if (inAlt) {
                    preAltCursor = terminal.cursorX to terminal.cursorY
                } else {
                    styleState.reset()
                    preAltCursor?.let { (x, y) ->
                        terminal.cursorPosition(
                            x.coerceIn(1, textBuffer.width),
                            y.coerceIn(1, textBuffer.height),
                        )
                    }
                    preAltCursor = null
                }
            }
        }
    }

    /**
     * Spawns the emulator parser thread (via the unbounded executor) and
     * blocks until the first prompt arrives. Idempotent: calling [start]
     * twice is a no-op. Equivalent to `JediTermWidget.start()`.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        executorMgr.unboundedExecutorService.submit {
            try {
                starter.start()
            } catch (t: Throwable) {
                // Emulator thread death is normal at session shutdown
                // (channel closed → IOException). Suppressing here matches
                // JediTerm's TerminalPanel behaviour: log if needed but
                // don't propagate.
            }
        }
    }

    /**
     * Sends raw bytes to the shell (typically the result of encoding a
     * key event via [JediTerminal.getCodeForKey]). The second argument
     * tells JediTerm whether the bytes originated from user input: this
     * matters for type-ahead and bracketed paste, but with type-ahead
     * disabled it's purely informational.
     */
    fun sendBytes(bytes: ByteArray, userInput: Boolean = true) {
        if (closed.get()) return
        starter.sendBytes(bytes, userInput)
        if (userInput) onUserInput?.invoke(bytes)
    }

    fun sendString(text: String, userInput: Boolean = true) {
        if (closed.get()) return
        starter.sendString(text, userInput)
        if (userInput) onUserInput?.invoke(text.toByteArray())
    }

    /**
     * Notifies JediTerm that the renderer Composable has measured a new
     * window size. Goes through `TerminalStarter.postResize` (not
     * `JediTerminal.resize` directly) so the emulator debounces the resize
     * before notifying the PTY: ConPTY on Windows breaks if it receives
     * back-to-back resizes during a drag.
     */
    fun resize(columns: Int, rows: Int, origin: RequestOrigin = RequestOrigin.User) {
        if (closed.get()) return
        if (columns <= 0 || rows <= 0) return
        starter.postResize(TermSize(columns, rows), origin)
    }

    /**
     * Tears down the emulator, the executors, and the [TtyConnector].
     * Idempotent. Safe to call from any thread.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        onUserInput = null
        runCatching { starter.close() }
        runCatching { connector.close() }
        runCatching { executorMgr.shutdownWhenAllExecuted() }
        // Cancel the display's throttle coroutine scope so the per-tab
        // daemon thread doesn't leak.
        runCatching { display.shutdown() }
    }
}
