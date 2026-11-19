// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.lock

import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.Fido2PromptCoordinator
import fr.techtical.nextsh.core.ssh.HostKeyPromptCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ends the prompts that are up when the app locks.
 *
 * While the app is locked it counts as absent for the prompts that need a person (see
 * `UnlockedAppVisibility`): a new one ends at once with "nobody on screen". A prompt that was already
 * published when the lock came up would not: it would hold the turn it shares with the others, and the
 * SSH connection behind it, for up to a minute, on a screen nobody who proved who they are is looking
 * at, and the button of the lock screen would wait for a dialog that cannot show.
 *
 * So the moment [AppLock] goes to locked, the key of an unknown server, the security key sheet and
 * the fingerprint of a biometric key end as they do for an app that was left: the answer of the
 * connection is the one of "nobody on screen". The vault request of the coordinator is not one of
 * them: it is the unlock itself, or the confirmation of a save, and goes on.
 */
@Singleton
class PromptsEndedByLock @Inject constructor(
    private val appLock: AppLock,
    private val hostKeys: HostKeyPromptCoordinator,
    private val securityKeys: Fido2PromptCoordinator,
    private val fingerprints: BiometricPromptCoordinator,
) {

    /** Follows the lock for as long as [scope] lives. Called once, at app start. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            // The flow says each change once; the start of the process is the first "locked", with
            // nothing up yet.
            appLock.locked.filter { it }.collect { endPrompts() }
        }
    }

    internal fun endPrompts() {
        hostKeys.endForAbsence()
        securityKeys.endForAbsence()
        fingerprints.endSignaturesForAbsence()
    }
}
