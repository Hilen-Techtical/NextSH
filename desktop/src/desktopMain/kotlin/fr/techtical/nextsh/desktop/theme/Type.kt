// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Inter ships with only regular and medium TTF files here; SemiBold/Bold map to medium
// so typography styles that request heavier weights still resolve to the provided glyphs
// without falling back to the system font.
private val InterFamily = FontFamily(
    Font(resource = "fonts/inter_regular.ttf", weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(resource = "fonts/inter_medium.ttf", weight = FontWeight.Medium, style = FontStyle.Normal),
    Font(resource = "fonts/inter_medium.ttf", weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(resource = "fonts/inter_medium.ttf", weight = FontWeight.Bold, style = FontStyle.Normal),
)

val JetBrainsMonoFamily = FontFamily(
    Font(resource = "fonts/jetbrains_mono_regular.ttf", weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(resource = "fonts/jetbrains_mono_bold.ttf", weight = FontWeight.Bold, style = FontStyle.Normal),
)

// Space Grotesk, famille « display » réservée aux titres marquants, brand
// et page headers de la refonte UI (Phase 1+). Pas câblée dans
// `TechticalTypography` pour ne pas modifier le rendu des écrans existants ;
// les nouveaux composables consomment cette famille explicitement via
// `fontFamily = SpaceGroteskFamily`. SemiBold aliasé sur Bold faute de
// fichier statique dédié dans le repo amont.
val SpaceGroteskFamily = FontFamily(
    Font(resource = "fonts/space_grotesk_regular.ttf", weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(resource = "fonts/space_grotesk_medium.ttf", weight = FontWeight.Medium, style = FontStyle.Normal),
    Font(resource = "fonts/space_grotesk_bold.ttf", weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(resource = "fonts/space_grotesk_bold.ttf", weight = FontWeight.Bold, style = FontStyle.Normal),
)

val TechticalTypography = Typography(
    displayLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 57.sp),
    displayMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 45.sp),
    displaySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp),
    headlineLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)
