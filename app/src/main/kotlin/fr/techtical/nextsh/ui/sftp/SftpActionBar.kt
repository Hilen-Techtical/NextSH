// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.*

/**
 * Bottom bar du mode multi-sélection SFTP : Phase 3.3 DA.
 *
 * Surface NearBlack + Border1 1dp top, label compteur Gold mono 12sp à
 * gauche, actions à droite en icônes seules : "⋮" (autres actions, seulement quand
 * [onMoreActions] est fourni), "Tout sélectionner" (GoldMuted), "Supprimer" (ErrorRed), "✕"
 * annuler (TextSecondary).
 *
 * Icônes seules et non plus boutons texte : avec le "⋮" en plus, compteur, "Tout" et
 * "Supprimer" avec leur libellé ne tiennent plus sur 360 dp (le compteur seul prend environ
 * 115 dp à trois chiffres, les deux boutons texte plus de 175 dp). Chaque icône garde son
 * libellé d'accessibilité (« Tout sélectionner » pour la double coche, plus long que le « Tout »
 * du bouton texte d'avant, qui ne dit rien à un lecteur d'écran), et le compteur prend la place
 * qui reste (une ligne, tronqué par une ellipse si la police est agrandie).
 *
 * Le "⋮" est le premier du groupe, à gauche : il apparaît quand la sélection passe à un seul
 * élément et disparaît au deuxième, les boutons qui suivent restent donc à leur place (un
 * toucher visé sur "Supprimer" ne tombe jamais sur "Tout sélectionner").
 *
 * @param onMoreActions ouvre la feuille d'actions de l'élément sélectionné (Renommer,
 *   Permissions, Supprimer...). Null tant que la sélection ne compte pas exactement un élément.
 */
@Composable
fun SftpActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancelSelection: () -> Unit,
    modifier: Modifier = Modifier,
    onMoreActions: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface)
            .border(width = 1.dp, color = Border1),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.Md, vertical = Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = pluralStringResource(R.plurals.sftp_selection_count, selectedCount, selectedCount),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 12.sp,
                color      = Gold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f),
            )

            if (onMoreActions != null) {
                IconButton(onClick = onMoreActions) {
                    Icon(
                        Lucide.EllipsisVertical,
                        contentDescription = stringResource(R.string.sftp_action_more),
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            IconButton(onClick = onSelectAll) {
                Icon(
                    Lucide.CheckCheck,
                    contentDescription = stringResource(R.string.sftp_select_all),
                    tint = GoldMuted,
                    modifier = Modifier.size(18.dp),
                )
            }

            IconButton(onClick = onDeleteSelected) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = stringResource(R.string.sftp_action_delete_selected),
                    tint = ErrorRed,
                    modifier = Modifier.size(18.dp),
                )
            }

            IconButton(onClick = onCancelSelection) {
                Icon(
                    Lucide.X,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
