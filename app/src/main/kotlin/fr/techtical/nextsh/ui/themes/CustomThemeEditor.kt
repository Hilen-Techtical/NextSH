// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.ui.components.ColorPickerDialog
import fr.techtical.nextsh.ui.components.NextShTextField
import fr.techtical.nextsh.ui.components.opaque
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.TERMINAL_THEMES
import fr.techtical.nextsh.ui.theme.TerminalThemeId
import fr.techtical.nextsh.ui.theme.TerminalThemePalette
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White

// ── Sentinel + factory ──────────────────────────────────────────────────────

/** Sentinel + factory pour le mode "créer un nouveau thème" de l'éditeur. */
object CustomThemeEditorState {
    /**
     * Thème vierge initialisé depuis le preset par défaut. Utilisé comme
     * valeur initiale de l'éditeur lors de la création d'un nouveau thème.
     * Son id est un UUID frais (décision 3).
     */
    val NEW: CustomTerminalTheme
        get() {
            val preset = TERMINAL_THEMES.getValue(TerminalThemeId.TECHTICAL_DARK)
            return CustomTerminalTheme(
                id = java.util.UUID.randomUUID().toString(),
                name = "",
                background = preset.background,
                foreground = preset.foreground,
                cursor = preset.cursor,
                selectionBg = preset.selectionBg,
                ansi = preset.ansi.toList(),
            )
        }
}

// ── Slot cible couleur ──────────────────────────────────────────────────────

/** Identifie quel slot de couleur le picker est en train d'éditer. */
internal sealed interface ColorTarget {
    data object Background : ColorTarget
    data object Foreground : ColorTarget
    data object Cursor : ColorTarget
    data object Selection : ColorTarget
    data class Ansi(val index: Int) : ColorTarget
}

// ── Helpers ─────────────────────────────────────────────────────────────────

/** Formate un ARGB int en #RRGGBB (opaque, alpha ignoré) pour l'affichage du slot. */
internal fun formatHexRgb(argb: Int): String {
    val rgb = argb and 0x00FFFFFF
    return "#" + rgb.toString(16).uppercase().padStart(6, '0')
}

/**
 * Recombine le RGB de [picked] avec l'alpha de [base]. Utilisé pour le slot
 * de sélection afin que l'utilisateur choisisse teinte/saturation/valeur tout
 * en conservant l'alpha existant (généralement translucide, ex. 0x44).
 */
internal fun withAlphaOf(base: Int, picked: Int): Int =
    (base and 0xFF000000.toInt()) or (picked and 0x00FFFFFF)

// ── Composables extraits ─────────────────────────────────────────────────────

/**
 * Prévisualisation miniature d'une palette de thème terminal.
 * Affiche trois lignes fake-terminal avec les couleurs de la palette.
 */
@Composable
internal fun TerminalThemePreview(palette: TerminalThemePalette) {
    val bg = Color(palette.background)
    val fg = Color(palette.foreground)
    val green = Color(palette.ansi[2])
    val blue = Color(palette.ansi[4])
    val cursorColor = Color(palette.cursor)
    val monoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.sp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(Radii.Sm))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row {
            Text("user@host", style = monoStyle, color = green)
            Text(":~$ ", style = monoStyle, color = fg)
            Text("ls", style = monoStyle, color = fg)
        }
        Text("Documents  Downloads  Music", style = monoStyle, color = blue)
        Row {
            Text("user@host", style = monoStyle, color = green)
            Text(":~$ ", style = monoStyle, color = fg)
            Box(
                modifier = Modifier
                    .size(width = 6.dp, height = 12.dp)
                    .background(cursorColor),
            )
        }
    }
}

/**
 * Slot de couleur unique : un swatch cliquable + label. Taper le swatch ouvre
 * le [ColorPickerDialog] HSV pour ce slot ; la saisie hex vit dans le picker.
 */
@Composable
internal fun ColorSlot(
    label: String,
    argb: Int,
    onClick: () -> Unit,
) {
    val swatchDescription = stringResource(R.string.color_picker_edit_slot, label)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Sm))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.Xs),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(Radii.Sm))
                .background(Color(argb))
                .border(1.dp, Border1, RoundedCornerShape(Radii.Sm))
                .semantics { contentDescription = swatchDescription },
        )
        Spacer(Modifier.width(Spacing.Sm))
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatHexRgb(argb),
            color = TextSecondary,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
        )
    }
}

