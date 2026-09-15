// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.techtical.nextsh.desktop.DesktopBuildInfo
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.StatusBarBg
import fr.techtical.nextsh.desktop.theme.SuccessGreen
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.WarningAmber
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.statusbar_label_sessions
import fr.techtical.nextsh.desktop.generated.resources.statusbar_label_tunnels
import fr.techtical.nextsh.desktop.generated.resources.statusbar_label_vault
import fr.techtical.nextsh.desktop.generated.resources.statusbar_label_version
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_days_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_hours_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_minutes_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_never
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_seconds_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_sync_error_prefix
import fr.techtical.nextsh.desktop.generated.resources.statusbar_sync_lan_prefix
import fr.techtical.nextsh.desktop.generated.resources.statusbar_sync_offline
import fr.techtical.nextsh.desktop.generated.resources.statusbar_sync_ok_prefix
import fr.techtical.nextsh.desktop.generated.resources.statusbar_sync_pending
import fr.techtical.nextsh.desktop.generated.resources.statusbar_devices_many
import fr.techtical.nextsh.desktop.generated.resources.statusbar_devices_one
import fr.techtical.nextsh.desktop.generated.resources.statusbar_sync_syncing
import fr.techtical.nextsh.desktop.generated.resources.status_locked
import fr.techtical.nextsh.desktop.generated.resources.status_unlocked
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.util.RelativeTime
import org.jetbrains.compose.resources.stringResource

/**
 * Status bar bas-de-fenêtre alignée avec la maquette Claude Design.
 * Synthèse temps réel : sessions actives, tunnels actifs, statut vault,
 * statut sync LAN. Tous les compteurs sont déjà collectés au niveau
 * supérieur (App.kt) : le composable est purement présentationnel.
 *
 * Hauteur fixe 24 dp avec séparateur top.
 */
@Composable
fun StatusBar(
    sessionsActive: Int,
    tunnelsActive: Int,
    tunnelsTotal: Int,
    vaultUnlocked: Boolean,
    syncEnabled: Boolean,
    enrolledDevicesCount: Int,
    syncStatus: SyncStatus = SyncStatus.IDLE,
    lastSyncAt: Long? = null,
    onVersionClick: () -> Unit = {},
    appVersion: String = DesktopBuildInfo.VERSION,
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    // Column (pas Box) pour empiler le séparateur AU-DESSUS du fond
    // StatusBar ; sinon le bg de la Row recouvre le 1 dp de Border1
    // qui devient invisible.
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Border1),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(StatusBarBg)
                .padding(horizontal = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Bloc gauche : Sessions │ Tunnels ────────────────────────────
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusItem(
                    label = stringResource(Res.string.statusbar_label_sessions),
                    value = sessionsActive.toString(),
                    valueColor = if (sessionsActive > 0) Gold else TextSecondary,
                )
                VerticalSep()
                StatusItem(
                    label = stringResource(Res.string.statusbar_label_tunnels),
                    value = "$tunnelsActive / $tunnelsTotal",
                    valueColor = if (tunnelsActive > 0) Gold else TextSecondary,
                )
            }

            // ── Bloc central : version cliquable (→ dépôt GitHub) ───────────
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onVersionClick() }
                    .padding(horizontal = Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(Res.string.statusbar_label_version), color = TextDisabled, fontSize = 11.sp)
                Spacer(Modifier.width(Spacing.Xs))
                VersionText(version = appVersion, fontSize = 11.sp)
            }

            // ── Bloc droit : [Sync ·] Vault ─────────────────────────────────
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (syncEnabled) {
                    SyncStatusBlock(
                        status = syncStatus,
                        lastSyncAt = lastSyncAt,
                        devicesCount = enrolledDevicesCount,
                        nowMs = nowMs,
                    )
                    VerticalSep()
                }
                StatusItem(
                    label = stringResource(Res.string.statusbar_label_vault),
                    value = stringResource(if (vaultUnlocked) Res.string.status_unlocked else Res.string.status_locked),
                    valueColor = if (vaultUnlocked) TextPrimary else WarningAmber,
                )
            }
        }
    }
}

@Composable
private fun SyncStatusBlock(
    status: SyncStatus,
    lastSyncAt: Long?,
    devicesCount: Int,
    nowMs: () -> Long,
) {
    val now = rememberNowTicking(lastSyncAt)
    val relative = formatRelative(lastSyncAt, if (lastSyncAt != null) now else nowMs())
    val lanPrefix = stringResource(Res.string.statusbar_sync_lan_prefix)
    val (dotColor, label) = when (status) {
        SyncStatus.SYNCING -> Gold to stringResource(Res.string.statusbar_sync_syncing)
        SyncStatus.ERROR -> ErrorRed to "${stringResource(Res.string.statusbar_sync_error_prefix)} • $relative"
        SyncStatus.OFFLINE -> WarningAmber to stringResource(Res.string.statusbar_sync_offline)
        // IDLE sans aucune sync passée = « en attente » : pastille neutre
        // (grise), pas verte : cohérent avec le statut Android.
        SyncStatus.IDLE ->
            if (lastSyncAt != null)
                SuccessGreen to "${stringResource(Res.string.statusbar_sync_ok_prefix)} • $relative"
            else
                TextDisabled to stringResource(Res.string.statusbar_sync_pending)
    }
    StatusDot(dotColor)
    Spacer(Modifier.width(Spacing.Xs))
    val devicesSuffix = when {
        devicesCount == 1 -> " • ${stringResource(Res.string.statusbar_devices_one, devicesCount)}"
        devicesCount > 1 -> " • ${stringResource(Res.string.statusbar_devices_many, devicesCount)}"
        else -> ""
    }
    Text(
        text = "$lanPrefix $label$devicesSuffix",
        color = if (status == SyncStatus.ERROR) ErrorRed else TextSecondary,
        fontSize = 11.sp,
    )
}

/**
 * Formate un timestamp (ms epoch) en delta relatif court. Retourne la
 * chaîne `never` quand [ts] est null. Les chaînes de format sont des
 * ressources localisées : FR "il y a 12 s", EN "12 s ago".
 * Délègue à [RelativeTime.bucket] pour la logique de tranches.
 */
@Composable
private fun formatRelative(ts: Long?, now: Long): String {
    if (ts == null) return stringResource(Res.string.statusbar_relative_never)
    val bucket = RelativeTime.bucket(now - ts)
    return when (bucket.unit) {
        RelativeTime.Unit.SECONDS -> stringResource(Res.string.statusbar_relative_seconds_ago, bucket.value)
        RelativeTime.Unit.MINUTES -> stringResource(Res.string.statusbar_relative_minutes_ago, bucket.value)
        RelativeTime.Unit.HOURS   -> stringResource(Res.string.statusbar_relative_hours_ago, bucket.value)
        RelativeTime.Unit.DAYS    -> stringResource(Res.string.statusbar_relative_days_ago, bucket.value)
    }
}

@Composable
private fun StatusItem(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = TextDisabled, fontSize = 11.sp)
        Spacer(Modifier.width(Spacing.Xs))
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun VerticalSep() {
    Spacer(Modifier.width(Spacing.Md))
    Box(modifier = Modifier.size(width = 1.dp, height = 12.dp).background(Border1))
    Spacer(Modifier.width(Spacing.Md))
}

@Composable
private fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
}
