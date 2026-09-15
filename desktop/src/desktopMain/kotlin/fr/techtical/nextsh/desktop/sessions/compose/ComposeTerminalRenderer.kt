// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
// Aliased: androidx.compose.ui.input.key already exports identically-named
// isCtrlPressed/isMetaPressed/isShiftPressed extension properties (on
// KeyEvent, not PointerKeyboardModifiers) elsewhere in this file: Kotlin
// resolves same-named extensions by receiver type, but aliasing removes any
// ambiguity for the reader (and for the compiler on older toolchains).
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as isPointerMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jediterm.core.compatibility.Point
import java.awt.event.KeyEvent as AwtKeyEvent
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle as JediTextStyle
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.SelectionUtil
import com.jediterm.terminal.model.TerminalLine
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.TerminalThemePalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * Compose-native renderer for a [ComposeTerminalSession]. **No SwingPanel,
 * no AWT heavyweight**: the entire terminal is drawn on a Compose [Canvas]
 * over the Skia compositor, eliminating the GPU framebuffer flash and
 * "see-behind" bugs that plague the Swing JediTermWidget on Windows.
 *
 * **Pipeline per frame.**
 *  1. Read the (cell-width, cell-height) from a [TextMeasurer] using a
 *     monospace font; compute (columns, rows) from the available pixel
 *     size and ask [ComposeTerminalSession.resize] if it changed.
 *  2. Lock [com.jediterm.terminal.model.TerminalTextBuffer] (the emulator
 *     thread mutates it on every byte received).
 *  3. Walk visible rows; each row is processed by [lineToRuns] which produces
 *     a list of style-uniform [RunInfo] values. Those are aggregated into one
 *     [AnnotatedString] per line and drawn with a single [drawText] call
 *     (background rects first, then the glyph layer on top).
 *  4. Draw the cursor (only when the screen is at the bottom, scrollback
 *     view hides it).
 *  5. Overlay the selection rectangle if a drag is active.
 *
 * **Performance.** Building one [AnnotatedString] per line and calling
 * [TextMeasurer.measure] once per line (instead of once per styled run) cuts
 * the number of Skia layout calls from ~3×rows to rows. A [TextLayoutCache]
 * further avoids re-measuring lines whose content hasn't changed between
 * frames, e.g. the static prompt line while `top` scrolls the lower part.
 *
 * **Colour resolution.** Indexed ANSI colours go through
 * [TerminalThemePalette.ansi]; supplier-backed default fg/bg are resolved
 * via `TerminalColor.toColor()` which consults `TechticalTerminalSettings`'s
 * atomic palette ref (so theme switches re-tint past output).
 *
 * **Input.**
 *  - Keyboard: `Modifier.onKeyEvent` consumes [androidx.compose.ui.input.key.KeyEvent]s.
 *    Special keys (arrows, F-keys, Tab, Escape, modifier-keystrokes) go
 *    through `JediTerminal.getCodeForKey(vk, modifiers)` which produces
 *    the right xterm escape sequence under the current cursor-key /
 *    keypad mode. Printable characters that `getCodeForKey` doesn't
 *    consume fall back to `sendString`, using `event.utf16CodePoint` to
 *    resolve composed characters (Shift+key on AZERTY, dead keys, …)
 *    rather than the AWT keyChar, which returns CHAR_UNDEFINED (0xFFFF)
 *    for composed input in Compose 1.8.
 *  - Selection: drag gestures on the Canvas update an in-component
 *    [TerminalSelection]; on drag end we keep the selection visible until
 *    the user clicks elsewhere or copies.
 *  - Clipboard: `Ctrl+Shift+C` copies the current selection,
 *    `Ctrl+Shift+V` pastes from the system clipboard. `Ctrl+C` always
 *    sends SIGINT to the shell (standard terminal convention, selects
 *    Ctrl+Shift for copy/paste).
 *  - Scroll: mouse wheel scrolls the viewport up into the scrollback
 *    history when the buffer has lines available; `Page Up`/`Page Down`
 *    do the same in larger steps.
 *
 * **Recomposition.** [ComposeTerminalDisplay.renderTrigger] increments on
 * every emulator change; `collectAsState` here makes Compose re-render the
 * Canvas. The `selection` and `scrollOffset` are local Compose state and
 * trigger their own recompositions.
 *
 * **Thread safety.** Compose runs the canvas DrawScope lambda on the
 * Compose render thread; the emulator runs on its dedicated worker thread.
 * `TerminalTextBuffer.lock()` ensures the snapshot we draw is consistent.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ComposeTerminalRenderer(
    session: ComposeTerminalSession,
    modifier: Modifier = Modifier,
    // Optional hoisted requester so the caller can re-grab keyboard focus on
    // events this composable can't see (e.g. a modal picker DialogWindow
    // closing). Defaults to an internal one when the caller doesn't care.
    externalFocusRequester: FocusRequester? = null,
    // Live font size, sourced by the caller (TerminalScreen) from
    // DesktopSettingsStore.settings via collectAsState(). Unlike the theme
    // palette (which the Canvas DrawScope re-reads every frame from
    // session.settings, see below), text layout is measured with `remember`
    // at the composable level: recomputing it on a plain field mutation
    // isn't possible without a recomposition. Passing the size as a
    // composable parameter is what makes that recomposition happen: when
    // the caller's collected State changes, this parameter changes value,
    // `remember(fontSize)` below invalidates, and cellMetrics/layoutCache
    // are rebuilt for the new size: applying to an already-open session
    // with no reconnection needed.
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    val density = LocalDensity.current
    // Optimisation B: increase TextMeasurer's internal Skia layout cache from
    // the default 8 entries to 256. In a typical 80×25 terminal each distinct
    // styled line takes one slot; 256 covers ~10 screen-heights of unique lines
    // which is enough to hit on most static rows (prompt, htop header, etc.)
    // without the LRU overhead of an oversized cache.
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val clipboardManager = LocalClipboardManager.current
    val focusRequester = externalFocusRequester ?: remember { FocusRequester() }

    // Subscribe to emulator state: these flows are observed so Compose
    // recomposes when the buffer changes.
    //
    // Optimisation A: renderTrigger is read INSIDE the Canvas DrawScope (see
    // below) rather than via `by collectAsState()` at the top-level. Reading
    // state inside a DrawScope lambda causes Compose to only invalidate the
    // *drawing* phase (cheap) without triggering a full Composable
    // recomposition that would re-install gesture handlers, re-run LaunchedEffects
    // and re-evaluate every other `by collectAsState()` at this level.
    // The other cursor flows DO drive recomposition (cursor blink, visibility)
    // because their values are also read in DrawScope conditions: we keep them
    // as State references so their reads inside the lambda stay tracked.
    val renderTriggerState = session.display.renderTrigger.collectAsState()
    val cursorVisibleState = session.display.cursorVisible.collectAsState()
    val cursorBlinkingState = session.display.cursorBlinking.collectAsState()
    val cursorPosState = session.display.cursorPosition.collectAsState()
    // Cursor blink drives a LaunchedEffect, so we still need a local snapshot.
    val cursorBlinking by cursorBlinkingState

    // Cursor blink phase. Standard terminal cadence is ~530 ms (xterm,
    // gnome-terminal, iTerm2). When the emulator disables blinking (the
    // shell sent the appropriate DECSCUSR sequence), we keep the cursor
    // permanently visible. Resetting `phase = true` whenever blinking
    // turns off prevents a stale-off frame from sticking.
    var cursorBlinkPhase by remember { mutableStateOf(true) }
    LaunchedEffect(cursorBlinking) {
        if (!cursorBlinking) {
            cursorBlinkPhase = true
            return@LaunchedEffect
        }
        while (true) {
            delay(530)
            cursorBlinkPhase = !cursorBlinkPhase
        }
    }

    // Palette snapshot used only for the LaunchedEffect(palette.background)
    // cache-clear below and for the Box background colour. Palette-derived
    // draw colours (themeFg, themeCursor, themeSelectionBg) are computed
    // inside the Canvas DrawScope so their reads are scoped to the draw
    // phase and don't trigger a full recomposition.
    val palette = session.settings.currentPalette()
    val themeBg = palette.background.toComposeColor()

    // Cell metrics: measured for "M" with the chosen font / size and cached
    // by (textMeasurer, fontSizeSp, density). JetBrains Mono is monospace so
    // all glyphs share the same advance. `fontSize` is the composable
    // parameter (see its kdoc), when the caller's collected settings State
    // changes value, this composable recomposes with a new `fontSize`, the
    // `remember` key below changes, and cellMetrics is re-measured for the
    // new size. This is what makes a live Settings font-size change apply to
    // an already-open session without reconnecting.
    val fontSizeSp = fontSize
    val cellMetrics = remember(textMeasurer, fontSizeSp, density) {
        // Mesurer un sample de N caractères pour obtenir l'advance pur du
        // glyphe, sans le padding/bearing Skia que `measure(single char)`
        // accumule à chaque cellule. JediTerm Swing utilise
        // FontMetrics.charWidth('W') qui retourne l'advance ; on n'a pas
        // d'équivalent direct en Compose, mais diviser la width d'une
        // chaîne longue par sa longueur donne le même résultat à 0.01 px près.
        val sampleLength = 64
        val sample = "M".repeat(sampleLength)
        val measure = textMeasurer.measure(
            AnnotatedString(sample),
            style = ComposeTextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontSize = fontSizeSp,
            ),
        )
        CellMetrics(
            width = measure.size.width.toFloat() / sampleLength,
            height = measure.size.height.toFloat(),
        )
    }

    // Layout cache: avoids re-measuring lines that haven't changed between
    // frames. Key = stable hash of (line text, palette identity). Capacité
    // 150 = ~5× une fenêtre typique de 30 lignes, sans le surcoût de lookup
    // LRU sur 500 entrées qu'on ne réutilise jamais (top/htop invalide tout
    // à chaque refresh). Évite aussi les re-creates inutiles sur resize.
    val layoutCache = remember { TextLayoutCache(capacity = 150) }
    // Invalidate all cached layouts when the palette changes (theme swap) or
    // the font size changes. We detect palette identity via the background
    // colour as a cheap proxy: a palette change always changes the
    // background. A stale-size layout would keep drawing glyphs measured at
    // the old fontSizeSp on top of cells now advancing at the new cellW/cellH.
    LaunchedEffect(palette.background, fontSizeSp) { layoutCache.clear() }

    // Track the last-reported terminal size to avoid spamming resize().
    var lastTermSize by remember { mutableStateOf(IntSize(80, 24)) }

    // Scrollback viewport offset. 0 = bottom (live screen). Positive =
    // number of lines scrolled up into the history.
    var scrollOffset by remember { mutableStateOf(0) }

    // Alt-screen apps (nano/vim/less/htop) own their own scrolling model and
    // expose no scrollback of their own. Reset the viewport offset on every
    // alt-screen transition so the user always lands on the live bottom: entering
    // alt clears any prior history scroll, and exiting brings the main buffer
    // back to its current cursor row (instead of staying parked N lines up,
    // which would let the next shell prompt overwrite already-printed history).
    val alternateScreen by session.display.alternateScreen.collectAsState()
    LaunchedEffect(alternateScreen) {
        scrollOffset = 0
    }

    // Nombre de lignes d'historique, recalculé à chaque mutation du buffer
    // (renderTriggerState.value force la réévaluation). Lu hors DrawScope
    // (composition level) pour que le TerminalScrollbarAdapter soit à jour
    // sans dépendre de la phase de dessin : conforme à l'Optimisation A.
    // Pas de buffer.lock() ici : même convention que le chemin wheel (race
    // bénigne, l'historique ne peut que croître entre deux recompositions).
    val historyCount by remember(session.sessionId) {
        derivedStateOf {
            @Suppress("UNUSED_EXPRESSION")
            renderTriggerState.value          // subscribe recomposition aux changements buffer
            session.textBuffer.historyLinesCount
        }
    }

    // Adaptateur scrollbar : mappe l'offset terminal (ancré en bas) vers le
    // modèle ancré en haut attendu par VerticalScrollbar (v2).
    // Recréé uniquement si la session change (pas à chaque recomposition).
    val scrollbarAdapter = remember(session.sessionId) {
        TerminalScrollbarAdapter(
            historyCount = { historyCount },
            rows = { lastTermSize.height },
            getTermOffset = { scrollOffset },
            setTermOffset = { scrollOffset = it },
        )
    }

    // Active selection (in absolute buffer coordinates, where Y can be
    // negative to address scrollback lines, same convention JediTerm
    // uses internally for `TerminalSelection`).
    var selection by remember { mutableStateOf<TerminalSelection?>(null) }
    // Anchor for new drags. Captured on press; updated on every drag move.
    var dragAnchor by remember { mutableStateOf<Point?>(null) }

    // Auto-grab focus when the renderer enters the composition, and again
    // when the SSH session changes (e.g. tab switch). Without this the
    // user would have to click first to be able to type.
    LaunchedEffect(session.sessionId) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .background(themeBg)
            .focusRequester(focusRequester)
            // Grab keyboard focus on pointer-press (Initial pass, before any
            // child gesture detector) so a click that switches the active split
            // pane routes the very next keystroke / paste to THIS pane.
            // detectDragGestures only focuses on drag-start (after the touch
            // slop), so a plain tap or a click with a tiny movement would
            // otherwise leave focus on the previously-active pane: the
            // "click then paste/arrows go to the wrong terminal" latency (B2).
            .pointerInput(session.sessionId) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.any { it.pressed && !it.previousPressed }) {
                            runCatching { focusRequester.requestFocus() }
                        }
                    }
                }
            }
            // `focusProperties { next/previous = FocusRequester.Cancel }` blocks
            // Compose's default Tab / Shift+Tab focus traversal: without this
            // the very first Tab keystroke moves focus away from the terminal
            // ("clavier qui plante") because Compose forwards Tab to the
            // traversal logic before our `onPreviewKeyEvent` ever runs. With
            // Cancel, traversal is suppressed and Tab arrives at the preview
            // handler, which encodes it as HT (0x09) and ships it to the shell.
            .focusProperties {
                next = FocusRequester.Cancel
                previous = FocusRequester.Cancel
            }
            // `onPreviewKeyEvent` runs in the Compose pre-dispatch phase
            // (parent → child) and lets us swallow the event before any
            // descendant handler can claim it.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Scroll-to-bottom-on-typing: while scrolled up into history, any input
                // key (anything other than a bare PageUp/PageDown that scrolls the
                // viewport) snaps the view back to the live bottom: otherwise the user
                // types blind, never seeing what they enter.
                val bareViewportScroll = !event.isCtrlPressed && !event.isShiftPressed &&
                    !event.isAltPressed && !event.isMetaPressed &&
                    (event.key == Key.PageUp || event.key == Key.PageDown)
                if (scrollOffset != 0 && !bareViewportScroll) {
                    scrollOffset = 0
                }
                handleKeyEvent(
                    event = event,
                    session = session,
                    selection = selection,
                    onClearSelection = { selection = null },
                    onScroll = { delta ->
                        scrollOffset = clampScrollOffset(scrollOffset + delta, session)
                    },
                    clipboardCopy = { text ->
                        runCatching { clipboardManager.setText(AnnotatedString(text)) }
                    },
                    clipboardPaste = {
                        clipboardManager.getText()?.text
                    },
                )
            }
            .focusable(),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // xterm mouse-tracking report path (htop / vim / tmux / mc).
                // Runs in the Initial pass, which completes fully across the
                // whole tree BEFORE any Main-pass gesture detector below sees
                // the event (Compose's documented pattern for one handler to
                // claim an event ahead of sibling detectors: `consume()` here
                // is visible to `detectDragGestures`/`detectTapGestures`'s
                // `awaitFirstDown(requireUnconsumed = true)` in the pointerInput
                // blocks declared below). When no mouse mode is active (every
                // session today, until the remote app opts in), this block
                // only keeps its physical-button bookkeeping up to date (so a
                // mode enabled mid-gesture starts from the truth) and never
                // sends or consumes, so local click/drag/wheel behaviour is
                // unchanged.
                .pointerInput(session.sessionId) {
                    // Per-pane pointer bookkeeping, recreated whenever the session
                    // changes (the pointerInput key). Both are pure state machines
                    // so their edge cases are unit-tested without a Compose
                    // PointerEvent (see ComposeTerminalRendererMouseGestureTest).
                    val gestures = MouseReportGestureTracker()
                    val wheelNotches = WheelNotchAccumulator()
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            val mode = session.display.mouseMode.value
                            val reporting = mode != MouseMode.MOUSE_REPORTING_NONE
                            val mods = event.keyboardModifiers
                            // Shift bypass: xterm convention, Shift held always
                            // falls back to local selection/scroll even while the
                            // app is actively reporting. It gates *emission* only:
                            // [MouseReportGestureTracker] still updates the physical
                            // button state, otherwise a Shift-bypassed release would
                            // leave the tracker armed and every later move would be
                            // encoded as a phantom Drag.
                            val shiftHeld = mods.isPointerShiftPressed
                            val ctrlHeld = mods.isPointerCtrlPressed
                            val metaHeld = mods.isPointerMetaPressed

                            // A wheel event can yield several reports at once (see
                            // [WheelNotchAccumulator]); every other gesture yields
                            // at most one. `emptyList()` means "nothing to send:
                            // let the local handlers below deal with it".
                            val reports: List<MouseReportEvent> =
                                if (event.type == PointerEventType.Scroll) {
                                    if (!reporting || shiftHeld) {
                                        emptyList()
                                    } else {
                                        // Trackpads deliver fractional deltas; accumulate
                                        // them into whole notches exactly like the local
                                        // scroll path below, and emit one report per notch
                                        // so a fast flick isn't collapsed into a single
                                        // report. Sign convention is shared with that path:
                                        // positive delta = toward the user = wheel down.
                                        val notches = wheelNotches.accumulate(change.scrollDelta.y)
                                        if (notches == 0) {
                                            emptyList()
                                        } else {
                                            val wheel = MouseReportEvent.Wheel(
                                                up = notches < 0,
                                                ctrl = ctrlHeld,
                                                meta = metaHeld,
                                            )
                                            List(abs(notches)) { wheel }
                                        }
                                    }
                                } else {
                                    val kind = event.type.toGestureKind() ?: continue
                                    listOfNotNull(
                                        gestures.onEvent(
                                            kind = kind,
                                            eventButton = event.button.toReportButton(),
                                            shiftHeld = shiftHeld,
                                            reportingEnabled = reporting,
                                            ctrl = ctrlHeld,
                                            meta = metaHeld,
                                        )
                                    )
                                }
                            if (reports.isEmpty()) continue

                            val cellW = cellMetrics.width.takeIf { it > 0f } ?: continue
                            val cellH = cellMetrics.height.takeIf { it > 0f } ?: continue
                            val maxCol = (lastTermSize.width - 1).coerceAtLeast(0)
                            val maxRow = (lastTermSize.height - 1).coerceAtLeast(0)
                            val column = (change.position.x / cellW).toInt().coerceIn(0, maxCol) + 1
                            val row = (change.position.y / cellH).toInt().coerceIn(0, maxRow) + 1

                            val format = session.display.mouseFormat.value
                            var sent = false
                            for (report in reports) {
                                val bytes = MouseReportEncoder.encode(mode, format, report, column, row)
                                    ?: break // mode ladder rejects this event kind entirely
                                // CONTRACT: mouse reports are per-pane by nature and must
                                // never enter the broadcast relay. They are NOT keystrokes:
                                // the coordinates only mean something for the pane the
                                // pointer is actually over, and relaying them would hand a
                                // compromised remote host an injection primitive (enable
                                // mouse tracking → every pointer move it provokes is
                                // mirrored verbatim to the OTHER hosts of the broadcast
                                // group). `userInput = false` keeps them out of
                                // [ComposeTerminalSession.onUserInput], which is the sole
                                // trigger of that relay. No functional loss: type-ahead is
                                // a no-op here (see NoOpTypeAheadModel), so `userInput` is
                                // purely informational for JediTerm.
                                session.sendBytes(bytes, userInput = false)
                                sent = true
                            }
                            if (sent) change.consume()
                        }
                    }
                }
                .pointerInput(session.sessionId) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            // Compute the cell under the press point and
                            // grab focus while we're at it (so the user can
                            // click + type without an extra click).
                            runCatching { focusRequester.requestFocus() }
                            val cellW = cellMetrics.width.takeIf { it > 0f } ?: return@detectDragGestures
                            val cellH = cellMetrics.height.takeIf { it > 0f } ?: return@detectDragGestures
                            // Floor strict : aligne sur le comportement Swing canonique
                            // (TerminalPanel: `(p.x - insetX) / charWidth` = division
                            // entière Java). L'arrondi au plus proche créait un saut
                            // d'une cellule au début du drag car start (round) et drag
                            // continu (floor) utilisaient des conventions différentes.
                            val col = (offset.x / cellW).toInt()
                                .coerceIn(0, lastTermSize.width - 1)
                            val visY = (offset.y / cellH).toInt()
                                .coerceIn(0, lastTermSize.height - 1)
                            val bufY = visibleYToBufferY(visY, scrollOffset)
                            val anchor = Point(col, bufY)
                            dragAnchor = anchor
                            selection = TerminalSelection(anchor, anchor)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val cellW = cellMetrics.width.takeIf { it > 0f } ?: return@detectDragGestures
                            val cellH = cellMetrics.height.takeIf { it > 0f } ?: return@detectDragGestures
                            val pos = change.position
                            // Drag end uses floor (towards 0) to give the user
                            // fine control over the last character included.
                            val col = (pos.x / cellW).toInt()
                                .coerceIn(0, lastTermSize.width - 1)
                            val visY = (pos.y / cellH).toInt()
                                .coerceIn(0, lastTermSize.height - 1)
                            val bufY = visibleYToBufferY(visY, scrollOffset)
                            val anchor = dragAnchor
                            if (anchor != null) {
                                selection = TerminalSelection(anchor, Point(col, bufY))
                                session.display.updateSelection(selection)
                            }
                        },
                        onDragEnd = { dragAnchor = null },
                        onDragCancel = { dragAnchor = null },
                    )
                }
                .pointerInput(session.sessionId) {
                    // Double-tap → select the word under the click (xterm
                    // convention). Triple-tap → select the whole line.
                    // We use `detectTapGestures` in a separate pointerInput
                    // block so it doesn't conflict with `detectDragGestures`
                    // above (Compose runs them as siblings; tap detection
                    // races a drag detection by waiting for a brief
                    // settle window).
                    detectTapGestures(
                        onTap = {
                            // Single tap clears the active selection so the
                            // user can start a fresh drag from anywhere.
                            selection = null
                            session.display.updateSelection(null)
                            runCatching { focusRequester.requestFocus() }
                        },
                        onDoubleTap = { offset ->
                            val cellW = cellMetrics.width.takeIf { it > 0f }
                                ?: return@detectTapGestures
                            val cellH = cellMetrics.height.takeIf { it > 0f }
                                ?: return@detectTapGestures
                            val col = (offset.x / cellW).toInt()
                                .coerceIn(0, lastTermSize.width - 1)
                            val visY = (offset.y / cellH).toInt()
                                .coerceIn(0, lastTermSize.height - 1)
                            val bufY = visibleYToBufferY(visY, scrollOffset)
                            val sel = selectWordAt(session.textBuffer, Point(col, bufY))
                            if (sel != null) {
                                selection = sel
                                session.display.updateSelection(sel)
                            }
                        },
                    )
                }
                .pointerInput(session.sessionId) {
                    // Mouse wheel → scroll the viewport. Wheel deltas are
                    // small floats; we accumulate and convert to whole
                    // line steps so the scroll feels snappy without lag.
                    var accum = 0f
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            // Already handled by the mouse-reporting interceptor above
                            // (Initial pass, runs first): the app consumed this wheel
                            // notch as an xterm button-64/65 report: don't ALSO scroll
                            // the local viewport / send arrow keys for the same notch.
                            if (change.isConsumed) continue
                            // While the active app owns the alternate screen, the terminal's own
                            // scrollback view is semantically meaningless (alt buffer has no
                            // history): scrollOffset must stay parked at 0 (Bug 2). Instead of
                            // swallowing the wheel, translate it to arrow keys (3 per notch, the
                            // xterm / Windows Terminal convention) so nano / vim / less scroll
                            // their own content. getCodeForKey honours DECCKM, so application
                            // cursor-key mode gets `ESC O A/B` instead of `ESC [ A/B`.
                            if (session.display.alternateScreen.value) {
                                accum += change.scrollDelta.y
                                val altStep = if (accum >= 1f) 1 else if (accum <= -1f) -1 else 0
                                if (altStep != 0) {
                                    accum = 0f
                                    val vk = if (altStep > 0) AwtKeyEvent.VK_DOWN else AwtKeyEvent.VK_UP
                                    val code = runCatching {
                                        session.terminal.getCodeForKey(vk, 0)
                                    }.getOrNull()
                                    if (code != null && code.isNotEmpty()) {
                                        // userInput = false: pointer-derived bytes, scoped to the
                                        // hovered pane: scrolling must never enter the broadcast
                                        // relay (same contract as the mouse-report interceptor).
                                        repeat(3) { session.sendBytes(code, userInput = false) }
                                    }
                                }
                                change.consume()
                                continue
                            }
                            accum += change.scrollDelta.y
                            val step = if (accum >= 1f) -1
                                       else if (accum <= -1f) 1
                                       else 0
                            if (step != 0) {
                                accum = 0f
                                scrollOffset = clampScrollOffset(scrollOffset + step, session)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            // ── Optimisation A (DrawScope half) ──────────────────────────────
            // Reading state values HERE (inside the DrawScope lambda) instead
            // of at the Composable top-level tells Compose that only the *draw*
            // phase depends on these states. When they change, Compose skips
            // recomposition entirely and only re-invokes this lambda: no
            // gesture-handler reinstall, no LaunchedEffect re-evaluation, no
            // Modifier chain rebuild.
            @Suppress("UNUSED_VARIABLE")
            val _trigger = renderTriggerState.value  // subscribe draw phase to buffer changes
            val cursorVisible = cursorVisibleState.value
            val cursorPos = cursorPosState.value

            // Palette-derived colours computed here so they too are scoped to
            // the draw phase and don't force recomposition when they change.
            val drawPalette = session.settings.currentPalette()
            val drawThemeFg = drawPalette.foreground.toComposeColor()
            val drawThemeBg = drawPalette.background.toComposeColor()
            val drawThemeCursor = drawPalette.cursor.toComposeColor()
            val drawThemeSelectionBg = drawPalette.selectionBg.toComposeColor()

            // Paint the full terminal background in the draw phase instead of
            // relying only on the composable-level Modifier.background. The draw
            // phase re-reads the live palette on every forceInvalidate(), so an
            // in-session theme switch recolours the background immediately;
            // default-background cells are not painted individually (see the run
            // loop below), so they show through to this fill. Without it, a theme
            // swap recoloured the text but left the background stale because the
            // composable (and its Modifier.background) never recomposed (B4).
            drawRect(color = drawThemeBg)

            val cellW = cellMetrics.width
            val cellH = cellMetrics.height
            if (cellW <= 0f || cellH <= 0f) return@Canvas

            val cols = (size.width / cellW).toInt().coerceAtLeast(1)
            val rows = (size.height / cellH).toInt().coerceAtLeast(1)

            if (cols != lastTermSize.width || rows != lastTermSize.height) {
                lastTermSize = IntSize(cols, rows)
                session.resize(cols, rows)
            }

            val buffer = session.textBuffer
            buffer.lock()
            try {
                val historyCount = buffer.historyLinesCount
                val effectiveOffset = scrollOffset.coerceIn(0, historyCount)
                // Top buffer-row index of the visible window. Negative values
                // address the scrollback (history) lines. We mimic
                // TerminalPanel.java's processHistoryAndScreenLines convention:
                // y=0 is the top of the screen, y<0 reaches into history.
                val topBufferY = -effectiveOffset

                val baseStyle = ComposeTextStyle(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = fontSizeSp,
                )

                for (vy in 0 until rows) {
                    val by = topBufferY + vy
                    val line = lineAt(buffer, by) ?: continue

                    // Extract style-uniform runs from the JediTerm line. This
                    // function is the single source of truth for both rendering
                    // AND selection text extraction, ensuring pixel-perfect
                    // consistency between the two.
                    val runs = lineToRuns(line, drawPalette, drawThemeFg, drawThemeBg)
                    if (runs.isEmpty()) continue

                    // Pass 1: draw background rectangles for non-default runs.
                    // We draw backgrounds first so the glyph layer (which uses
                    // drawText) paints on top and the bg colour shows through
                    // any inter-glyph gaps without bleeding into adjacent cells.
                    for (run in runs) {
                        if (run.bg != drawThemeBg) {
                            drawRect(
                                color = run.bg,
                                topLeft = Offset(run.cellX * cellW, vy * cellH),
                                size = Size(run.visibleText.length * cellW, cellH),
                            )
                        }
                    }

                    // Pass 2: measure/draw one AnnotatedString for the entire row.
                    // Hidden runs (ANSI SGR 8, conceal) are replaced by spaces
                    // so cursor and selection positions remain correct.
                    //
                    // Optimisation C: the cache is keyed by a hash of the raw
                    // RunInfo list so we can detect hits *before* allocating the
                    // AnnotatedString. buildAnnotatedString is only called on a
                    // miss.
                    val layout = layoutCache.getOrPut(
                        runs = runs,
                        buildAnnotated = {
                            buildAnnotatedString {
                                var col = 0
                                for (run in runs) {
                                    // Gap between previous run end and this run start:
                                    // fill with spaces so cell positions stay aligned.
                                    if (run.cellX > col) {
                                        append(" ".repeat(run.cellX - col))
                                        col = run.cellX
                                    }
                                    val displayText = if (run.hidden) " ".repeat(run.visibleText.length)
                                                     else run.visibleText
                                    withStyle(
                                        SpanStyle(
                                            color = run.fg,
                                            fontWeight = run.bold,
                                            fontStyle = run.italic,
                                            textDecoration = run.decoration,
                                        )
                                    ) {
                                        append(displayText)
                                    }
                                    col = run.cellX + run.visibleText.length
                                }
                            }
                        },
                        measure = { annotated -> textMeasurer.measure(annotated, baseStyle) },
                    )
                    drawText(layout, topLeft = Offset(0f, vy * cellH))
                }

                // Selection overlay: drawn AFTER text so the highlight is
                // visible. We clip each highlighted row to the line's real
                // text length so the selection rectangle hugs the text
                // instead of stretching to the right edge of the screen
                // through trailing-space cells (xterm/iTerm2 convention).
                selection?.let { sel ->
                    drawSelectionOverlay(
                        selection = sel,
                        topBufferY = topBufferY,
                        rows = rows,
                        cols = cols,
                        cellWidth = cellW,
                        cellHeight = cellH,
                        color = drawThemeSelectionBg,
                        lineLengthAt = { bufY -> resolveLineLength(buffer, bufY) },
                    )
                }

                // Cursor: only at the bottom of the viewport (scrollOffset == 0)
                // and only when the blink phase is "on". When blinking is
                // disabled by the shell (DECSCUSR), `cursorBlinkPhase` stays
                // permanently true so the cursor is always visible.
                if (cursorVisible && effectiveOffset == 0 && cursorBlinkPhase) {
                    val cx = cursorPos.x.coerceAtMost(cols - 1).coerceAtLeast(0)
                    val cy = (cursorPos.y - 1).coerceAtMost(rows - 1).coerceAtLeast(0)
                    drawRect(
                        color = drawThemeCursor,
                        topLeft = Offset(cx * cellW, cy * cellH),
                        size = Size(cellW, cellH),
                        alpha = 0.7f,
                    )
                }
            } finally {
                buffer.unlock()
            }
        }

        // Scrollbar verticale : visible uniquement hors alt-screen et quand
        // il y a de l'historique. Dessinée APRÈS le Canvas (z-order supérieur).
        // Les couleurs du pouce dérivent de la palette active afin que les
        // thèmes custom recolorent automatiquement la scrollbar.
        if (!alternateScreen && historyCount > 0) {
            val thumbColor = palette.foreground.toComposeColor()
            VerticalScrollbar(
                adapter = scrollbarAdapter,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
                style = ScrollbarStyle(
                    minimalHeight = 16.dp,
                    thickness = 8.dp,
                    shape = RoundedCornerShape(4.dp),
                    hoverDurationMillis = 300,
                    unhoverColor = thumbColor.copy(alpha = 0.25f),
                    hoverColor = thumbColor.copy(alpha = 0.45f),
                ),
            )
        }
    }
}

