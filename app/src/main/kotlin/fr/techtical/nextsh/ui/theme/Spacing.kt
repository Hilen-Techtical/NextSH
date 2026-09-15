// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Échelle de spacing alignée avec la maquette Claude Design (--space-1..9)
 * et avec le module `:desktop` refondu Phase 2. Centralise toutes les
 * valeurs d'espacement utilisées par les composables du chrome refondu
 * pour éviter les magic numbers et garder un rythme cohérent entre
 * Desktop et Android.
 */
object Spacing {
    val Xs:   Dp = 4.dp   // --space-1
    val Sm:   Dp = 8.dp   // --space-2
    val Md:   Dp = 12.dp  // --space-3
    val Lg:   Dp = 16.dp  // --space-4
    val Xl:   Dp = 24.dp  // --space-5
    val Xxl:  Dp = 32.dp  // --space-6
    val Xxxl: Dp = 48.dp  // --space-7
    val Huge: Dp = 64.dp  // --space-8
}

/**
 * Échelle de radii alignée avec la maquette (--radius-xs..2xl). Tokens
 * additionnels : ne modifie pas `TechticalShapes` (qui sert les
 * composants Material3 existants à 4dp uniforme), pour ne pas régresser
 * subtilement le rendu des Card/Button déjà en place.
 */
object Radii {
    val Xs:  Dp = 2.dp
    val Sm:  Dp = 4.dp
    val Md:  Dp = 6.dp
    val Lg:  Dp = 8.dp
    val Xl:  Dp = 12.dp
    val Xxl: Dp = 16.dp
}
