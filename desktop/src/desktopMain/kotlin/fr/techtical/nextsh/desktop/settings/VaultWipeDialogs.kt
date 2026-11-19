// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.data.di.VaultWipeState
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_close
import fr.techtical.nextsh.desktop.generated.resources.settings_wipe_failed
import fr.techtical.nextsh.desktop.generated.resources.settings_wipe_progress
import fr.techtical.nextsh.desktop.generated.resources.settings_wipe_retry
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import org.jetbrains.compose.resources.stringResource

/**
 * The two dialogs of the vault wipe, hosted at the root of the window (see `App.kt`) and driven by the
 * container state, so they show over whichever screen is current:
 *  - RUNNING: a modal with no button and no way to dismiss it (outside click and Escape do nothing). The
 *    global shortcuts are ignored meanwhile (see `Main.kt`, also while FAILED), so nothing can leave the screen or start
 *    something while the sessions close and the data is erased.
 *  - FAILED: something could not be erased even after a second attempt. "Retry" replays what is left,
 *    "Close" gives up: the lockdown is lifted and the app goes to the unlock screen (vault still there) or
 *    to the creation screen (vault gone). Only the two buttons act, not Escape nor a click outside.
 *
 * Reading the container state costs nothing at cold start: the runner itself is only touched on a click.
 */
@Composable
fun VaultWipeDialogs() {
    val state by DesktopContainer.vaultWipeState.collectAsState()
    if (state == VaultWipeState.RUNNING) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Gold, strokeWidth = 2.dp)
                    Text(stringResource(Res.string.settings_wipe_progress), color = TextPrimary)
                }
            },
            containerColor = Surface,
        )
    }
    if (state == VaultWipeState.FAILED) {
        AlertDialog(
            onDismissRequest = {}, // only the two buttons act: Escape or a click outside must not give up
            text = { Text(stringResource(Res.string.settings_wipe_failed), color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { DesktopContainer.vaultWipeRunner.retry() }) {
                    Text(stringResource(Res.string.settings_wipe_retry), color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { DesktopContainer.vaultWipeRunner.dismissFailure() }) {
                    Text(stringResource(Res.string.action_close), color = TextSecondary)
                }
            },
            containerColor = Surface,
        )
    }
}
