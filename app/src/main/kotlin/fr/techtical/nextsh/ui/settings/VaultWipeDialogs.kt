// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.vault.AndroidVaultWipe
import fr.techtical.nextsh.core.vault.VaultWipeState
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White

/** What the app shows of the wipe of the vault, from its state: see [wipeOverlayOf]. */
internal enum class WipeOverlay { NONE, PROGRESS, FAILED }

/**
 * The overlay of a [state] of the wipe. [VaultWipeState.DONE] keeps the progress up: the wipe is over and
 * the activity restarts the app on the creation of the vault a moment later, and the screens that were
 * under the modal must not show again in that gap (they hold data that is gone).
 */
internal fun wipeOverlayOf(state: VaultWipeState): WipeOverlay = when (state) {
    VaultWipeState.IDLE -> WipeOverlay.NONE
    VaultWipeState.RUNNING, VaultWipeState.DONE -> WipeOverlay.PROGRESS
    VaultWipeState.FAILED -> WipeOverlay.FAILED
}

/**
 * Whether the activity restarts the app now: the wipe is done (see [VaultWipeState.DONE]) and the activity is on
 * screen. Android refuses to start an activity from the background, so a wipe that ends while the user is
 * away waits for their return.
 */
internal fun shouldRestartAfterWipe(state: VaultWipeState): Boolean = state == VaultWipeState.DONE

/**
 * The modal of the wipe of the vault, composed by `MainActivity` after the hosts of the prompts and BEFORE
 * the lock screen: it covers every screen, and the lock screen covers it if the app locks meanwhile (the
 * wipe goes on under it, it belongs to the application).
 *
 * Progress: a dialog that neither Back nor a tap outside closes. Failure: a dialog with "Retry" and
 * "Close", closed by neither Back nor a tap outside either: leaving it any other way would leave the app
 * closed (see `VaultWipeGate`) with no way to end the state.
 */
@Composable
fun VaultWipeOverlay(wipe: AndroidVaultWipe, restarting: Boolean = false) {
    val state by wipe.state.collectAsState()
    val closeBlocked by wipe.closeBlocked.collectAsState()
    // [restarting]: the app was told to restart after the wipe and this activity is still up for a moment.
    when (if (restarting) WipeOverlay.PROGRESS else wipeOverlayOf(state)) {
        WipeOverlay.NONE -> Unit
        WipeOverlay.PROGRESS -> VaultWipeProgressDialog()
        WipeOverlay.FAILED -> VaultWipeFailedDialog(onRetry = wipe::retry, onClose = wipe::dismissFailure, closeBlocked = closeBlocked)
    }
}

@Composable
private fun VaultWipeProgressDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Box(
            modifier = Modifier
                .background(Surface, RoundedCornerShape(Radii.Lg))
                .padding(Spacing.Lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Spacing.Md))
                Text(
                    text = stringResource(R.string.settings_wipe_progress),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun VaultWipeFailedDialog(onRetry: () -> Unit, onClose: () -> Unit, closeBlocked: Boolean) {
    AlertDialog(
        // Neither Back nor a tap outside ends the failure: only the two buttons do.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(ErrorRed.copy(alpha = 0.12f), RoundedCornerShape(Radii.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.TriangleAlert, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(Spacing.Md))
                Text(
                    text = stringResource(R.string.settings_wipe_failed_title),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text = stringResource(R.string.settings_wipe_failed),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                // "Close" did nothing visible: the marker of the wipe could not be cleared, say why.
                if (closeBlocked) {
                    Text(
                        text = stringResource(R.string.settings_wipe_close_blocked),
                        color = ErrorRed,
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onRetry,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = ErrorRed, contentColor = White),
                border = BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(Radii.Md),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.action_retry), color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.action_close), color = TextSecondary)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(Radii.Lg),
    )
}
