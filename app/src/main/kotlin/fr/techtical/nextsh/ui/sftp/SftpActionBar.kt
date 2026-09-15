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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.*

/**
 * Bottom bar du mode multi-sélection SFTP : Phase 3.3 DA.
 *
 * Surface NearBlack + Border1 1dp top, label compteur Gold mono 12sp à
 * gauche, actions à droite : "Tout" (TextButton GoldMuted), "Supprimer"
 * (TextButton ErrorRed), "✕" annuler (IconButton TextSecondary).
 */
@Composable
fun SftpActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancelSelection: () -> Unit,
    modifier: Modifier = Modifier,
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
                text       = stringResource(R.string.sftp_selection_count, selectedCount),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 12.sp,
                color      = Gold,
            )

            Spacer(Modifier.weight(1f))

            TextButton(onClick = onSelectAll) {
                Icon(
                    Lucide.CheckCheck,
                    contentDescription = stringResource(R.string.sftp_select_all),
                    tint = GoldMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.Xs))
                Text(
                    text = stringResource(R.string.sftp_select_all),
                    color = GoldMuted,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }

            TextButton(onClick = onDeleteSelected) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = stringResource(R.string.sftp_action_delete_selected),
                    tint = ErrorRed,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.Xs))
                Text(
                    text = stringResource(R.string.sftp_action_delete_selected),
                    color = ErrorRed,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
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
