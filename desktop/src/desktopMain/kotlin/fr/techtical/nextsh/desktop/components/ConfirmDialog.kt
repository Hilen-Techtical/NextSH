// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.confirm_dialog_default_cancel
import fr.techtical.nextsh.desktop.generated.resources.confirm_dialog_default_confirm
import org.jetbrains.compose.resources.stringResource

/**
 * Dialog de confirmation destructive : DA Techtical refondue (Phase 2.9).
 *
 * Overlay Compose dans la fenêtre principale (pas de DialogWindow OS, pas
 * d'entrée taskbar). Pattern aligné sur HostDetailScreen : scrim plein
 * écran NearBlack@0.65 + carte centrée max 420dp + header icon-wrap rouge
 * + body TextSecondary + footer Annuler ghost / Supprimer danger.
 *
 * Dismiss : click sur scrim, ESC, ou bouton Annuler. La carte propre
 * consomme ses taps via `pointerInput` vide pour ne pas remonter au scrim.
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    confirmLabel: String = stringResource(Res.string.confirm_dialog_default_confirm),
    cancelLabel: String = stringResource(Res.string.confirm_dialog_default_cancel),
    icon: ImageVector = Lucide.Trash2,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
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
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(Radii.Xl))
                .background(Surface)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Xl))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(ErrorRed.copy(alpha = 0.12f), RoundedCornerShape(Radii.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(Spacing.Md))
                Text(
                    text = title,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))

            // Body
            Text(
                text = message,
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))

            // Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BtnGhostSm(onClick = onDismiss, label = cancelLabel)
                Spacer(Modifier.width(Spacing.Sm))
                BtnDangerSolid(onClick = onConfirm, label = confirmLabel, icon = icon)
            }
        }
    }
}

@Composable
private fun BtnGhostSm(onClick: () -> Unit, label: String) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent
    val fg = if (hovered) TextPrimary else TextSecondary
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = bg, contentColor = fg),
        border = BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Bouton primaire destructif. On utilise `OutlinedButton` avec border
 * transparente (et non `Button` Material3) parce que `Button` applique un
 * tonal-elevation overlay sur `containerColor` qui décale la teinte
 * visible : la couleur effective du fond ne correspondait plus à
 * `ErrorRed`. Hover géré manuellement (idle = ErrorRed, hover = teinte
 * légèrement plus claire `#E07788`) au lieu de laisser le ripple par
 * défaut. Garde `White` en foreground en toutes circonstances.
 */
@Composable
private fun BtnDangerSolid(onClick: () -> Unit, label: String, icon: ImageVector) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (hovered) Color(0xFFE07788) else ErrorRed
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = White,
        ),
        border = BorderStroke(0.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, tint = White, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = White)
    }
}