// ─── RunInfo ──────────────────────────────────────────────────────────────────

/**
 * A style-uniform text run produced by parsing a [TerminalLine] entry.
 *
 * [cellX] is the column index of the first character (0-based). [visibleText]
 * contains only the printable characters: NUL entries and ISO control codes
 * are excluded (not replaced by spaces). This ensures:
 *  - the visual rendering and the selection-text extraction iterate over
 *    **exactly** the same characters, keeping highlight and copy in sync
 *    (fixes Bug 3);
 *  - no phantom characters accumulate to the right of the real content,
 *    which was the root cause of the post-snippet cursor shift (Bug 4).
 *
 * [hidden] is true when the ANSI SGR 8 (conceal) attribute is set; the caller
 * substitutes spaces in the glyph layer but keeps the correct advance width.
 */
private data class RunInfo(
    val visibleText: String,
    val fg: Color,
    val bg: Color,
    val bold: FontWeight,
    val italic: FontStyle,
    val decoration: TextDecoration,
    val hidden: Boolean,
    /** Column index of the first character in this run. */
    val cellX: Int,
)

// ─── Shared character filter ──────────────────────────────────────────────────

/**
 * Returns true if [c] is a glyph that both [lineToRuns] renders and
 * [lineToCells] places into the cell array. This is the single source of
 * truth for the character filter so the two functions never diverge.
 *
 * Excluded:
 *  - ISO control codes (BEL, BS, NUL mid-run, etc.): Skia renders them as
 *    the replacement-character glyph "□" if not filtered.
 *  - 0xFFFF ([Char.MAX_VALUE]): the CHAR_UNDEFINED sentinel used by AWT and
 *    JediTerm to mark uninitialised / composed-input slots.
 */
