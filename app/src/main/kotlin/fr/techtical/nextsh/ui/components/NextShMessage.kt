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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
internal const val INFO_DURATION_MS = 3_000L

/**
 * Durée d'affichage d'une erreur.
 *
 * Plus longue qu'une information : une erreur porte une consigne à lire et
 * souvent une action à décider.
 */
internal const val ERROR_DURATION_MS = 7_000L

/**
 * Durée réelle d'affichage d'un message, en millisecondes.
 *
 * Part de 3 s (information) ou 7 s (erreur), puis laisse le réglage
 * d'accessibilité « Délai de réaction » l'allonger : [recommend] reçoit la
 * durée de base et rend la durée recommandée par le système. Sans réglage
 * disponible ([recommend] nul), la durée de base est conservée. Une durée
 * recommandée plus courte que la base est ignorée : le réglage ne sert qu'à
 * laisser plus de temps, jamais moins.
 *
 * [long] donne les 7 s d une erreur à un message qui n en est pas une (un avis à lire, affiché
 * sans le rouge).
 */
internal fun messageDurationMillis(
    isError: Boolean,
    recommend: ((Long) -> Long)?,
    long: Boolean = false,
): Long {
    val base = if (isError || long) ERROR_DURATION_MS else INFO_DURATION_MS
    val recommended = recommend?.invoke(base) ?: return base
    return maxOf(base, recommended)
}

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
 * Accessibilité : le message est une région vive annoncée par TalkBack (polie
 * pour une information, assertive pour une erreur), avec une action « fermer ».
 * Sa durée suit le réglage « Délai de réaction » du système.
 *
 * @param message   texte à afficher, null pour ne rien afficher.
 * @param isError   colorise en rouge, ajoute l'icône d'alerte et laisse le
 *                  message affiché plus longtemps.
 * @param onDismiss appelé à la fermeture, manuelle ou automatique. L'appelant
 *                  doit y remettre [message] à null.
 * @param long      affiche 7 s comme une erreur, sans le rouge : pour un avis à lire.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextShMessage(
    message: String?,
    isError: Boolean = false,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    long: Boolean = false,
) {
    val accessibilityManager = LocalAccessibilityManager.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    // Le compte à rebours redémarre à chaque nouveau texte : deux messages
    // successifs ne doivent pas partager le délai du premier.
    LaunchedEffect(message, isError, long) {
        if (message == null) return@LaunchedEffect
        val recommend = accessibilityManager?.let { manager ->
            { base: Long ->
                manager.calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis = base,
                    containsIcons = true,
                    containsText = true,
                    containsControls = true,
                )
            }
        }
        delay(messageDurationMillis(isError, recommend, long))
        currentOnDismiss()
    }

    AnimatedVisibility(
        visible = message != null,
        enter   = fadeIn() + slideInVertically { it / 2 },
        exit    = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val accent = if (isError) ErrorRed else Gold
        val closeLabel = stringResource(R.string.action_close)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                    dismiss(label = closeLabel) {
                        onDismiss()
                        true
                    }
                }
                .clip(RoundedCornerShape(Radii.Md))
                .background(Surface, RoundedCornerShape(Radii.Md))
                .border(1.dp, if (isError) ErrorRed else Border1, RoundedCornerShape(Radii.Md))
                .padding(horizontal = Spacing.Md, vertical = Spacing.Xs),
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
                contentDescription = closeLabel,
                tint     = TextSecondary,
                // Zone tactile de 48 dp, le dessin de la croix reste de 16 dp.
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(16.dp)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}
