// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import fr.techtical.nextsh.desktop.components.PageHeader
import fr.techtical.nextsh.desktop.components.rememberNowTicking
import fr.techtical.nextsh.shared.util.RelativeTime
import fr.techtical.nextsh.desktop.core.security.WindowSecurityHelper
import fr.techtical.nextsh.desktop.sessions.ThemePickerDialog
import fr.techtical.nextsh.desktop.sync.LanSyncServer
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.BurgundyDark
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SuccessGreen
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.WarningAmber
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.desktop.data.preferences.LanguagePref
import fr.techtical.nextsh.desktop.vault.RecoveryPhraseDisplay
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.settings_clipboard_disabled
import fr.techtical.nextsh.desktop.generated.resources.settings_clipboard_seconds_format
import fr.techtical.nextsh.desktop.generated.resources.settings_danger_warning
import fr.techtical.nextsh.desktop.generated.resources.settings_label_clipboard_expiry_short
import fr.techtical.nextsh.desktop.generated.resources.settings_label_connection_timeout_short
import fr.techtical.nextsh.desktop.generated.resources.settings_label_enroll_mobile
import fr.techtical.nextsh.desktop.generated.resources.settings_hint_connection_timeout
import fr.techtical.nextsh.desktop.generated.resources.settings_hint_hide_from_capture
import fr.techtical.nextsh.desktop.generated.resources.settings_hint_minimize_to_tray
import fr.techtical.nextsh.desktop.generated.resources.settings_hint_minimize_to_tray_unavailable
import fr.techtical.nextsh.desktop.generated.resources.settings_label_hide_from_capture
import fr.techtical.nextsh.desktop.generated.resources.settings_label_known_hosts
import fr.techtical.nextsh.desktop.generated.resources.settings_label_minimize_to_tray
import fr.techtical.nextsh.desktop.generated.resources.settings_label_recovery_phrase
import fr.techtical.nextsh.desktop.generated.resources.settings_label_recovery_phrase_setup
import fr.techtical.nextsh.desktop.generated.resources.settings_recovery_regenerate_body
import fr.techtical.nextsh.desktop.generated.resources.settings_recovery_regenerate_confirm
import fr.techtical.nextsh.desktop.generated.resources.settings_recovery_regenerate_title
import fr.techtical.nextsh.desktop.generated.resources.settings_recovery_status_exists
import fr.techtical.nextsh.desktop.generated.resources.settings_recovery_status_missing
import fr.techtical.nextsh.desktop.generated.resources.settings_label_sync_enabled_short
import fr.techtical.nextsh.desktop.generated.resources.settings_label_sync_enrolled
import fr.techtical.nextsh.desktop.generated.resources.settings_label_terminal_font
import fr.techtical.nextsh.desktop.generated.resources.settings_label_wipe_vault
import fr.techtical.nextsh.desktop.generated.resources.settings_relative_just_now
import fr.techtical.nextsh.desktop.generated.resources.settings_section_connection
import fr.techtical.nextsh.desktop.generated.resources.settings_section_danger
import fr.techtical.nextsh.desktop.generated.resources.settings_section_security
import fr.techtical.nextsh.desktop.generated.resources.settings_section_sync
import fr.techtical.nextsh.desktop.generated.resources.settings_section_terminal
import fr.techtical.nextsh.desktop.generated.resources.settings_subtitle
import fr.techtical.nextsh.desktop.generated.resources.settings_terminal_themes_action
import fr.techtical.nextsh.desktop.generated.resources.settings_terminal_themes_subtitle
import fr.techtical.nextsh.desktop.generated.resources.settings_terminal_themes_title
import fr.techtical.nextsh.desktop.generated.resources.settings_sync_last_none
import fr.techtical.nextsh.desktop.generated.resources.settings_sync_last_received
import fr.techtical.nextsh.desktop.generated.resources.settings_sync_server_active
import fr.techtical.nextsh.desktop.generated.resources.settings_sync_server_error
import fr.techtical.nextsh.desktop.generated.resources.settings_sync_server_starting
import fr.techtical.nextsh.desktop.generated.resources.settings_sync_server_retry
import fr.techtical.nextsh.desktop.generated.resources.settings_sync_server_stopped
import fr.techtical.nextsh.desktop.generated.resources.settings_sync_help_firewall_profile
import fr.techtical.nextsh.desktop.generated.resources.settings_sync_help_firewall_prompts
import fr.techtical.nextsh.desktop.generated.resources.settings_label_language
import fr.techtical.nextsh.desktop.generated.resources.settings_language_restart_hint
import fr.techtical.nextsh.desktop.generated.resources.settings_section_interface
import fr.techtical.nextsh.desktop.generated.resources.settings_title
import fr.techtical.nextsh.desktop.generated.resources.settings_value_language_en
import fr.techtical.nextsh.desktop.generated.resources.settings_value_language_fr
import fr.techtical.nextsh.desktop.generated.resources.settings_value_language_system
import fr.techtical.nextsh.desktop.generated.resources.settings_wipe_confirm_body
import fr.techtical.nextsh.desktop.generated.resources.settings_wipe_confirm_continue
import fr.techtical.nextsh.desktop.generated.resources.settings_wipe_confirm_title
import fr.techtical.nextsh.desktop.generated.resources.settings_wipe_token_body
import fr.techtical.nextsh.desktop.generated.resources.settings_wipe_token_button
import fr.techtical.nextsh.desktop.generated.resources.settings_wipe_token_field_label
import fr.techtical.nextsh.desktop.generated.resources.settings_wipe_token_title
import fr.techtical.nextsh.desktop.generated.resources.settings_label_purge_all
import fr.techtical.nextsh.desktop.generated.resources.settings_purge_confirm_title
import fr.techtical.nextsh.desktop.generated.resources.settings_purge_confirm_body
import fr.techtical.nextsh.desktop.generated.resources.settings_purge_confirm_continue
import fr.techtical.nextsh.desktop.generated.resources.settings_purge_token_title
import fr.techtical.nextsh.desktop.generated.resources.settings_purge_token_body
import fr.techtical.nextsh.desktop.generated.resources.settings_purge_token_field_label
import fr.techtical.nextsh.desktop.generated.resources.settings_purge_token_button
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_days_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_hours_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_minutes_ago
import org.jetbrains.compose.resources.stringResource

