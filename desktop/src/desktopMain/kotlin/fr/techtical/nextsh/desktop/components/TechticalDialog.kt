// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary

/**
 * Carte modale Techtical réutilisable : scrim NearBlack@0.65 + carte
 * centrée Surface/Border1/Radii.Xl avec header (icon-wrap + titre +
 * subtitle + ✕), body scrollable et footer right-aligned. ESC + click
 * sur scrim dismissent. Carte consomme ses taps pour ne pas dismisser.
 *
 * Pattern aligné sur HostDetailScreen / TunnelConfigScreen, extrait
 * en composant partagé pour les dialogs Vault et futurs (suppression
 * Tunnel, etc.). Ne couvre pas le cas DialogWindow (HostPicker) qui
 * doit ouvrir une fenêtre OS séparée à cause de JediTerm.
 */
@Composable
fun TechticalDialogCard(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    iconBg: Color = Burgundy.copy(alpha = 0.16f),
    iconTint: Color = GoldLight,
    maxWidth: Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable RowScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack.copy(alpha = 0.65f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        val maxCardHeight = maxHeight * 0.9f

        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .heightIn(max = maxCardHeight)
                .clip(RoundedCornerShape(Radii.Xl))
                .background(Surface)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Xl))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            CardHeader(
                title = title,
                subtitle = subtitle,
                icon = icon,
                iconBg = iconBg,
                iconTint = iconTint,
                onClose = onDismiss,
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                content = content,
            )

            CardFooter(content = footer)
        }
    }
}

@Composable
private fun CardHeader(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    onClose: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(iconBg, RoundedCornerShape(Radii.Md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(Spacing.Md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = TextDisabled,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                }
            }
            CloseButton(onClick = onClose)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
    }
}

@Composable
private fun CardFooter(content: @Composable RowScope.() -> Unit) {
    Column {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val tint = if (hovered) TextPrimary else TextSecondary
    val bg = if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(Radii.Md))
            .background(bg)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Lucide.X, contentDescription = "Fermer", tint = tint, modifier = Modifier.size(14.dp))
    }
}
