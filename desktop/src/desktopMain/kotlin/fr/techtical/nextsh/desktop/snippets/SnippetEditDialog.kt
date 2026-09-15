// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.snippets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.snippets_error_command_empty
import fr.techtical.nextsh.desktop.generated.resources.snippets_error_label_empty
import fr.techtical.nextsh.desktop.generated.resources.snippets_field_category
import fr.techtical.nextsh.desktop.generated.resources.snippets_field_command
import fr.techtical.nextsh.desktop.generated.resources.snippets_field_host
import fr.techtical.nextsh.desktop.generated.resources.snippets_field_host_global
import fr.techtical.nextsh.desktop.generated.resources.snippets_field_label
import fr.techtical.nextsh.desktop.generated.resources.snippets_action_add
import fr.techtical.nextsh.desktop.generated.resources.snippets_action_edit
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import org.jetbrains.compose.resources.stringResource

/**
 * Dialog OS-natif d'édition/création d'un snippet.
 *
 * Utilise une [DialogWindow] séparée (obligatoire : règle CLAUDE.md projet
 * "DialogWindow OS séparé pour pickers Desktop") pour éviter d'être masqué
 * par les SwingPanel JediTerm quand une session est active.
 *
 * @param snippet    Snippet à éditer, ou null pour une création.
 * @param hosts      Liste des hôtes disponibles pour le scope.
 * @param onDismiss  Appelé lors de l'annulation ou fermeture.
 * @param onSave     Appelé avec (label, command, category, hostId) à la validation.
 */
@Composable
fun SnippetEditDialog(
    snippet: Snippet?,
    hosts: List<Host>,
    onDismiss: () -> Unit,
    onSave: (label: String, command: String, category: String?, hostId: String?) -> Unit,
) {
    val isNew = snippet == null
    val dialogTitle = if (isNew) stringResource(Res.string.snippets_action_add)
    else stringResource(Res.string.snippets_action_edit)

    DialogWindow(
        onCloseRequest = onDismiss,
        title = dialogTitle,
        state = rememberDialogState(size = DpSize(520.dp, 520.dp)),
        resizable = false,
    ) {
        // Le corps de la commande est visible en clair dans ce dialog.
        WindowCaptureProtection(window)
        var label by remember { mutableStateOf(snippet?.label ?: "") }
        var command by remember { mutableStateOf(snippet?.command ?: "") }
        var category by remember { mutableStateOf(snippet?.category ?: "") }
        var selectedHostId by remember { mutableStateOf<String?>(snippet?.hostId) }
        var localError by remember { mutableStateOf<String?>(null) }

        val labelErrorText = stringResource(Res.string.snippets_error_label_empty)
        val commandErrorText = stringResource(Res.string.snippets_error_command_empty)
        val globalLabel = stringResource(Res.string.snippets_field_host_global)

        val selectedHostLabel = hosts.find { it.id == selectedHostId }?.label ?: globalLabel

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NearBlack),
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(Radii.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Code, contentDescription = null, tint = Gold, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(Spacing.Sm))
                Column {
                    Text(
                        text = dialogTitle,
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = stringResource(Res.string.snippets_field_label),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            // ── Form ─────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                // Label field
                SnippetFieldGroup(stringResource(Res.string.snippets_field_label)) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it; localError = null },
                        placeholder = { Text("git pull --rebase", color = TextDisabled, fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = TextPrimary),
                        colors = snippetTextFieldColors(),
                        shape = RoundedCornerShape(Radii.Md),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    )
                }

                // Command field (multiline)
                SnippetFieldGroup(stringResource(Res.string.snippets_field_command)) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it; localError = null },
                        placeholder = { Text("git pull --rebase origin main", color = TextDisabled, fontSize = 12.sp) },
                        singleLine = false,
                        minLines = 4,
                        textStyle = TextStyle(
                            fontSize = 12.sp,
                            color = TextPrimary,
                            fontFamily = JetBrainsMonoFamily,
                        ),
                        colors = snippetTextFieldColors(),
                        shape = RoundedCornerShape(Radii.Md),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Category field
                SnippetFieldGroup(stringResource(Res.string.snippets_field_category)) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        placeholder = { Text("git, docker, system…", color = TextDisabled, fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = TextPrimary),
                        colors = snippetTextFieldColors(),
                        shape = RoundedCornerShape(Radii.Md),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    )
                }

                // Host scope dropdown
                SnippetFieldGroup(stringResource(Res.string.snippets_field_host)) {
                    HostScopeDropdown(
                        selectedLabel = selectedHostLabel,
                        hosts = hosts,
                        globalLabel = globalLabel,
                        onSelect = { selectedHostId = it },
                    )
                }

                // Error message
                if (localError != null) {
                    Text(
                        text = localError!!,
                        color = ErrorRed,
                        fontSize = 11.sp,
                    )
                }
            }

            // ── Footer ────────────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SnippetBtnGhost(onClick = onDismiss, label = stringResource(Res.string.action_cancel))
                Spacer(Modifier.width(Spacing.Sm))
                SnippetBtnPrimary(
                    onClick = {
                        when {
                            label.isBlank() -> localError = labelErrorText
                            command.isBlank() -> localError = commandErrorText
                            else -> onSave(
                                label.trim(),
                                command.trim(),
                                category.trim().takeIf { it.isNotBlank() },
                                selectedHostId,
                            )
                        }
                    },
                    label = if (isNew) stringResource(Res.string.snippets_action_add)
                    else stringResource(Res.string.snippets_action_edit),
                )
            }
        }
    }
}

// ── Composants locaux ─────────────────────────────────────────────────────────

@Composable
private fun SnippetFieldGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        Text(
            text = label.uppercase(),
            color = GoldMuted,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        )
        content()
    }
}

@Composable
private fun snippetTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold.copy(alpha = 0.55f),
    unfocusedBorderColor = Border1,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Gold,
    focusedContainerColor = Surface,
    unfocusedContainerColor = Surface,
)

@Composable
private fun HostScopeDropdown(
    selectedLabel: String,
    hosts: List<Host>,
    globalLabel: String,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { triggerWidthPx = it.width },
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Surface,
                contentColor = TextPrimary,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border1),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(selectedLabel, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(Lucide.ChevronDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .background(Surface)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
                .width(with(density) { triggerWidthPx.toDp() }),
        ) {
            // Global option first
            DropdownMenuItem(
                text = { Text(globalLabel, color = TextPrimary, fontSize = 12.sp) },
                onClick = { onSelect(null); expanded = false },
            )
            hosts.forEach { host ->
                DropdownMenuItem(
                    text = { Text(host.label, color = TextPrimary, fontSize = 12.sp) },
                    onClick = { onSelect(host.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun SnippetBtnGhost(onClick: () -> Unit, label: String) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SnippetBtnPrimary(onClick: () -> Unit, label: String) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Burgundy,
            contentColor = White,
        ),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Lucide.Code, contentDescription = null, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