private val CLIPBOARD_EXPIRY_OPTIONS = listOf(0, 15, 30, 60, 90, 120)
private const val WIPE_CONFIRMATION_TOKEN = "CONFIRMER"
private const val PURGE_CONFIRMATION_TOKEN = "SUPPRIMER"

/**
 * Écran Paramètres, refonte Phase 2.6.
 *
 * Layout : Column NearBlack → PageHeader → contenu scrollable contraint
 * à 760dp max (`Modifier.widthIn(max=760.dp)`) pour rester lisible sur
 * grand écran. Suppression Scaffold/TopAppBar Material3 → cohérent
 * avec HostList/Tunnels/Vault refondus.
 *
 * Structure : sections nommées (`SettingsSection`) avec header
 * GoldLight UPPERCASE + card Surface/Border1, contenant des composants
 * de saisie (`SliderRow`, `RowSelect`, `ToggleRow`) inspirés de la
 * maquette `SettingsPage`. Boutons d'accès aux écrans secondaires en
 * `.btn-secondary` full-width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKnownHosts: () -> Unit,
    onOpenEnrollment: () -> Unit,
    onVaultWiped: () -> Unit,
    onPurged: () -> Unit = {},
    onOpenConflictResolution: () -> Unit = {},
    onOpenEnrolledDevices: () -> Unit = {},
) {
    val viewModel = remember { SettingsViewModel() }
    val settings by viewModel.settings.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val schedulerState by viewModel.schedulerState.collectAsState()
    val customThemes by viewModel.customThemes.collectAsState()
    val recoveryState by viewModel.recoveryState.collectAsState()
    var showWipeConfirmStep1 by remember { mutableStateOf(false) }
    var showWipeConfirmStep2 by remember { mutableStateOf(false) }
    var wipeConfirmText by remember { mutableStateOf("") }
    var showPurgeConfirmStep1 by remember { mutableStateOf(false) }
    var showPurgeConfirmStep2 by remember { mutableStateOf(false) }
    var purgeConfirmText by remember { mutableStateOf("") }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    // Global terminal-themes manager (manage-only ThemePickerDialog). No host to
    // assign to: pure create/edit/delete with live-apply to open sessions.
    var showThemeManager by remember { mutableStateOf(false) }

    // B3 self-heal: simply opening Settings re-checks the LAN sync server and
    // restarts it if it should be running but died (e.g. a transient "no LAN
    // interface" error at boot before the network was up). No-op when sync is
    // disabled / vault locked / server already running.
    LaunchedEffect(Unit) { viewModel.ensureSyncRunning() }

    Column(modifier = Modifier.fillMaxSize().background(NearBlack)) {
        PageHeader(
            title = stringResource(Res.string.settings_title),
            subtitle = stringResource(Res.string.settings_subtitle),
        )

        // Distribution responsive 1 / 2 / 3 colonnes pour éviter d'avoir
        // jamais de card excessivement large quelle que soit la taille
        // fenêtre :
        //  - >= 900 dp : 3 colonnes (densité maximale, écran standard).
        //  - >= 650 dp : 2 colonnes (≈ 280 dp/col, encore confortable).
        //  - <  650 dp : 1 colonne (cappé à 760 dp), évite la card étirée.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Xxl, vertical = Spacing.Xl),
        ) {
            val columns = when {
                maxWidth >= 900.dp -> 3
                maxWidth >= 650.dp -> 2
                else -> 1
            }

            @Composable
            fun ConnectionSection() = SettingsSection(title = stringResource(Res.string.settings_section_connection)) {
                // Crans 5s : valeurs autorisées 5/10/15/20/25/30. En Compose
                // `steps` = nombre de paliers intermédiaires hors endpoints,
                // donc 4 → 6 valeurs effectives (5,10,15,20,25,30). Parité
                // avec l'écran Android.
                SliderRow(
                    label = stringResource(Res.string.settings_label_connection_timeout_short),
                    value = settings.connectionTimeout,
                    valueRange = 5..30,
                    steps = 4,
                    suffix = "s",
                    onChange = viewModel::setConnectionTimeout,
                    hint = stringResource(Res.string.settings_hint_connection_timeout),
                )
            }

            @Composable
            fun TerminalSection() = SettingsSection(title = stringResource(Res.string.settings_section_terminal)) {
                SliderRow(
                    label = stringResource(Res.string.settings_label_terminal_font),
                    value = settings.terminalFontSize,
                    valueRange = 10..20,
                    steps = 9,
                    suffix = "px",
                    onChange = viewModel::setTerminalFontSize,
                )
            }

            // Global terminal-themes manager. Lives in its own section so the
            // create/edit/delete entry is reachable without opening a host's edit
            // form (the per-host dropdown + host-form "Gérer les thèmes" button are
            // unchanged, this is purely additive).
            @Composable
            fun TerminalThemesSection() = SettingsSection(title = stringResource(Res.string.settings_terminal_themes_title)) {
                Text(
                    text = stringResource(Res.string.settings_terminal_themes_subtitle),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                SecondaryRowButton(
                    icon = Lucide.Palette,
                    label = stringResource(Res.string.settings_terminal_themes_action),
                    onClick = { showThemeManager = true },
                )
            }

            @Composable
            fun SyncSection() {
                val pendingConflicts = schedulerState.pendingConflicts
                SettingsSection(title = stringResource(Res.string.settings_section_sync)) {
                    ToggleRow(
                        label = stringResource(Res.string.settings_label_sync_enabled_short),
                        checked = settings.syncEnabled,
                        onChange = viewModel::setSyncEnabled,
                    )
                    if (settings.syncEnabled) {
                        LanSyncStatusLine(
                            syncState = syncState,
                            schedulerState = schedulerState,
                            onRetry = { viewModel.ensureSyncRunning() },
                        )
                    }
                    // Alerte conflits intégrée dans la card (au lieu d'être
                    // au-dessus), plus cohérent avec les autres actions
                    // sync : la résolution est une action de la sync, pas
                    // un état séparé.
                    if (pendingConflicts > 0) {
                        ConflictAlert(count = pendingConflicts, onClick = onOpenConflictResolution)
                    }
                    SecondaryRowButton(
                        icon = Lucide.Smartphone,
                        label = stringResource(Res.string.settings_label_sync_enrolled),
                        onClick = onOpenEnrolledDevices,
                    )
                    SecondaryRowButton(
                        icon = Lucide.Smartphone,
                        label = stringResource(Res.string.settings_label_enroll_mobile),
                        onClick = onOpenEnrollment,
                    )
                }
            }

            @Composable
            fun SecuritySection() = SettingsSection(title = stringResource(Res.string.settings_section_security)) {
                ClipboardExpiryRow(
                    current = settings.clipboardClearTimeout,
                    onChange = viewModel::setClipboardClearTimeout,
                )
                // Hide-from-capture toggle, Windows only.
                // On non-Windows the toggle is not rendered to keep the UI clean.
                if (WindowSecurityHelper.isPlatformSupported) {
                    HideFromCaptureRow(
                        checked = settings.hideFromScreenCapture,
                        onChange = viewModel::setHideFromScreenCapture,
                    )
                }
                SecondaryRowButton(
                    icon = Lucide.ShieldCheck,
                    label = stringResource(Res.string.settings_label_known_hosts),
                    onClick = onOpenKnownHosts,
                )
                RecoveryPhraseRow(
                    exists = recoveryState.exists,
                    isBusy = recoveryState.isBusy,
                    onGenerate = { viewModel.generateRecoveryPhrase() },
                    onRegenerate = { showRegenerateConfirm = true },
                )
            }

            @Composable
            fun InterfaceSection() {
                val langPref by viewModel.languagePreference.collectAsState()
                val langLabel = when (langPref) {
                    LanguagePref.SYSTEM -> stringResource(Res.string.settings_value_language_system)
                    LanguagePref.FR -> stringResource(Res.string.settings_value_language_fr)
                    LanguagePref.EN -> stringResource(Res.string.settings_value_language_en)
                }
                SettingsSection(title = stringResource(Res.string.settings_section_interface)) {
                    RowSelect(
                        label = stringResource(Res.string.settings_label_language),
                        value = langLabel,
                        options = listOf(
                            stringResource(Res.string.settings_value_language_system) to { viewModel.setLanguagePreference(LanguagePref.SYSTEM) },
                            stringResource(Res.string.settings_value_language_fr) to { viewModel.setLanguagePreference(LanguagePref.FR) },
                            stringResource(Res.string.settings_value_language_en) to { viewModel.setLanguagePreference(LanguagePref.EN) },
                        ),
                    )
                    Text(
                        text = stringResource(Res.string.settings_language_restart_hint),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                    MinimizeToTrayRow(
                        checked = settings.minimizeToTrayOnClose,
                        trayAvailable = viewModel.trayAvailable,
                        onChange = viewModel::setMinimizeToTrayOnClose,
                    )
                }
            }

            @Composable
            fun DangerSection() = SettingsSection(title = stringResource(Res.string.settings_section_danger), accent = ErrorRed) {
                Text(
                    text = stringResource(Res.string.settings_danger_warning),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                DangerRowButton(
                    icon = Lucide.Trash2,
                    label = stringResource(Res.string.settings_label_wipe_vault),
                    onClick = { showWipeConfirmStep1 = true },
                )
                DangerRowButton(
                    icon = Lucide.Trash2,
                    label = stringResource(Res.string.settings_label_purge_all),
                    onClick = { showPurgeConfirmStep1 = true },
                )
            }

            when (columns) {
                3 -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xl),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Xl),
                    ) {
                        ConnectionSection()
                        TerminalThemesSection()
                        DangerSection()
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Xl),
                    ) {
                        TerminalSection()
                        InterfaceSection()
                        SecuritySection()
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Xl),
                    ) {
                        SyncSection()
                    }
                }
                2 -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xl),
                ) {
                    // Distribution équilibrée : SyncLAN à droite (la plus
                    // grande après absorption d'Enrôler), le reste à gauche.
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Xl),
                    ) {
                        ConnectionSection()
                        TerminalThemesSection()
                        TerminalSection()
                        InterfaceSection()
                        SecuritySection()
                        DangerSection()
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Xl),
                    ) {
                        SyncSection()
                    }
                }
                else -> Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xl),
                ) {
                    ConnectionSection()
                    TerminalThemesSection()
                    TerminalSection()
                    InterfaceSection()
                    SyncSection()
                    SecuritySection()
                    DangerSection()
                }
            }
        }
    }

    // ── Manager de thèmes (manage-only ThemePickerDialog) ────────────────────
    // manageMode = true : aucune sélection (pas d'hôte à assigner), présets masqués,
    // seuls les thèmes personnalisés sont listés avec Modifier/Supprimer + Nouveau.
    // currentThemeName est ignoré en manage mode (passé vide), onThemeChange jamais
    // appelé. Save/Delete passent par le VM qui live-applique aux sessions ouvertes.
    if (showThemeManager) {
        ThemePickerDialog(
            currentThemeName = "",
            customThemes = customThemes,
            onThemeChange = {},
            onSaveCustomTheme = { theme -> viewModel.saveCustomTheme(theme) },
            onDeleteCustomTheme = { id -> viewModel.deleteCustomTheme(id) },
            onDismiss = { showThemeManager = false },
            manageMode = true,
        )
    }

    // ── Dialogs wipe (deux étapes) ───────────────────────────────────────────
    if (showWipeConfirmStep1) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmStep1 = false },
            title = { Text(stringResource(Res.string.settings_wipe_confirm_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(Res.string.settings_wipe_confirm_body),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showWipeConfirmStep1 = false
                    wipeConfirmText = ""
                    showWipeConfirmStep2 = true
                }) { Text(stringResource(Res.string.settings_wipe_confirm_continue), color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmStep1 = false }) {
                    Text(stringResource(Res.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = Surface,
        )
    }

    if (showWipeConfirmStep2) {
        val canWipe = wipeConfirmText == WIPE_CONFIRMATION_TOKEN
        AlertDialog(
            onDismissRequest = { showWipeConfirmStep2 = false },
            title = { Text(stringResource(Res.string.settings_wipe_token_title, WIPE_CONFIRMATION_TOKEN), color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                    Text(
                        stringResource(Res.string.settings_wipe_token_body, WIPE_CONFIRMATION_TOKEN),
                        color = TextSecondary,
                    )
                    OutlinedTextField(
                        value = wipeConfirmText,
                        onValueChange = { wipeConfirmText = it },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.settings_wipe_token_field_label)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ErrorRed,
                            unfocusedBorderColor = GoldMuted,
                            focusedLabelColor = ErrorRed,
                            unfocusedLabelColor = TextSecondary,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = ErrorRed,
                            focusedContainerColor = SurfaceVariant,
                            unfocusedContainerColor = SurfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWipeConfirmStep2 = false
                        wipeConfirmText = ""
                        viewModel.wipeVault(onVaultWiped)
                    },
                    enabled = canWipe,
                ) { Text(stringResource(Res.string.settings_wipe_token_button), color = if (canWipe) ErrorRed else TextSecondary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showWipeConfirmStep2 = false
                    wipeConfirmText = ""
                }) { Text("Annuler", color = TextSecondary) }
            },
            containerColor = Surface,
        )
    }

    // ── Dialogs purge complète (deux étapes) ─────────────────────────────────
    if (showPurgeConfirmStep1) {
        AlertDialog(
            onDismissRequest = { showPurgeConfirmStep1 = false },
            title = { Text(stringResource(Res.string.settings_purge_confirm_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(Res.string.settings_purge_confirm_body),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPurgeConfirmStep1 = false
                    purgeConfirmText = ""
                    showPurgeConfirmStep2 = true
                }) { Text(stringResource(Res.string.settings_purge_confirm_continue), color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeConfirmStep1 = false }) {
                    Text(stringResource(Res.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = Surface,
        )
    }

    if (showPurgeConfirmStep2) {
        val canPurge = purgeConfirmText == PURGE_CONFIRMATION_TOKEN
        AlertDialog(
            onDismissRequest = { showPurgeConfirmStep2 = false },
            title = { Text(stringResource(Res.string.settings_purge_token_title, PURGE_CONFIRMATION_TOKEN), color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                    Text(
                        stringResource(Res.string.settings_purge_token_body, PURGE_CONFIRMATION_TOKEN),
                        color = TextSecondary,
                    )
                    OutlinedTextField(
                        value = purgeConfirmText,
                        onValueChange = { purgeConfirmText = it },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.settings_purge_token_field_label)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ErrorRed,
                            unfocusedBorderColor = GoldMuted,
                            focusedLabelColor = ErrorRed,
                            unfocusedLabelColor = TextSecondary,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = ErrorRed,
                            focusedContainerColor = SurfaceVariant,
                            unfocusedContainerColor = SurfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPurgeConfirmStep2 = false
                        purgeConfirmText = ""
                        viewModel.purgeAllLocalData(onPurged)
                    },
                    enabled = canPurge,
                ) { Text(stringResource(Res.string.settings_purge_token_button), color = if (canPurge) ErrorRed else TextSecondary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPurgeConfirmStep2 = false
                    purgeConfirmText = ""
                }) { Text(stringResource(Res.string.action_cancel), color = TextSecondary) }
            },
            containerColor = Surface,
        )
    }

    // ── Recovery phrase: regenerate confirmation ─────────────────────────────
    if (showRegenerateConfirm) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = { Text(stringResource(Res.string.settings_recovery_regenerate_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(Res.string.settings_recovery_regenerate_body),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRegenerateConfirm = false
                    viewModel.generateRecoveryPhrase()
                }) { Text(stringResource(Res.string.settings_recovery_regenerate_confirm), color = WarningAmber) }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }) {
                    Text(stringResource(Res.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = Surface,
        )
    }

    // ── Recovery phrase: one-time word display ───────────────────────────────
    val recoveryWords = recoveryState.words
    if (recoveryWords != null) {
        AlertDialog(
            onDismissRequest = { /* acknowledgement-gated; no dismiss on scrim */ },
            title = {},
            text = {
                RecoveryPhraseDisplay(
                    words = recoveryWords,
                    onAcknowledged = { viewModel.dismissRecoveryWords() },
                )
            },
            confirmButton = {},
            containerColor = Surface,
        )
    }
}

