// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

import com.jediterm.core.Color
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.TerminalSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Compose-native implementation of [TerminalDisplay] consumed by JediTerm's
 * [com.jediterm.terminal.model.JediTerminal] core. The display **does not**
 * paint anything itself: it merely maintains observable state (cursor
 * position, visibility, blinking, alt-screen flag, …) and pumps an
 * invalidation token. The actual rendering is performed by a Compose
 * `Canvas` reading [com.jediterm.terminal.model.TerminalTextBuffer] under
 * its `lock()` and observing the [renderTrigger] flow + this display's
 * cursor flows.
 *
 * **Why this works.** JediTerm's `TerminalPanel` (Swing) implements the
 * same interface; the only thing it does that we don't is push pixels via
 * AWT/Graphics2D. By reproducing the *behavioural* contract (state +
 * invalidation) we get full xterm/VT100/256-color/bold/inverse/blink
 * support for free: the parser (`JediEmulator`) writes into the shared
 * `TerminalTextBuffer` and we just iterate over it for rendering.
 *
 * **Threading.** All [TerminalDisplay] callbacks fire on the JediTerm
 * emulator thread (spawned by `TerminalStarter` via the unbounded executor).
 * State writes therefore must be safe from a non-Compose thread → we use
 * [MutableStateFlow], which is thread-safe and triggers Compose
 * recomposition when collected with `collectAsState()`.
 *
 * No-op methods (`beep`, `getSelection`) are valid: the contract allows
 * null/empty returns, the parser handles the absence gracefully. Selection
 * is implemented at the Compose layer (mouse pointerInput) and passed to
 * the buffer via `TerminalTextBuffer` directly, not through this interface.
 *
 * **Mouse reporting.** [terminalMouseModeSet] / [setMouseFormat] are called
 * by `JediTerminal` whenever the remote app toggles xterm mouse-tracking
 * DECSET/DECRST sequences (1000/1002/1003 for the mode, 1006/1015/1005 for
 * the format). We store the latest values in [mouseMode] / [mouseFormat]:
 * [ComposeTerminalRenderer]'s pointer handler reads them on every event to
 * decide whether to forward clicks/drag/wheel to the shell as xterm mouse
 * reports (via [MouseReportEncoder]) instead of the local
 * selection/scrollback behaviour.
 */
class ComposeTerminalDisplay : TerminalDisplay {

    // ── Observable state (Compose collects via collectAsState) ──────────────

    private val _cursorPosition = MutableStateFlow(CursorPosition(x = 0, y = 0))
    val cursorPosition: StateFlow<CursorPosition> = _cursorPosition.asStateFlow()

    private val _cursorVisible = MutableStateFlow(true)
    val cursorVisible: StateFlow<Boolean> = _cursorVisible.asStateFlow()

    private val _cursorBlinking = MutableStateFlow(true)
    val cursorBlinking: StateFlow<Boolean> = _cursorBlinking.asStateFlow()

    private val _cursorShape = MutableStateFlow(CursorShape.BLINK_BLOCK)
    val cursorShape: StateFlow<CursorShape> = _cursorShape.asStateFlow()

    private val _alternateScreen = MutableStateFlow(false)
    val alternateScreen: StateFlow<Boolean> = _alternateScreen.asStateFlow()

    /**
     * Invoked on the emulator thread when the alternate-screen mode toggles
     * (`true` = entered, `false` = exited). [ComposeTerminalSession] hooks the
     * entry edge to capture the main-buffer cursor and the exit edge to reset
     * the SGR style and restore that cursor (see the 1049→1047 downgrade
     * rationale in `SshTtyConnector.downgradeAltScreenMode`). NB: JediTerm
     * re-invokes this on every `CSI ?1047 h/l`, transition or not: callers
     * must edge-detect themselves.
     */
    @Volatile
    var onAlternateScreenChanged: ((Boolean) -> Unit)? = null

    private val _windowTitle = MutableStateFlow("Terminal")
    val windowTitle: StateFlow<String> = _windowTitle.asStateFlow()

    /**
     * Current xterm mouse-tracking mode, last reported by the emulator via
     * [terminalMouseModeSet]. `MOUSE_REPORTING_NONE` (the initial value)
     * means no app has requested mouse tracking: the renderer's pointer
     * handler stays a pure pass-through in that case, so sessions that never
     * enable mouse tracking (the overwhelming majority today) see byte-for-
     * byte the same local click/drag/wheel behaviour as before this feature.
     */
    private val _mouseMode = MutableStateFlow(MouseMode.MOUSE_REPORTING_NONE)
    val mouseMode: StateFlow<MouseMode> = _mouseMode.asStateFlow()

    /**
     * Current mouse-report wire format, last reported by the emulator via
     * [setMouseFormat]. Only [MouseFormat.MOUSE_FORMAT_SGR] (mode 1006)
     * changes the encoding: every other format (the JediTerm default, plus
     * URXVT/UTF8 extended) is treated as the legacy X10 3-byte protocol by
     * [MouseReportEncoder], which covers the vast majority of real terminal
     * apps (htop, vim, tmux, mc) without needing per-format branches.
     */
    private val _mouseFormat = MutableStateFlow(MouseFormat.MOUSE_FORMAT_XTERM)
    val mouseFormat: StateFlow<MouseFormat> = _mouseFormat.asStateFlow()

