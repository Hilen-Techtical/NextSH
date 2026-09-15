// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import fr.techtical.nextsh.desktop.components.WindowCardBtnGhostSm
import fr.techtical.nextsh.desktop.components.TechticalWindowCard
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.action_save
import fr.techtical.nextsh.desktop.generated.resources.theme_delete_message
import fr.techtical.nextsh.desktop.generated.resources.theme_delete_title
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_ansi
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_ansi_index
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_background
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_cursor
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_foreground
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_name
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_selection
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_subtitle
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_title
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_close
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_customs
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_delete
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_edit
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_manage
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_manage_empty
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_manage_subtitle
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_new
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_presets
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_selected
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_subtitle
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_title
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.TerminalThemeId
import fr.techtical.nextsh.desktop.theme.TerminalThemePalette
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.desktop.theme.paletteFor
import fr.techtical.nextsh.desktop.theme.toPalette
import fr.techtical.nextsh.desktop.window.PopupRoundedCorners
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Theme picker surfaced from the tab bar. Lists the 9 presets AND the user's
 * custom themes, plus a "new custom theme" entry. Custom rows expose edit/delete.
 *
 * Uses [DialogWindow] (separate OS window): a same-window Compose `Popup`/`Dialog`
 * is punched through by the heavyweight JediTerm [androidx.compose.ui.awt.SwingPanel].
 * The editor is likewise a [DialogWindow] for the same reason.
 *
 * Selection applies live: [onThemeChange] forwards the chosen theme NAME (preset
 * enum name or custom UUID) to the session manager, which hot-swaps the palette.
 */
@Composable
fun TerminalThemePickerButton(
    currentThemeName: String,
    customThemes: List<CustomTerminalTheme>,
    onThemeChange: (String) -> Unit,
    onSaveCustomTheme: (CustomTerminalTheme) -> Unit,
    onDeleteCustomTheme: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val contentDesc = stringResource(Res.string.theme_picker_title)
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(Lucide.Palette, contentDescription = contentDesc, tint = Gold)
    }
    if (open) {
        ThemePickerDialog(
            currentThemeName = currentThemeName,
            customThemes = customThemes,
            onThemeChange = { picked ->
                open = false
                onThemeChange(picked)
            },
            onSaveCustomTheme = onSaveCustomTheme,
            onDeleteCustomTheme = onDeleteCustomTheme,
            onDismiss = { open = false },
        )
    }
}

/**
 * The theme picker dialog. Reused from three entry points:
 *  - the in-session tab-bar [TerminalThemePickerButton]
 *  - the Host detail screen's "Gérer les thèmes" button (no live session needed)
 *  - the global Settings screen's "Thèmes du terminal" manager ([manageMode] = true)
 *
 * Lists presets + customs, with edit/delete/new. Selection forwards [onThemeChange]
 * (only meaningful in-session; the Host screen passes a no-op there since the host
 * theme is chosen via its own dropdown).
 *
 * When [manageMode] is `true` the dialog is a pure CRUD manager with no host to
 * assign to: the presets section and the selection checkmarks are hidden (presets
 * aren't editable), only the user's custom themes are listed (each with Edit +
 * Delete), and clicking a row opens the editor instead of selecting. The window
 * title switches to the "manage" string. [onThemeChange] is never invoked in this
 * mode. When [manageMode] is `false` the behaviour is unchanged.
 */