// ── Section ──────────────────────────────────────────────────────────────────

/**
 * Section paramètres : header label UPPERCASE GoldLight (ou autre accent
 * pour la zone dangereuse) au-dessus d'une card Surface/Border1 contenant
 * les contrôles, alignée maquette `SettingsPage` `Section` :
 *
 * ```css
 * .section-header { font-size:11; letter-spacing:0.08em; text-transform:uppercase;
 *                   color:var(--gold-light); font-weight:600; margin-bottom:12 }
 * .section-card   { background:var(--bg-2); border:1px solid #1f1f1f;
 *                   border-radius:8; padding:14 18; gap:12 }
 * ```
 */
@Composable
private fun SettingsSection(
    title: String,
    accent: Color = GoldLight,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
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
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            content = content,
        )
    }
}

// ── Composants de saisie ─────────────────────────────────────────────────────

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    valueRange: IntRange,
    steps: Int,
    suffix: String,
    onChange: (Int) -> Unit,
    /** Optional plain-language help text rendered below the slider (non-technical explanation). */
    hint: String? = null,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = TextPrimary, fontSize = 13.sp)
            Text(
                text = "$value $suffix",
                color = GoldLight,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps,
            // Ticks visibles aux paliers (parité Android) : Gold sur la
            // partie active du track, GoldMuted sur la partie inactive.
            colors = SliderDefaults.colors(
                thumbColor = Burgundy,
                activeTrackColor = Burgundy,
                inactiveTrackColor = SurfaceVariant,
                activeTickColor = GoldLight,
                inactiveTickColor = GoldMuted,
            ),
        )
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(text = hint, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

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
        Text(label, color = TextPrimary, fontSize = 13.sp)
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

/**
 * Toggle row with a sub-label for the hide-from-screen-capture setting.
 * Mirrors the label+hint pattern common in Android Settings screens.
 */
@Composable
private fun HideFromCaptureRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.Md)) {
            Text(
                text = stringResource(Res.string.settings_label_hide_from_capture),
                color = TextPrimary,
                fontSize = 13.sp,
            )
            Text(
                text = stringResource(Res.string.settings_hint_hide_from_capture),
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
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

/**
 * Toggle row for "minimize to tray on close", same label+hint pattern as
 * [HideFromCaptureRow]. Grayed out and forced off-looking (switch disabled)
 * when the tray icon could not be registered with the OS: enabling the
 * setting in that case would let the user hide the window with no way to
 * bring it back. The hint text itself explains why when disabled, rather
 * than just showing a mysteriously inert switch.
 */
@Composable
private fun MinimizeToTrayRow(
    checked: Boolean,
    trayAvailable: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.Md)) {
            Text(
                text = stringResource(Res.string.settings_label_minimize_to_tray),
                color = if (trayAvailable) TextPrimary else TextDisabled,
                fontSize = 13.sp,
            )
            Text(
                text = if (trayAvailable) {
                    stringResource(Res.string.settings_hint_minimize_to_tray)
                } else {
                    stringResource(Res.string.settings_hint_minimize_to_tray_unavailable)
                },
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
        Switch(
            checked = checked && trayAvailable,
            onCheckedChange = onChange,
            enabled = trayAvailable,
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

/**
 * Trigger style maquette `RowSelect` :
 * `<label>          <pill bg=#0d0d0d border=#232323> value <chevron> </pill>`.
 */
@Composable
private fun RowSelect(
    label: String,
    value: String,
    options: List<Pair<String, () -> Unit>>,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp)
        Box {
            val interactionSource = remember { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()
            val borderColor = if (hovered) Border2.copy(alpha = 1f) else Border2
            OutlinedButton(
                onClick = { expanded = true },
                interactionSource = interactionSource,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF0D0D0D),
                    contentColor = TextPrimary,
                ),
                border = BorderStroke(1.dp, borderColor),
                shape = RoundedCornerShape(Radii.Md),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 120.dp, minHeight = 0.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(value, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.Sm))
                Icon(Lucide.ChevronDown, contentDescription = null, modifier = Modifier.size(12.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Surface),
            ) {
                options.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label, color = TextPrimary) },
                        onClick = {
                            action()
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Sélecteur d'expiration du presse-papiers, slider à crans visibles
 * comme côté Android (parité UX). Les valeurs autorisées sont
 * `CLIPBOARD_EXPIRY_OPTIONS` = [0, 15, 30, 60, 90, 120], non
 * uniformément espacées en secondes : donc on slide sur l'**index**
 * (0..5) avec `steps = 4` (5 valeurs intermédiaires + 2 endpoints =
 * 6 paliers visibles), et on remappe vers la valeur réelle pour la
 * persistance et l'affichage. Active/inactive ticks colorés en Gold
 * pour matérialiser les paliers.
 */
@Composable
private fun ClipboardExpiryRow(current: Int, onChange: (Int) -> Unit) {
    val currentIndex = CLIPBOARD_EXPIRY_OPTIONS
        .indexOf(current)
        .takeIf { it >= 0 } ?: 0
    val displayValue = if (current == 0) stringResource(Res.string.settings_clipboard_disabled) else stringResource(Res.string.settings_clipboard_seconds_format, current)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.settings_label_clipboard_expiry_short), color = TextPrimary, fontSize = 13.sp)
            Text(
                text = displayValue,
                color = GoldLight,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = currentIndex.toFloat(),
            onValueChange = { idx ->
                val rounded = idx.toInt().coerceIn(0, CLIPBOARD_EXPIRY_OPTIONS.lastIndex)
                onChange(CLIPBOARD_EXPIRY_OPTIONS[rounded])
            },
            valueRange = 0f..CLIPBOARD_EXPIRY_OPTIONS.lastIndex.toFloat(),
            steps = CLIPBOARD_EXPIRY_OPTIONS.size - 2, // intermediate stops between endpoints
            colors = SliderDefaults.colors(
                thumbColor = Burgundy,
                activeTrackColor = Burgundy,
                inactiveTrackColor = SurfaceVariant,
                activeTickColor = GoldLight,
                inactiveTickColor = GoldMuted,
            ),
        )
    }
}

// ── Boutons secondaires d'accès aux écrans ───────────────────────────────────

@Composable
private fun SecondaryRowButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val borderColor = if (hovered) Border2.copy(alpha = 1f) else Border2
    val bg = if (hovered) Color.White.copy(alpha = 0.04f) else Color.Transparent
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = TextPrimary,
        ),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = ErrorRed,
        ),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Recovery-phrase management row in the Security section.
 *
 *  - When no phrase exists yet (migrated vault): a single "Generate recovery
 *    phrase" secondary button.
 *  - When a phrase already exists: a status line ("a recovery phrase is set")
 *    plus a "Regenerate" action whose click first opens a warning dialog (the
 *    previous phrase stops working).
 *
 * The vault is unlocked while in Settings so the underlying
 * [fr.techtical.nextsh.desktop.core.vault.VaultPinManager.setupRecoveryPhrase]
 * call always succeeds.
 */
