// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import fr.techtical.nextsh.desktop.components.ConfirmDeleteDialog
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
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.action_close
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_danger_text
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_delete
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_delete_default_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_delete_dialog_body
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_delete_dialog_title
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_host_empty
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_host_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_label_placeholder
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_label_short
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_local_port_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_local_port_target
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_option_auto_start
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_option_keep_after_browser
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_option_open_browser
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_pick_host
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_pick_host_disabled
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_remote_bind_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_remote_host_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_remote_port_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_save
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_save_loading
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_section_danger
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_section_host
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_section_identity
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_section_options
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_section_routing
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_socks5_hint
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_socks5_port_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_title_edit
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_title_new
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_type_dynamic_desc
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_type_dynamic_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_type_local_desc
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_type_local_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_type_remote_desc
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_type_remote_label
import org.jetbrains.compose.resources.stringResource

/**
 * Édition d'un tunnel, refonte Phase 2.9 (overlay modal).
 *
 * Pattern aligné sur HostDetailScreen : scrim NearBlack@0.65 plein écran +
 * carte centrée 520dp max (~90% hauteur dispo) + sections Identité /
 * Hôte / Configuration / Options + ConfirmDeleteDialog Techtical pour la
 * suppression. Plus de Scaffold/TopAppBar Material legacy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelConfigScreen(
    tunnelId: String?,
    onBack: () -> Unit,
) {
    val viewModel = remember { TunnelConfigViewModel() }
    val state by viewModel.state.collectAsState()
    val hosts by viewModel.hosts.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(tunnelId) { viewModel.load(tunnelId) }

    val scrimFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { scrimFocus.requestFocus() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack.copy(alpha = 0.65f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onBack() }) }
            .focusRequester(scrimFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onBack(); true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        val maxCardHeight = maxHeight * 0.9f

        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .heightIn(max = maxCardHeight)
                .clip(RoundedCornerShape(Radii.Xl))
                .background(Surface)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Xl))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            CardHeader(
                isExisting = state.isExisting,
                title = stringResource(if (state.isExisting) Res.string.tunnel_config_title_edit else Res.string.tunnel_config_title_new),
                subtitle = state.label.takeIf { state.isExisting && it.isNotBlank() },
                onClose = onBack,
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                state.error?.let { ErrorBanner(it) }

                IdentitySection(state, viewModel)
                HostSection(state, viewModel, hosts)
                RoutingSection(state, viewModel)
                OptionsSection(state, viewModel)

                if (state.isExisting) {
                    DangerZoneSection(label = state.label, onDelete = { showDeleteDialog = true })
                }
            }

            CardFooter(
                isSaving = state.isSaving,
                onCancel = onBack,
                onSave = { viewModel.save(onBack) },
            )
        }
    }

    if (showDeleteDialog) {
        ConfirmDeleteDialog(
            title = stringResource(Res.string.tunnel_config_delete_dialog_title),
            message = stringResource(Res.string.tunnel_config_delete_dialog_body, state.label.ifBlank { stringResource(Res.string.tunnel_config_delete_default_label) }),
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete(onBack)
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

// ── Header / Footer de la carte ─────────────────────────────────────────────

@Composable
private fun CardHeader(
    isExisting: Boolean,
    title: String,
    subtitle: String?,
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
                Icon(
                    imageVector = if (isExisting) Lucide.Pencil else Lucide.Plus,
                    contentDescription = null,
                    tint = GoldLight,
                    modifier = Modifier.size(13.dp),
                )
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
            CloseButton(onClick = onClose)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
    }
}

@Composable
private fun CardFooter(isSaving: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Column {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtnGhostSm(onClick = onCancel, label = stringResource(Res.string.action_cancel))
            Spacer(Modifier.width(Spacing.Sm))
            BtnPrimarySm(
                onClick = onSave,
                icon = Lucide.Save,
                label = stringResource(if (isSaving) Res.string.tunnel_config_save_loading else Res.string.tunnel_config_save),
                enabled = !isSaving,
            )
        }
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
        Icon(Lucide.X, contentDescription = stringResource(Res.string.action_close), tint = tint, modifier = Modifier.size(14.dp))
    }
}

// ── Sections ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentitySection(state: TunnelConfigState, viewModel: TunnelConfigViewModel) {
    FormSection(title = stringResource(Res.string.tunnel_config_section_identity)) {
        FieldGroup {
            FieldLabel(stringResource(Res.string.tunnel_config_label_short))
            TechticalTextField(
                value = state.label,
                onValueChange = viewModel::onLabel,
                placeholder = stringResource(Res.string.tunnel_config_label_placeholder),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostSection(
    state: TunnelConfigState,
    viewModel: TunnelConfigViewModel,
    hosts: List<Host>,
) {
    FormSection(title = stringResource(Res.string.tunnel_config_section_host)) {
        FieldGroup {
            FieldLabel(stringResource(Res.string.tunnel_config_host_label))
            HostDropdown(
                hosts = hosts,
                selectedId = state.hostId,
                onSelected = viewModel::onHost,
            )
            if (hosts.isEmpty()) {
                HelperText(
                    stringResource(Res.string.tunnel_config_host_empty),
                    warn = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutingSection(state: TunnelConfigState, viewModel: TunnelConfigViewModel) {
    FormSection(title = stringResource(Res.string.tunnel_config_section_routing)) {
        FieldGroup {
            FieldLabel("Type")
            RowSelectFull(
                value = state.type.displayName(),
                options = TunnelType.entries.map { type ->
                    type.displayName() to { viewModel.onType(type) }
                },
            )
            HelperText(state.type.helperText())
        }

        when (state.type) {
            TunnelType.LOCAL_FORWARD -> {
                FieldGroup {
                    FieldLabel(stringResource(Res.string.tunnel_config_local_port_label))
                    TechticalTextField(
                        value = state.localPort,
                        onValueChange = viewModel::onLocalPort,
                        placeholder = "8080",
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        FieldLabel(stringResource(Res.string.tunnel_config_remote_host_label))
                        TechticalTextField(
                            value = state.remoteHost,
                            onValueChange = viewModel::onRemoteHost,
                            placeholder = "10.0.0.5",
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        FieldLabel(stringResource(Res.string.tunnel_config_remote_port_label))
                        TechticalTextField(
                            value = state.remotePort,
                            onValueChange = viewModel::onRemotePort,
                            placeholder = "5432",
                        )
                    }
                }
            }

            TunnelType.REMOTE_FORWARD -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        FieldLabel(stringResource(Res.string.tunnel_config_remote_bind_label))
                        TechticalTextField(
                            value = state.remoteHost,
                            onValueChange = viewModel::onRemoteHost,
                            placeholder = "0.0.0.0",
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        FieldLabel(stringResource(Res.string.tunnel_config_remote_port_label))
                        TechticalTextField(
                            value = state.remotePort,
                            onValueChange = viewModel::onRemotePort,
                            placeholder = "8080",
                        )
                    }
                }
                FieldGroup {
                    FieldLabel(stringResource(Res.string.tunnel_config_local_port_target))
                    TechticalTextField(
                        value = state.localPort,
                        onValueChange = viewModel::onLocalPort,
                        placeholder = "3000",
                    )
                }
            }

            TunnelType.DYNAMIC_SOCKS5 -> {
                FieldGroup {
                    FieldLabel(stringResource(Res.string.tunnel_config_socks5_port_label))
                    TechticalTextField(
                        value = state.localPort,
                        onValueChange = viewModel::onLocalPort,
                        placeholder = "1080",
                    )
                    HelperText(
                        stringResource(Res.string.tunnel_config_socks5_hint, state.localPort.ifBlank { "?" }),
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionsSection(state: TunnelConfigState, viewModel: TunnelConfigViewModel) {
    FormSection(title = stringResource(Res.string.tunnel_config_section_options)) {
        ToggleRow(
            label = stringResource(Res.string.tunnel_config_option_auto_start),
            checked = state.autoStart,
            onChange = viewModel::onAutoStart,
        )
        // Browser-related options ne s'appliquent qu'aux LOCAL_FORWARD :
        // un REMOTE/SOCKS5 n'expose rien côté localhost qu'on puisse ouvrir.
        if (state.type == TunnelType.LOCAL_FORWARD) {
            ToggleRow(
                label = stringResource(Res.string.tunnel_config_option_open_browser),
                checked = state.openBrowserOnConnect,
                onChange = viewModel::onOpenBrowserOnConnect,
            )
            ToggleRow(
                label = stringResource(Res.string.tunnel_config_option_keep_after_browser),
                checked = state.keepAliveAfterBrowserClose,
                onChange = viewModel::onKeepAliveAfterBrowserClose,
            )
        }
    }
}

@Composable
private fun DangerZoneSection(label: String, onDelete: () -> Unit) {
    FormSection(title = stringResource(Res.string.tunnel_config_section_danger), accent = ErrorRed) {
        Text(
            text = stringResource(Res.string.tunnel_config_danger_text, label.ifBlank { stringResource(Res.string.tunnel_config_delete_default_label) }),
            color = TextSecondary,
            fontSize = 11.sp,
        )
        DangerRowButton(
            icon = Lucide.Trash2,
            label = stringResource(Res.string.tunnel_config_delete),
            onClick = onDelete,
        )
    }
}

// ── Section primitive ───────────────────────────────────────────────────────

@Composable
private fun FormSection(
    title: String,
    accent: Color = GoldLight,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        Text(
            text = title.uppercase(),
            color = accent,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(Radii.Lg))
                .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            content = content,
        )
    }
}

@Composable
private fun FieldGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs), content = content)
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = GoldMuted,
        fontFamily = JetBrainsMonoFamily,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    )
}

@Composable
private fun HelperText(text: String, warn: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (warn) {
            Icon(
                Lucide.TriangleAlert,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color(0xFFE8A838),
                modifier = Modifier.size(10.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            color = if (warn) androidx.compose.ui.graphics.Color(0xFFE8A838) else TextDisabled,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ErrorRed.copy(alpha = 0.08f), RoundedCornerShape(Radii.Md))
            .border(1.dp, ErrorRed.copy(alpha = 0.30f), RoundedCornerShape(Radii.Md))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.TriangleAlert,
            contentDescription = null,
            tint = ErrorRed,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(Spacing.Sm))
        Text(text = message, color = ErrorRed, fontSize = 11.sp)
    }
}

// ── Champs de saisie ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TechticalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(text = placeholder, color = TextDisabled, fontSize = 12.sp)
        },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
        colors = techticalTextFieldColors(),
        shape = RoundedCornerShape(Radii.Md),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun techticalTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold.copy(alpha = 0.55f),
    unfocusedBorderColor = Border1,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Gold,
    focusedContainerColor = Surface,
    unfocusedContainerColor = Surface,
)

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Spacing.Sm))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = White,
                checkedTrackColor = Burgundy,
                checkedBorderColor = Burgundy,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceVariant,
                uncheckedBorderColor = Border2,
            ),
        )
    }
}

// ── Pill dropdowns ──────────────────────────────────────────────────────────

@Composable
private fun RowSelectFull(
    value: String,
    options: List<Pair<String, () -> Unit>>,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val borderColor = if (hovered) Border2 else Border1

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { triggerWidthPx = it.width },
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            interactionSource = interactionSource,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Surface,
                contentColor = TextPrimary,
            ),
            border = BorderStroke(1.dp, borderColor),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(
                text = value,
                color = TextPrimary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(Lucide.ChevronDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = techticalDropdownModifier()
                .width(with(density) { triggerWidthPx.toDp() }),
        ) {
            options.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label, color = TextPrimary, fontSize = 12.sp) },
                    onClick = {
                        action()
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HostDropdown(
    hosts: List<Host>,
    selectedId: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val selected = hosts.firstOrNull { it.id == selectedId }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val enabled = hosts.isNotEmpty()
    val borderColor = when {
        !enabled -> Border1.copy(alpha = 0.5f)
        hovered -> Border2
        else -> Border1
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { triggerWidthPx = it.width },
    ) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            interactionSource = interactionSource,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Surface,
                contentColor = TextPrimary,
                disabledContainerColor = Surface.copy(alpha = 0.5f),
                disabledContentColor = TextDisabled,
            ),
            border = BorderStroke(1.dp, borderColor),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Sm)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Server, contentDescription = null, tint = Gold, modifier = Modifier.size(11.dp))
            }
            Spacer(Modifier.width(Spacing.Sm))
            Column(modifier = Modifier.weight(1f)) {
                if (selected != null) {
                    Text(
                        text = selected.label,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${selected.username}@${selected.hostname}:${selected.port}",
                        color = TextSecondary,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                    )
                } else {
                    Text(
                        text = stringResource(if (enabled) Res.string.tunnel_config_pick_host else Res.string.tunnel_config_pick_host_disabled),
                        color = TextDisabled,
                        fontSize = 12.sp,
                    )
                }
            }
            Icon(Lucide.ChevronDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = techticalDropdownModifier()
                .width(with(density) { triggerWidthPx.toDp() }),
        ) {
            hosts.forEach { host ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(host.label, color = TextPrimary, fontSize = 12.sp)
                            Text(
                                text = "${host.username}@${host.hostname}:${host.port}",
                                color = TextSecondary,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.sp,
                            )
                        }
                    },
                    onClick = {
                        onSelected(host.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun techticalDropdownModifier(): Modifier =
    Modifier
        .clip(RoundedCornerShape(Radii.Md))
        .background(Surface)
        .border(1.dp, Border1, RoundedCornerShape(Radii.Md))

// ── Boutons ─────────────────────────────────────────────────────────────────

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

@Composable
private fun BtnPrimarySm(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Burgundy,
            contentColor = White,
            disabledContainerColor = Burgundy.copy(alpha = 0.4f),
            disabledContentColor = White.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DangerRowButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val borderColor = if (hovered) ErrorRed else ErrorRed.copy(alpha = 0.5f)
    val bg = if (hovered) ErrorRed.copy(alpha = 0.08f) else Color.Transparent
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = bg, contentColor = ErrorRed),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

@Composable
private fun TunnelType.displayName(): String = stringResource(when (this) {
    TunnelType.LOCAL_FORWARD -> Res.string.tunnel_config_type_local_label
    TunnelType.REMOTE_FORWARD -> Res.string.tunnel_config_type_remote_label
    TunnelType.DYNAMIC_SOCKS5 -> Res.string.tunnel_config_type_dynamic_label
})

@Composable
private fun TunnelType.helperText(): String = stringResource(when (this) {
    TunnelType.LOCAL_FORWARD -> Res.string.tunnel_config_type_local_desc
    TunnelType.REMOTE_FORWARD -> Res.string.tunnel_config_type_remote_desc
    TunnelType.DYNAMIC_SOCKS5 -> Res.string.tunnel_config_type_dynamic_desc
})
