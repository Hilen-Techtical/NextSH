// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.view.TerminalView
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.ui.components.NextShButton
import fr.techtical.nextsh.ui.sftp.EmbeddedSftpBrowser
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldMuted
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.resolveThemePalette

// ── SplitTerminalLayout ───────────────────────────────────────────────────────

/**
 * Top-level composable that renders two terminal panes side-by-side (HORIZONTAL)
 * or stacked (VERTICAL), separated by a draggable divider.
 *
 * @param splitState          Current split configuration (orientation, pane contents, ratio, focus)
 * @param tabs                All open session tabs
 * @param fontSize            Terminal font size (pt)
 * @param readCtrl            Lambda returning current CTRL-down state
 * @param readAlt             Lambda returning current ALT-down state
 * @param onPaneFocused       Called when the user taps a pane to focus it
 * @param onSplitRatioChanged Called with the new ratio (clamped 0.2–0.8) after dragging
 * @param onTerminalViewReady Called once the [TerminalView] for a pane is ready
 * @param onExitSplit         Ferme le split, appele par le retour du panneau SFTP focalise
 */
@Composable
internal fun SplitTerminalLayout(
    splitState: SplitState,
    tabs: List<SessionTab>,
    customThemes: List<fr.techtical.nextsh.domain.model.CustomTerminalTheme>,
    fontSize: Int,
    readCtrl: () -> Boolean,
    readAlt: () -> Boolean,
    onPaneFocused: (PaneSlot) -> Unit,
    onSplitRatioChanged: (Float) -> Unit,
    onTerminalViewReady: (PaneSlot, TerminalView) -> Unit,
    onCopyModeChanged: (Boolean) -> Unit = {},
    onExitSplit: () -> Unit = {},
    onReconnect: (Int) -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Total size in pixels along the split axis, used to convert drag delta to ratio.
        val totalPx = when (splitState.orientation) {
            SplitOrientation.HORIZONTAL -> constraints.maxWidth.toFloat()
            SplitOrientation.VERTICAL   -> constraints.maxHeight.toFloat()
        }

        when (splitState.orientation) {
            SplitOrientation.HORIZONTAL -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    // ── Left pane ────────────────────────────────────────────
                    PaneWrapper(
                        isFocused = splitState.focusedPane == PaneSlot.LEFT_OR_TOP,
                        onTap     = { onPaneFocused(PaneSlot.LEFT_OR_TOP) },
                        modifier  = Modifier
                            .weight(splitState.splitRatio)
                            .fillMaxHeight(),
                    ) {
                        PaneContentView(
                            slot        = PaneSlot.LEFT_OR_TOP,
                            paneContent = splitState.leftOrTopPane,
                            splitState  = splitState,
                            tabs        = tabs,
                            customThemes = customThemes,
                            fontSize    = fontSize,
                            readCtrl    = readCtrl,
                            readAlt     = readAlt,
                            onTerminalViewReady = onTerminalViewReady,
                            onCopyModeChanged   = onCopyModeChanged,
                            onExitSplit         = onExitSplit,
                            onReconnect = onReconnect,
                        )
                    }

                    // ── Divider ───────────────────────────────────────────────
                    DraggableDivider(
                        orientation = splitState.orientation,
                        totalSize   = totalPx,
                        onDrag      = { ratioChange ->
                            val newRatio = (splitState.splitRatio + ratioChange).coerceIn(0.2f, 0.8f)
                            onSplitRatioChanged(newRatio)
                        },
                        onDoubleTap = {
                            // Reset to equal split on double-tap
                            onSplitRatioChanged(0.5f)
                        },
                    )

                    // ── Right pane ────────────────────────────────────────────
                    PaneWrapper(
                        isFocused = splitState.focusedPane == PaneSlot.RIGHT_OR_BOTTOM,
                        onTap     = { onPaneFocused(PaneSlot.RIGHT_OR_BOTTOM) },
                        modifier  = Modifier
                            .weight(1f - splitState.splitRatio)
                            .fillMaxHeight(),
                    ) {
                        PaneContentView(
                            slot        = PaneSlot.RIGHT_OR_BOTTOM,
                            paneContent = splitState.rightOrBottomPane,
                            splitState  = splitState,
                            tabs        = tabs,
                            customThemes = customThemes,
                            fontSize    = fontSize,
                            readCtrl    = readCtrl,
                            readAlt     = readAlt,
                            onTerminalViewReady = onTerminalViewReady,
                            onCopyModeChanged   = onCopyModeChanged,
                            onExitSplit         = onExitSplit,
                            onReconnect = onReconnect,
                        )
                    }
                }
            }

            SplitOrientation.VERTICAL -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ── Top pane ──────────────────────────────────────────────
                    PaneWrapper(
                        isFocused = splitState.focusedPane == PaneSlot.LEFT_OR_TOP,
                        onTap     = { onPaneFocused(PaneSlot.LEFT_OR_TOP) },
                        modifier  = Modifier
                            .weight(splitState.splitRatio)
                            .fillMaxWidth(),
                    ) {
                        PaneContentView(
                            slot        = PaneSlot.LEFT_OR_TOP,
                            paneContent = splitState.leftOrTopPane,
                            splitState  = splitState,
                            tabs        = tabs,
                            customThemes = customThemes,
                            fontSize    = fontSize,
                            readCtrl    = readCtrl,
                            readAlt     = readAlt,
                            onTerminalViewReady = onTerminalViewReady,
                            onCopyModeChanged   = onCopyModeChanged,
                            onExitSplit         = onExitSplit,
                            onReconnect = onReconnect,
                        )
                    }

                    // ── Divider ───────────────────────────────────────────────
                    DraggableDivider(
                        orientation = splitState.orientation,
                        totalSize   = totalPx,
                        onDrag      = { ratioChange ->
                            val newRatio = (splitState.splitRatio + ratioChange).coerceIn(0.2f, 0.8f)
                            onSplitRatioChanged(newRatio)
                        },
                        onDoubleTap = {
                            // Reset to equal split on double-tap
                            onSplitRatioChanged(0.5f)
                        },
                    )

                    // ── Bottom pane ───────────────────────────────────────────
                    PaneWrapper(
                        isFocused = splitState.focusedPane == PaneSlot.RIGHT_OR_BOTTOM,
                        onTap     = { onPaneFocused(PaneSlot.RIGHT_OR_BOTTOM) },
                        modifier  = Modifier
                            .weight(1f - splitState.splitRatio)
                            .fillMaxWidth(),
                    ) {
                        PaneContentView(
                            slot        = PaneSlot.RIGHT_OR_BOTTOM,
                            paneContent = splitState.rightOrBottomPane,
                            splitState  = splitState,
                            tabs        = tabs,
                            customThemes = customThemes,
                            fontSize    = fontSize,
                            readCtrl    = readCtrl,
                            readAlt     = readAlt,
                            onTerminalViewReady = onTerminalViewReady,
                            onCopyModeChanged   = onCopyModeChanged,
                            onExitSplit         = onExitSplit,
                            onReconnect = onReconnect,
                        )
                    }
                }
            }
        }
    }
}

