// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.FileBadge
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import fr.techtical.nextsh.desktop.components.ConfirmDeleteDialog
import fr.techtical.nextsh.desktop.sessions.ThemePickerDialog
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
import fr.techtical.nextsh.desktop.theme.TerminalThemeId
import fr.techtical.nextsh.desktop.theme.resolveTheme
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.WarningAmber
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.SshKey
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.action_close
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_method
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_type_biometric
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_type_certificate
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_type_fido2
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_type_password
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_type_ssh_key
import fr.techtical.nextsh.desktop.generated.resources.host_detail_cert_dialog_title
import fr.techtical.nextsh.desktop.generated.resources.host_detail_cert_kept
import fr.techtical.nextsh.desktop.generated.resources.host_detail_cert_label
import fr.techtical.nextsh.desktop.generated.resources.host_detail_cert_none
import fr.techtical.nextsh.desktop.generated.resources.host_detail_cert_replace
import fr.techtical.nextsh.desktop.generated.resources.host_detail_cert_select
import fr.techtical.nextsh.desktop.generated.resources.host_detail_danger_text
import fr.techtical.nextsh.desktop.generated.resources.host_detail_delete
import fr.techtical.nextsh.desktop.generated.resources.host_detail_delete_default_label
import fr.techtical.nextsh.desktop.generated.resources.host_detail_delete_dialog_body
import fr.techtical.nextsh.desktop.generated.resources.host_detail_delete_dialog_title
import fr.techtical.nextsh.desktop.generated.resources.host_detail_existing_label
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_fido2_no_keys
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_fido2_select
import fr.techtical.nextsh.desktop.generated.resources.host_detail_fido2_unavailable
import fr.techtical.nextsh.desktop.generated.resources.host_detail_field_hide
import fr.techtical.nextsh.desktop.generated.resources.host_detail_field_show
import fr.techtical.nextsh.desktop.generated.resources.host_detail_group_optional
import fr.techtical.nextsh.desktop.generated.resources.host_detail_group_placeholder
import fr.techtical.nextsh.desktop.generated.resources.host_detail_hostname_label
import fr.techtical.nextsh.desktop.generated.resources.host_detail_key_empty
import fr.techtical.nextsh.desktop.generated.resources.host_detail_key_pick
import fr.techtical.nextsh.desktop.generated.resources.host_detail_key_pick_disabled
import fr.techtical.nextsh.desktop.generated.resources.host_detail_key_select_label
import fr.techtical.nextsh.desktop.generated.resources.host_detail_label_placeholder_alt
import fr.techtical.nextsh.desktop.generated.resources.host_detail_label_short
import fr.techtical.nextsh.desktop.generated.resources.host_detail_password_current
import fr.techtical.nextsh.desktop.generated.resources.host_detail_password_keep_hint
import fr.techtical.nextsh.desktop.generated.resources.host_detail_password_new
import fr.techtical.nextsh.desktop.generated.resources.host_detail_save
import fr.techtical.nextsh.desktop.generated.resources.host_detail_save_loading
import fr.techtical.nextsh.desktop.generated.resources.host_detail_section_auth
import fr.techtical.nextsh.desktop.generated.resources.host_detail_section_connection
import fr.techtical.nextsh.desktop.generated.resources.host_detail_section_danger
import fr.techtical.nextsh.desktop.generated.resources.host_detail_section_identity
import fr.techtical.nextsh.desktop.generated.resources.host_detail_section_terminal
import fr.techtical.nextsh.desktop.generated.resources.host_detail_terminal_theme
import fr.techtical.nextsh.desktop.generated.resources.host_detail_title_edit
import fr.techtical.nextsh.desktop.generated.resources.host_detail_title_new
import fr.techtical.nextsh.desktop.generated.resources.host_detail_user_label
import fr.techtical.nextsh.desktop.generated.resources.theme_picker_manage
import org.jetbrains.compose.resources.stringResource

