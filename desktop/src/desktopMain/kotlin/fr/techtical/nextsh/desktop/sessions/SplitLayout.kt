// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import fr.techtical.nextsh.desktop.theme.Burgundy
import java.awt.Cursor

/**
 * Two-pane split container with a draggable divider. The focused pane is
 * signalled by its [PaneHeader] tint, not by a frame border (see [PaneFrame]).
 *
 * Drag responsiveness: the divider uses a **local** ratio (`localRatio`)
 * while the drag is in flight. Pushing every frame through the session
 * state flow was making the divider visibly lag the cursor because the
 * whole SessionTabsScreen subtree would recompose on each delta. We only
 * commit the final ratio to [onRatioChange] on drag end. External ratio
 * changes (orientation toggle, close split) are re-synced via
 * [LaunchedEffect].
 */
@Composable
fun SplitLayout(
    orientation: SplitOrientation,
    ratio: Float,
    focusedSlot: PaneSlot,
    onRatioChange: (Float) -> Unit,
    onFocusFirst: () -> Unit,
    onFocusSecond: () -> Unit,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    var localRatio by remember { mutableStateOf(ratio) }
    LaunchedEffect(ratio) {
        if (ratio != localRatio) localRatio = ratio
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalPx = if (orientation == SplitOrientation.HORIZONTAL) constraints.maxWidth.toFloat()
        else constraints.maxHeight.toFloat()

        when (orientation) {
            SplitOrientation.HORIZONTAL -> Row(modifier = Modifier.fillMaxSize()) {
                PaneFrame(
                    isFocused = focusedSlot == PaneSlot.LEFT_OR_TOP,
                    onFocus = onFocusFirst,
                    modifier = Modifier.weight(localRatio).fillMaxHeight(),
                    content = first,
                )
                Divider(
                    orientation = orientation,
                    onDragDelta = { delta ->
                        if (totalPx > 0f) {
                            localRatio = (localRatio + delta / totalPx).coerceIn(0.20f, 0.80f)
                        }
                    },
                    onDragEnd = { onRatioChange(localRatio) },
                    onDoubleTap = {
                        localRatio = 0.5f
                        onRatioChange(0.5f)
                    },
                )
                PaneFrame(
                    isFocused = focusedSlot == PaneSlot.RIGHT_OR_BOTTOM,
                    onFocus = onFocusSecond,
                    modifier = Modifier.weight(1f - localRatio).fillMaxHeight(),
                    content = second,
                )
            }
            SplitOrientation.VERTICAL -> Column(modifier = Modifier.fillMaxSize()) {
                PaneFrame(
                    isFocused = focusedSlot == PaneSlot.LEFT_OR_TOP,
                    onFocus = onFocusFirst,
                    modifier = Modifier.weight(localRatio).fillMaxWidth(),
                    content = first,
                )
                Divider(
                    orientation = orientation,
                    onDragDelta = { delta ->
                        if (totalPx > 0f) {
                            localRatio = (localRatio + delta / totalPx).coerceIn(0.20f, 0.80f)
                        }
                    },
                    onDragEnd = { onRatioChange(localRatio) },
                    onDoubleTap = {
                        localRatio = 0.5f
                        onRatioChange(0.5f)
                    },
                )
                PaneFrame(
                    isFocused = focusedSlot == PaneSlot.RIGHT_OR_BOTTOM,
                    onFocus = onFocusSecond,
                    modifier = Modifier.weight(1f - localRatio).fillMaxWidth(),
                    content = second,
                )
            }
        }
    }
}

@Composable
private fun PaneFrame(
    isFocused: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // No visible border on the frame: the [PaneHeader] tints itself Burgundy
    // when focused, which is enough to locate keyboard focus without doubling
    // up signals. Pane separation in a split comes from the [Divider] between
    // panes (4 dp Burgundy, draggable).
    Box(
        modifier = modifier
            // Intercept pointer-down at the Initial pass so the focus grab
            // runs before Compose children consume the click (breadcrumbs,
            // file rows, etc.). Clicks landing inside a heavyweight
            // `SwingPanel` child (JediTerm) never reach Compose at all; pane
            // focus for those is driven by the session manager's explicit
            // focus routing (see `setFocusAt` / `terminalFocusEpoch`).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && !it.previousPressed }) onFocus()
                    }
                }
            },
    ) {
        content()
    }
}

@Composable
private fun Divider(
    orientation: SplitOrientation,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val cursor = if (orientation == SplitOrientation.HORIZONTAL) {
        PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
    } else {
        PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR))
    }
    // `Modifier.pointerInput(orientation)` only re-runs its block when
    // `orientation` changes; subsequent recompositions produce fresh
    // `onDragDelta` / `onDragEnd` / `onDoubleTap` closures that wouldn't
    // reach the detectors without `rememberUpdatedState`.
    val currentDelta by rememberUpdatedState(onDragDelta)
    val currentEnd by rememberUpdatedState(onDragEnd)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val dragMod = Modifier.pointerInput(orientation) {
        detectDragGestures(
            onDragEnd = { currentEnd() },
            onDragCancel = { currentEnd() },
            onDrag = { change, dragAmount ->
                change.consume()
                val delta = if (orientation == SplitOrientation.HORIZONTAL) dragAmount.x else dragAmount.y
                currentDelta(delta)
            },
        )
    }
    // Double-tap on the divider resets the split ratio to 50/50: restore
    // of the legacy gesture. `detectTapGestures` runs in a separate
    // pointerInput block so it does not race with `detectDragGestures`.
    val tapMod = Modifier.pointerInput(orientation) {
        detectTapGestures(onDoubleTap = { currentDoubleTap() })
    }
    val sizeMod = if (orientation == SplitOrientation.HORIZONTAL) Modifier.width(4.dp).fillMaxHeight()
    else Modifier.height(4.dp).fillMaxWidth()
    Box(
        modifier = sizeMod
            .background(Burgundy)
            .pointerHoverIcon(cursor)
            .then(dragMod)
            .then(tapMod),
    )
}