// ── PaneWrapper ───────────────────────────────────────────────────────────────

/**
 * Container for a single split pane.
 *
 * Draws a 2 dp Gold border when focused. Touch events are detected WITHOUT
 * consuming them so the terminal view embedded inside still receives all touches.
 *
 * @param isFocused Whether this pane currently holds input focus
 * @param onTap     Called when the user taps anywhere inside this pane
 * @param modifier  Layout modifier applied to the outer Box
 * @param content   The pane content (terminal or SFTP placeholder)
 */
@Composable
private fun PaneWrapper(
    isFocused: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .border(
                width = 2.dp,
                color = if (isFocused) Gold else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            // Detect taps without consuming the pointer event so the terminal
            // still receives the touch normally.
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Wait for the first finger down: do NOT consume it.
                    awaitFirstDown(requireUnconsumed = false)
                    onTap()
                }
            },
    ) {
        content()
    }
}

// ── DraggableDivider ──────────────────────────────────────────────────────────

/**
 * A thin strip rendered between the two panes. Supports:
 * - Drag to resize: converts pixel displacement to split-ratio delta.
 * - Double-tap to reset ratio to 0.5 (equal split).
 *
 * A single [pointerInput] block using [awaitEachGesture] handles both drag
 * detection and double-tap detection to avoid gesture conflicts.
 *
 * @param orientation HORIZONTAL (vertical strip) or VERTICAL (horizontal strip)
 * @param totalSize   Parent dimension in px along the split axis
 * @param onDrag      Called with the ratio change (delta px / totalSize)
 * @param onDoubleTap Called when the user double-taps the divider
 * @param modifier    Additional modifier
 */
