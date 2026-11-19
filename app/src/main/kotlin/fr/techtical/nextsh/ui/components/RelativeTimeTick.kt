// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.techtical.nextsh.shared.util.RelativeTime
import kotlinx.coroutines.delay

/** Le plus court délai d'un rafraîchissement calé sur la limite des 7 jours : évite une boucle serrée. */
internal const val MIN_TICK_DELAY_MS = 1_000L

/**
 * Retourne `System.currentTimeMillis()` en se rafraîchissant automatiquement, à la cadence de
 * [nextTickDelayMs] (calculée via [RelativeTime.nextRefreshDelayMs], bornée par la limite de
 * [RELATIVE_TIME_LIMIT_MS]) :
 * - toutes les 10 s tant que l'écart avec [anchorMs] est < 1 min
 * - toutes les 1 min tant que < 1 h
 * - toutes les 1 h  tant que < 1 j
 * - toutes les 1 j  au-delà, sans dépasser le moment où l'écart atteint 7 jours : c'est là que
 *   "il y a 6 j" devient une date
 *
 * L'horloge est relue à chaque retour de l'écran au premier plan (état STARTED), et le rythme n'est
 * tenu que pendant ce temps : `delay` ne compte pas le sommeil profond de l'appareil, un texte
 * resté en arrière-plan pendant des heures serait sinon périmé à son retour.
 *
 * Retourne `System.currentTimeMillis()` figé à la composition quand [anchorMs] est null (aucun
 * tick lancé).
 */
@Composable
fun rememberNowTicking(anchorMs: Long?): Long {
    var now by remember(anchorMs) { mutableStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(anchorMs, lifecycleOwner) {
        if (anchorMs == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = System.currentTimeMillis()
                now = current
                delay(nextTickDelayMs(anchorMs, current))
            }
        }
    }
    return now
}

/**
 * Le délai avant de relire l'horloge pour un instant [timestampMillis], vu à [nowMillis].
 *
 * C'est [RelativeTime.nextRefreshDelayMs] (10 s, 1 min, 1 h, puis 1 j), avec une borne : quand l'âge
 * est dans `[0, RELATIVE_TIME_LIMIT_MS[`, le délai ne dépasse pas le temps restant avant les 7 jours,
 * sinon "il y a 6 j" resterait affiché jusqu'à 24 h après le passage à la date. Au moins
 * [MIN_TICK_DELAY_MS], pour ne pas tourner en boucle serrée juste avant la limite.
 *
 * Un âge négatif (instant dans le futur) garde le rythme d'un âge nul, 10 s, comme avant la limite.
 * Un âge de 7 jours ou plus garde le rythme quotidien : la date affichée ne change plus.
 */
internal fun nextTickDelayMs(timestampMillis: Long, nowMillis: Long): Long {
    val ageMs = nowMillis - timestampMillis
    val regularMs = RelativeTime.nextRefreshDelayMs(ageMs)
    if (ageMs < 0L || ageMs >= RELATIVE_TIME_LIMIT_MS) return regularMs
    val untilLimitMs = (RELATIVE_TIME_LIMIT_MS - ageMs).coerceAtLeast(MIN_TICK_DELAY_MS)
    return minOf(regularMs, untilLimitMs)
}