@Composable
private fun RecoveryPhraseRow(
    exists: Boolean,
    isBusy: Boolean,
    onGenerate: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (exists) SuccessGreen else WarningAmber, RoundedCornerShape(9999.dp)),
            )
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = stringResource(
                    if (exists) Res.string.settings_recovery_status_exists
                    else Res.string.settings_recovery_status_missing
                ),
                color = if (exists) TextSecondary else WarningAmber,
                fontSize = 11.sp,
            )
        }
        SecondaryRowButton(
            icon = Lucide.KeyRound,
            label = stringResource(
                if (exists) Res.string.settings_label_recovery_phrase
                else Res.string.settings_label_recovery_phrase_setup
            ),
            onClick = if (isBusy) ({}) else if (exists) onRegenerate else onGenerate,
        )
    }
}

// ── Banner conflit / status sync ─────────────────────────────────────────────

/**
 * Bandeau de conflits, animation d'entrée one-shot pour attirer l'œil
 * sans devenir agaçante :
 *
 *  1. Petit délai de 350 ms à l'arrivée pour laisser la page s'installer.
 *  2. **Pulse de scale** sur 2 cycles décroissants (1.0 → 1.05 → 1.0 →
 *     1.03 → 1.0) : le composable n'est *pas* clipé, il dilate juste sa
 *     bounding box visuellement (graphicsLayer scale).
 *  3. **Flash de bordure** synchrone : alpha WarningAmber 0.30 → 0.85 →
 *     0.30 → 0.65 → 0.30. Renforce le pulse sans recourir à une teinte
 *     extra. Total ~720 ms.
 *
 * Pas de loop : on stocke un flag `playedOnce` pour ne déclencher
 * l'animation qu'à la première composition (V1). Le passage en
 * permanent loop sera évalué ensuite si la version one-shot manque
 * d'attention visuelle.
 */
