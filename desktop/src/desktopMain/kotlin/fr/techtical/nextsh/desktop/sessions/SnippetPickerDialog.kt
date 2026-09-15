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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import fr.techtical.nextsh.desktop.components.WindowCardBtnGhostSm
import fr.techtical.nextsh.desktop.components.TechticalWindowCard
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.window.PopupRoundedCorners
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.snippet_picker_cancel
import fr.techtical.nextsh.desktop.generated.resources.snippet_picker_empty_hint_no_query
import fr.techtical.nextsh.desktop.generated.resources.snippet_picker_empty_hint_with_query
import fr.techtical.nextsh.desktop.generated.resources.snippet_picker_empty_no_query
import fr.techtical.nextsh.desktop.generated.resources.snippet_picker_empty_with_query
import fr.techtical.nextsh.desktop.generated.resources.snippet_picker_global_badge
import fr.techtical.nextsh.desktop.generated.resources.snippet_picker_search_placeholder
import fr.techtical.nextsh.desktop.generated.resources.snippet_picker_subtitle
import fr.techtical.nextsh.desktop.generated.resources.snippet_picker_title
import fr.techtical.nextsh.shared.domain.model.Snippet
import org.jetbrains.compose.resources.stringResource

/**
 * Snippet picker: Ctrl+Shift+S global shortcut from any session tab.
 *
 * Mirrors [HostPickerDialog]'s structure (separate `DialogWindow` to avoid
 * being painted-over by JediTerm's heavyweight `SwingPanel`) but adds a
 * search field at the top: snippets often number in the dozens, so a
 * filter is the difference between useful and useless.
 *
 * The visible list is the union of:
 *  - global snippets (`hostId == null`)
 *  - snippets attached to the active tab's host (`hostId == activeHostId`)
 *
 * Snippets attached to OTHER hosts are hidden: sending another host's
 * command into the active terminal is almost always a mistake.
 */
@Composable
fun SnippetPickerDialog(
    snippets: List<Snippet>,
    activeHostId: String?,
    onPick: (Snippet) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDialogState(size = DpSize(520.dp, 560.dp))
    val title = stringResource(Res.string.snippet_picker_title)
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
        var query by remember { mutableStateOf("") }

        val filtered = remember(snippets, activeHostId, query) {
            val q = query.trim().lowercase()
            snippets
                .asSequence()
                .filter { it.hostId == null || it.hostId == activeHostId }
                .filter {
                    q.isEmpty() ||
                        it.label.lowercase().contains(q) ||
                        it.command.lowercase().contains(q) ||
                        (it.category?.lowercase()?.contains(q) == true)
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }

        TechticalWindowCard(
            title = title,
            icon = Lucide.Code,
            onDismiss = onDismiss,
            subtitle = stringResource(Res.string.snippet_picker_subtitle),
            footer = {
                WindowCardBtnGhostSm(onClick = onDismiss, label = stringResource(Res.string.snippet_picker_cancel))
            },
            // ESC dismisses (handled by TechticalWindowCard itself, always
            // priority). Enter selects the first filtered result, the one
            // extra shortcut this picker has on top of the shared card.
            onPreviewKeyEvent = { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && filtered.isNotEmpty()) {
                    onPick(filtered.first()); true
                } else {
                    false
                }
            },
        ) {
            SearchField(value = query, onChange = { query = it })

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { EmptyState(query = query) }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    items(filtered, key = { it.id }) { snippet ->
                        SnippetRow(snippet = snippet, onPick = onPick)
                    }
                }
            }
        }
    }
}

// ── Search ────────────────────────────────────────────────────────────────────

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(Radii.Md))
            .background(SurfaceVariant.copy(alpha = 0.6f))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(Res.string.snippet_picker_search_placeholder),
                    color = TextDisabled,
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 12.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                cursorBrush = SolidColor(Gold),
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 12.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Liste ────────────────────────────────────────────────────────────────────

@Composable
private fun SnippetRow(snippet: Snippet, onPick: (Snippet) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val borderColor = if (hovered) Border2 else Border1
    val bg = if (hovered) SurfaceVariant.copy(alpha = 0.6f) else SurfaceVariant.copy(alpha = 0.3f)
    val previewLine = snippet.command.lineSequence().firstOrNull()?.take(120) ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Md))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(Radii.Md))
            .clickable(interactionSource = interactionSource, indication = null) { onPick(snippet) }
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
            Icon(Lucide.Code, contentDescription = null, tint = Gold, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(Spacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = snippet.label,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                snippet.category?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(Spacing.Sm))
                    CategoryBadge(name = it)
                }
                if (snippet.hostId == null) {
                    Spacer(Modifier.width(Spacing.Sm))
                    GlobalBadge()
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = previewLine,
                color = TextSecondary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CategoryBadge(name: String) {
    Box(
        modifier = Modifier
            .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(3.dp))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = name,
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun GlobalBadge() {
    Box(
        modifier = Modifier
            .background(Burgundy.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
            .border(1.dp, Burgundy.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = stringResource(Res.string.snippet_picker_global_badge),
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun EmptyState(query: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Lucide.Code,
            contentDescription = null,
            tint = GoldMuted,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(
                if (query.isBlank()) Res.string.snippet_picker_empty_no_query
                else Res.string.snippet_picker_empty_with_query,
            ),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(
                if (query.isBlank()) Res.string.snippet_picker_empty_hint_no_query
                else Res.string.snippet_picker_empty_hint_with_query,
            ),
            color = TextSecondary,
            fontSize = 11.sp,
        )
    }
}
