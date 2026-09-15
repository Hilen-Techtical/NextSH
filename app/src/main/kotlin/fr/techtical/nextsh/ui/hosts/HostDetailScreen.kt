// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.FileBadge
import com.composables.icons.lucide.Fingerprint
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Usb
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.ui.components.ConfirmDeleteDialog
import fr.techtical.nextsh.ui.components.NextShTextField
import fr.techtical.nextsh.ui.themes.CustomThemeEditorDialog
import fr.techtical.nextsh.ui.themes.CustomThemeEditorState
import fr.techtical.nextsh.ui.themes.TerminalThemePreview
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
import fr.techtical.nextsh.ui.theme.TERMINAL_THEMES
import fr.techtical.nextsh.ui.theme.TerminalThemeId
import fr.techtical.nextsh.ui.theme.TerminalThemePalette
import fr.techtical.nextsh.ui.theme.toPalette
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White

/**
 * HostDetailScreen : refonte Phase 3.2 (DA Techtical).
 *
 * Conventions Android conservées : Scaffold + TopAppBar + AlertDialog
 * (mobile). Les écrans d'édition restent en fullscreen avec bottomBar
 * d'action (sticky), pas d'overlay modal comme sur Desktop : la place
 * est trop limitée en portrait pour un overlay 520dp utile.
 *
 * Sections refondues en `FormSection` cards Surface/Border1/Radii.Lg
 * avec header GoldLight UPPERCASE 11sp mono. AuthType selector et
 * Fido2Mode en chips Border1. AutoReconnect en `ToggleRow` Burgundy.
 * Carousel theme terminal en cards Border1 → Burgundy si sélectionné.
 * Save sticky en bottom bar avec `BtnPrimary` Burgundy + `Lucide.Save`.
 * Suppression via `ConfirmDeleteDialog` partagé.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HostDetailScreen(
    hostId: String?,
    onBack: () -> Unit,
    viewModel: HostViewModel = hiltViewModel(),
) {
    val form by viewModel.formState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val customThemes by viewModel.customThemes.collectAsState()
    val isEditing = hostId != null
    var showDeleteDialog by remember { mutableStateOf(false) }
    // null = editor closed; CustomThemeEditorState.NEW = create; otherwise edit an existing theme.
    var editingTheme by remember { mutableStateOf<CustomTerminalTheme?>(null) }
    // Non-null while a delete confirmation is shown for a custom theme.
    var deletingTheme by remember { mutableStateOf<CustomTerminalTheme?>(null) }

    LaunchedEffect(hostId) {
        if (hostId != null) viewModel.loadHostForEdit(hostId)
        else viewModel.resetForm()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                HostEvent.HostSaved, HostEvent.HostDeleted -> onBack()
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isEditing) stringResource(R.string.title_edit_host) else stringResource(R.string.title_new_host),
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
                label = if (isEditing) stringResource(R.string.action_save) else stringResource(R.string.action_add_host),
                enabled = form.isValid,
                onClick = { viewModel.saveHost() },
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
            // ── Connexion ────────────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_connection)) {
                NextShTextField(
                    value = form.label,
                    onValueChange = { v -> viewModel.updateForm { copy(label = v) } },
                    label = stringResource(R.string.label_name),
                    placeholder = stringResource(R.string.placeholder_host_name),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    NextShTextField(
                        value = form.hostname,
                        onValueChange = { v -> viewModel.updateForm { copy(hostname = v) } },
                        label = stringResource(R.string.label_host),
                        placeholder = stringResource(R.string.placeholder_hostname),
                        modifier = Modifier.weight(2f),
                    )
                    NextShTextField(
                        value = form.port,
                        onValueChange = { v -> viewModel.updateForm { copy(port = v) } },
                        label = stringResource(R.string.label_port),
                        placeholder = stringResource(R.string.placeholder_port),
                        modifier = Modifier.weight(1f),
                    )
                }
                NextShTextField(
                    value = form.username,
                    onValueChange = { v -> viewModel.updateForm { copy(username = v) } },
                    label = stringResource(R.string.label_username),
                    placeholder = stringResource(R.string.placeholder_username),
                )
            }

            // ── Authentification ─────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_authentication)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    AuthType.entries
                        .filter { it in listOf(AuthType.PASSWORD, AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2) }
                        .forEach { type ->
                            AuthChip(
                                type = type,
                                selected = form.authType == type,
                                onClick = { viewModel.updateForm { copy(authType = type) } },
                            )
                        }
                }

                when (form.authType) {
                    AuthType.PASSWORD -> {
                        NextShTextField(
                            value = form.password,
                            onValueChange = { v -> viewModel.updateForm { copy(password = v) } },
                            label = stringResource(R.string.label_password),
                            placeholder = stringResource(R.string.placeholder_password),
                        )
                    }
                    AuthType.SSH_KEY -> {
                        SshKeyDropdown(
                            keys = uiState.sshKeys,
                            selectedKeyId = form.selectedKeyId,
                            label = stringResource(R.string.label_ssh_key),
                            onSelect = { id -> viewModel.updateForm { copy(selectedKeyId = id) } },
                        )
                    }
                    AuthType.CERTIFICATE -> {
                        SshKeyDropdown(
                            keys = uiState.sshKeys,
                            selectedKeyId = form.selectedKeyId,
                            label = stringResource(R.string.label_associated_private_key),
                            onSelect = { id -> viewModel.updateForm { copy(selectedKeyId = id) } },
                        )
                        HelperText(stringResource(R.string.certificate_hint))
                    }
                    AuthType.FIDO2 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                            Fido2Mode.entries.forEach { mode ->
                                Fido2ModeChip(
                                    mode = mode,
                                    selected = form.fido2Mode == mode,
                                    onClick = { viewModel.updateForm { copy(fido2Mode = mode) } },
                                )
                            }
                        }
                        val filteredKeys = when (form.fido2Mode) {
                            Fido2Mode.HARDWARE_KEY -> uiState.sshKeys.filter {
                                it.keyType in listOf(SshKeyType.SK_ED25519, SshKeyType.SK_ECDSA_256)
                            }
                            Fido2Mode.PASSKEY -> uiState.sshKeys.filter { !it.isBiometric }
                        }
                        SshKeyDropdown(
                            keys = filteredKeys,
                            selectedKeyId = form.selectedKeyId,
                            label = when (form.fido2Mode) {
                                Fido2Mode.HARDWARE_KEY -> stringResource(R.string.label_fido2_sk_key)
                                Fido2Mode.PASSKEY -> stringResource(R.string.label_ssh_key)
                            },
                            emptyMessage = when (form.fido2Mode) {
                                Fido2Mode.HARDWARE_KEY -> stringResource(R.string.fido2_no_sk_keys)
                                Fido2Mode.PASSKEY -> stringResource(R.string.label_no_key_import_first)
                            },
                            onSelect = { id -> viewModel.updateForm { copy(selectedKeyId = id) } },
                        )
                        HelperText(
                            when (form.fido2Mode) {
                                Fido2Mode.HARDWARE_KEY -> stringResource(R.string.fido2_info_line1)
                                Fido2Mode.PASSKEY -> stringResource(R.string.fido2_info_line2)
                            },
                        )
                    }
                    else -> {}
                }
            }

            // ── Options ──────────────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_options)) {
                NextShTextField(
                    value = form.group,
                    onValueChange = { v -> viewModel.updateForm { copy(group = v) } },
                    label = stringResource(R.string.label_group),
                    placeholder = stringResource(R.string.placeholder_group),
                )
                NextShTextField(
                    value = form.keepAliveSeconds,
                    onValueChange = { v -> viewModel.updateForm { copy(keepAliveSeconds = v) } },
                    label = stringResource(R.string.label_keepalive),
                )
                ToggleRow(
                    label = stringResource(R.string.label_auto_reconnect),
                    checked = form.autoReconnect,
                    onChange = { v -> viewModel.updateForm { copy(autoReconnect = v) } },
                )
            }

            // ── Terminal ─────────────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_terminal)) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Preset themes
                    items(TerminalThemeId.entries) { themeId ->
                        val palette = TERMINAL_THEMES[themeId] ?: return@items
                        val isSelected = form.terminalTheme == themeId.name
                        ThemeCard(
                            label = themeId.displayName,
                            palette = palette,
                            selected = isSelected,
                            onClick = { viewModel.updateForm { copy(terminalTheme = themeId.name) } },
                        )
                    }
                    // User-defined custom themes
                    items(customThemes, key = { it.id }) { custom ->
                        val isSelected = form.terminalTheme == custom.id
                        ThemeCard(
                            label = custom.name,
                            palette = custom.toPalette(),
                            selected = isSelected,
                            onClick = { viewModel.updateForm { copy(terminalTheme = custom.id) } },
                            onEdit = { editingTheme = custom },
                            onDelete = { deletingTheme = custom },
                        )
                    }
                    // "New custom theme" entry
                    item {
                        NewThemeCard(onClick = { editingTheme = CustomThemeEditorState.NEW })
                    }
                }
            }
        }
    }

    if (showDeleteDialog && hostId != null) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.dialog_delete_host_title),
            message = stringResource(R.string.dialog_delete_host_message),
            onConfirm = {
                viewModel.deleteHost(hostId)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    editingTheme?.let { initial ->
        CustomThemeEditorDialog(
            initial = initial,
            onSave = { theme ->
                viewModel.saveCustomTheme(theme)
                // Select the just-saved theme for this host.
                viewModel.updateForm { copy(terminalTheme = theme.id) }
                editingTheme = null
            },
            onDismiss = { editingTheme = null },
        )
    }

    deletingTheme?.let { theme ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.dialog_delete_theme_title),
            message = stringResource(R.string.dialog_delete_theme_message, theme.name),
            onConfirm = {
                viewModel.deleteCustomTheme(theme.id)
                // If the deleted theme was selected, fall back to the default preset.
                if (form.terminalTheme == theme.id) {
                    viewModel.updateForm { copy(terminalTheme = TerminalThemeId.TECHTICAL_DARK.name) }
                }
                deletingTheme = null
            },
            onDismiss = { deletingTheme = null },
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
    Text(
        text = text,
        color = TextDisabled,
        fontSize = 11.sp,
    )
}

// ── Auth chips ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthChip(type: AuthType, selected: Boolean, onClick: () -> Unit) {
    val (icon: ImageVector, label: String) = when (type) {
        AuthType.PASSWORD -> Lucide.Lock to stringResource(R.string.auth_type_password)
        AuthType.SSH_KEY -> Lucide.KeyRound to stringResource(R.string.auth_type_ssh_key)
        AuthType.CERTIFICATE -> Lucide.FileBadge to stringResource(R.string.auth_type_certificate)
        AuthType.FIDO2 -> Lucide.Fingerprint to stringResource(R.string.auth_type_fido2)
        else -> Lucide.Lock to type.name
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp)) },
        shape = RoundedCornerShape(Radii.Md),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Burgundy,
            selectedLabelColor = White,
            selectedLeadingIconColor = White,
            containerColor = SurfaceVariant.copy(alpha = 0.5f),
            labelColor = TextSecondary,
            iconColor = TextSecondary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Border1,
            selectedBorderColor = Burgundy,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Fido2ModeChip(mode: Fido2Mode, selected: Boolean, onClick: () -> Unit) {
    val (icon: ImageVector, label: String) = when (mode) {
        Fido2Mode.HARDWARE_KEY -> Lucide.Usb to stringResource(R.string.fido2_mode_hardware)
        Fido2Mode.PASSKEY -> Lucide.Smartphone to stringResource(R.string.fido2_mode_passkey)
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp)) },
        shape = RoundedCornerShape(Radii.Md),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Burgundy,
            selectedLabelColor = White,
            selectedLeadingIconColor = White,
            containerColor = SurfaceVariant.copy(alpha = 0.5f),
            labelColor = TextSecondary,
            iconColor = TextSecondary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Border1,
            selectedBorderColor = Burgundy,
        ),
    )
}

// ── SshKey dropdown ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshKeyDropdown(
    keys: List<fr.techtical.nextsh.domain.model.SshKey>,
    selectedKeyId: String?,
    label: String,
    emptyMessage: String = stringResource(R.string.label_no_key_import_first),
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedKey = keys.firstOrNull { it.id == selectedKeyId }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        NextShTextField(
            value = selectedKey?.label ?: "",
            onValueChange = {},
            label = label,
            placeholder = stringResource(R.string.placeholder_select_key),
            readOnly = true,
            modifier = Modifier.menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Surface),
        ) {
            if (keys.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(emptyMessage, color = TextSecondary, fontSize = 12.sp) },
                    onClick = { expanded = false },
                )
            } else {
                keys.forEach { key ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(key.label, color = TextPrimary, fontSize = 13.sp)
                                Text(
                                    text = key.keyType.name,
                                    color = TextSecondary,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp,
                                )
                            }
                        },
                        onClick = {
                            onSelect(key.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

// ── Toggle row ──────────────────────────────────────────────────────────────

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

// ── Terminal theme picker ───────────────────────────────────────────────────

@Composable
private fun ThemeCard(
    label: String,
    palette: TerminalThemePalette,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val borderColor = if (selected) Burgundy else Border1
    val labelColor = if (selected) GoldLight else TextSecondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(Radii.Md))
            .background(SurfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, borderColor, RoundedCornerShape(Radii.Md))
            .clickable(onClick = onClick)
            .padding(Spacing.Sm),
    ) {
        TerminalThemePreview(palette = palette)
        Spacer(Modifier.height(Spacing.Xs))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                color = labelColor,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            if (onEdit != null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Lucide.Pencil,
                        contentDescription = stringResource(R.string.action_edit_theme),
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = stringResource(R.string.action_delete_theme),
                        tint = ErrorRed,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NewThemeCard(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(150.dp)
            .height(118.dp)
            .clip(RoundedCornerShape(Radii.Md))
            .background(SurfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
            .clickable(onClick = onClick)
            .padding(Spacing.Sm),
    ) {
        Icon(
            Lucide.Plus,
            contentDescription = null,
            tint = GoldLight,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(R.string.action_new_custom_theme),
            color = TextSecondary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
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