/**
 * Édition d'un hôte, refonte Phase 2.9 (overlay modal).
 *
 * Affichage : scrim NearBlack@0.65 plein écran + carte centrée 520×~auto
 * (max 90% hauteur dispo). Inspirée du pattern CmdK : pas de nouvelle
 * fenêtre OS (pas d'entrée taskbar), juste un overlay Compose au-dessus
 * du HostList rendu en sous-couche par App.kt. Le scrim absorbe les
 * clics extérieurs (= dismiss), ESC dismisse aussi. La carte propre
 * consomme les clics pour ne pas dismisser.
 *
 * Layout interne compact :
 *  - header 44dp (icône + titre + ✕)
 *  - body scrollable : sections cards Surface/Border1 avec padding 14×10,
 *    gap inter-section 12dp, FieldLabel 10sp UPPERCASE GoldMuted au-dessus
 *    de chaque champ
 *  - footer 56dp avec Annuler ghost + Enregistrer Burgundy à droite,
 *    séparé du body par 1dp Border1
 *
 * Sections : Identité / Connexion / Authentification / Terminal /
 * Zone dangereuse (édition only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailScreen(
    hostId: String?,
    onBack: () -> Unit,
) {
    val viewModel = remember { HostDetailViewModel() }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(hostId) { viewModel.load(hostId) }

    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val scrimFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { scrimFocus.requestFocus() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack.copy(alpha = 0.65f))
            // Click-outside : tap sur le scrim → dismiss. detectTapGestures
            // sur Box parent ne déclenche que si le tap atteint cette couche
            // (la carte au-dessus consomme ses propres taps via pointerInput).
            .pointerInput(Unit) { detectTapGestures(onTap = { onBack() }) }
            // ESC dismisse l'overlay. focusRequester + focusable rendent le
            // Box capable de recevoir les events clavier.
            .focusRequester(scrimFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onBack(); true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        // Hauteur max = 90% de la zone disponible : la carte doit pouvoir
        // tenir sur des fenêtres ~700dp de haut sans déborder.
        val maxCardHeight = maxHeight * 0.9f

        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .heightIn(max = maxCardHeight)
                .clip(RoundedCornerShape(Radii.Xl))
                .background(Surface)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Xl))
                // Consomme tous les taps : empêche que les clics dans la
                // carte remontent jusqu'au scrim parent et ferment l'overlay.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            CardHeader(
                isExisting = state.isExisting,
                title = stringResource(if (state.isExisting) Res.string.host_detail_title_edit else Res.string.host_detail_title_new),
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
                ConnectionSection(state, viewModel)
                AuthenticationSection(
                    state = state,
                    viewModel = viewModel,
                    passwordInput = passwordInput,
                    onPasswordInput = { passwordInput = it },
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                )
                CustomizationSection(state, viewModel)

                if (state.isExisting) {
                    DangerZoneSection(label = state.label, onDelete = { showDeleteDialog = true })
                }
            }

            CardFooter(
                isSaving = state.isSaving,
                onCancel = onBack,
                onSave = {
                    val pinChars = if (state.authType == AuthType.PASSWORD && passwordInput.isNotEmpty()) {
                        passwordInput.toCharArray()
                    } else null
                    passwordInput = ""
                    viewModel.save(pinChars, onBack)
                },
            )
        }
    }

    if (showDeleteDialog) {
        ConfirmDeleteDialog(
            title = stringResource(Res.string.host_detail_delete_dialog_title),
            message = stringResource(Res.string.host_detail_delete_dialog_body, state.label.ifBlank { stringResource(Res.string.host_detail_delete_default_label) }),
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
                label = stringResource(if (isSaving) Res.string.host_detail_save_loading else Res.string.host_detail_save),
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
private fun IdentitySection(state: HostDetailFormState, viewModel: HostDetailViewModel) {
    FormSection(title = stringResource(Res.string.host_detail_section_identity)) {
        FieldGroup {
            FieldLabel(stringResource(Res.string.host_detail_label_short))
            TechticalTextField(
                value = state.label,
                onValueChange = viewModel::onLabel,
                placeholder = stringResource(Res.string.host_detail_label_placeholder_alt),
            )
        }
        FieldGroup {
            FieldLabel(stringResource(Res.string.host_detail_group_optional))
            TechticalTextField(
                value = state.group,
                onValueChange = viewModel::onGroup,
                placeholder = stringResource(Res.string.host_detail_group_placeholder),
            )
            val suggestions = state.availableGroups
                .filter { it.contains(state.group, ignoreCase = true) && it != state.group }
                .take(8)
            if (suggestions.isNotEmpty()) {
                GroupChipsRow(suggestions = suggestions, onPick = viewModel::onGroup)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSection(state: HostDetailFormState, viewModel: HostDetailViewModel) {
    FormSection(title = stringResource(Res.string.host_detail_section_connection)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                FieldLabel(stringResource(Res.string.host_detail_hostname_label))
                TechticalTextField(
                    value = state.hostname,
                    onValueChange = viewModel::onHostname,
                    placeholder = "192.168.1.10",
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                FieldLabel("Port")
                TechticalTextField(
                    value = state.port,
                    onValueChange = viewModel::onPort,
                    placeholder = "22",
                )
            }
        }
        FieldGroup {
            FieldLabel(stringResource(Res.string.host_detail_user_label))
            TechticalTextField(
                value = state.username,
                onValueChange = viewModel::onUsername,
                placeholder = "root",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticationSection(
    state: HostDetailFormState,
    viewModel: HostDetailViewModel,
    passwordInput: String,
    onPasswordInput: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
) {
    FormSection(title = stringResource(Res.string.host_detail_section_auth)) {
        FieldGroup {
            FieldLabel(stringResource(Res.string.host_detail_auth_method))
            RowSelectFull(
                value = state.authType.displayName(),
                options = AuthType.entries.map { type ->
                    type.displayName() to { viewModel.onAuthType(type) }
                },
            )
        }

        when (state.authType) {
            AuthType.PASSWORD -> {
                FieldGroup {
                    FieldLabel(
                        stringResource(if (state.isExisting) Res.string.host_detail_password_new else Res.string.host_detail_password_current),
                    )
                    PasswordField(
                        value = passwordInput,
                        onValueChange = onPasswordInput,
                        visible = passwordVisible,
                        onToggleVisible = onTogglePasswordVisible,
                    )
                    if (state.isExisting) {
                        HelperText(stringResource(Res.string.host_detail_password_keep_hint))
                    }
                }
            }

            AuthType.SSH_KEY, AuthType.CERTIFICATE -> {
                FieldGroup {
                    FieldLabel(stringResource(Res.string.host_detail_key_select_label))
                    KeyDropdown(
                        selectedKeyId = state.credentialId.takeIf { it.isNotBlank() },
                        availableKeys = state.availableKeys,
                        onSelected = viewModel::onSelectKey,
                    )
                    if (state.availableKeys.isEmpty()) {
                        HelperText(
                            stringResource(Res.string.host_detail_key_empty),
                            warn = true,
                        )
                    }
                }
                if (state.authType == AuthType.CERTIFICATE) {
                    FieldGroup {
                        FieldLabel(stringResource(Res.string.host_detail_cert_label))
                        CertificatePickerRow(
                            pendingFileName = state.pendingCertFileName,
                            certAlreadyStored = state.certAlreadyStored && state.pendingCertPem == null,
                            onCertLoaded = viewModel::onCertLoaded,
                        )
                    }
                }
            }

            AuthType.FIDO2 -> {
                FieldGroup {
                    FieldLabel(stringResource(Res.string.host_detail_auth_fido2_select))
                    KeyDropdown(
                        selectedKeyId = state.credentialId.takeIf { it.isNotBlank() },
                        availableKeys = state.availableFido2Keys,
                        onSelected = viewModel::onSelectKey,
                    )
                    if (state.availableFido2Keys.isEmpty()) {
                        HelperText(
                            stringResource(Res.string.host_detail_auth_fido2_no_keys),
                            warn = true,
                        )
                    }
                }
            }

            AuthType.BIOMETRIC_KEY -> {
                ErrorBanner(stringResource(Res.string.host_detail_fido2_unavailable))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizationSection(state: HostDetailFormState, viewModel: HostDetailViewModel) {
    val customThemes by viewModel.customThemes.collectAsState()
    // Tracks the set of custom theme ids known before opening the picker, so that
    // when a NEW theme is created from here we can auto-select it for this host.
    var manageOpen by remember { mutableStateOf(false) }
    var idsBeforeManage by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Auto-select a theme created while the picker was open (id not seen before).
    LaunchedEffect(customThemes, manageOpen) {
        if (manageOpen) {
            val newId = customThemes.map { it.id }.firstOrNull { it !in idsBeforeManage }
            if (newId != null) {
                viewModel.onTerminalTheme(newId)
                idsBeforeManage = customThemes.map { it.id }.toSet()
            }
        }
    }

    FormSection(title = stringResource(Res.string.host_detail_section_terminal)) {
        FieldGroup {
            FieldLabel(stringResource(Res.string.host_detail_terminal_theme))
            // The stored value is either a preset enum NAME or a custom theme UUID.
            // Resolve it so the trigger shows the right display name, then list both
            // presets and custom themes in the dropdown (parity with Android).
            val resolved = resolveTheme(state.terminalTheme, customThemes)
            val displayName = TerminalThemeId.entries.firstOrNull { it.name == resolved.name }?.displayName
                ?: customThemes.firstOrNull { it.id == resolved.name }?.name
                ?: TerminalThemeId.TECHTICAL_DARK.displayName
            val options = buildList<Pair<String, () -> Unit>> {
                TerminalThemeId.entries.forEach { id ->
                    add(id.displayName to { viewModel.onTerminalTheme(id.name) })
                }
                customThemes.forEach { custom ->
                    add(custom.name to { viewModel.onTerminalTheme(custom.id) })
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    RowSelectFull(
                        value = displayName,
                        options = options,
                    )
                }
                // Manage custom themes (create / edit / delete) WITHOUT an SSH
                // session, reuses the in-session ThemePickerDialog + editor +
                // HSV color picker, wired to the host-screen save/delete.
                ManageThemesButton(onClick = {
                    idsBeforeManage = customThemes.map { it.id }.toSet()
                    manageOpen = true
                })
            }
        }
    }

    if (manageOpen) {
        ThemePickerDialog(
            currentThemeName = state.terminalTheme,
            customThemes = customThemes,
            // Selecting a row also assigns it to this host (extra nicety: the
            // dropdown already does this, but it keeps the picker selection in sync).
            onThemeChange = { picked -> viewModel.onTerminalTheme(picked) },
            onSaveCustomTheme = { theme -> viewModel.saveCustomTheme(theme) },
            onDeleteCustomTheme = { id -> viewModel.deleteCustomTheme(id) },
            onDismiss = { manageOpen = false },
        )
    }
}

/** Palette button opening the theme manager dialog from the Host detail screen. */
@Composable
private fun ManageThemesButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val borderColor = if (hovered) Border2 else Border1
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Surface, contentColor = Gold),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modifier = Modifier
            .heightIn(min = 36.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Lucide.Palette, contentDescription = stringResource(Res.string.theme_picker_manage), tint = Gold, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.theme_picker_manage), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DangerZoneSection(label: String, onDelete: () -> Unit) {
    FormSection(title = stringResource(Res.string.host_detail_section_danger), accent = ErrorRed) {
        Text(
            text = stringResource(Res.string.host_detail_danger_text, label.ifBlank { stringResource(Res.string.host_detail_delete_default_label) }),
            color = TextSecondary,
            fontSize = 11.sp,
        )
        DangerRowButton(
            icon = Lucide.Trash2,
            label = stringResource(Res.string.host_detail_delete),
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
                tint = WarningAmber,
                modifier = Modifier.size(10.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            color = if (warn) WarningAmber else TextDisabled,
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(text = placeholder, color = TextDisabled, fontSize = 12.sp)
        },
        singleLine = true,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    TechticalTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "••••••••",
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisible, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = if (visible) Lucide.EyeOff else Lucide.Eye,
                    contentDescription = stringResource(if (visible) Res.string.host_detail_field_hide else Res.string.host_detail_field_show),
                    tint = TextSecondary,
                    modifier = Modifier.size(13.dp),
                )
            }
        },
    )
}