@Composable
internal fun ThemePickerDialog(
    currentThemeName: String,
    customThemes: List<CustomTerminalTheme>,
    onThemeChange: (String) -> Unit,
    onSaveCustomTheme: (CustomTerminalTheme) -> Unit,
    onDeleteCustomTheme: (String) -> Unit,
    onDismiss: () -> Unit,
    manageMode: Boolean = false,
) {
    val title = if (manageMode) {
        stringResource(Res.string.theme_picker_manage)
    } else {
        stringResource(Res.string.theme_picker_title)
    }
    val closeLabel = stringResource(Res.string.theme_picker_close)
    // Manage mode is a pure CRUD manager: the selection-oriented subtitle
    // ("presets and custom themes") would be misleading there since presets
    // are hidden and nothing gets assigned.
    val subtitle = if (manageMode) {
        stringResource(Res.string.theme_picker_manage_subtitle)
    } else {
        stringResource(Res.string.theme_picker_subtitle)
    }
    // Adaptive height: with 0-2 custom themes and manageMode = true (Settings'
    // "Gérer les thèmes"), the presets section is hidden entirely, so the old
    // fixed 560.dp height rendered a mostly-blank window. The height is now
    // estimated from the actual row count (see [estimatedThemePickerHeight])
    // and clamped between a floor and the previous fixed height as ceiling.
    // Width stays fixed: only the reported "big empty popup" complaint was
    // about height.
    val presetCount = TerminalThemeId.entries.size
    val estimatedHeight = remember(manageMode, customThemes.size) {
        estimatedThemePickerHeight(manageMode, presetCount, customThemes.size)
    }
    val state = rememberDialogState(size = DpSize(420.dp, estimatedHeight))
    // DialogState.size is a mutable property, so if the custom theme count
    // changes while the picker stays open (e.g. deleting the last custom
    // theme in manage mode), the window height re-adjusts instead of staying
    // stuck at whatever it was when first opened.
    LaunchedEffect(estimatedHeight) {
        state.size = DpSize(state.size.width, estimatedHeight)
    }

    // null = editor closed; non-null = editing/creating this theme.
    var editing by remember { mutableStateOf<CustomTerminalTheme?>(null) }
    // Non-null while a delete confirmation is shown.
    var deleting by remember { mutableStateOf<CustomTerminalTheme?>(null) }

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
            icon = Lucide.Palette,
            onDismiss = onDismiss,
            subtitle = subtitle,
            footer = {
                WindowCardBtnGhostSm(onClick = onDismiss, label = closeLabel)
            },
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                // Presets are read-only built-ins: only shown when the dialog is
                // assigning a theme (selection mode). In manage mode there is no
                // host to assign to, so the presets section is hidden entirely.
                if (!manageMode) {
                    item {
                        SectionLabel(stringResource(Res.string.theme_picker_presets))
                    }
                    items(TerminalThemeId.entries) { id ->
                        ThemeRow(
                            label = id.displayName,
                            palette = paletteFor(id),
                            selected = id.name == currentThemeName,
                            onPick = { onThemeChange(id.name) },
                        )
                    }
                    item {
                        Spacer(Modifier.height(6.dp))
                        SectionLabel(stringResource(Res.string.theme_picker_customs))
                    }
                }
                if (manageMode && customThemes.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(Res.string.theme_picker_manage_empty),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                items(customThemes, key = { it.id }) { theme ->
                    ThemeRow(
                        label = theme.name,
                        palette = theme.toPalette(),
                        // No selection state in manage mode (nothing to assign).
                        selected = !manageMode && theme.id == currentThemeName,
                        // Manage mode: clicking the row edits it (the primary action)
                        // instead of selecting it.
                        onPick = { if (manageMode) editing = theme else onThemeChange(theme.id) },
                        onEdit = { editing = theme },
                        onDelete = { deleting = theme },
                    )
                }
                item {
                    TextButton(onClick = { editing = newCustomTheme() }) {
                        Text(stringResource(Res.string.theme_picker_new), color = Gold)
                    }
                }
            }
        }
    }

    editing?.let { initial ->
        CustomThemeEditorWindow(
            initial = initial,
            onSave = { theme ->
                editing = null
                onSaveCustomTheme(theme)
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { theme ->
        DeleteThemeConfirmWindow(
            theme = theme,
            onConfirm = {
                deleting = null
                onDeleteCustomTheme(theme.id)
            },
            onDismiss = { deleting = null },
        )
    }
}

// ── Adaptive height (ThemePickerDialog only: see call site) ────────────────

/** Floor for [ThemePickerDialog]'s height: enough for header + footer + a couple of rows. */
private val ThemePickerMinHeight = 300.dp

/** Ceiling for [ThemePickerDialog]'s height: the previous fixed height, kept as the cap. */
private val ThemePickerMaxHeight = 560.dp

private val ThemeRowHeightEstimate = 40.dp // one preset/custom row: 8.dp*2 vertical padding + ~24.dp content/icon-button
private val ThemeSectionLabelHeightEstimate = 24.dp // SectionLabel text + vertical padding
private val ThemeListTrailingHeightEstimate = 40.dp // "+ Nouveau thème" trailing item

/**
 * Header + footer + `LazyColumn` content padding + inter-item spacing that
 * doesn't scale with row count, i.e. everything in [ThemePickerDialog] that
 * isn't a preset row, a custom-theme row, or a section label.
 */
private val ThemePickerChromeHeightEstimate = 150.dp

/**
 * Rough estimate of [ThemePickerDialog]'s natural content height, given how
 * many rows it will actually render. See the `LazyColumn` body in
 * [ThemePickerDialog] for the exact item structure this mirrors:
 *  - selection mode (`!manageMode`): a "PRESETS" label + [presetCount] rows,
 *    then a "CUSTOM THEMES" label + [customCount] rows;
 *  - manage mode: no presets, no section label, just [customCount] rows, or
 *    a single empty-state line when there are none.
 *
 * This is deliberately approximate (row heights vary a little with font
 * metrics/DPI) rather than pixel-exact: the body stays a scrollable
 * `LazyColumn` regardless, so under-estimating just means a bit of scroll,
 * never clipped content. The result is clamped to
 * [ThemePickerMinHeight]..[ThemePickerMaxHeight] before being returned.
 */
private fun estimatedThemePickerHeight(manageMode: Boolean, presetCount: Int, customCount: Int): Dp {
    var height = ThemePickerChromeHeightEstimate + ThemeListTrailingHeightEstimate

    if (!manageMode) {
        // "PRESETS" label + its rows, "CUSTOM THEMES" label (customs' rows added below).
        height += ThemeSectionLabelHeightEstimate + ThemeRowHeightEstimate * presetCount
        height += ThemeSectionLabelHeightEstimate
    } else if (customCount == 0) {
        // manage mode with no custom themes: a single empty-state text line
        // instead of a section label, roughly the same height.
        height += ThemeSectionLabelHeightEstimate
    }

    height += ThemeRowHeightEstimate * customCount

    return height.coerceIn(ThemePickerMinHeight, ThemePickerMaxHeight)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Gold,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun ThemeRow(
    label: String,
    palette: TerminalThemePalette,
    selected: Boolean,
    onPick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    // Hover highlight, same visual pattern as CmdKResultRow (components/CmdK.kt):
    // a translucent Burgundy tint on Radii.Sm rounded corners, driven by the
    // standard hoverable/collectIsHoveredAsState pair used elsewhere in the
    // module (e.g. HostPickerRow). Previously this row had a static
    // `.background(Surface)` (identical to the card's own background) so
    // rows never visibly reacted to the mouse.
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val rowBg = if (hovered) Burgundy.copy(alpha = 0.18f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Sm))
            .background(rowBg)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onPick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorSwatch(palette.background)
        Spacer(Modifier.width(4.dp))
        ColorSwatch(palette.foreground)
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = if (selected) Gold else TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Lucide.Check, contentDescription = stringResource(Res.string.theme_picker_selected), tint = Gold)
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.Pencil, contentDescription = stringResource(Res.string.theme_picker_edit), tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.Trash2, contentDescription = stringResource(Res.string.theme_picker_delete), tint = ErrorRed, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ColorSwatch(argb: Int) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(argb))
            .border(1.dp, Border1, RoundedCornerShape(3.dp)),
    )
}