    /**
     * Monotonic counter incremented every time the buffer is mutated by the
     * emulator. Compose collects this as state and triggers a re-render of
     * the Canvas. Using a counter rather than a Boolean toggle guarantees
     * the StateFlow always emits a distinct value even on rapid back-to-back
     * mutations.
     *
     * **Throttled.** Bursty shells (`top`, `htop`, `tail -f`, scrolling
     * `cat large_file`) can fire `forceInvalidate()` hundreds of times per
     * second: pushing each one straight into the StateFlow makes Compose
     * recompose the whole Canvas hundreds of times, with measurable input
     * lag and dropped frames on any non-trivial buffer size. We instead
     * coalesce calls onto a 16 ms (≈60 fps) tick: each
     * [forceInvalidate] increments [pendingCount] and arms a one-shot
     * coroutine; the coroutine waits 16 ms (during which any further
     * invalidations just bump the counter), then publishes the *latest*
     * value. The user perceives no latency (16 ms is below the perceptual
     * threshold), but the Canvas paints once per frame instead of once per
     * shell write.
     */
    private val _renderTrigger = MutableStateFlow(0L)
    val renderTrigger: StateFlow<Long> = _renderTrigger.asStateFlow()

    private val pendingCount = AtomicLong(0)
    private val flushScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val flushJobRef = AtomicReference<Job?>(null)
    // Optimisation E: 33 ms ≈ 30 fps instead of 16 ms ≈ 60 fps.
    // Terminal text output is not animation: 30 fps is imperceptible for
    // shell I/O and halves the number of Canvas re-draws per second.
    // This doubles the coalescing window for bursty output (top, cat large file)
    // so many more invalidations are squashed into a single frame, reducing
    // per-second Skia layout work proportionally.
    private val frameThrottleMs = 33L

    /**
     * Selection accessor used by the JediTerm core when an OSC 52 (clipboard
     * read) request is received. The renderer Composable owns the actual
     * selection state and pushes it here via [updateSelection]. Default
     * `null` means "no selection": the parser handles it gracefully.
     */
    @Volatile private var currentSelection: TerminalSelection? = null

    fun updateSelection(selection: TerminalSelection?) { currentSelection = selection }

    // ── TerminalDisplay impl ────────────────────────────────────────────────

    override fun setCursor(x: Int, y: Int) {
        _cursorPosition.value = CursorPosition(x = x, y = y)
        invalidate()
    }

    override fun setCursorShape(cursorShape: CursorShape) {
        _cursorShape.value = cursorShape
    }

    override fun beep() {
        // System beep is intrusive; intentionally suppressed. Could be wired
        // to a Compose Snackbar / icon flash in a future polish phase.
    }

    override fun onResize(newTermSize: TermSize, origin: RequestOrigin) {
        // Resize ack handled at the renderer level (it's the renderer that
        // measured the new size and called terminal.resize()). This callback
        // fires when the resize flow originates from the emulator (rare).
        invalidate()
    }

    override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) {
        // The actual scroll happens inside TerminalTextBuffer; we just need
        // to invalidate the renderer.
        invalidate()
    }

    override fun historyBufferLineCountChanged() {
        // Drives the scrollback bar in Compose (future phase). For now, just
        // invalidate so the visible area is repainted in case the emulator
        // shifted lines.
        invalidate()
    }

    override fun setCursorVisible(isCursorVisible: Boolean) {
        _cursorVisible.value = isCursorVisible
    }

    override fun useAlternateScreenBuffer(use: Boolean) {
        _alternateScreen.value = use
        onAlternateScreenChanged?.invoke(use)
        invalidate()
    }

    override fun setBlinkingCursor(isCursorBlinking: Boolean) {
        _cursorBlinking.value = isCursorBlinking
    }

    override fun getWindowTitle(): String = _windowTitle.value

    override fun setWindowTitle(windowTitle: String) {
        _windowTitle.value = windowTitle
    }

    override fun getSelection(): TerminalSelection? = currentSelection

    override fun terminalMouseModeSet(mouseMode: MouseMode) {
        _mouseMode.value = mouseMode
    }

    override fun setMouseFormat(mouseFormat: MouseFormat) {
        _mouseFormat.value = mouseFormat
    }

    override fun ambiguousCharsAreDoubleWidth(): Boolean = false

    override fun setBracketedPasteMode(enabled: Boolean) {
        // Hook for future bracketed-paste support. Storing the flag is enough;
        // the renderer's paste handler will consult it when the user pastes.
        bracketedPaste = enabled
    }

    override fun getWindowForeground(): Color? = null

    override fun getWindowBackground(): Color? = null

    @Volatile var bracketedPaste: Boolean = false
        private set

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Coalesces invalidations onto a ~60 fps tick. Called from the JediTerm
     * emulator thread (which is itself driven by `TerminalStarter`'s worker
     * pool). Thread-safe via [AtomicReference.compareAndSet]: si deux threads
     * observent simultanément `existing == null`, un seul gagnera le CAS ;
     * l'autre annulera son job superflu. Élimine la double-recomposition
     * ~16 ms décrite dans P4 du diagnostic.
     */
    private fun invalidate() {
        pendingCount.incrementAndGet()
        // Already a flush scheduled? Just bump the counter and let the
        // pending coroutine pick up the latest value when it fires.
        val existing = flushJobRef.get()
        if (existing != null && existing.isActive) return
        val newJob = flushScope.launch {
            delay(frameThrottleMs)
            _renderTrigger.value = pendingCount.get()
        }
        if (!flushJobRef.compareAndSet(existing, newJob)) {
            // Another thread won the race; cancel our job and let theirs proceed.
            newJob.cancel()
        }
    }

    /**
     * Force-invalidate from outside (e.g. when the renderer subscribes a new
     * `TerminalModelListener` and wants an initial paint).
     */
    fun forceInvalidate() = invalidate()

    /**
     * Cancels the throttling scope when the session is being torn down.
     * Otherwise the flushScope leaks one daemon coroutine per closed tab.
     */
    fun shutdown() {
        runCatching { flushScope.cancel() }
    }

    data class CursorPosition(val x: Int, val y: Int)
}