private fun isVisibleChar(c: Char): Boolean =
    !c.isISOControl() && c != Char.MAX_VALUE

// ─── lineToRuns ───────────────────────────────────────────────────────────────

/**
 * Converts a [TerminalLine] to a list of [RunInfo] values ready to render.
 *
 * This is the **canonical** line parser: both [ComposeTerminalRenderer] (for
 * drawing) and [extractSelectionText] (for Ctrl+Shift+C copy) must call this
 * function to guarantee that what the user sees is exactly what gets copied.
 *
 * NUL entries (uninitialised cells, trailing blank columns, post-clear runs)
 * are skipped entirely: they carry no printable content and including them
 * was the source of "□ glyph" artefacts and the phantom-space cursor shift.
 *
 * ISO control codes embedded in otherwise printable runs (BEL, BS, NUL in
 * the middle of a run, etc.) are also dropped character-by-character to
 * prevent the Skia renderer from substituting the "replacement character"
 * glyph. CHAR_UNDEFINED (0xFFFF) is explicitly excluded for the same reason.
 * Both checks are centralised in [isVisibleChar].
 */
private fun lineToRuns(
    line: TerminalLine,
    palette: TerminalThemePalette,
    themeFg: Color,
    themeBg: Color,
): List<RunInfo> {
    val runs = mutableListOf<RunInfo>()
    var x = 0
    line.forEachEntry { entry ->
        val len = entry.length
        if (len <= 0) { return@forEachEntry }

        // Skip uninitialised NUL runs entirely (no printable content, no draw).
        if (entry.isNul) {
            x += len
            return@forEachEntry
        }

        // Build the visible text by dropping control chars and CHAR_UNDEFINED.
        // We do NOT replace them with spaces because spaces are "real" content
        // in terms of cell positioning: a replaced space would be indistinguishable
        // from a space the user actually typed, causing off-by-one cursor positions.
        val raw = entry.text.toString()
        val clean = buildString(raw.length) {
            for (c in raw) {
                if (isVisibleChar(c)) append(c)
            }
        }

        if (clean.isNotEmpty()) {
            val style = entry.style
            val isInverse = style.hasOption(JediTextStyle.Option.INVERSE)
            val rawFg = style.foregroundForRun.resolveCompose(
                palette, isForeground = true, themeFg = themeFg, themeBg = themeBg,
            )
            val rawBg = style.backgroundForRun.resolveCompose(
                palette, isForeground = false, themeFg = themeFg, themeBg = themeBg,
            )
            runs += RunInfo(
                visibleText = clean,
                fg = if (isInverse) rawBg else rawFg,
                bg = if (isInverse) rawFg else rawBg,
                bold = if (style.hasOption(JediTextStyle.Option.BOLD)) FontWeight.Bold
                       else FontWeight.Normal,
                italic = if (style.hasOption(JediTextStyle.Option.ITALIC)) FontStyle.Italic
                         else FontStyle.Normal,
                decoration = if (style.hasOption(JediTextStyle.Option.UNDERLINED))
                                TextDecoration.Underline
                             else TextDecoration.None,
                hidden = style.hasOption(JediTextStyle.Option.HIDDEN),
                cellX = x,
            )
        }
        x += len
    }
    return runs
}

