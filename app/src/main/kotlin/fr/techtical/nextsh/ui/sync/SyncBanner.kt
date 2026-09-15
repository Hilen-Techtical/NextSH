// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.util.RelativeTime
import fr.techtical.nextsh.ui.components.rememberNowTicking
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.WarningAmber
import fr.techtical.nextsh.ui.theme.White

/**
 * A compact full-width status banner displayed above the host list when there is
 * sync activity or pending conflicts that require user attention.
 *
 * Priority order:
 * 1. pendingConflicts > 0 → Burgundy background, tap navigates to conflict resolution
 * 2. status == SYNCING    → neutral surface, animated rotation icon
 * 3. status == ERROR      → neutral surface, error icon + truncated message
 * 4. status == OFFLINE    → neutral surface, cloud-off icon
 * 5. status == IDLE + lastSyncAt != null → compact "Syncé il y a X" row (TextSecondary)
 * 6. otherwise            → nothing (returns early)
 */
@Composable
fun SyncBanner(
    syncState: SyncState,
    onResolveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pendingConflicts = syncState.pendingConflicts
    val status = syncState.status
    val lastSyncAt: Long? = syncState.lastSyncAt

    when {
        pendingConflicts > 0 -> {
            ConflictBanner(
                count = pendingConflicts,
                onClick = onResolveClick,
                modifier = modifier,
            )
        }

        status == SyncStatus.SYNCING -> {
            SyncingBanner(modifier = modifier)
        }

        status == SyncStatus.ERROR -> {
            ErrorBanner(
                message = syncState.lastError,
                modifier = modifier,
            )
        }

        status == SyncStatus.OFFLINE -> {
            OfflineBanner(modifier = modifier)
        }

        status == SyncStatus.IDLE && lastSyncAt != null -> {
            val now = rememberNowTicking(lastSyncAt)
            val bucket = RelativeTime.bucket(now - lastSyncAt)
            val relative = when (bucket.unit) {
                RelativeTime.Unit.SECONDS -> stringResource(R.string.relative_seconds_ago, bucket.value)
                RelativeTime.Unit.MINUTES -> stringResource(R.string.relative_minutes_ago, bucket.value)
                RelativeTime.Unit.HOURS   -> stringResource(R.string.relative_hours_ago, bucket.value)
                RelativeTime.Unit.DAYS    -> stringResource(R.string.relative_days_ago, bucket.value)
            }
            IdleBanner(
                relativeTime = relative,
                modifier = modifier,
            )
        }
        // IDLE + never synced → nothing
    }
}

// ── Internal banner variants ──────────────────────────────────────────────────

@Composable
private fun ConflictBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Burgundy,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.conflict_banner_pending, count),
                style = MaterialTheme.typography.labelMedium,
                color = White,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SyncingBanner(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sync_icon_rotation",
    )

    BannerRow(
        icon = {
            Icon(
                Icons.Rounded.Sync,
                contentDescription = null,
                tint = Gold,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation),
            )
        },
        text = stringResource(R.string.sync_banner_syncing),
        textColor = TextPrimary,
        modifier = modifier,
    )
}

@Composable
private fun ErrorBanner(
    message: String?,
    modifier: Modifier = Modifier,
) {
    val text = if (!message.isNullOrBlank()) {
        stringResource(R.string.sync_banner_error, message)
    } else {
        stringResource(R.string.sync_banner_error, "erreur inconnue")
    }

    BannerRow(
        icon = {
            Icon(
                Icons.Rounded.Error,
                contentDescription = null,
                tint = ErrorRed,
                modifier = Modifier.size(18.dp),
            )
        },
        text = text,
        textColor = ErrorRed,
        modifier = modifier,
    )
}

@Composable
private fun OfflineBanner(modifier: Modifier = Modifier) {
    BannerRow(
        icon = {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        },
        text = stringResource(R.string.sync_banner_offline),
        textColor = TextSecondary,
        modifier = modifier,
    )
}

@Composable
private fun IdleBanner(
    relativeTime: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = stringResource(R.string.sync_banner_synced_relative, relativeTime),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun BannerRow(
    icon: @Composable () -> Unit,
    text: String,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Surface,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon()
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
