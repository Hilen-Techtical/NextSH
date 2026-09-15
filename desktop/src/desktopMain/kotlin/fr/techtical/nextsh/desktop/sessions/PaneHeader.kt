// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Columns2
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Rows2
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.X
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.InfoBlue
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.SuccessGreen
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.pane_header_broadcast_badge
import fr.techtical.nextsh.desktop.generated.resources.pane_header_close
import fr.techtical.nextsh.desktop.generated.resources.pane_header_split_h
import fr.techtical.nextsh.desktop.generated.resources.pane_header_split_v
import org.jetbrains.compose.resources.stringResource

/** Which kind of content the pane holds: determines the leading icon. */
enum class PaneIcon { Terminal, Sftp }

/**
 * Compact header bar displayed above each Terminal or SFTP pane.
 *
 * Layout (left → right):
 *  - kind icon (Terminal / Folder)
 *  - host label (TextSecondary when unfocused, TextPrimary when focused)
 *  - "- user@host:port" in GoldMuted
 *  - live latency dot + ms : grey dot + "-" when [showLatency] is true and
 *    [latencyMs] is null (signal "session live, waiting first probe / lost"),
 *    SuccessGreen + "${ms}ms" when measured
 *  - Spacer (push actions right)
 *  - split-horizontal / split-vertical / close buttons (hidden when
 *    [canSplit] = false for the split buttons)
 *
 * Visual spec from the Claude Design maquette:
 *  - min-height 24 dp, padding 4×10 dp, gap 8 dp
 *  - background #111111, border-bottom 1 dp Border1
 *  - When focused: background Burgundy α=0.10, border-bottom Burgundy
 *
 * [isBroadcastTarget] renders a small "Diffusion"/"Broadcast" pill right
 * after the host label when this pane is one of the active tab's broadcast
 * ("synchronize-panes", Ctrl+Shift+B) targets: a deliberately distinct
 * InfoBlue accent so it never reads as the Burgundy focus tint.
 */
@Composable
fun PaneHeader(
    icon: PaneIcon,
    hostName: String,
    userAtHost: String,
    latencyMs: Long?,
    showLatency: Boolean,
    isFocused: Boolean,
    isBroadcastTarget: Boolean = false,
    canSplit: Boolean,
    canClose: Boolean,
    onSplitHorizontal: () -> Unit,
    onSplitVertical: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgBase = Color(0xFF111111)
    val bgColor = if (isFocused) bgBase.copy(alpha = 1f).let {
        // Overlay Burgundy α=0.10 on top of #111111
        Color(
            red = it.red * (1f - 0.10f) + Burgundy.red * 0.10f,
            green = it.green * (1f - 0.10f) + Burgundy.green * 0.10f,
            blue = it.blue * (1f - 0.10f) + Burgundy.blue * 0.10f,
            alpha = 1f,
        )
    } else bgBase
    val borderColor = if (isFocused) Burgundy else Border1
    val labelColor = if (isFocused) TextPrimary else TextSecondary

    val paneIcon = if (icon == PaneIcon.Terminal) Lucide.Terminal else Lucide.Folder

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 24.dp)
            .background(bgColor)
            .drawBehind {
                // 1 dp border at the bottom
                val strokePx = 1.dp.toPx()
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height - strokePx / 2),
                    end = Offset(size.width, size.height - strokePx / 2),
                    strokeWidth = strokePx,
                )
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Kind icon, fixed width, never compressed.
        Icon(
            imageVector = paneIcon,
            contentDescription = null,
            tint = TextDisabled,
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(8.dp))

        // Host label + user@host as a single annotated Text taking the
        // remaining space (weight 1f) with maxLines = 1 + ellipsis. This
        // way the action buttons on the right are NEVER pushed off-screen
        // even when the pane is very narrow: the label gracefully truncates
        // first, the user@host part second, the actions stay reachable.
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = labelColor, fontWeight = FontWeight.Medium)) {
                    append(hostName)
                }
                append("  ")
                withStyle(SpanStyle(color = GoldMuted)) {
                    append("- $userAtHost")
                }
            },
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Broadcast target pill, deliberately InfoBlue, distinct from the
        // Burgundy focus tint, so a pane can visibly be both focused AND a
        // broadcast target at once without the two states blending together.
        if (isBroadcastTarget) {
            Spacer(Modifier.width(8.dp))
            BroadcastBadge()
        }

        // Latency: green when measured, grey "● -" placeholder when the
        // session is live but no probe has succeeded yet (or last probe lost).
        // Always shown after the label since it has fixed width: does not
        // compete with the label for space.
        if (showLatency) {
            Spacer(Modifier.width(8.dp))
            if (latencyMs != null) {
                Text(
                    text = "● ${latencyMs}ms",
                    color = SuccessGreen,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = "● -",
                    color = TextDisabled,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }

        // Pane action buttons, fixed width on the right, always visible.
        // Small left-margin so they don't touch the latency text when the
        // label collapses.
        if (canSplit || canClose) {
            Spacer(Modifier.width(8.dp))
        }
        if (canSplit) {
            PaneActionButton(
                onClick = onSplitHorizontal,
                contentDescription = stringResource(Res.string.pane_header_split_h),
            ) {
                Icon(Lucide.Columns2, contentDescription = null, tint = TextDisabled, modifier = Modifier.size(12.dp))
            }
            PaneActionButton(
                onClick = onSplitVertical,
                contentDescription = stringResource(Res.string.pane_header_split_v),
            ) {
                Icon(Lucide.Rows2, contentDescription = null, tint = TextDisabled, modifier = Modifier.size(12.dp))
            }
        }

        if (canClose) {
            PaneActionButton(
                onClick = onClose,
                contentDescription = stringResource(Res.string.pane_header_close),
            ) {
                Icon(Lucide.X, contentDescription = null, tint = TextDisabled, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/**
 * Small "Diffusion" / "Broadcast" pill: outline-only (no fill) so it stays
 * discreet next to the latency indicator instead of competing with it.
 */
@Composable
private fun BroadcastBadge() {
    Box(
        modifier = Modifier
            .border(1.dp, InfoBlue, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = stringResource(Res.string.pane_header_broadcast_badge),
            color = InfoBlue,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

/**
 * 22×22 dp icon button with a subtle hover background matching the maquette's
 * `rgba(255,255,255,0.04)` action button spec.
 */
@Composable
private fun PaneActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(22.dp)
            .hoverable(interactionSource)
            .background(
                color = if (hovered) Color.White.copy(alpha = 0.04f) else Color.Transparent,
                shape = RoundedCornerShape(3.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            // Never holds keyboard focus: a focused button re-activates on
            // Enter/Space, so Esc-dismissing the split host picker and then
            // pressing Enter would reopen it instead of typing into the
            // terminal (same defect class as the TabBar snippet button).
            modifier = Modifier
                .focusProperties { canFocus = false }
                .size(22.dp),
        ) {
            content()
        }
    }
}