@Composable
private fun ConflictAlert(count: Int, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (hovered) WarningAmber.copy(alpha = 0.12f) else WarningAmber.copy(alpha = 0.08f)

    // ── Pulse animation (one-shot à l'arrivée sur la page) ──────────────────
    val pulseScale = remember { Animatable(1f) }
    val borderAlpha = remember { Animatable(0.30f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        coroutineScope {
            launch {
                pulseScale.animateTo(1.05f, tween(180, easing = FastOutSlowInEasing))
                pulseScale.animateTo(1.0f, tween(180, easing = FastOutSlowInEasing))
                pulseScale.animateTo(1.03f, tween(180, easing = FastOutSlowInEasing))
                pulseScale.animateTo(1.0f, tween(180, easing = FastOutSlowInEasing))
            }
            launch {
                borderAlpha.animateTo(0.85f, tween(180, easing = FastOutSlowInEasing))
                borderAlpha.animateTo(0.30f, tween(180, easing = FastOutSlowInEasing))
                borderAlpha.animateTo(0.65f, tween(180, easing = FastOutSlowInEasing))
                borderAlpha.animateTo(0.30f, tween(180, easing = FastOutSlowInEasing))
            }
        }
    }

    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = WarningAmber,
        ),
        border = BorderStroke(1.dp, WarningAmber.copy(alpha = borderAlpha.value)),
        shape = RoundedCornerShape(Radii.Lg),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pulseScale.value
                scaleY = pulseScale.value
            }
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Lucide.TriangleAlert, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.Md))
        Text(
            text = "$count conflit${if (count > 1) "s" else ""} à résoudre",
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text("→", color = WarningAmber, fontSize = 14.sp)
    }
}

