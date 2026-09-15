// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.techtical.nextsh.R

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/**
 * Famille display Space Grotesk, utilisée pour les titres de l'identité
 * Techtical refondue (PageHeader, card headers, dialog titles). Aligne
 * Android sur le module `:desktop`.
 */
val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

val TechticalTypography = Typography(
    // Titres UI
    headlineMedium  = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.sp),
    titleLarge      = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 0.sp),
    titleMedium     = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium,   fontSize = 16.sp, letterSpacing = 0.15.sp),
    titleSmall      = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium,   fontSize = 14.sp, letterSpacing = 0.1.sp),

    // Corps de texte
    bodyLarge       = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium      = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall       = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 16.sp),

    // Labels
    labelLarge      = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium,   fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium     = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium,   fontSize = 12.sp, letterSpacing = 0.5.sp),
    labelSmall      = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium,   fontSize = 11.sp, letterSpacing = 0.5.sp),
)

// Taille de police terminal (configurable par l'utilisateur)
val TerminalTextStyle = TextStyle(
    fontFamily  = JetBrainsMonoFamily,
    fontWeight  = FontWeight.Normal,
    fontSize    = 13.sp,
    lineHeight  = 18.sp,
    letterSpacing = 0.sp,
)