// ─── lineToCells ──────────────────────────────────────────────────────────────

/**
 * Builds a flat cell-indexed view of [line] that mirrors exactly what
 * [lineToRuns] feeds to the renderer.
 *
 * For each buffer column 0..([bufferWidth]-1), the array contains:
 *  - the visible character rendered at that column, or
 *  - `' '` (space) as a default for uninitialised / control-char cells.
 *
 * **Consistency contract with [lineToRuns]:**
 *  - NUL entries advance the column pointer but write nothing (same as
 *    [lineToRuns] skipping them via `continue`).
 *  - Characters filtered by [isVisibleChar] are silently dropped; the next
 *    visible char in the same entry lands at the next column slot. This
 *    matches [lineToRuns], which builds `clean` by appending only visible
 *    chars and placing the whole `clean` string starting at `cellX`.
 *
 * [bufferWidth] should be the terminal width so the returned array covers
 * the full line without truncating or over-allocating. When the entry
 * content exceeds [bufferWidth], excess chars are clamped.
 */
private fun lineToCells(line: TerminalLine, bufferWidth: Int): CharArray {
    val cells = CharArray(bufferWidth) { ' ' }
    var x = 0
    line.forEachEntry { entry ->
        val len = entry.length
        if (len <= 0) return@forEachEntry
        if (entry.isNul) {
            x += len
            return@forEachEntry
        }
        val raw = entry.text.toString()
        var col = x
        for (c in raw) {
            if (col >= cells.size) break
            if (isVisibleChar(c)) {
                cells[col] = c
                col++
            }
            // invisible chars: advance neither col nor the visual grid
            // (same as lineToRuns dropping them from `clean`)
        }
        x += len
    }
    return cells
}

