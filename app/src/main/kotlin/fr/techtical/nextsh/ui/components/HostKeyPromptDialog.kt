// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldQuestion
import com.composables.icons.lucide.TriangleAlert
import fr.techtical.nextsh.core.ssh.HostKeyPromptCoordinator
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.SurfaceVariant
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.WarningAmber
import fr.techtical.nextsh.ui.theme.White

/**
 * Dialogue de vérification TOFU "host key", rendu globalement par
 * MainActivity au-dessus du NavGraph. Observe le `HostKeyPromptCoordinator`
 * Singleton, ce qui permet à l'utilisateur de répondre depuis n'importe
 * quel écran (HostList, Sessions, Tunnels…).
 */
@Composable
fun HostKeyPromptDialog(coordinator: HostKeyPromptCoordinator) {
    val state by coordinator.state.collectAsState()
    val s = state ?: return

    if (s.isMismatch) {
        // MITM warning, pas de bypass utilisateur
        AlertDialog(
            onDismissRequest = { coordinator.respond(false) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(ErrorRed.copy(alpha = 0.12f), RoundedCornerShape(Radii.Md)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Lucide.TriangleAlert,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Empreinte changée",
                        color = ErrorRed,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "L'empreinte de ${s.hostname} a changé depuis la dernière connexion. Cela peut indiquer une attaque MITM. Connexion refusée.",
                        color = TextPrimary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Stockée", color = TextSecondary, fontSize = 11.sp)
                    Text(
                        text = s.storedFingerprint.orEmpty(),
                        color = WarningAmber,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                    )
                    Text("Reçue", color = TextSecondary, fontSize = 11.sp)
                    Text(
                        text = s.fingerprint,
                        color = ErrorRed,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                Button(
                    onClick = { coordinator.respond(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorRed,
                        contentColor = White,
                    ),
                    shape = RoundedCornerShape(Radii.Md),
                ) {
                    Text("Annuler la connexion", fontWeight = FontWeight.Medium)
                }
            },
            containerColor = Surface,
            shape = RoundedCornerShape(Radii.Lg),
        )
        return
    }

    AlertDialog(
        onDismissRequest = { coordinator.respond(false) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Burgundy.copy(alpha = 0.16f), RoundedCornerShape(Radii.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.ShieldQuestion,
                        contentDescription = null,
                        tint = GoldLight,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Vérifier l'hôte",
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelValue(label = "Hôte", value = s.hostname)
                LabelValue(label = "Algorithme", value = s.algorithm, mono = true)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(Radii.Md))
                        .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(Radii.Md))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text(
                            text = "EMPREINTE SHA-256",
                            color = TextDisabled,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 9.sp,
                            letterSpacing = 0.5.sp,
                        )
                        Text(
                            text = s.fingerprint,
                            color = Gold,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 12.sp,
                        )
                    }
                }
                Text(
                    text = "Faire confiance à cet hôte ?",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { coordinator.respond(true) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor = White,
                ),
                shape = RoundedCornerShape(Radii.Md),
            ) {
                Text("Faire confiance", fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = { coordinator.respond(false) }) {
                Text("Refuser", color = TextSecondary)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(Radii.Lg),
    )
}

@Composable
private fun LabelValue(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = TextPrimary,
            fontFamily = if (mono) JetBrainsMonoFamily else SpaceGroteskFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
