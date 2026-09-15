// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import fr.techtical.nextsh.desktop.core.ssh.HostKeyVerifyResult
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection

/**
 * TOFU dialog: first connection to a host with an unknown public key.
 * Blocking (modal): the SSH handshake is suspended in SSHJ's IO thread until
 * the user picks Accept or Reject. DialogWindow is used (not Compose Popup)
 * because JediTerm's heavyweight SwingPanel punches through lightweight
 * Compose overlays, same pattern as the theme / host pickers.
 */
@Composable
fun UnknownHostKeyDialog(
    prompt: HostKeyVerifyResult.Unknown,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onReject,  // closing the window = rejecting
        title = "Hôte inconnu",
        state = rememberDialogState(size = DpSize(520.dp, 340.dp)),
        resizable = false,
    ) {
        // Contient l'adresse de l'hôte et l'empreinte SHA-256 de sa clé :
        // exactement ce que le réglage « Cacher de la capture d'écran » protège.
        WindowCaptureProtection(window)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NearBlack)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Première connexion à ${prompt.hostPort}",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "L'empreinte SHA-256 de la clé serveur :",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = prompt.fingerprint,
                color = Gold,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "Algorithme : ${prompt.algorithm}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Vérifie cette empreinte auprès de l'administrateur de l'hôte avant de faire confiance. Une empreinte inattendue peut indiquer une tentative d'interception (MITM).",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onReject) {
                    Text("Annuler", color = TextPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
                ) {
                    Text("Faire confiance et connecter")
                }
            }
        }
    }
}

/**
 * Read-only alert: the host key CHANGED since the last trust. Connection has
 * already been rejected by the verifier. This dialog only informs the user
 * and directs them to wipe the stale entry via Settings.
 */
@Composable
fun HostKeyMismatchAlert(
    mismatch: HostKeyVerifyResult.Mismatch,
    onDismiss: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Alerte : empreinte SSH changée",
        state = rememberDialogState(size = DpSize(560.dp, 380.dp)),
        resizable = false,
    ) {
        WindowCaptureProtection(window)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NearBlack)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Empreinte hôte modifiée : connexion refusée",
                color = ErrorRed,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Hôte : ${mismatch.hostPort}",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Empreinte stockée :", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(mismatch.storedFingerprint, color = Gold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("Empreinte reçue :", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(mismatch.receivedFingerprint, color = ErrorRed, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "Si vous êtes certain que le changement est légitime (rotation de clé, reinstallation serveur), supprimez l'ancienne entrée dans Paramètres → Hôtes connus, puis reconnectez.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
                ) {
                    Text("J'ai compris")
                }
            }
        }
    }
}
