// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.RefreshCcw
import com.composables.icons.lucide.ScanLine
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import fr.techtical.nextsh.BuildConfig
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.RepositoryUrls
import fr.techtical.nextsh.shared.core.sync.SyncInterval
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.util.RelativeTime
import fr.techtical.nextsh.ui.components.BrandText
import fr.techtical.nextsh.ui.components.VersionText
import fr.techtical.nextsh.ui.components.rememberNowTicking
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Border2
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.GoldMuted
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.SuccessGreen
import fr.techtical.nextsh.ui.theme.SurfaceVariant
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.WarningAmber
import fr.techtical.nextsh.ui.theme.White
import kotlin.math.roundToInt
import fr.techtical.nextsh.ui.theme.Surface as SurfaceColor

/**
 * SettingsScreen : refonte Phase 3.1 (DA Techtical portée du Desktop).
 *
 * Sections cards Surface/Border1/Radii.Lg avec headers GoldLight
 * UPPERCASE 11sp mono. Tous les SliderRow / ToggleRow / NavigationRow
 * sont rendus à l'intérieur des cards avec un padding cohérent. La sync
 * LAN expose son statut (dot + label) en intra-card et un alert de
 * conflit (parité ConflictAlert Desktop) avec icône TriangleAlert.
 *
 * Conventions Android conservées : Scaffold + TopAppBar + AlertDialog
 * (un seul pas de confirmation pour le wipe, le ViewModel ne réclame
 * pas de token comme côté Desktop ; on garde la simplicité existante).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToKnownHosts: () -> Unit = {},
    onNavigateToEnrollmentScan: () -> Unit = {},
    onNavigateToConflictResolution: () -> Unit = {},
    onNavigateToEnrolledDevices: () -> Unit = {},
    onNavigateToTerminalThemes: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    var showWipeDialog by remember { mutableStateOf(false) }
    var showAboutLinkDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.title_settings),
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = "Connexion, terminal, sécurité, sync LAN",
                            color = TextDisabled,
                            fontSize = 12.sp,
                        )
                    }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
            )
        },
        containerColor = NearBlack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
        ) {
            // ── Terminal ─────────────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_terminal)) {
                SliderRow(
                    label = stringResource(R.string.label_terminal_font_size),
                    value = state.terminalFontSize.toFloat(),
                    valueRange = 12f..46f,
                    steps = 33,
                    displayValue = stringResource(R.string.settings_font_size_pt, state.terminalFontSize),
                    onValueChange = { viewModel.updateFontSize(it.roundToInt()) },
                )
                SliderRow(
                    label = stringResource(R.string.label_scrollback),
                    value = state.scrollbackLines.toFloat(),
                    valueRange = 1000f..100000f,
                    steps = 0,
                    displayValue = formatScrollback(
                        state.scrollbackLines,
                        stringResource(R.string.settings_scrollback_lines, state.scrollbackLines),
                        stringResource(R.string.settings_scrollback_klines, state.scrollbackLines / 1000),
                    ),
                    onValueChange = { viewModel.updateScrollback(it.roundToInt()) },
                )
                NavigationRowItem(
                    label = stringResource(R.string.settings_terminal_themes),
                    icon = Lucide.Palette,
                    onClick = onNavigateToTerminalThemes,
                )
            }

            // ── Synchronisation LAN ─────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_sync_lan)) {
                ToggleRow(
                    label = stringResource(R.string.label_sync_enabled),
                    checked = state.syncEnabled,
                    onToggle = { viewModel.toggleSyncEnabled() },
                )

                if (state.syncEnabled) {
                    val syncIntervals = SyncInterval.entries
                    val intervalIndex = syncIntervals.indexOf(state.syncInterval).coerceAtLeast(0).toFloat()
                    SliderRow(
                        label = stringResource(R.string.label_sync_interval),
                        value = intervalIndex,
                        valueRange = 0f..(syncIntervals.size - 1).toFloat(),
                        steps = syncIntervals.size - 2,
                        displayValue = state.syncInterval.displayLabel,
                        onValueChange = { index ->
                            val snapped = syncIntervals[index.roundToInt().coerceIn(0, syncIntervals.size - 1)]
                            viewModel.updateSyncInterval(snapped)
                        },
                    )

                    SyncStatusBlock(
                        status = syncState.status,
                        lastError = syncState.lastError,
                        lastSyncAt = syncState.lastSyncAt,
                    )

                    if (syncState.pendingConflicts > 0) {
                        ConflictAlert(
                            count = syncState.pendingConflicts,
                            onClick = onNavigateToConflictResolution,
                        )
                    }

                    BtnSecondaryFull(
                        onClick = { viewModel.forceSync() },
                        enabled = syncState.status != SyncStatus.SYNCING,
                        icon = Lucide.RefreshCcw,
                        label = stringResource(R.string.action_sync_now),
                    )
                }

                NavigationRowItem(
                    label = stringResource(R.string.title_enrolled_devices),
                    icon = Lucide.Smartphone,
                    onClick = onNavigateToEnrolledDevices,
                )
                NavigationRowItem(
                    label = stringResource(R.string.action_scan_qr),
                    icon = Lucide.ScanLine,
                    onClick = onNavigateToEnrollmentScan,
                )
            }

            // ── Connexion ────────────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_connection)) {
                SliderRow(
                    label = stringResource(R.string.label_keepalive_interval),
                    value = state.keepAliveInterval.toFloat(),
                    valueRange = 0f..120f,
                    steps = 23,
                    displayValue = if (state.keepAliveInterval == 0) {
                        stringResource(R.string.status_disabled)
                    } else {
                        stringResource(R.string.settings_keepalive_seconds, state.keepAliveInterval)
                    },
                    onValueChange = { viewModel.updateKeepAlive(it.roundToInt()) },
                )
                ToggleRow(
                    label = stringResource(R.string.label_auto_reconnect),
                    checked = state.autoReconnect,
                    onToggle = { viewModel.toggleAutoReconnect() },
                )
                SliderRow(
                    label = stringResource(R.string.label_connection_timeout),
                    value = state.connectionTimeout.toFloat(),
                    valueRange = 5f..30f,
                    steps = 4,
                    displayValue = stringResource(R.string.settings_timeout_seconds, state.connectionTimeout),
                    onValueChange = { viewModel.updateConnectionTimeout(it.roundToInt()) },
                    hint = stringResource(R.string.settings_connection_timeout_help),
                )
            }

            // ── Sécurité ─────────────────────────────────────────────────────
            FormSection(title = stringResource(R.string.section_security)) {
                NavigationRowItem(
                    label = stringResource(R.string.label_known_hosts),
                    icon = Lucide.ShieldCheck,
                    onClick = onNavigateToKnownHosts,
                )
                val clipboardSteps = listOf(0, 15, 30, 60, 90, 120)
                val clipboardIndex = clipboardSteps
                    .indexOf(state.clipboardClearTimeout)
                    .coerceAtLeast(0)
                    .toFloat()
                SliderRow(
                    label = stringResource(R.string.label_clipboard_expiry),
                    value = clipboardIndex,
                    valueRange = 0f..(clipboardSteps.size - 1).toFloat(),
                    steps = clipboardSteps.size - 2,
                    displayValue = if (state.clipboardClearTimeout == 0) {
                        stringResource(R.string.status_disabled)
                    } else {
                        stringResource(R.string.settings_timeout_seconds, state.clipboardClearTimeout)
                    },
                    onValueChange = { index ->
                        val snapped = clipboardSteps[index.roundToInt().coerceIn(0, clipboardSteps.size - 1)]
                        viewModel.updateClipboardClearTimeout(snapped)
                    },
                )
            }

            // ── Zone dangereuse ──────────────────────────────────────────────
            FormSection(title = "Zone dangereuse", accent = ErrorRed) {
                Text(
                    text = "Action irréversible. Toutes les données locales seront perdues.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                DangerRowButton(
                    icon = Lucide.Trash2,
                    label = stringResource(R.string.action_wipe_vault),
                    onClick = { showWipeDialog = true },
                )
            }

            // ── À propos ─────────────────────────────────────────────────────
            FormSection(
                title = stringResource(R.string.section_about),
                modifier = Modifier.clickable { showAboutLinkDialog = true },
                titleTrailing = {
                    Icon(
                        imageVector = Lucide.ExternalLink,
                        contentDescription = stringResource(R.string.action_open_link),
                        tint = Gold,
                        modifier = Modifier.size(14.dp),
                    )
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.label_version),
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                    VersionText(version = BuildConfig.VERSION_NAME, fontSize = 12.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.label_application),
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandText(fontSize = 13.sp)
                        Text(
                            text = " by Techtical",
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.Xl))
        }
    }

    // ── Dialogue confirmation effacement vault ─────────────────────────
    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(ErrorRed.copy(alpha = 0.12f), RoundedCornerShape(Radii.Md)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Trash2, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(13.dp))
                    }
                    Spacer(Modifier.width(Spacing.Md))
                    Text(
                        text = stringResource(R.string.dialog_wipe_vault_title),
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_wipe_vault_message),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                BtnDangerSolid(
                    onClick = {
                        viewModel.wipeVault()
                        showWipeDialog = false
                    },
                    label = stringResource(R.string.action_wipe),
                )
            },
            dismissButton = {
                TextButton(onClick = { showWipeDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = SurfaceColor,
            shape = RoundedCornerShape(Radii.Lg),
        )
    }

    // ── Dialogue confirmation ouverture lien externe (dépôt GitHub) ─────
    if (showAboutLinkDialog) {
        AlertDialog(
            onDismissRequest = { showAboutLinkDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Gold.copy(alpha = 0.12f), RoundedCornerShape(Radii.Md)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Lucide.ExternalLink,
                            contentDescription = null,
                            tint = Gold,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Spacer(Modifier.width(Spacing.Md))
                    Text(
                        text = stringResource(R.string.dialog_open_link_title),
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    Text(
                        text = stringResource(R.string.dialog_open_repo_body),
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = RepositoryUrls.GITHUB,
                        color = Gold,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(RepositoryUrls.GITHUB))
                            )
                        } catch (_: ActivityNotFoundException) {
                            // Aucun navigateur installé : silencieux ; l'utilisateur
                            // peut copier l'URL depuis le dialog avant fermeture.
                        }
                        showAboutLinkDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_open_link), color = Burgundy)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAboutLinkDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = SurfaceColor,
            shape = RoundedCornerShape(Radii.Lg),
        )
    }
}

// ── Section primitive ───────────────────────────────────────────────────────

@Composable
private fun FormSection(
    title: String,
    accent: Color = GoldLight,
    modifier: Modifier = Modifier,
    titleTrailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        if (titleTrailing == null) {
            Text(
                text = title.uppercase(),
                color = accent,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title.uppercase(),
                    color = accent,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.weight(1f))
                titleTrailing()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceColor, RoundedCornerShape(Radii.Lg))
                .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
                .padding(horizontal = Spacing.Md, vertical = Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            content = content,
        )
    }
}

// ── Slider / Toggle / Navigation rows ───────────────────────────────────────

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    /** Optional plain-language supporting text rendered below the slider (non-technical explanation). */
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
                text = displayValue,
                color = GoldLight,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
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
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Spacing.Sm))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
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

