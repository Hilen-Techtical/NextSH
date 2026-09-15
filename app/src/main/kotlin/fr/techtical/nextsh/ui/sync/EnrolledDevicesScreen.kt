// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.RefreshCcw
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Users
import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.GoldMuted
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White

/**
 * EnrolledDevicesScreen : refonte Phase 3.2 (DA Techtical).
 *
 * TopAppBar Space Grotesk + subtitle compteur. DeviceCard refondue
 * Surface/Border1/Radii.Lg : icon-wrap Gold avec Monitor (DESKTOP) ou
 * Smartphone (ANDROID), label Space Grotesk SemiBold + PlatformBadge,
 * 5 MetaLines mono pour les fingerprints (ECDH + TLS), enrôlement et
 * dernière sync. Bouton "Révoquer" outlined ErrorRed à droite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrolledDevicesScreen(
    onBack: () -> Unit,
    viewModel: EnrolledDevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsState()
    val searchingDeviceId by viewModel.searchingDeviceId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var deviceToRevoke by remember { mutableStateOf<EnrolledDevice?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val effectScope = this
        viewModel.events.collect { event ->
            val message = when (event) {
                is EnrolledDevicesViewModel.Event.Revoked ->
                    context.getString(R.string.enrolled_status_revoked, event.deviceName)
                is EnrolledDevicesViewModel.Event.Error ->
                    context.getString(R.string.enrolled_status_error)
                is EnrolledDevicesViewModel.Event.SearchStarted ->
                    context.getString(R.string.enrolled_search_started, event.deviceName)
                is EnrolledDevicesViewModel.Event.SearchFound ->
                    context.getString(R.string.enrolled_search_found, event.deviceName)
                is EnrolledDevicesViewModel.Event.SearchNotFound ->
                    context.getString(R.string.enrolled_search_not_found, event.deviceName)
            }
            val duration = when (event) {
                // The "not found" message carries the actionable hint (same network? VPN?):
                // it needs long enough to be read.
                is EnrolledDevicesViewModel.Event.SearchNotFound -> SnackbarDuration.Long
                else -> SnackbarDuration.Short
            }
            // Retire the in-flight snackbar before showing the next one: "Recherche de X…" must
            // give way to its own result the moment it arrives, not linger for its full duration.
            snackbarHostState.currentSnackbarData?.dismiss()
            // Shown from a child coroutine on purpose: showSnackbar() suspends until the snackbar
            // is dismissed, so awaiting it here would block the collector and delay the result
            // event by the full display duration of the "searching" message.
            effectScope.launch { snackbarHostState.showSnackbar(message, duration = duration) }
        }
    }

    val total = devices.size
    val subtitle = when {
        total == 0 -> "Aucun appareil enrôlé"
        total == 1 -> "1 appareil enrôlé"
        else -> "$total appareils enrôlés"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.title_enrolled_devices),
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = subtitle,
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = NearBlack,
    ) { padding ->
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Md),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                items(devices, key = { it.deviceId }) { device ->
                    DeviceCard(
                        device = device,
                        isSearching = searchingDeviceId == device.deviceId,
                        onRevokeClick = { deviceToRevoke = device },
                        onSearchClick = { viewModel.searchOnNetwork(device) },
                    )
                }
            }
        }
    }

    deviceToRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToRevoke = null },
            title = {
                Text(
                    text = stringResource(R.string.dialog_revoke_title, device.deviceName),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_revoke_message),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        viewModel.revoke(device)
                        deviceToRevoke = null
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = ErrorRed,
                        contentColor = White,
                    ),
                    border = BorderStroke(0.dp, Color.Transparent),
                    shape = RoundedCornerShape(Radii.Md),
                ) {
                    Text(
                        text = stringResource(R.string.action_revoke),
                        color = White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRevoke = null }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = Surface,
            shape = RoundedCornerShape(Radii.Lg),
        )
    }
}

// ── DeviceCard ──────────────────────────────────────────────────────────────

@Composable
private fun DeviceCard(
    device: EnrolledDevice,
    isSearching: Boolean,
    onRevokeClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        // ── Header : icon-wrap + name + platform badge ─────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = device.platform.icon(),
                    contentDescription = null,
                    tint = Gold,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(Spacing.Md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                PlatformBadge(device.platform)
            }
        }

        // ── Metadata : ECDH fingerprint, TLS, host, enrolledAt, lastSync ────
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val hostColor = if (device.lastKnownHost == null) ErrorRed else TextPrimary
            MetaLine(
                label = "HOST",
                value = device.lastKnownHost ?: "-",
                valueColor = hostColor,
            )
            MetaLine(
                label = "ECDH",
                value = device.publicKeyFingerprint.take(24) + if (device.publicKeyFingerprint.length > 24) "…" else "",
            )
            MetaLine(
                label = "TLS",
                value = device.tlsCertFingerprint?.take(16) ?: "-",
            )
            MetaLine(
                label = "ENROLLED",
                value = relativeTime(device.enrolledAt),
            )
            MetaLine(
                label = "LAST SYNC",
                value = device.lastSyncAt?.let { relativeTime(it) }
                    ?: stringResource(R.string.enrolled_last_sync_never),
            )
        }

        // ── Actions : Rechercher (DESKTOP only, left) + Révoquer (right) ────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (device.platform == Platform.DESKTOP) {
                OutlinedButton(
                    onClick = onSearchClick,
                    enabled = !isSearching,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold),
                    border = BorderStroke(1.dp, Gold.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(Radii.Md),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = Gold,
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(
                            Lucide.RefreshCcw,
                            contentDescription = null,
                            tint = Gold,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = stringResource(R.string.action_search_network),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                Spacer(Modifier)
            }
            OutlinedButton(
                onClick = onRevokeClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(Radii.Md),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_revoke),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun PlatformBadge(platform: Platform) {
    Box(
        modifier = Modifier
            .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Sm))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(Radii.Sm))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = platform.name,
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun MetaLine(label: String, value: String, valueColor: Color = TextSecondary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = TextDisabled,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            color = valueColor,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Empty state ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = Spacing.Xl),
    ) {
        Icon(
            Lucide.Users,
            contentDescription = null,
            tint = GoldMuted,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(R.string.enrolled_empty),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(R.string.enrolled_empty_hint),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun Platform.icon(): ImageVector = when (this) {
    Platform.ANDROID -> Lucide.Smartphone
    Platform.DESKTOP -> Lucide.Monitor
}

private fun relativeTime(timestampMs: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestampMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
