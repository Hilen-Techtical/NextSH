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
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_algorithm
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_fingerprint_label
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_host
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_mismatch_heading
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_mismatch_hint
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_mismatch_window_title
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_received_fingerprint
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_stored_fingerprint
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_trust_and_connect
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_understood
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_unknown_heading
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_unknown_warning
import fr.techtical.nextsh.desktop.generated.resources.host_key_dialog_unknown_window_title
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection
import org.jetbrains.compose.resources.stringResource

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
        title = stringResource(Res.string.host_key_dialog_unknown_window_title),
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
                text = stringResource(Res.string.host_key_dialog_unknown_heading, prompt.hostPort),
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.host_key_dialog_fingerprint_label),
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
                text = stringResource(Res.string.host_key_dialog_algorithm, prompt.algorithm),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(Res.string.host_key_dialog_unknown_warning),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onReject) {
                    Text(stringResource(Res.string.action_cancel), color = TextPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
                ) {
                    Text(stringResource(Res.string.host_key_dialog_trust_and_connect))
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
        title = stringResource(Res.string.host_key_dialog_mismatch_window_title),
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
                text = stringResource(Res.string.host_key_dialog_mismatch_heading),
                color = ErrorRed,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.host_key_dialog_host, mismatch.hostPort),
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
                Text(stringResource(Res.string.host_key_dialog_stored_fingerprint), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(mismatch.storedFingerprint, color = Gold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.host_key_dialog_received_fingerprint), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(mismatch.receivedFingerprint, color = ErrorRed, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = stringResource(Res.string.host_key_dialog_mismatch_hint),
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
                    Text(stringResource(Res.string.host_key_dialog_understood))
                }
            }
        }
    }
}
