// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Echelle d'élévation alignée avec la maquette (--shadow-sm..xl).
// Compose Desktop rend les ombres via Skia (`Modifier.shadow`), avec un
// comportement plus discret que sur Android. Les valeurs ici sont calibrées
// pour rester subtiles sur fond NearBlack tout en marquant la hiérarchie.
object Elevation {
    val None: Dp = 0.dp
    val Sm:   Dp = 1.dp
    val Md:   Dp = 4.dp
    val Lg:   Dp = 10.dp
    val Xl:   Dp = 20.dp
}