@Composable
internal fun CustomThemeEditorWindow(
    initial: CustomTerminalTheme,
    onSave: (CustomTerminalTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(Res.string.theme_editor_title)
    val subtitle = stringResource(Res.string.theme_editor_subtitle)
    val state = rememberDialogState(size = DpSize(460.dp, 660.dp))

    var name by remember(initial.id) { mutableStateOf(initial.name) }
    // Colors are held as opaque ARGB Ints. Each swatch opens the ColorPicker.
    var background by remember(initial.id) { mutableStateOf(initial.background) }
    var foreground by remember(initial.id) { mutableStateOf(initial.foreground) }
    var cursor by remember(initial.id) { mutableStateOf(initial.cursor) }
    var selectionBg by remember(initial.id) { mutableStateOf(initial.selectionBg) }
    val ansi = remember(initial.id) {
        mutableStateListOf<Int>().apply {
            val padded = initial.ansi.take(16).toMutableList()
            while (padded.size < 16) padded.add(0xFF000000.toInt())
            addAll(padded)
        }
    }

    // Which color slot is being edited (null = picker closed).
    var editingSlot by remember(initial.id) { mutableStateOf<ColorSlot?>(null) }

    val allValid = name.isNotBlank()

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
            icon = Lucide.Palette,
            onDismiss = onDismiss,
            subtitle = subtitle,
            footer = {
                WindowCardBtnGhostSm(onClick = onDismiss, label = stringResource(Res.string.action_cancel))
                Spacer(Modifier.width(Spacing.Sm))
                Button(
                    onClick = {
                        if (allValid) {
                            onSave(
                                initial.copy(
                                    name = name.trim(),
                                    // Force opaque: bg/fg/cursor/ANSI are always alpha 0xFF.
                                    background = opaqueArgb(background),
                                    foreground = opaqueArgb(foreground),
                                    cursor = opaqueArgb(cursor),
                                    // Selection keeps its alpha (translucent highlight), saved raw.
                                    selectionBg = selectionBg,
                                    ansi = ansi.map { opaqueArgb(it) },
                                )
                            )
                        }
                    },
                    enabled = allValid,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = NearBlack),
                ) {
                    Text(stringResource(Res.string.action_save))
                }
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.theme_editor_name)) },
                    singleLine = true,
                    isError = name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorSlotRow(stringResource(Res.string.theme_editor_background), background) { editingSlot = ColorSlot.Background }
                ColorSlotRow(stringResource(Res.string.theme_editor_foreground), foreground) { editingSlot = ColorSlot.Foreground }
                ColorSlotRow(stringResource(Res.string.theme_editor_cursor), cursor) { editingSlot = ColorSlot.Cursor }
                ColorSlotRow(stringResource(Res.string.theme_editor_selection), selectionBg) { editingSlot = ColorSlot.Selection }
                SectionLabel(stringResource(Res.string.theme_editor_ansi))
                // 16 ANSI colors in a compact grid (4 per row). Tapping any swatch
                // opens the picker for that index.
                AnsiSwatchGrid(ansi = ansi, onPick = { index -> editingSlot = ColorSlot.Ansi(index) })
            }
        }
    }

    // Color picker overlay for the currently-edited slot. The picker is itself a
    // DialogWindow with pure-Compose content, so it stacks safely over the editor.
    editingSlot?.let { slot ->
        val current = when (slot) {
            ColorSlot.Background -> background
            ColorSlot.Foreground -> foreground
            ColorSlot.Cursor -> cursor
            ColorSlot.Selection -> selectionBg
            is ColorSlot.Ansi -> ansi[slot.index]
        }
        ColorPickerWindow(
            initialArgb = current,
            onConfirm = { picked ->
                when (slot) {
                    ColorSlot.Background -> background = picked
                    ColorSlot.Foreground -> foreground = picked
                    ColorSlot.Cursor -> cursor = picked
                    // Selection highlight stays translucent: keep the slot's current
                    // alpha (e.g. preset 0x44) and only adopt the picked RGB.
                    ColorSlot.Selection -> selectionBg = withAlphaOf(selectionBg, picked)
                    is ColorSlot.Ansi -> ansi[slot.index] = picked
                }
                editingSlot = null
            },
            onDismiss = { editingSlot = null },
        )
    }
}

