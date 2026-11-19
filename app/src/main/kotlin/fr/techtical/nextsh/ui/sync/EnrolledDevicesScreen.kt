// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.RefreshCcw
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Users
import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.ui.components.NextShMessage
import fr.techtical.nextsh.ui.components.relativeTimeText
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
 * Gravité d un message de l écran : un échec de révocation et un appareil introuvable sont des
 * erreurs (rouges, 7 s : le second porte le conseil réseau et VPN), le reste est une information.
 */
internal fun enrolledMessageIsError(event: EnrolledDevicesViewModel.Event): Boolean = when (event) {
    is EnrolledDevicesViewModel.Event.Error,
    is EnrolledDevicesViewModel.Event.SearchNotFound -> true
    is EnrolledDevicesViewModel.Event.Revoked,
    is EnrolledDevicesViewModel.Event.SearchStarted,
    is EnrolledDevicesViewModel.Event.SearchFound -> false
}

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
    // Un nouveau message remplace l ancien : « Recherche de X… » cède la place à son résultat dès
    // qu il arrive, et le collecteur d événements n attend jamais la disparition d un message.
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var deviceToRevoke by remember { mutableStateOf<EnrolledDevice?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val text = when (event) {
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
            // « Introuvable » porte le conseil réseau et VPN : en erreur, donc affiché 7 s.
            messageIsError = enrolledMessageIsError(event)
            message = text
        }
    }

    val total = devices.size
    // The zero case is its own sentence: French plural "one" covers 0 and 1.
    val subtitle = if (total == 0) {
        stringResource(R.string.enrolled_empty)
    } else {
        pluralStringResource(R.plurals.enrolled_devices_count, total, total)
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
        // Le slot du Scaffold superpose le message en bas de la liste sans la redimensionner.
        snackbarHost = {
            NextShMessage(
                message   = message,
                isError   = messageIsError,
                onDismiss = { message = null },
                modifier  = Modifier.padding(Spacing.Md),
            )
        },
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
                label = stringResource(R.string.enrolled_meta_host),
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
                label = stringResource(R.string.enrolled_meta_enrolled),
                value = relativeTimeText(device.enrolledAt),
            )
            MetaLine(
                label = stringResource(R.string.enrolled_meta_last_sync),
                value = device.lastSyncAt?.let { relativeTimeText(it) }
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

/**
 * Minimum width of the label column, in dp. It is a floor, not a width that follows the font scale:
 * a width in sp converted to dp does not follow the text either, because Android 14+ scales sp
 * non-linearly, so at 130 % the label outgrew it and "DERNIÈRE SYNC" was cut. The longest label
 * (13 characters of mono 9sp plus spacing, about 77dp at the normal size) fits under this floor, so
 * the values line up; with a larger font a long label widens its own line and pushes its value, it is
 * never cut (see [MetaLine]).
 */
private val MetaLabelMinWidth = 80.dp

@Composable
private fun MetaLine(label: String, value: String, valueColor: Color = TextSecondary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            // Capitals at display time (Locale.ROOT): the resources stay in sentence case.
            text = label.uppercase(),
            color = TextDisabled,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false,
            // The gap is outside the floor: padding first, then the minimum width of the label itself.
            modifier = Modifier.padding(end = Spacing.Sm).widthIn(min = MetaLabelMinWidth),
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