// ── Pill dropdown plein largeur (auth type, thème) ──────────────────────────

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
private fun KeyDropdown(
    selectedKeyId: String?,
    availableKeys: List<SshKey>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val selected = availableKeys.firstOrNull { it.id == selectedKeyId }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val enabled = availableKeys.isNotEmpty()
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
                Icon(Lucide.KeyRound, contentDescription = null, tint = Gold, modifier = Modifier.size(11.dp))
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
                        text = selected.keyType.name,
                        color = TextSecondary,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                    )
                } else {
                    Text(
                        text = stringResource(if (enabled) Res.string.host_detail_key_pick else Res.string.host_detail_key_pick_disabled),
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
            availableKeys.forEach { key ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(key.label, color = TextPrimary, fontSize = 12.sp)
                            Text(
                                text = key.keyType.name,
                                color = TextSecondary,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.sp,
                            )
                        }
                    },
                    onClick = {
                        onSelected(key.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Style commun aux popups DropdownMenu : Surface bg + Border1 1dp clip
 * Radii.Md, pour rester cohérent avec le reste du chrome refondu
 * (RowSelectFull, KeyDropdown, popup `…` HostCard).
 */
@Composable
private fun techticalDropdownModifier(): Modifier =
    Modifier
        .clip(RoundedCornerShape(Radii.Md))
        .background(Surface)
        .border(1.dp, Border1, RoundedCornerShape(Radii.Md))

@Composable
private fun CertificatePickerRow(
    pendingFileName: String?,
    certAlreadyStored: Boolean,
    onCertLoaded: (pem: String, fileName: String) -> Unit,
) {
    val statusText = pendingFileName
        ?: stringResource(if (certAlreadyStored) Res.string.host_detail_cert_kept else Res.string.host_detail_cert_none)
    val hasCert = pendingFileName != null || certAlreadyStored

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Md))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.FileBadge, contentDescription = null, tint = Gold, modifier = Modifier.size(11.dp))
        }
        Spacer(Modifier.width(Spacing.Sm))
        Text(
            text = statusText,
            color = if (hasCert) TextPrimary else TextDisabled,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        BtnSecondarySm(
            onClick = {
                val picked = pickCertificateFile()
                if (picked != null) {
                    onCertLoaded(picked.readText(Charsets.UTF_8), picked.name)
                }
            },
            label = stringResource(if (hasCert) Res.string.host_detail_cert_replace else Res.string.host_detail_cert_select),
        )
    }
}