// ─── TextLayoutCache ──────────────────────────────────────────────────────────

/**
 * LRU cache for [TextLayoutResult] values keyed by a cheap content hash of
 * the [RunInfo] list rather than the full [AnnotatedString].
 *
 * **Optimisation C**: the previous key type was `(AnnotatedString,
 * ComposeTextStyle)`, which required the AnnotatedString to already be
 * constructed before a cache lookup could be attempted. In a 80×25 terminal
 * at 60 fps that means ~1 500 AnnotatedString allocations/second even when
 * every line is static. By hashing the raw [RunInfo] list first (text +
 * fg/bg packed ints + attribute bitmask + cellX) we can detect a cache hit
 * *before* calling [buildAnnotatedString], saving both the allocation and the
 * Skia layout call for unchanged lines.
 *
 * Collision risk: hash collisions would produce a wrong layout. We mitigate
 * this by using a 64-bit hash computed over every semantic field of every run:
 * the probability of a false hit on a real terminal workload is negligible.
 * In the event of a hypothetical collision the worst outcome is a single
 * frame rendered with the wrong style for one line; the next invalidation
 * corrects it.
 *
 * [capacity] is the maximum number of cached layouts. Entries beyond this
 * limit evict the least-recently-used entry. 150 = ~5× a 30-row terminal
 * window.
 */
private class TextLayoutCache(private val capacity: Int) {

    // accessOrder = true → LinkedHashMap evicts the least-recently-accessed
    // entry when capacity is exceeded.
    private val map = object : LinkedHashMap<Long, TextLayoutResult>(
        /* initialCapacity = */ capacity + 1,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, TextLayoutResult>): Boolean =
            size > capacity
    }

    /**
     * Return a cached layout for [runs] if one exists, or compute it via
     * [buildAnnotated] + [measure] and cache the result.
     *
     * The caller provides two lambdas so the expensive work (building the
     * AnnotatedString and calling Skia layout) is only executed on a miss.
     */
    fun getOrPut(
        runs: List<RunInfo>,
        buildAnnotated: () -> AnnotatedString,
        measure: (AnnotatedString) -> TextLayoutResult,
    ): TextLayoutResult {
        val hash = runsHash(runs)
        map[hash]?.let { return it }
        val annotated = buildAnnotated()
        val result = measure(annotated)
        map[hash] = result
        return result
    }

    fun clear() = map.clear()

    /** 64-bit FNV-1a hash over every semantic field of every [RunInfo]. */
    private fun runsHash(runs: List<RunInfo>): Long {
        var h = -3750763034362895579L // FNV-1a 64-bit offset basis
        for (run in runs) {
            h = h xor run.visibleText.hashCode().toLong()
            h *= 1099511628211L
            h = h xor run.fg.value.toLong()
            h *= 1099511628211L
            h = h xor run.bg.value.toLong()
            h *= 1099511628211L
            // Pack boolean attributes into a nibble to avoid 4 separate mults
            val attrs = (if (run.bold == FontWeight.Bold) 1 else 0) or
                        (if (run.italic == FontStyle.Italic) 2 else 0) or
                        (if (run.decoration != TextDecoration.None) 4 else 0) or
                        (if (run.hidden) 8 else 0)
            h = h xor (attrs.toLong() or (run.cellX.toLong() shl 4))
            h *= 1099511628211L
        }
        return h
    }
}

// ─── Key handling ─────────────────────────────────────────────────────────────

