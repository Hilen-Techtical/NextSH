// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Trash2
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.ui.components.ConfirmDeleteDialog
import fr.techtical.nextsh.ui.components.NextShTextField
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Border2
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.SurfaceVariant
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White

/**
 * TunnelConfigScreen : refonte Phase 3.2 (DA Techtical).
 *
 * Mêmes patterns que HostDetailScreen : FormSection cards, sticky save
 * bottom bar, ConfirmDeleteDialog, ToggleRow Burgundy. Sections :
 * Identité / Type / Hôte / Routage (par type) / Options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelConfigScreen(
    tunnelId: String?,
    onBack: () -> Unit,
    viewModel: TunnelViewModel = hiltViewModel(),
) {
    val form by viewModel.formState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isEditing = tunnelId != null
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showHostDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(tunnelId) {
        if (tunnelId != null) viewModel.loadTunnelForEdit(tunnelId) else viewModel.resetForm()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TunnelEvent.TunnelSaved -> onBack()
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isEditing) stringResource(R.string.title_edit_tunnel) else stringResource(R.string.title_new_tunnel),
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextPrimary,
                        )
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Lucide.Trash2,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = ErrorRed.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
            )
        },
        bottomBar = {
            BottomSaveBar(
                label = if (isEditing) stringResource(R.string.action_save) else stringResource(R.string.action_create_tunnel),
                enabled = form.isValid,
                onClick = { viewModel.saveTunnel() },
            )
        },
        containerColor = NearBlack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
        ) {
            // ── Identité ──────────────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_general)) {
                NextShTextField(
                    value = form.label,
                    onValueChange = { v -> viewModel.updateForm { copy(label = v) } },
                    label = stringResource(R.string.label_name),
                    placeholder = stringResource(R.string.placeholder_tunnel_name),
                )
            }

            // ── Type ──────────────────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_tunnel_type)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                    TunnelType.entries.forEach { type ->
                        TypeChip(
                            type = type,
                            selected = form.type == type,
                            onClick = { viewModel.updateForm { copy(type = type) } },
                        )
                    }
                }
                HelperText(text = form.type.helperText())
            }

            // ── Hôte SSH ──────────────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_ssh_host)) {
                ExposedDropdownMenuBox(
                    expanded = showHostDropdown,
                    onExpandedChange = { showHostDropdown = it },
                ) {
                    NextShTextField(
                        value = uiState.hosts.firstOrNull { it.id == form.hostId }?.label ?: "",
                        onValueChange = {},
                        label = stringResource(R.string.label_host),
                        readOnly = true,
                        modifier = Modifier.menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showHostDropdown) },
                    )
                    ExposedDropdownMenu(
                        expanded = showHostDropdown,
                        onDismissRequest = { showHostDropdown = false },
                        modifier = Modifier.background(Surface),
                    ) {
                        if (uiState.hosts.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.label_no_host_configured),
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                    )
                                },
                                onClick = { showHostDropdown = false },
                            )
                        } else {
                            uiState.hosts.forEach { host ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(host.label, color = TextPrimary, fontSize = 13.sp)
                                            Text(
                                                text = "${host.username}@${host.hostname}:${host.port}",
                                                color = TextSecondary,
                                                fontFamily = JetBrainsMonoFamily,
                                                fontSize = 10.sp,
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.updateForm { copy(hostId = host.id) }
                                        showHostDropdown = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ── Routage selon le type ─────────────────────────────────────────
            FormSection(
                title = when (form.type) {
                    TunnelType.LOCAL_FORWARD -> stringResource(R.string.section_local_forward)
                    TunnelType.REMOTE_FORWARD -> stringResource(R.string.section_remote_forward)
                    TunnelType.DYNAMIC_SOCKS5 -> stringResource(R.string.section_socks5_proxy)
                },
            ) {
                when (form.type) {
                    TunnelType.LOCAL_FORWARD -> {
                        NextShTextField(
                            value = form.localPort,
                            onValueChange = { v -> viewModel.updateForm { copy(localPort = v) } },
                            label = stringResource(R.string.label_local_port),
                            placeholder = stringResource(R.string.placeholder_local_port),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                            NextShTextField(
                                value = form.remoteHost,
                                onValueChange = { v -> viewModel.updateForm { copy(remoteHost = v) } },
                                label = stringResource(R.string.label_remote_host),
                                placeholder = stringResource(R.string.placeholder_remote_host),
                                modifier = Modifier.weight(2f),
                            )
                            NextShTextField(
                                value = form.remotePort,
                                onValueChange = { v -> viewModel.updateForm { copy(remotePort = v) } },
                                label = stringResource(R.string.label_remote_port),
                                placeholder = stringResource(R.string.placeholder_remote_port),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    TunnelType.REMOTE_FORWARD -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                            NextShTextField(
                                value = form.remoteHost,
                                onValueChange = { v -> viewModel.updateForm { copy(remoteHost = v) } },
                                label = stringResource(R.string.label_remote_host),
                                placeholder = "0.0.0.0",
                                modifier = Modifier.weight(2f),
                            )
                            NextShTextField(
                                value = form.remotePort,
                                onValueChange = { v -> viewModel.updateForm { copy(remotePort = v) } },
                                label = stringResource(R.string.label_remote_port),
                                placeholder = stringResource(R.string.placeholder_local_port),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        NextShTextField(
                            value = form.localPort,
                            onValueChange = { v -> viewModel.updateForm { copy(localPort = v) } },
                            label = stringResource(R.string.label_local_port),
                            placeholder = "3000",
                        )
                    }
                    TunnelType.DYNAMIC_SOCKS5 -> {
                        NextShTextField(
                            value = form.localPort,
                            onValueChange = { v -> viewModel.updateForm { copy(localPort = v) } },
                            label = stringResource(R.string.label_local_socks5_port),
                            placeholder = stringResource(R.string.placeholder_socks5_port),
                        )
                        HelperText(
                            text = "Proxy SOCKS5 exposé sur localhost:${form.localPort.ifBlank { "?" }}.",
                        )
                    }
                }
            }

            // ── Options ──────────────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_options)) {
                ToggleRow(
                    label = stringResource(R.string.label_auto_start),
                    checked = form.autoStart,
                    onChange = { v -> viewModel.updateForm { copy(autoStart = v) } },
                )
                if (form.type == TunnelType.LOCAL_FORWARD) {
                    ToggleRow(
                        label = stringResource(R.string.label_open_browser_on_connect),
                        checked = form.openBrowserOnConnect,
                        onChange = { v -> viewModel.updateForm { copy(openBrowserOnConnect = v) } },
                    )
                    ToggleRow(
                        label = stringResource(R.string.label_keep_alive_after_browser_close),
                        checked = form.keepAliveAfterBrowserClose,
                        onChange = { v -> viewModel.updateForm { copy(keepAliveAfterBrowserClose = v) } },
                    )
                }
            }
        }
    }

    if (showDeleteDialog && tunnelId != null) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.dialog_delete_tunnel_title),
            message = stringResource(R.string.dialog_delete_tunnel_message),
            onConfirm = {
                viewModel.deleteTunnel(tunnelId)
                showDeleteDialog = false
                onBack()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

// ── Section primitive ───────────────────────────────────────────────────────

@Composable
private fun FormSection(
    title: String,
    accent: Color = GoldLight,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        Text(
            text = title.uppercase(),
            color = accent,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(Radii.Lg))
                .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
                .padding(horizontal = Spacing.Md, vertical = Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            content = content,
        )
    }
}

@Composable
private fun HelperText(text: String) {
    Text(text = text, color = TextDisabled, fontSize = 11.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeChip(type: TunnelType, selected: Boolean, onClick: () -> Unit) {
    val label = when (type) {
        TunnelType.LOCAL_FORWARD -> "Local"
        TunnelType.REMOTE_FORWARD -> "Remote"
        TunnelType.DYNAMIC_SOCKS5 -> "SOCKS5"
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
        shape = RoundedCornerShape(Radii.Md),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Burgundy,
            selectedLabelColor = White,
            containerColor = SurfaceVariant.copy(alpha = 0.5f),
            labelColor = TextSecondary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Border1,
            selectedBorderColor = Burgundy,
        ),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
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

// ── Bottom sticky save bar ──────────────────────────────────────────────────

@Composable
private fun BottomSaveBar(label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.background(NearBlack)) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Lucide.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.Sm))
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun TunnelType.helperText(): String = when (this) {
    TunnelType.LOCAL_FORWARD -> "Expose un service distant sur localhost via SSH."
    TunnelType.REMOTE_FORWARD -> "Expose un service local sur le bind distant via SSH."
    TunnelType.DYNAMIC_SOCKS5 -> "Crée un proxy SOCKS5 pour router le trafic via SSH."
}
