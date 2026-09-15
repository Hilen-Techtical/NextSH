// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/** Durée d'affichage d'un message d'information, en millisecondes. */
private const val INFO_DURATION_MS = 3_000L

/**
 * Durée d'affichage d'une erreur.
 *
 * Plus longue qu'une information : une erreur porte une consigne à lire et
 * souvent une action à décider.
 */
private const val ERROR_DURATION_MS = 7_000L

/**
 * Message applicatif dans la direction artistique NextSH.
 *
 * Remplace le Snackbar Material, qui posait deux problèmes : il se plaçait au
 * même endroit que le bandeau de transfert et le recouvrait, et son apparence
 * ne suivait pas la charte du reste de l'application.
 *
 * Ce composant ne se positionne pas lui-même. L'écran l'insère là où il ne
 * masque rien, typiquement empilé au-dessus d'un bandeau existant.
 *
 * @param message   texte à afficher, null pour ne rien afficher.
 * @param isError   colorise en rouge, ajoute l'icône d'alerte et laisse le
 *                  message affiché plus longtemps.
 * @param onDismiss appelé à la fermeture, manuelle ou automatique. L'appelant
 *                  doit y remettre [message] à null.
 */
@Composable
fun NextShMessage(
    message: String?,
    isError: Boolean = false,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Le compte à rebours redémarre à chaque nouveau texte : deux messages
    // successifs ne doivent pas partager le délai du premier.
    LaunchedEffect(message, isError) {
        if (message == null) return@LaunchedEffect
        delay(if (isError) ERROR_DURATION_MS else INFO_DURATION_MS)
        onDismiss()
    }

    AnimatedVisibility(
        visible = message != null,
        enter   = fadeIn() + slideInVertically { it / 2 },
        exit    = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val accent = if (isError) ErrorRed else Gold
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.Md))
                .background(Surface, RoundedCornerShape(Radii.Md))
                .border(1.dp, if (isError) ErrorRed else Border1, RoundedCornerShape(Radii.Md))
                .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Icon(
                imageVector = if (isError) Lucide.TriangleAlert else Lucide.Info,
                contentDescription = null,
                tint     = accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text       = message.orEmpty(),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Medium,
                fontSize   = 12.sp,
                color      = if (isError) ErrorRed else TextPrimary,
                modifier   = Modifier.weight(1f),
            )
            Icon(
                Lucide.X,
                contentDescription = stringResource(R.string.action_close),
                tint     = TextSecondary,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}
