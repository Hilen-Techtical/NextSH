// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TechticalDarkColorScheme = darkColorScheme(
    primary           = Burgundy,
    onPrimary         = White,
    primaryContainer  = BurgundyDark,
    onPrimaryContainer= GoldLight,

    secondary         = Gold,
    onSecondary       = NearBlack,
    secondaryContainer= GoldMuted,
    onSecondaryContainer = White,

    background        = NearBlack,
    onBackground      = TextPrimary,

    surface           = Surface,
    onSurface         = TextPrimary,
    surfaceVariant    = SurfaceVariant,
    onSurfaceVariant  = TextSecondary,

    error             = ErrorRed,
    onError           = White,

    outline           = GoldMuted,
    outlineVariant    = SurfaceVariant,
)

@Composable
fun TechticalTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TechticalDarkColorScheme,
        typography  = TechticalTypography,
        shapes      = TechticalShapes,
        content     = content
    )
}
