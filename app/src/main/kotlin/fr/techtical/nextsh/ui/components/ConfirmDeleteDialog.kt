// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White

/**
 * Dialog de confirmation destructive : DA Techtical (parité avec le
 * composant Desktop). Conserve l'`AlertDialog` Material3 pour rester
 * conforme aux conventions Android, mais avec :
 *   - icon-wrap ErrorRed@0.12 + icône Trash2 dans le titre
 *   - corps body 13sp TextSecondary
 *   - bouton "Supprimer" solid ErrorRed (OutlinedButton border
 *     transparente bg ErrorRed → hover plus clair côté Desktop ; ici
 *     on garde solide sans hover state, Material3 Button apporte un
 *     tonal-elevation overlay qui décalait la teinte donc on passe
 *     par OutlinedButton border transparente).
 *   - texte/icône en blanc forcés.
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    confirmLabel: String = "Supprimer",
    cancelLabel: String = "Annuler",
    icon: ImageVector = Lucide.Trash2,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(ErrorRed.copy(alpha = 0.12f), RoundedCornerShape(Radii.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        },
        text = {
            Text(text = message, color = TextSecondary, fontSize = 13.sp)
        },
        confirmButton = {
            OutlinedButton(
                onClick = onConfirm,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = ErrorRed,
                    contentColor = White,
                ),
                border = BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(Radii.Md),
            ) {
                Icon(icon, contentDescription = null, tint = White, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(confirmLabel, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelLabel, color = TextSecondary)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(Radii.Lg),
    )
}
