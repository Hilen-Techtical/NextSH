// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.lock

import fr.techtical.nextsh.core.ActivityVisibilityTracker
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the clock of the lock when the dialog that asks the owner for the vault ends while the app is
 * not on screen.
 *
 * With that dialog up, the stop of the activity is not noted as an absence (`AppLock.onAppHidden`): the
 * screen of the phone code stops the activity while it is up, and the return would lock the app right
 * after the owner proved who they are. But the owner may also have left, by Home, in the middle of a
 * confirmation of a save, and then nothing would ever start the clock. So when the dialog ends, however
 * it ends (answered, cancelled, timed out), and the app is still out of the foreground, the absence is
 * noted at that moment: it locks when the delay has passed, as for any absence. A rotation is not one
 * (the visibility of the tracker holds through the recreation), and a dialog that is still up, on the
 * screen of the phone code, changes nothing.
 */
@Singleton
class OwnerPromptEndedAway @Inject constructor(
    private val appLock: AppLock,
    private val fingerprints: BiometricPromptCoordinator,
    private val tracker: ActivityVisibilityTracker,
) {

    /** Follows the vault dialogs for as long as [scope] lives. Called once, at app start. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            fingerprints.vaultRequestEnded.collect { onVaultRequestEnded() }
        }
    }

    internal fun onVaultRequestEnded() {
        if (!tracker.isVisible()) appLock.onAppHidden()
    }
}
