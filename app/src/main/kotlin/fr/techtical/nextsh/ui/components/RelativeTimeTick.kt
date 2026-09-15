// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.techtical.nextsh.shared.util.RelativeTime
import kotlinx.coroutines.delay

/**
 * Retourne `System.currentTimeMillis()` en se rafraîchissant automatiquement
 * à une cadence adaptative calculée via [RelativeTime.nextRefreshDelayMs] :
 * - toutes les 10 s tant que l'écart avec [anchorMs] est < 1 min
 * - toutes les 1 min tant que < 1 h
 * - toutes les 1 h  tant que < 1 j
 * - toutes les 1 j  au-delà
 *
 * Retourne [System.currentTimeMillis()] figé à la composition quand
 * [anchorMs] est null (aucune tick lancé).
 */
@Composable
fun rememberNowTicking(anchorMs: Long?): Long {
    var now by remember(anchorMs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(anchorMs) {
        if (anchorMs == null) return@LaunchedEffect
        while (true) {
            delay(RelativeTime.nextRefreshDelayMs(now - anchorMs))
            now = System.currentTimeMillis()
        }
    }
    return now
}