@Composable
private fun NavigationRowItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Spacing.Sm))
                Text(label, color = TextPrimary, fontSize = 13.sp)
            }
            Icon(
                Lucide.ChevronRight,
                contentDescription = null,
                tint = TextDisabled,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(
            text = value,
            color = TextPrimary,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 12.sp,
        )
    }
}

// ── Sync status block ───────────────────────────────────────────────────────

@Composable
private fun SyncStatusBlock(
    status: SyncStatus,
    lastError: String?,
    lastSyncAt: Long?,
) {
    val statusText: String
    val statusColor: Color
    val statusDot: Color
    when (status) {
        SyncStatus.IDLE -> {
            if (lastSyncAt != null) {
                statusText = stringResource(R.string.sync_status_uptodate)
                statusColor = SuccessGreen; statusDot = SuccessGreen
            } else {
                statusText = stringResource(R.string.sync_status_pending)
                statusColor = TextDisabled; statusDot = TextDisabled
            }
        }
        SyncStatus.SYNCING -> {
            statusText = stringResource(R.string.sync_status_syncing)
            statusColor = GoldLight; statusDot = GoldLight
        }
        SyncStatus.ERROR -> {
            statusText = stringResource(R.string.sync_status_error, lastError ?: "")
            statusColor = ErrorRed; statusDot = ErrorRed
        }
        SyncStatus.OFFLINE -> {
            statusText = stringResource(R.string.sync_status_offline)
            statusColor = TextDisabled; statusDot = TextDisabled
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(statusDot, RoundedCornerShape(9999.dp)),
            )
            Spacer(Modifier.width(Spacing.Sm))
            Text(text = statusText, color = statusColor, fontSize = 11.sp)
        }
        val now = rememberNowTicking(lastSyncAt)
        val lastSyncText = lastSyncAt?.let { ts ->
            stringResource(R.string.sync_last_sync_at, formatRelativeBucket(RelativeTime.bucket(now - ts)))
        } ?: stringResource(R.string.sync_never_synced)
        Text(
            text = lastSyncText,
            color = TextDisabled,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/** Convertit un [RelativeTime.Bucket] en chaîne localisée courte. */
@Composable
private fun formatRelativeBucket(bucket: RelativeTime.Bucket): String = when (bucket.unit) {
    RelativeTime.Unit.SECONDS -> stringResource(R.string.relative_seconds_ago, bucket.value)
    RelativeTime.Unit.MINUTES -> stringResource(R.string.relative_minutes_ago, bucket.value)
    RelativeTime.Unit.HOURS   -> stringResource(R.string.relative_hours_ago, bucket.value)
    RelativeTime.Unit.DAYS    -> stringResource(R.string.relative_days_ago, bucket.value)
}

@Composable
private fun ConflictAlert(count: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = WarningAmber.copy(alpha = 0.10f),
            contentColor = WarningAmber,
        ),
        border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.50f)),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Lucide.TriangleAlert, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(
            text = if (count == 1) "1 conflit à résoudre" else "$count conflits à résoudre",
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start,
        )
        Text("→", color = WarningAmber, fontSize = 14.sp)
    }
}

// ── Boutons ─────────────────────────────────────────────────────────────────

@Composable
private fun BtnSecondaryFull(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary,
            disabledContentColor = TextDisabled,
        ),
        border = BorderStroke(1.dp, if (enabled) Border2 else Border2.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = Spacing.Md, vertical = Spacing.Sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DangerRowButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = ErrorRed,
        ),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = Spacing.Md, vertical = Spacing.Sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BtnDangerSolid(onClick: () -> Unit, label: String) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = ErrorRed,
            contentColor = White,
        ),
        border = BorderStroke(0.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatScrollback(lines: Int, linesStr: String, kLinesStr: String): String =
    if (lines >= 1000) kLinesStr else linesStr
