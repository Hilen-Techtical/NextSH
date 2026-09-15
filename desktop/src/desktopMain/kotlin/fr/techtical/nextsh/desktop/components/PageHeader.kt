// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary

/**
 * Page header partagé pour les écrans refondus. Affiche le titre (Space
 * Grotesk SemiBold), un sous-titre descriptif (TextDisabled), et un slot
 * `actions` à droite pour les boutons d'écran (Nouvel hôte, etc.).
 *
 * Aligné avec la maquette Claude Design : pas de gradient burgundy en
 * V1 (option esthétique laissée pour plus tard).
 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().background(NearBlack)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Xl, vertical = Spacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier
                .weight(1f),
                verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = TextDisabled,
                        fontSize = 12.sp,
                    )
                }
            }
            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Border1))
    }
}
