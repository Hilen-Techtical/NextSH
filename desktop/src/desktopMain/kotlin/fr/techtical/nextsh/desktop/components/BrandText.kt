// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextSecondary

/**
 * Brand "Next" + "SH" : partie "Next" en Gold SemiBold, partie "SH"
 * en Burgundy Bold (Space Grotesk). Composant partagé réutilisé dans
 * VaultUnlockScreen, FirstLaunchScreen, BrandStrip.
 */
@Composable
fun BrandText(
    fontSize: TextUnit = 26.sp,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Next",
            color = Gold,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
        )
        Text(
            text = "SH",
            color = Burgundy,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
        )
    }
}

/**
 * Numéro de version semver colorisé. Convention :
 *   - **major** Gold (le plus visible, palette principale)
 *   - **minor** Burgundy (accent fort, change de surface produit)
 *   - **patch** GoldMuted (gradation gold descendante, fix discret)
 *   - séparateurs `.` TextDisabled, suffixe `-debug` ou similaire en
 *     TextSecondary pour rester en arrière-plan
 *
 * Mono JetBrainsMono volontaire : la version est un "fait technique".
 * Sera intégré plus tard côté Desktop (sidebar footer ou Settings).
 */
@Composable
fun VersionText(
    version: String,
    fontSize: TextUnit = 13.sp,
    fontFamily: FontFamily = JetBrainsMonoFamily,
    fontWeight: FontWeight = FontWeight.Medium,
    modifier: Modifier = Modifier,
) {
    val (core, suffix) = version.split("-", limit = 2).let {
        it[0] to it.getOrNull(1)
    }
    val parts = core.split(".")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { idx, part ->
            if (idx > 0) {
                Text(
                    text = ".",
                    color = TextDisabled,
                    fontFamily = fontFamily,
                    fontWeight = fontWeight,
                    fontSize = fontSize,
                )
            }
            val color = when (idx) {
                0 -> Gold        // major
                1 -> Burgundy    // minor
                2 -> GoldMuted   // patch
                else -> TextSecondary
            }
            Text(
                text = part,
                color = color,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                fontSize = fontSize,
            )
        }
        suffix?.let {
            Text(
                text = "-$it",
                color = TextSecondary,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                fontSize = fontSize,
            )
        }
    }
}
