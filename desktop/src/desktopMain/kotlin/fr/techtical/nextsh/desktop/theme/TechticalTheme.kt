// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TechticalColorScheme = darkColorScheme(
    primary = Burgundy,
    onPrimary = White,
    primaryContainer = BurgundyDark,
    onPrimaryContainer = GoldLight,
    secondary = Gold,
    onSecondary = NearBlack,
    secondaryContainer = GoldMuted,
    onSecondaryContainer = TextPrimary,
    background = NearBlack,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = White,
)

@Composable
fun TechticalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TechticalColorScheme,
        typography = TechticalTypography,
        shapes = TechticalShapes,
        content = content,
    )
}