@Composable
private fun DraggableDivider(
    orientation: SplitOrientation,
    totalSize: Float,
    onDrag: (Float) -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 12 dp touch target; centered 2 dp visible line.
    val isHorizontalSplit = orientation == SplitOrientation.HORIZONTAL

    // Keep callbacks current without restarting the pointerInput coroutine.
    // Without this, the closure captures a stale splitRatio on each drag event.
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)

    Box(
        modifier = modifier
            .then(
                if (isHorizontalSplit) {
                    Modifier
                        .fillMaxHeight()
                        .width(12.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                },
            )
            // ── Unified drag + double-tap detection ────────────────────────
            .pointerInput(totalSize, isHorizontalSplit) {
                var lastTapTime = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var isDragging = false
                    var accumulatedDelta = 0f
                    loop@ while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: break@loop
                        if (!change.pressed) {
                            // Pointer released: check for double-tap
                            if (!isDragging) {
                                val now = SystemClock.uptimeMillis()
                                if (now - lastTapTime < 300L) {
                                    currentOnDoubleTap()
                                    lastTapTime = 0L
                                } else {
                                    lastTapTime = now
                                }
                            }
                            break@loop
                        }
                        val delta = if (isHorizontalSplit) {
                            change.positionChange().x
                        } else {
                            change.positionChange().y
                        }
                        accumulatedDelta += delta
                        if (!isDragging && kotlin.math.abs(accumulatedDelta) > viewConfiguration.touchSlop) {
                            isDragging = true
                        }
                        if (isDragging) {
                            change.consume()
                            if (totalSize > 0f) {
                                currentOnDrag(delta / totalSize)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // ── Visible divider line ────────────────────────────────────────────
        if (isHorizontalSplit) {
            // Vertical line centered in the 12 dp touch strip
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(GoldMuted),
            )
        } else {
            // Horizontal line centered in the 12 dp touch strip
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .fillMaxWidth()
                    .background(GoldMuted),
            )
        }

        // ── Handle indicator (3 dots) ───────────────────────────────────────
        if (isHorizontalSplit) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(4.dp)
                            .background(TextSecondary, RoundedCornerShape(2.dp)),
                    )
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(4.dp)
                            .background(TextSecondary, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

// ── PaneContentView ───────────────────────────────────────────────────────────

/**
 * Renders the content of a single split pane according to its [PaneContent] type.
 *
 * - [PaneContent.Terminal]: looks up the [SessionTab] by index and delegates to
 *   [TerminalViewWrapper].
 * - [PaneContent.Sftp]: delegates to [EmbeddedSftpBrowser] with a keyed ViewModel
 *   instance, providing a full SFTP browser without a Scaffold/TopAppBar wrapper.
 */
@Composable
private fun PaneContentView(
    slot: PaneSlot,
    paneContent: PaneContent,
    splitState: SplitState,
    tabs: List<SessionTab>,
    customThemes: List<fr.techtical.nextsh.domain.model.CustomTerminalTheme>,
    fontSize: Int,
    readCtrl: () -> Boolean,
    readAlt: () -> Boolean,
    onTerminalViewReady: (PaneSlot, TerminalView) -> Unit,
    onCopyModeChanged: (Boolean) -> Unit = {},
    onExitSplit: () -> Unit = {},
    onReconnect: (Int) -> Unit = {},
) {
    when (paneContent) {
        is PaneContent.Terminal -> {
            val tab = tabs.getOrNull(paneContent.tabIndex)
            if (tab != null && (tab.status == SessionStatus.DISCONNECTED || tab.status == SessionStatus.ERROR)) {
                Box(
                    modifier = Modifier.fillMaxSize().background(NearBlack),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        androidx.compose.material3.Text(
                            text = if (tab.status == SessionStatus.ERROR)
                                stringResource(R.string.status_connection_error)
                            else
                                stringResource(R.string.status_session_disconnected),
                            fontFamily = fr.techtical.nextsh.ui.theme.SpaceGroteskFamily,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = if (tab.status == SessionStatus.ERROR) ErrorRed else TextSecondary,
                        )
                        Box(
                            modifier = androidx.compose.ui.Modifier
                                .background(fr.techtical.nextsh.ui.theme.Burgundy, RoundedCornerShape(6.dp))
                                .clickable { onReconnect(paneContent.tabIndex) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            androidx.compose.material3.Text(
                                text = stringResource(R.string.action_reconnect),
                                fontFamily = fr.techtical.nextsh.ui.theme.SpaceGroteskFamily,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = fr.techtical.nextsh.ui.theme.White,
                            )
                        }
                    }
                }
            } else if (tab != null) {
                TerminalViewWrapper(
                    terminalSession = tab.terminalSession,
                    fontSize        = fontSize,
                    palette         = resolveThemePalette(tab.host.terminalTheme, customThemes),
                    readCtrl        = readCtrl,
                    readAlt         = readAlt,
                    isSelected      = { splitState.focusedPane == slot },
                    onCopyModeChanged = onCopyModeChanged,
                    onViewReady     = { view -> onTerminalViewReady(slot, view) },
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(NearBlack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.label_no_session),
                        color = TextSecondary,
                    )
                }
            }
        }

        is PaneContent.Sftp -> {
            EmbeddedSftpBrowser(
                sessionId = paneContent.sessionId,
                hostLabel = paneContent.hostLabel,
                modifier  = Modifier.fillMaxSize(),
                // Seul le panneau focalise prend le retour, sinon les deux
                // panneaux se disputeraient le geste. La chaine du navigateur
                // epuisee, le retour ferme le split.
                enableBackHandler = splitState.focusedPane == slot,
                onBack            = onExitSplit,
            )
        }
    }
}