/**
 * Translate a Compose key event to bytes the shell understands and ship
 * them down the PTY. Returns `true` if the event was handled (so the
 * Compose key system stops propagating it).
 *
 * Order of handling:
 *  1. **Ctrl+Shift+C**: copy current selection to clipboard. Standard
 *     terminal convention (xterm/gnome-terminal/iTerm2): plain Ctrl+C
 *     remains SIGINT.
 *  2. **Ctrl+Shift+V**: paste clipboard contents.
 *  3. **PageUp/PageDown** with no modifier: scroll viewport one screen.
 *     Other PageUp/PageDown variants (with Shift, Ctrl, etc.) fall through
 *     to `getCodeForKey` so vim/less can use them.
 *  4. **Hand-coded special keys**: Backspace→DEL(0x7F), Tab→HT(0x09),
 *     Ctrl+letter→ASCII control (vk-64). These are intercepted before
 *     JediTerm's encoder to avoid its 3.40 inconsistencies.
 *  5. **`getCodeForKey(vk, modifiers)`**: JediTerm's xterm encoder for
 *     arrows, function keys, Home/End, Insert/Delete, etc.
 *  6. **Printable fallback**: `event.utf16CodePoint` resolves composed
 *     characters correctly (Shift+key on AZERTY, dead-key sequences, …).
 *     We explicitly skip code point 0 (pending composition), 0xFFFF
 *     (CHAR_UNDEFINED), and anything [Char.isISOControl] classifies as a
 *     control character. This fixes Bug 1: previously `awt.keyChar` returned
 *     0xFFFF for composed input, `isISOControl(0xFFFF)` returned false, and
 *     the shell received and echoed the undefined codepoint as "□".
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun handleKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    session: ComposeTerminalSession,
    selection: TerminalSelection?,
    onClearSelection: () -> Unit,
    onScroll: (Int) -> Unit,
    clipboardCopy: (String) -> Unit,
    clipboardPaste: () -> String?,
): Boolean {
    // CMP 1.8 wraps the AWT key event behind an opaque `InternalKeyEvent`
    // type: the historical `event.nativeKeyEvent as java.awt.event.KeyEvent`
    // cast returns `null`, which silently dropped every keystroke. Use the
    // Compose API directly (`event.key.nativeKeyCode`, `event.utf16CodePoint`,
    // `isCtrlPressed`, …): these are stable since CMP 1.6.
    val isCtrl = event.isCtrlPressed
    val isShift = event.isShiftPressed
    val isAlt = event.isAltPressed
    val isMeta = event.isMetaPressed

    // 1. Copy: Ctrl+Shift+C
    if (isCtrl && isShift && event.key == Key.C) {
        val sel = selection ?: return true
        val text = runCatching {
            extractSelectionText(session.textBuffer, sel)
        }.getOrNull()
        if (!text.isNullOrEmpty()) clipboardCopy(text)
        return true
    }
    // 2. Paste: Ctrl+Shift+V
    if (isCtrl && isShift && event.key == Key.V) {
        val pasted = runCatching { clipboardPaste() }.getOrNull()
        if (!pasted.isNullOrEmpty()) {
            // Bracketed paste support: when the shell has enabled bracketed
            // paste mode (set by setBracketedPasteMode) we wrap the content
            // in the magic markers so the shell can distinguish "pasted"
            // text from "typed" text and avoid auto-execution of multi-line
            // commands.
            val payload = if (session.display.bracketedPaste) {
                "[200~$pasted[201~"
            } else pasted
            session.sendString(payload, userInput = true)
        }
        return true
    }
    // 3. PageUp / PageDown with no modifier → scroll viewport
    if (!isCtrl && !isShift && !isAlt && !isMeta) {
        if (event.key == Key.PageUp) {
            onScroll(10) // scroll 10 lines up into history
            return true
        }
        if (event.key == Key.PageDown) {
            onScroll(-10)
            return true
        }
    }

    // 4a. Backspace → DEL (0x7F). Modern shells (bash, zsh, fish) default
    // to `stty erase ^?` so they expect DEL, not BS. JediTerm's encoder
    // sometimes returns BS (0x08) which renders as a "□" glyph in the
    // terminal echo instead of erasing the character.
    if (event.key == Key.Backspace && !isCtrl && !isAlt && !isMeta) {
        if (selection != null) onClearSelection()
        session.sendBytes(byteArrayOf(0x7F), userInput = true)
        return true
    }

    // 4b. Tab / Shift+Tab: must be intercepted in preview phase BEFORE Compose
    // focus traversal moves focus away from the renderer, otherwise the
    // first Tab steals focus and the keyboard appears to "freeze". xterm
    // sends HT (0x09) for plain Tab and CSI Z (ESC[Z) for Shift+Tab.
    if (event.key == Key.Tab && !isCtrl && !isAlt && !isMeta) {
        if (selection != null) onClearSelection()
        if (isShift) {
            session.sendBytes(byteArrayOf(0x1B, '['.code.toByte(), 'Z'.code.toByte()), userInput = true)
        } else {
            session.sendBytes(byteArrayOf(0x09), userInput = true)
        }
        return true
    }

    // 4c. Ctrl+letter (no Shift, no Alt, no Meta) → ASCII control char.
    // Ctrl+A=0x01, Ctrl+B=0x02, …, Ctrl+Z=0x1A. JediTerm's encoder is
    // unreliable for these in 3.40, so we encode directly. The chord
    // `Ctrl+Shift+letter` (Copy/Paste) is already filtered out above.
    if (isCtrl && !isShift && !isAlt && !isMeta) {
        val nativeCode = event.key.nativeKeyCode
        if (nativeCode in 65..90) { // VK_A..VK_Z (matches AWT)
            if (selection != null) onClearSelection()
            val ctrlByte = (nativeCode - 64).toByte()
            session.sendBytes(byteArrayOf(ctrlByte), userInput = true)
            return true
        }
    }

    // 5. Translate the rest via JediTerm's xterm key encoder. `Key.nativeKeyCode`
    // returns the AWT VK_* value on the Desktop target: exactly what
    // `JediTerminal.getCodeForKey(vk, mods)` expects (jediterm-core's
    // `com.jediterm.core.input.KeyEvent.VK_*` constants are aligned with
    // `java.awt.event.KeyEvent.VK_*`). Handles arrows, F-keys, Home/End,
    // Insert/Delete, etc.
    val nativeCode = event.key.nativeKeyCode
    val jediMods = jediModifiersFromCompose(isShift, isCtrl, isMeta, isAlt)
    val code = runCatching { session.terminal.getCodeForKey(nativeCode, jediMods) }.getOrNull()
    if (code != null && code.isNotEmpty()) {
        if (selection != null) onClearSelection()
        session.sendBytes(code, userInput = true)
        return true
    }

    // 6. Printable fallback: send the typed character via utf16CodePoint.
    //
    // WHY utf16CodePoint, not awt.keyChar:
    //   On Compose 1.8, `event.nativeKeyEvent as java.awt.event.KeyEvent` is
    //   null. Even when it was accessible in earlier versions, `keyChar` was
    //   CHAR_UNDEFINED (0xFFFF) for composed input (e.g. Shift+`:` → `/` on
    //   AZERTY). `isISOControl(0xFFFF)` returns **false** in Java, so 0xFFFF
    //   would pass all guards and be sent to the shell, which echoes it as "□".
    //
    // The actual admission logic (incl. the AltGr/Ctrl+Alt special case on
    // Windows AZERTY) lives in [shouldSendPrintable]: kept pure so it can be
    // unit-tested in isolation from a Compose KeyEvent.
    val cp = event.utf16CodePoint
    if (shouldSendPrintable(cp, isCtrl, isAlt, isMeta)) {
        if (selection != null) onClearSelection()
        session.sendString(cp.toChar().toString(), userInput = true)
        return true
    }

    return false
}

/**
 * Decide whether the step-6 printable-fallback path of [handleKeyEvent] should
 * admit [cp] as a keystroke to forward to the shell. Pulled out as a pure
 * function so the modifier-combination logic (which is the heart of the
 * AltGr/Windows-AZERTY fix) can be unit-tested without constructing a
 * Compose `KeyEvent` (whose Desktop-internal wrapper cannot be instantiated
 * from outside `androidx.compose.ui`).
 *
 * Guards in order:
 *  - `cp <= 0`           → no codepoint produced (dead-key, pending composition).
 *  - `cp == 0xFFFF`      → CHAR_UNDEFINED sentinel from AWT.
 *  - `Char.isISOControl` → residual control codes that slipped past steps 4-5.
 *  - `isMeta`            → Cmd / Win chord; never a printable input.
 *  - `isCtrl && !isAlt`  → pure Ctrl chord; routed by step 4c or step 5.
 *  - `isCtrl && isAlt`   → admitted as AltGr. On Windows, Win32 reports AltGr
 *                          as Ctrl+Alt simultaneously (a historical PC keyboard
 *                          convention); Java AWT and Compose propagate the
 *                          dual modifier unchanged. The OS has *already*
 *                          composed the printable char ('|', '@', '{', …);
 *                          a genuine Ctrl+Alt+<letter> chord arrives with
 *                          `cp == 0xFFFF` (no composition) and short-circuits
 *                          at the CHAR_UNDEFINED guard above.
 */
