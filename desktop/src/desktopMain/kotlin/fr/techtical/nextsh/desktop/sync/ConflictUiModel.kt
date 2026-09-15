// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import androidx.compose.runtime.Composable
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.relative_time_days
import fr.techtical.nextsh.desktop.generated.resources.relative_time_hours
import fr.techtical.nextsh.desktop.generated.resources.relative_time_minutes
import fr.techtical.nextsh.desktop.generated.resources.relative_time_seconds
import org.jetbrains.compose.resources.stringResource

/**
 * Formats a duration as a relative human-readable string (localised via compose-resources).
 * Use this overload inside @Composable functions.
 *
 * The other conflict UI helpers (DecodedEntity, decodeEntity, diffFields,
 * SyncableEntityType.displayName) live in :shared under
 * fr.techtical.nextsh.shared.ui.conflict.
 */
@Composable
internal fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diffMs = now - timestamp
    return when {
        diffMs < 60_000L -> stringResource(Res.string.relative_time_seconds)
        diffMs < 3_600_000L -> stringResource(Res.string.relative_time_minutes, (diffMs / 60_000L).toInt())
        diffMs < 86_400_000L -> stringResource(Res.string.relative_time_hours, (diffMs / 3_600_000L).toInt())
        else -> stringResource(Res.string.relative_time_days, (diffMs / 86_400_000L).toInt())
    }
}
