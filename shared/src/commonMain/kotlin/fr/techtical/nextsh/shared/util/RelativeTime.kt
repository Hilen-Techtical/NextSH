// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

/**
 * Calcul de temps relatif localisable, sans dépendance Compose ni Android SDK.
 *
 * Utilisé côté Android et Desktop pour produire un [Bucket] (valeur + unité)
 * depuis un âge en millisecondes, et pour connaître le délai avant lequel
 * il faut rafraîchir l'affichage (tick adaptatif : 10 s / 1 min / 1 h / 1 j).
 */
object RelativeTime {

    /** Unité de la valeur exposée dans un [Bucket]. */
    enum class Unit { SECONDS, MINUTES, HOURS, DAYS }

    /**
     * Valeur entière tronquée (>=0) et son unité.
     *
     * Exemples : 3700 s → `Bucket(1, HOURS)` ; 45 s → `Bucket(45, SECONDS)`.
     */
    data class Bucket(val value: Int, val unit: Unit)

    /**
     * Convertit [ageMs] (durée écoulée en ms) en un [Bucket].
     *
     * Les tranches sont calées sur celles du `formatRelative` de StatusBar.kt :
     * - < 60 s  → SECONDS
     * - < 1 h   → MINUTES
     * - < 1 j   → HOURS
     * - sinon   → DAYS
     *
     * Un âge négatif est traité comme 0 (agraf au présent).
     */
    fun bucket(ageMs: Long): Bucket {
        val safe = ageMs.coerceAtLeast(0L)
        val sec = safe / 1_000L
        return when {
            sec < 60L      -> Bucket(sec.toInt(), Unit.SECONDS)
            sec < 3_600L   -> Bucket((sec / 60L).toInt(), Unit.MINUTES)
            sec < 86_400L  -> Bucket((sec / 3_600L).toInt(), Unit.HOURS)
            else           -> Bucket((sec / 86_400L).toInt(), Unit.DAYS)
        }
    }

    /**
     * Délai en ms avant lequel l'affichage doit être rafraîchi pour
     * un âge de [ageMs] ms.
     *
     * - < 1 min  → toutes les 10 s
     * - < 1 h    → toutes les 1 min
     * - < 1 j    → toutes les 1 h
     * - sinon    → toutes les 1 j
     *
     * Un âge négatif est ramené à 0 avant le calcul.
     */
    fun nextRefreshDelayMs(ageMs: Long): Long {
        val safe = ageMs.coerceAtLeast(0L)
        return when {
            safe < 60_000L         -> 10_000L
            safe < 3_600_000L      -> 60_000L
            safe < 86_400_000L     -> 3_600_000L
            else                   -> 86_400_000L
        }
    }
}