internal fun shouldSendPrintable(
    cp: Int,
    isCtrl: Boolean,
    isAlt: Boolean,
    isMeta: Boolean,
): Boolean {
    if (cp <= 0 || cp == 0xFFFF) return false
    if (cp.toChar().isISOControl()) return false
    if (isMeta) return false
    val isAltGr = isCtrl && isAlt
    return !isCtrl || isAltGr
}

/**
 * Builds the `com.jediterm.core.input.InputEvent` modifier mask from the
 * Compose KeyEvent boolean accessors. JediTerm uses SHIFT=1, CTRL=2,
 * META=4, ALT=8 (matches `java.awt.event.InputEvent`'s legacy mask values).
 */
private fun jediModifiersFromCompose(
    isShift: Boolean,
    isCtrl: Boolean,
    isMeta: Boolean,
    isAlt: Boolean,
): Int {
    var mods = 0
    if (isShift) mods = mods or 1
    if (isCtrl) mods = mods or 2
    if (isMeta) mods = mods or 4
    if (isAlt) mods = mods or 8
    return mods
}

/**
 * Maps a Compose [PointerButton] to the xterm [MouseReportButton] identity
 * used by [MouseReportEncoder]. Returns `null` for buttons xterm mouse
 * tracking has no code for (Back/Forward) or when Compose didn't report one
 * (e.g. a bare hover [PointerEventType.Move], where no single button
 * "triggered" the event).
 */
private fun PointerButton?.toReportButton(): MouseReportButton? = when (this) {
    PointerButton.Primary -> MouseReportButton.LEFT
    PointerButton.Secondary -> MouseReportButton.RIGHT
    PointerButton.Tertiary -> MouseReportButton.MIDDLE
    else -> null
}

/**
 * The pointer gestures [MouseReportGestureTracker] understands. Wheel notches
 * are handled separately ([WheelNotchAccumulator]) because one wheel event can
 * expand to several reports. Enter/Exit and any other [PointerEventType] map to
 * `null`: they carry no mouse-tracking meaning.
 */
private fun PointerEventType.toGestureKind(): MouseGestureKind? = when (this) {
    PointerEventType.Press -> MouseGestureKind.PRESS
    PointerEventType.Release -> MouseGestureKind.RELEASE
    PointerEventType.Move -> MouseGestureKind.MOVE
    else -> null
}

/**
 * Compose-free gesture identity, mirroring [MouseReportEvent]'s "no Compose,
 * no AWT" rule so [MouseReportGestureTracker] can be unit-tested without
 * constructing a Compose `PointerEvent` (whose Desktop wrapper is `internal`
 * to `androidx.compose.ui`).
 */
internal enum class MouseGestureKind { PRESS, RELEASE, MOVE }

/**
 * Tracks which mouse button is physically held down and turns each pointer
 * gesture into the xterm report to emit (or `null` when nothing should be
 * sent).
 *
 * **Why the state update is unconditional.** Two gates suppress *emission*:
 * `shiftHeld` (xterm's "Shift = local selection" bypass) and
 * `reportingEnabled` (the remote app hasn't asked for mouse tracking). Neither
 * may suppress the *bookkeeping*: an earlier version returned early on the
 * Shift gate, so releasing the button while Shift was held never cleared
 * `pressedButton`. Every subsequent move was then encoded as `Drag(LEFT)` and
 * tmux/vim believed the button was still down until the next clean click. The
 * same trap applies to `reportingEnabled`: an app that disables tracking
 * mid-drag (quitting vim with the button down) would otherwise leave the
 * tracker armed for the next app that enables it.
 *
 * A press *with Shift* is the one case that deliberately does not arm: the
 * gesture belongs to the local selection path, so the moves that follow must
 * stay local [MouseReportEvent.Move]s rather than becoming drags.
 *
 * Not thread-safe by design: it is owned by a single `pointerInput` coroutine.
 */
internal class MouseReportGestureTracker {

    /** Button currently held down, or `null` when none is. */
    var pressedButton: MouseReportButton? = null
        private set

    fun onEvent(
        kind: MouseGestureKind,
        eventButton: MouseReportButton?,
        shiftHeld: Boolean,
        reportingEnabled: Boolean,
        ctrl: Boolean = false,
        meta: Boolean = false,
    ): MouseReportEvent? = when (kind) {
        MouseGestureKind.PRESS -> {
            if (shiftHeld) {
                // Local selection gesture: deliberately left un-armed.
                null
            } else {
                // A button xterm has no code for (Back/Forward → null) leaves
                // the held state untouched, exactly as before.
                eventButton?.let { btn ->
                    pressedButton = btn
                    if (reportingEnabled) {
                        MouseReportEvent.Press(btn, ctrl = ctrl, meta = meta)
                    } else null
                }
            }
        }

        MouseGestureKind.RELEASE -> {
            // Disarm FIRST and unconditionally, before any gate can skip it.
            val released = pressedButton ?: eventButton
            pressedButton = null
            if (shiftHeld || !reportingEnabled) {
                null
            } else {
                released?.let { MouseReportEvent.Release(it, ctrl = ctrl, meta = meta) }
            }
        }

        MouseGestureKind.MOVE -> {
            if (shiftHeld || !reportingEnabled) {
                null
            } else {
                val held = pressedButton
                if (held != null) {
                    MouseReportEvent.Drag(held, ctrl = ctrl, meta = meta)
                } else {
                    MouseReportEvent.Move(ctrl = ctrl, meta = meta)
                }
            }
        }
    }
}

/**
 * Accumulates fractional wheel deltas into whole notches.
 *
 * Mice deliver `±1.0` per detent, but trackpads and precision wheels stream
 * fractions (`0.1`, `0.35`, …). Emitting one report per *event* would make a
 * trackpad spam the remote app with notches it never scrolled, while dropping
 * every sub-unit delta would make it scroll nothing at all. Accumulating and
 * emitting one report per whole unit crossed gives the same feel as the local
 * scrollback path, which uses the same threshold and the same sign convention:
 * **positive delta = toward the user = wheel down**.
 *
 * The remainder is carried over, so ten `0.1` deltas produce exactly one
 * notch. [MAX_NOTCHES_PER_EVENT] caps a single event so a driver reporting an
 * absurd delta cannot make the UI thread emit thousands of escape sequences;
 * the surplus is dropped rather than queued.
 *
 * Not thread-safe by design: owned by a single `pointerInput` coroutine.
 */
internal class WheelNotchAccumulator {

    private var accumulated = 0f

    /**
     * Feeds one raw wheel delta. Returns the number of whole notches to emit:
     * positive = down, negative = up, `0` = below the threshold (carry only).
     */
    fun accumulate(delta: Float): Int {
        if (!delta.isFinite()) return 0
        accumulated += delta
        // toInt() truncates toward zero, which is what we want on both signs.
        val raw = accumulated.toInt()
        if (raw == 0) return 0
        accumulated -= raw.toFloat()
        return when {
            raw > MAX_NOTCHES_PER_EVENT -> {
                accumulated = 0f
                MAX_NOTCHES_PER_EVENT
            }
            raw < -MAX_NOTCHES_PER_EVENT -> {
                accumulated = 0f
                -MAX_NOTCHES_PER_EVENT
            }
            else -> raw
        }
    }

    private companion object {
        /** Upper bound on the reports a single wheel event may expand to. */
        const val MAX_NOTCHES_PER_EVENT = 16
    }
}

/**
 * Clamps the scroll offset to the available history range. Always called
 * after any user-driven scroll so the viewport never overshoots the buffer
 * boundaries (and never goes negative, 0 means "live screen").
 */
private fun clampScrollOffset(rawOffset: Int, session: ComposeTerminalSession): Int {
    val historyCount = session.textBuffer.historyLinesCount
    return rawOffset.coerceIn(0, historyCount)
}

/** Convert a visible row index (0..rows-1) to a buffer-relative row,
 *  taking the current scroll offset into account. */
private fun visibleYToBufferY(visibleY: Int, scrollOffset: Int): Int =
    visibleY - scrollOffset

