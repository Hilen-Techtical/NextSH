// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Échelle d'élévation alignée avec la maquette (--shadow-sm..xl) et
 * avec le module `:desktop`. Sur Android Compose `Modifier.shadow` rend
 * via le pipeline natif (cf. ambientColor pour teinter en Burgundy).
 */
object Elevation {
    val None: Dp = 0.dp
    val Sm:   Dp = 1.dp
    val Md:   Dp = 4.dp
    val Lg:   Dp = 10.dp
    val Xl:   Dp = 20.dp
}
