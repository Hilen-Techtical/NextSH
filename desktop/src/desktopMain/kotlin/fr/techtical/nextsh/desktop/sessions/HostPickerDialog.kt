// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Server
import fr.techtical.nextsh.desktop.components.WindowCardBtnGhostSm
import fr.techtical.nextsh.desktop.components.TechticalWindowCard
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.window.PopupRoundedCorners
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.host_picker_cancel
import fr.techtical.nextsh.desktop.generated.resources.host_picker_empty_hint
import fr.techtical.nextsh.desktop.generated.resources.host_picker_empty_title
import fr.techtical.nextsh.desktop.generated.resources.host_picker_subtitle
import fr.techtical.nextsh.desktop.generated.resources.host_picker_title
import fr.techtical.nextsh.shared.domain.model.Host
import org.jetbrains.compose.resources.stringResource

/**
 * Host picker, refonte Phase 2.9 (DA Techtical).
 *
 * Reste un [DialogWindow] OS séparé (et non un overlay Compose interne)
 * parce que l'écran Sessions porte un widget JediTerm via heavyweight
 * `SwingPanel` qui perce tout overlay Compose dans la même fenêtre. Une
 * fenêtre OS séparée est la seule approche fiable (cf. CmdKOverlay).
 *
 * Chrome : window `undecorated = true` + `transparent = true`, contenu
 * stylé via [TechticalWindowCard] (composant partagé, cf.
 * `components/TechticalWindowCard.kt`) : card Surface/Border1/Radii.Xl +
 * header (icon-wrap Burgundy + titre + ✕) + body LazyColumn de
 * HostPickerRow + footer Annuler ghost. ESC dismisse via
 * `onPreviewKeyEvent` (géré par la carte partagée).
 */
@Composable
fun HostPickerDialog(
    hosts: List<Host>,
    onPick: (Host) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDialogState(size = DpSize(480.dp, 520.dp))
    val title = stringResource(Res.string.host_picker_title)
    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        title = title,
        undecorated = true,
        transparent = true,
        resizable = false,
    ) {
        PopupRoundedCorners(window, Radii.Xl)
        WindowCaptureProtection(window)
        TechticalWindowCard(
            title = title,
            icon = Lucide.Server,
            onDismiss = onDismiss,
            subtitle = stringResource(Res.string.host_picker_subtitle),
            footer = {
                WindowCardBtnGhostSm(onClick = onDismiss, label = stringResource(Res.string.host_picker_cancel))
            },
        ) {
            if (hosts.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    items(hosts, key = { it.id }) { host ->
                        HostPickerRow(host = host, onPick = onPick)
                    }
                }
            }
        }
    }
}

// ── Liste ───────────────────────────────────────────────────────────────────

@Composable
private fun HostPickerRow(host: Host, onPick: (Host) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val borderColor = if (hovered) Border2 else Border1
    val bg = if (hovered) SurfaceVariant.copy(alpha = 0.6f) else SurfaceVariant.copy(alpha = 0.3f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Md))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(Radii.Md))
            .clickable(interactionSource = interactionSource, indication = null) { onPick(host) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Server, contentDescription = null, tint = Gold, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(Spacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = host.label,
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Text(
                text = "${host.username}@${host.hostname}:${host.port}",
                color = TextSecondary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(Spacing.Sm))
        AuthBadge(authTypeName = host.authType.name)
    }
}

@Composable
private fun AuthBadge(authTypeName: String) {
    Box(
        modifier = Modifier
            .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(3.dp))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = authTypeName,
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Lucide.Server,
            contentDescription = null,
            tint = GoldMuted,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(Res.string.host_picker_empty_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(Res.string.host_picker_empty_hint),
            color = TextSecondary,
            fontSize = 11.sp,
        )
    }
}