/**
 * Highlights the cells covered by [selection] in the current viewport.
 *
 * Selection coordinates are buffer-relative (Y can be negative for
 * scrollback). `topBufferY` is the buffer-Y of the first visible row, so a
 * cell with buffer-Y `by` is rendered at visible row `by - topBufferY`.
 *
 * Multi-line selections are filled with three rectangles when needed:
 *  - first row (from start.x to end of line)
 *  - middle rows (full width)
 *  - last row (from start of line to end.x)
 *
 * Single-row selections collapse to a single rectangle.
 */
private fun DrawScope.drawSelectionOverlay(
    selection: TerminalSelection,
    topBufferY: Int,
    rows: Int,
    cols: Int,
    cellWidth: Float,
    cellHeight: Float,
    color: Color,
    lineLengthAt: (bufferY: Int) -> Int,
) {
    val sorted = SelectionUtil.sortPoints(selection.start, selection.end)
    val start = sorted.first
    val end = sorted.second
    if (start.y == end.y) {
        val visY = start.y - topBufferY
        if (visY !in 0 until rows) return
        val realLen = lineLengthAt(start.y)
        val x1 = min(start.x, end.x)
        val rawX2 = max(start.x, end.x) + 1
        // Clip to real text length so trailing-space cells aren't highlighted.
        val x2 = min(rawX2, max(realLen, x1))
        if (x2 > x1) drawSelectionRect(visY, x1, x2, cellWidth, cellHeight, cols, color)
    } else {
        // First row: from start.x to the end of the line's real text.
        val firstVis = start.y - topBufferY
        if (firstVis in 0 until rows) {
            val len1 = lineLengthAt(start.y)
            val x2 = max(len1, start.x)
            if (x2 > start.x) drawSelectionRect(firstVis, start.x, x2, cellWidth, cellHeight, cols, color)
        }
        // Middle rows: from 0 to the line's real text length.
        for (by in (start.y + 1) until end.y) {
            val vy = by - topBufferY
            if (vy in 0 until rows) {
                val len = lineLengthAt(by)
                if (len > 0) drawSelectionRect(vy, 0, len, cellWidth, cellHeight, cols, color)
            }
        }
        // Last row: from 0 to end.x+1 (or real length, whichever is smaller).
        val lastVis = end.y - topBufferY
        if (lastVis in 0 until rows) {
            val lenLast = lineLengthAt(end.y)
            val x2 = min(end.x + 1, max(lenLast, 0))
            if (x2 > 0) drawSelectionRect(lastVis, 0, x2, cellWidth, cellHeight, cols, color)
        }
    }
}

/**
 * Reads a [TerminalLine] at buffer-relative Y. Negative Y reaches into
 * the scrollback (history) buffer, non-negative Y addresses the screen.
 * Returns null when [bufferY] is out of range.
 */
private fun lineAt(buffer: TerminalTextBuffer, bufferY: Int): TerminalLine? {
    return if (bufferY >= 0) {
        if (bufferY < buffer.height) buffer.getLine(bufferY) else null
    } else {
        val historyCount = buffer.historyLinesCount
        val histIndex = historyCount + bufferY
        if (histIndex in 0 until historyCount) {
            runCatching { buffer.historyBuffer.getLine(histIndex) }.getOrNull()
        } else null
    }
}

/**
 * Returns the length (column count) of the visible text on line [bufferY],
 * trimming trailing whitespace so the selection rectangle clips at the
 * last printable glyph rather than at the right edge of the cell grid.
 */
private fun resolveLineLength(
    buffer: TerminalTextBuffer,
    bufferY: Int,
): Int {
    val line = lineAt(buffer, bufferY) ?: return 0
    val text = runCatching { line.text }.getOrNull() ?: return 0
    return text.trimEnd().length
}

/**
 * Extracts the text covered by [sel] as a plain string.
 *
 * Uses [lineToCells] (which mirrors [lineToRuns] exactly) to build a
 * cell-indexed view of each line, then slices the selection columns out of
 * it. This guarantees that the copied text is **identical** to what the
 * renderer painted: same character filter ([isVisibleChar]), same handling
 * of NUL entries, same treatment of control chars within printable runs.
 *
 * Trailing spaces on each line are trimmed before appending (JediTerm pads
 * every row to the full terminal width with spaces; the user perceives the
 * text as ending at the last printable glyph).
 *
 * Acquires [TerminalTextBuffer.lock] internally: the caller does NOT need
 * to hold it. JediTerm's lock is a [java.util.concurrent.locks.ReentrantLock],
 * so a re-acquire from the same thread is safe if this is ever called while
 * already holding the lock.
 */
private fun extractSelectionText(buffer: TerminalTextBuffer, sel: TerminalSelection): String {
    val sorted = SelectionUtil.sortPoints(sel.start, sel.end)
    val s = sorted.first
    val e = sorted.second
    val sb = StringBuilder()
    val bufWidth = buffer.width.coerceAtLeast(1)
    buffer.lock()
    try {
        for (y in s.y..e.y) {
            val line = lineAt(buffer, y) ?: continue

            // lineToCells produces the same cell sequence as lineToRuns:
            // visible chars land at their rendered column positions, NUL
            // entries and control chars are silently skipped.
            val cells = lineToCells(line, bufWidth)

            val startCol = if (y == s.y) s.x else 0
            // endCol is inclusive (the last selected column).
            val endCol = if (y == e.y) e.x else cells.size - 1
            val from = startCol.coerceIn(0, cells.size)
            val to = (endCol + 1).coerceIn(from, cells.size)

            // Trim trailing spaces from the slice so multi-line copies don't
            // carry JediTerm's padding whitespace on every line.
            var sliceEnd = to
            while (sliceEnd > from && cells[sliceEnd - 1] == ' ') sliceEnd--

            for (i in from until sliceEnd) sb.append(cells[i])
            if (y < e.y) sb.append('\n')
        }
    } finally {
        buffer.unlock()
    }
    return sb.toString()
}

/**
 * Returns the [TerminalSelection] covering the word under [anchor], or
 * null if the click landed on whitespace / outside the line. Word
 * boundary semantics match the common terminal convention: alphanumeric
 * + `_-./` form a contiguous "word" (so paths like `/usr/bin` or option
 * names like `--no-color` select as one unit). Tweakable via [isWordChar].
 */
private fun selectWordAt(buffer: TerminalTextBuffer, anchor: Point): TerminalSelection? {
    buffer.lock()
    try {
        val line = lineAt(buffer, anchor.y) ?: return null
        val text = runCatching { line.text }.getOrNull()?.trimEnd() ?: return null
        if (text.isEmpty()) return null
        val pos = anchor.x.coerceIn(0, text.length - 1)
        if (!isWordChar(text[pos])) return null
        var start = pos
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = pos
        while (end < text.length - 1 && isWordChar(text[end + 1])) end++
        return TerminalSelection(Point(start, anchor.y), Point(end, anchor.y))
    } finally {
        buffer.unlock()
    }
}

private fun isWordChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '/' || c == '@'

private fun DrawScope.drawSelectionRect(
    visY: Int,
    x1: Int,
    x2: Int,
    cellWidth: Float,
    cellHeight: Float,
    cols: Int,
    color: Color,
) {
    val left = x1.coerceIn(0, cols).toFloat() * cellWidth
    val right = x2.coerceIn(0, cols).toFloat() * cellWidth
    if (right <= left) return
    drawRect(
        color = color,
        topLeft = Offset(left, visY * cellHeight),
        size = Size(right - left, cellHeight),
        alpha = 0.45f,
    )
}

// ─── Colour resolution ────────────────────────────────────────────────────────

/**
 * Resolves a [TerminalColor] (which may be indexed-ANSI / RGB / supplier-backed)
 * to a Compose [Color] using our Techtical palette for indexed colours.
 */
private fun TerminalColor?.resolveCompose(
    palette: TerminalThemePalette,
    isForeground: Boolean,
    themeFg: Color,
    themeBg: Color,
): Color {
    if (this == null) return if (isForeground) themeFg else themeBg
    if (this.isIndexed) {
        val idx = this.colorIndex
        val safeIdx = if (idx in 0..15) idx else if (isForeground) 7 else 0
        return palette.ansi[safeIdx].toComposeColor()
    }
    val core = runCatching { this.toColor() }.getOrNull()
        ?: return if (isForeground) themeFg else themeBg
    return Color(
        red = core.red.coerceIn(0, 255) / 255f,
        green = core.green.coerceIn(0, 255) / 255f,
        blue = core.blue.coerceIn(0, 255) / 255f,
    )
}

/** Convert a packed RGB int (0xRRGGBB) to a Compose [Color]. */
private fun Int.toComposeColor(): Color = Color(
    red = ((this shr 16) and 0xFF) / 255f,
    green = ((this shr 8) and 0xFF) / 255f,
    blue = (this and 0xFF) / 255f,
)

private data class CellMetrics(val width: Float, val height: Float)
