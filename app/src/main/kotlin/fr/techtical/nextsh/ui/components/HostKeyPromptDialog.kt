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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldQuestion
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.HostKeyPromptCoordinator
import fr.techtical.nextsh.ui.theme.Burgundy
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
import fr.techtical.nextsh.ui.theme.White
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * Dialogue de vérification TOFU "host key" (première connexion à un serveur), rendu globalement par
 * MainActivity au-dessus du NavGraph. Observe le `HostKeyPromptCoordinator`
 * Singleton, ce qui permet à l'utilisateur de répondre depuis n'importe
 * quel écran (HostList, Sessions, Tunnels…).
 *
 * Tous les textes viennent des ressources (français par défaut, anglais), comme ceux de la clé de
 * sécurité et de l'empreinte : c'est le dialogue où l'utilisateur décide à qui il fait confiance.
 *
 * Une clé qui ne correspond plus à celle enregistrée n'a pas de dialogue : la connexion est refusée
 * sans que l'utilisateur puisse passer outre (voir `InteractiveHostKeyVerifier`).
 */
@Composable
fun HostKeyPromptDialog(coordinator: HostKeyPromptCoordinator) {
    val state by coordinator.state.collectAsState()
    val s = state ?: return

    // "Faire confiance" ne s'active qu'un instant après l'apparition du dialogue : un appui parti
    // avant qu'il ne surgisse (par exemple pendant une reconnexion, sur un autre écran) ne doit pas
    // valider une clé que personne n'a lue. Remis à zéro pour chaque demande (l'état change), et à
    // chaque retour de l'app au premier plan : un utilisateur qui la quitte puis y revient retrouve
    // le dialogue comme à son apparition, pas actif dès la première image.
    var confirmArmed by remember(s) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(s, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                delay(HostKeyPromptCoordinator.CONFIRM_ARM_MS)
                confirmArmed = true
                awaitCancellation()
            } finally {
                // L'app quitte le premier plan : désarmé tout de suite, avant même la reprise.
                confirmArmed = false
            }
        }
    }

    // Les réponses portent l'état affiché : celle d'un dialogue déjà remplacé par le suivant est
    // ignorée, elle ne doit pas faire confiance à la clé d'un autre hôte.
    AlertDialog(
        onDismissRequest = { coordinator.respond(s, false) },
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
                    text = stringResource(R.string.dialog_host_verification_title),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.host_key_dialog_hint),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                LabelValue(label = stringResource(R.string.label_host), value = s.hostname)
                LabelValue(label = stringResource(R.string.host_key_dialog_algorithm), value = s.algorithm, mono = true)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(Radii.Md))
                        .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(Radii.Md))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.host_key_dialog_fingerprint),
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
                    text = stringResource(R.string.dialog_trust_host_question),
                    color = TextPrimary,
                    fontSize = 13.sp,
                )
                Text(
                    text = stringResource(
                        R.string.host_key_dialog_time_limit,
                        (HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS / 1_000L).toInt(),
                    ),
                    color = TextDisabled,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { coordinator.respond(s, true) },
                enabled = confirmArmed,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor = White,
                ),
                shape = RoundedCornerShape(Radii.Md),
            ) {
                Text(stringResource(R.string.action_trust_host), fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = { coordinator.respond(s, false) }) {
                Text(stringResource(R.string.action_reject), color = TextSecondary)
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
