// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.open_link_body
import fr.techtical.nextsh.desktop.generated.resources.open_link_confirm
import fr.techtical.nextsh.desktop.generated.resources.open_link_title
import org.jetbrains.compose.resources.stringResource

/**
 * Dialog de confirmation avant ouverture d'un lien externe dans le
 * navigateur système. L'URL est affichée en clair (en JetBrains Mono
 * Gold) pour que l'utilisateur valide la destination avant qu'elle ne
 * soit transmise à un autre processus.
 *
 * @param url URL exacte qui sera ouverte si l'utilisateur confirme.
 * @param what Phrase courte décrivant ce que représente l'URL
 *   (ex. « le dépôt GitHub public de NextSH »). Insérée dans le corps
 *   du dialog après « Ceci va ouvrir … dans votre navigateur ».
 * @param onConfirm Appelé quand l'utilisateur clique sur Ouvrir.
 *   L'appelant est responsable d'effectuer l'ouverture (et de fermer
 *   le dialog).
 * @param onDismiss Appelé quand l'utilisateur clique sur Annuler ou en
 *   dehors du dialog. L'appelant est responsable de fermer le dialog.
 */
@Composable
fun OpenLinkConfirmDialog(
    url: String,
    what: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.open_link_title),
                color = TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                Text(
                    text = stringResource(Res.string.open_link_body, what),
                    color = TextSecondary,
                )
                Text(
                    text = url,
                    color = Gold,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.open_link_confirm), color = Burgundy)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel), color = TextSecondary)
            }
        },
        containerColor = Surface,
    )
}