@Composable
private fun LanSyncStatusLine(
    syncState: LanSyncServer.State,
    schedulerState: SyncState,
    onRetry: () -> Unit,
) {
    val statusText: String
    val statusColor: Color
    val statusDot: Color
    when (syncState) {
        is LanSyncServer.State.Listening -> {
            statusText = stringResource(Res.string.settings_sync_server_active, syncState.addr, syncState.port)
            statusColor = SuccessGreen
            statusDot = SuccessGreen
        }
        is LanSyncServer.State.Starting -> {
            statusText = stringResource(Res.string.settings_sync_server_starting)
            statusColor = GoldMuted
            statusDot = GoldMuted
        }
        is LanSyncServer.State.Error -> {
            statusText = stringResource(Res.string.settings_sync_server_error, syncState.reason)
            statusColor = ErrorRed
            statusDot = ErrorRed
        }
        is LanSyncServer.State.Stopped -> {
            statusText = stringResource(Res.string.settings_sync_server_stopped)
            statusColor = TextDisabled
            statusDot = TextDisabled
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(statusDot, RoundedCornerShape(9999.dp)),
            )
            Spacer(Modifier.width(Spacing.Sm))
            Text(text = statusText, color = statusColor, fontSize = 11.sp)
        }
        val now = rememberNowTicking(schedulerState.lastSyncAt)
        val lastSyncText = schedulerState.lastSyncAt?.let { ts ->
            stringResource(Res.string.settings_sync_last_received, formatRelativeTime(ts, now))
        } ?: stringResource(Res.string.settings_sync_last_none)
        Text(
            text = lastSyncText,
            color = TextDisabled,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 14.dp),
        )
        // Honest-status recovery (B3): when the server is down (Error after a
        // failed start, or Stopped while sync is enabled) the status above already
        // shows the problem in red. Offer a one-click restart so the user never
        // needs the manual off→on. ensureRunning() is idempotent and self-heals
        // with retry/backoff.
        //
        // The firewall help text is useful in a SECOND case the original
        // `serverDown`-only condition missed entirely: an inbound-blocking
        // Windows Firewall rule does not stop the server from starting: it
        // just silently drops every incoming sync request, so the state stays
        // Listening forever and this block never rendered even though the
        // user is stuck exactly where the help text would explain why. A
        // Listening server that has never once received a sync is the
        // observable proxy for that case.
        val serverDown = syncState is LanSyncServer.State.Error || syncState is LanSyncServer.State.Stopped
        val neverSynced = syncState is LanSyncServer.State.Listening && schedulerState.lastSyncAt == null
        if (serverDown) {
            Spacer(Modifier.height(Spacing.Sm))
            SecondaryRowButton(
                icon = Lucide.RefreshCw,
                label = stringResource(Res.string.settings_sync_server_retry),
                onClick = onRetry,
            )
        }
        if (serverDown || neverSynced) {
            // Static help text (C4), no network-profile detection here on purpose, just points
            // the user at the two most common Windows-side causes of a silently failing sync.
            Spacer(Modifier.height(Spacing.Sm))
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(start = 14.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_sync_help_firewall_profile),
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
                Text(
                    text = stringResource(Res.string.settings_sync_help_firewall_prompts),
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun formatRelativeTime(timestampMs: Long, now: Long): String {
    val bucket = RelativeTime.bucket(now - timestampMs)
    return when (bucket.unit) {
        RelativeTime.Unit.SECONDS -> stringResource(Res.string.settings_relative_just_now)
        RelativeTime.Unit.MINUTES -> stringResource(Res.string.statusbar_relative_minutes_ago, bucket.value)
        RelativeTime.Unit.HOURS   -> stringResource(Res.string.statusbar_relative_hours_ago, bucket.value)
        RelativeTime.Unit.DAYS    -> stringResource(Res.string.statusbar_relative_days_ago, bucket.value)
    }
}