/** Identifies which color slot the picker is currently editing. */
private sealed interface ColorSlot {
    data object Background : ColorSlot
    data object Foreground : ColorSlot
    data object Cursor : ColorSlot
    data object Selection : ColorSlot
    data class Ansi(val index: Int) : ColorSlot
}

/** Forces alpha 0xFF: terminal colors are always opaque. */
private fun opaqueArgb(argb: Int): Int = (0xFF000000.toInt()) or (argb and 0x00FFFFFF)

/**
 * Recombines the RGB of [picked] with the alpha of [base]. Used for the selection
 * highlight slot so the user picks the hue/sat/value while the existing (usually
 * translucent, e.g. 0x44) alpha is preserved.
 */
private fun withAlphaOf(base: Int, picked: Int): Int =
    (base and 0xFF000000.toInt()) or (picked and 0x00FFFFFF)

/**
 * A labelled color row: tappable swatch (showing the current color + its hex) that
 * opens the [ColorPickerWindow] for this slot when clicked.
 */
@Composable
private fun ColorSlotRow(
    label: String,
    argb: Int,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(opaqueArgb(argb)))
                .border(1.dp, Border1, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = formatHexRgbDisplay(argb),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 16 ANSI color swatches in a 4-wide grid; each opens the picker for its index. */
@Composable
private fun AnsiSwatchGrid(ansi: List<Int>, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ansi.chunked(4).forEachIndexed { rowIndex, rowColors ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                rowColors.forEachIndexed { colIndex, argb ->
                    val index = rowIndex * 4 + colIndex
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(opaqueArgb(argb)))
                            .border(1.dp, Border1, RoundedCornerShape(4.dp))
                            .clickable { onPick(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = index.toString(),
                            color = if (luminance(argb) > 0.55f) NearBlack else White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

/** Relative luminance (0..1) of an ARGB color, used to pick a legible label tint. */
private fun luminance(argb: Int): Float {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return 0.299f * r + 0.587f * g + 0.114f * b
}

/** Formats an ARGB int as #RRGGBB (opaque) for display next to a swatch. */
private fun formatHexRgbDisplay(argb: Int): String {
    val rgb = argb and 0x00FFFFFF
    return "#" + rgb.toString(16).uppercase().padStart(6, '0')
}

@Composable
private fun DeleteThemeConfirmWindow(
    theme: CustomTerminalTheme,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(Res.string.theme_delete_title)
    val message = stringResource(Res.string.theme_delete_message, theme.name)
    val state = rememberDialogState(size = DpSize(380.dp, 220.dp))
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
            icon = Lucide.Trash2,
            onDismiss = onDismiss,
            footer = {
                WindowCardBtnGhostSm(onClick = onDismiss, label = stringResource(Res.string.action_cancel))
                Spacer(Modifier.width(Spacing.Sm))
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = White),
                ) {
                    Text(stringResource(Res.string.theme_picker_delete))
                }
            },
        ) {
            Text(
                text = message,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }
}

// ── Color helpers ────────────────────────────────────────────────────────────

/** A blank custom theme seeded from the default preset, with a fresh UUID. */
internal fun newCustomTheme(): CustomTerminalTheme {
    val preset = paletteFor(TerminalThemeId.TECHTICAL_DARK)
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

/** Parses a #RRGGBB or #AARRGGBB hex string into an ARGB int, or null if invalid. */
internal fun parseHexColor(input: String): Int? {
    val s = input.trim().removePrefix("#")
    return when (s.length) {
        6 -> s.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        8 -> s.toLongOrNull(16)?.toInt()
        else -> null
    }
}
