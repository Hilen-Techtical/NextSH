// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_close
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import org.jetbrains.compose.resources.stringResource

/**
 * Carte Techtical partagée pour le contenu d'un `DialogWindow` OS séparé
 * (`undecorated = true`, `transparent = true`) : header (icon-wrap Burgundy
 * + titre SpaceGrotesk + sous-titre optionnel + bouton fermer), body libre
 * (fourni par [content]) et footer aligné à droite (fourni par [footer]).
 *
 * Contrairement à [TechticalDialogCard] (overlay Compose plein écran DANS
 * la fenêtre principale, avec scrim), cette carte n'a PAS de scrim ni de
 * click-outside-to-dismiss : elle remplit toute la fenêtre `DialogWindow`
 * qui l'héberge, et cette fenêtre OS est déjà le conteneur visuel du
 * dialog. Un scrim par-dessus une fenêtre `undecorated`/`transparent`
 * serait redondant et masquerait le contenu (terminal JediTerm) visible en
 * transparence derrière.
 *
 * ESC dismisse via `onPreviewKeyEvent`, comme tous les pickers `DialogWindow`
 * du module. [onPreviewKeyEvent] permet à un appelant d'intercepter d'autres
 * touches (ex. Entrée pour sélectionner le premier résultat dans
 * `SnippetPickerDialog`) sans dupliquer la gestion du focus/ESC : ESC reste
 * toujours prioritaire et n'est jamais transmis à ce hook.
 *
 * Extrait de `HostPickerDialog` (Phase 2.9) pour éliminer la duplication de
 * CardHeader/CardFooter/CloseButton/BtnGhostSm qui existait indépendamment
 * dans chaque fichier `DialogWindow` du module (host/snippet pickers, theme
 * picker, éditeur de thème, confirmation de suppression, color picker).
 */
@Composable
fun TechticalWindowCard(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    footer: @Composable RowScope.() -> Unit,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onDismiss(); true
                } else {
                    onPreviewKeyEvent(ev)
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(Radii.Xl))
                .background(Surface)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Xl)),
        ) {
            WindowCardHeader(title = title, subtitle = subtitle, icon = icon, onClose = onDismiss)
            content()
            WindowCardFooter(content = footer)
        }
    }
}

// ── Header / Footer ──────────────────────────────────────────────────────────

@Composable
internal fun WindowCardHeader(
    title: String,
    subtitle: String?,
    icon: ImageVector,
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
                    .background(Burgundy.copy(alpha = 0.16f), RoundedCornerShape(Radii.Md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = GoldLight, modifier = Modifier.size(13.dp))
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
                        maxLines = 1,
                    )
                }
            }
            WindowCardCloseButton(onClick = onClose)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
    }
}

@Composable
internal fun WindowCardFooter(content: @Composable RowScope.() -> Unit) {
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
internal fun WindowCardCloseButton(onClick: () -> Unit) {
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
        Icon(
            Lucide.X,
            contentDescription = stringResource(Res.string.action_close),
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ── Boutons ───────────────────────────────────────────────────────────────────

/**
 * Bouton ghost compact : action secondaire de footer (Annuler/Fermer).
 *
 * Nommé `WindowCardBtnGhostSm` (pas `BtnGhostSm` tout court) pour éviter
 * toute collision avec le `private fun BtnGhostSm` déjà présent dans
 * `ConfirmDialog.kt`, même package `components`, même signature exacte.
 */
@Composable
internal fun WindowCardBtnGhostSm(onClick: () -> Unit, label: String) {
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
