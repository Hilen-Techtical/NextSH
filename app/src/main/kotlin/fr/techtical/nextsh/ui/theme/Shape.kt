// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Style compact/pro : coins resserrés, pas de bulles
val TechticalShapes = Shapes(
    extraSmall  = RoundedCornerShape(2.dp),   // chips, badges
    small       = RoundedCornerShape(4.dp),   // boutons, inputs
    medium      = RoundedCornerShape(4.dp),   // cards, dialogs
    large       = RoundedCornerShape(6.dp),   // bottom sheets
    extraLarge  = RoundedCornerShape(8.dp),   // modales plein écran
)
