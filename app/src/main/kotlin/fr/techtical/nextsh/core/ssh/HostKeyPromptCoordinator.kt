// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of the host-key TOFU prompt: exposed app-wide via the
 * [HostKeyPromptCoordinator] so that **any** screen in the app can
 * render the dialog when an SSH connection is initiated.
 *
 * @property hostname `host:port` for display.
 * @property algorithm Key algorithm (e.g., "ssh-ed25519").
 * @property fingerprint SHA-256 fingerprint to confirm.
 * @property isMismatch True if a key is already stored and differs (MITM risk).
 * @property storedFingerprint Stored fingerprint (only when [isMismatch] = true).
 */
data class HostKeyDialogState(
    val hostname: String,
    val algorithm: String,
    val fingerprint: String,
    val isMismatch: Boolean,
    val storedFingerprint: String? = null,
)

/**
 * Singleton coordinating the host-key TOFU prompt across the whole app.
 *
 * Why this exists : the SSH host-key verifier callback used to be
 * installed in `SessionViewModel.init` only. As a result, if the user
 * tapped "Connect" from the HostList screen on a fresh install (no
 * `SessionViewModel` ever created), the callback was null and SSHJ
 * silently rejected the unknown host with `Could not verify <algo>
 * host key with fingerprint <SHA256:...>`. The TOFU dialog never had
 * a chance to appear, because there was no `Sessions` entry in the
 * Android nav to instantiate the ViewModel beforehand.
 *
 * Now installed at App level (`App.onCreate`) on the singleton
 * `SshSessionManager`, the callback is always live regardless of
 * navigation history. The dialog is rendered at MainActivity level,
 * overlaying the entire NavGraph.
 */
@Singleton
class HostKeyPromptCoordinator @Inject constructor() {

    private val _state = MutableStateFlow<HostKeyDialogState?>(null)
    val state: StateFlow<HostKeyDialogState?> = _state.asStateFlow()

    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Suspendable callback invoked by [SshSessionManager.InteractiveHostKeyVerifier]
     * when an unknown host key is encountered. Surface the dialog,
     * suspend until the user responds via [respond].
     *
     * Returns true if the user trusted the key, false if they rejected
     * (the verifier then aborts the SSH handshake).
     */
    suspend fun promptUnknown(unknown: HostKeyVerifyResult.Unknown): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        _state.value = HostKeyDialogState(
            hostname = unknown.hostname,
            algorithm = unknown.algorithm,
            fingerprint = unknown.fingerprint,
            isMismatch = false,
        )
        return try {
            deferred.await()
        } finally {
            _state.value = null
            pending = null
        }
    }

    /**
     * Called from the dialog UI to resolve the pending prompt.
     */
    fun respond(accepted: Boolean) {
        pending?.complete(accepted)
    }

    /**
     * Dismiss without accepting (equivalent to refusing).
     */
    fun dismiss() {
        respond(false)
    }
}