@Composable
private fun GroupChipsRow(suggestions: List<String>, onPick: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.host_detail_existing_label),
            color = TextDisabled,
            fontSize = 10.sp,
        )
        suggestions.forEach { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radii.Sm))
                    .background(Gold.copy(alpha = 0.08f))
                    .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(Radii.Sm))
                    .clickable { onPick(suggestion) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = suggestion,
                    color = GoldLight,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

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
private fun BtnSecondarySm(onClick: () -> Unit, label: String) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
        border = BorderStroke(1.dp, Border2),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .wrapContentWidth()
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
private fun AuthType.displayName(): String = stringResource(when (this) {
    AuthType.PASSWORD -> Res.string.host_detail_auth_type_password
    AuthType.SSH_KEY -> Res.string.host_detail_auth_type_ssh_key
    AuthType.CERTIFICATE -> Res.string.host_detail_auth_type_certificate
    AuthType.FIDO2 -> Res.string.host_detail_auth_type_fido2
    AuthType.BIOMETRIC_KEY -> Res.string.host_detail_auth_type_biometric
})

/**
 * AWT `FileDialog` pour pick le fichier `<keyname>-cert.pub`. Bloquant sur
 * l'AWT event thread, OK dans un click handler Compose. Même approche que
 * SFTP upload/download (pas de SwingPanel punch-through).
 */
private fun pickCertificateFile(): File? {
    val dialog = FileDialog(null as Frame?, "Sélectionner le certificat OpenSSH", FileDialog.LOAD).apply {
        isMultipleMode = false
        setFilenameFilter { _, name -> name.endsWith("-cert.pub") || name.endsWith(".pub") }
        isVisible = true
    }
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name).takeIf { it.exists() && it.isFile }
}