/**
 * Dialog d'édition d'un thème terminal personnalisé. Chaque couleur est éditée
 * via un swatch ouvrant le [ColorPickerDialog] HSV. La sauvegarde est activée
 * dès que le nom est non-vide.
 */
@Composable
internal fun CustomThemeEditorDialog(
    initial: CustomTerminalTheme,
    onSave: (CustomTerminalTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var background by remember(initial.id) { mutableStateOf(opaque(initial.background)) }
    var foreground by remember(initial.id) { mutableStateOf(opaque(initial.foreground)) }
    var cursor by remember(initial.id) { mutableStateOf(opaque(initial.cursor)) }
    // La sélection conserve son alpha source (surlignage translucide). Le picker
    // retourne du RGB opaque, mais withAlphaOf ré-applique l'alpha courant à la
    // confirmation et la valeur est sauvegardée brute, préservant le highlight.
    var selectionBg by remember(initial.id) { mutableStateOf(initial.selectionBg) }
    val ansi = remember(initial.id) {
        mutableStateListOf<Int>().apply {
            val padded = initial.ansi.take(16).map { opaque(it) }.toMutableList()
            while (padded.size < 16) padded.add(0xFF000000.toInt())
            addAll(padded)
        }
    }

    // Non-null pendant que le picker HSV est ouvert pour un slot donné.
    var pickerTarget by remember(initial.id) { mutableStateOf<ColorTarget?>(null) }

    val allValid = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.dialog_custom_theme_title),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // Prévisualisation live de la palette en cours d'édition.
                val previewPalette = TerminalThemePalette(
                    background = background,
                    foreground = foreground,
                    cursor = cursor,
                    selectionBg = selectionBg,
                    ansi = IntArray(16) { ansi[it] },
                )
                TerminalThemePreview(palette = previewPalette)
                Spacer(Modifier.height(Spacing.Xs))
                NextShTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.label_theme_name),
                    isError = name.isBlank(),
                    placeholder = stringResource(R.string.placeholder_theme_name),
                )
                ColorSlot(stringResource(R.string.label_theme_background), background) { pickerTarget = ColorTarget.Background }
                ColorSlot(stringResource(R.string.label_theme_foreground), foreground) { pickerTarget = ColorTarget.Foreground }
                ColorSlot(stringResource(R.string.label_theme_cursor), cursor) { pickerTarget = ColorTarget.Cursor }
                ColorSlot(stringResource(R.string.label_theme_selection), selectionBg) { pickerTarget = ColorTarget.Selection }
                Text(
                    text = stringResource(R.string.label_theme_ansi).uppercase(),
                    color = GoldLight,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                ansi.forEachIndexed { index, color ->
                    ColorSlot(
                        label = stringResource(R.string.label_theme_ansi_index, index),
                        argb = color,
                    ) { pickerTarget = ColorTarget.Ansi(index) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (allValid) {
                        onSave(
                            initial.copy(
                                name = name.trim(),
                                background = background,
                                foreground = foreground,
                                cursor = cursor,
                                selectionBg = selectionBg,
                                ansi = ansi.toList(),
                            )
                        )
                    }
                },
                enabled = allValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor = White,
                    disabledContainerColor = Burgundy.copy(alpha = 0.4f),
                    disabledContentColor = White.copy(alpha = 0.6f),
                ),
                shape = RoundedCornerShape(Radii.Md),
            ) {
                Text(stringResource(R.string.action_save), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = TextSecondary)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(Radii.Lg),
    )

    // Overlay picker HSV pour le slot sélectionné.
    pickerTarget?.let { target ->
        val current = when (target) {
            ColorTarget.Background -> background
            ColorTarget.Foreground -> foreground
            ColorTarget.Cursor -> cursor
            ColorTarget.Selection -> selectionBg
            is ColorTarget.Ansi -> ansi[target.index]
        }
        ColorPickerDialog(
            initialArgb = current,
            onConfirm = { picked ->
                when (target) {
                    ColorTarget.Background -> background = picked
                    ColorTarget.Foreground -> foreground = picked
                    ColorTarget.Cursor -> cursor = picked
                    // Le surlignage reste translucide : conserver l'alpha du slot (ex. 0x44).
                    ColorTarget.Selection -> selectionBg = withAlphaOf(selectionBg, picked)
                    is ColorTarget.Ansi -> ansi[target.index] = picked
                }
                pickerTarget = null
            },
            onDismiss = { pickerTarget = null },
        )
    }
}
