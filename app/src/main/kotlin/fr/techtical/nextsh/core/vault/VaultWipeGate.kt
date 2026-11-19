// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the "Wipe the vault" action stands. Held by the application, not by a screen or a view model:
 * the whole app reads it (the modal of the activity, the prompts, the tunnels, the sync, the sessions).
 *
 * - [IDLE]: nothing is going on.
 * - [RUNNING]: everything is being closed, then erased.
 * - [FAILED]: something could not be erased even after a second attempt. Still closed: a retry or
 *   the "Close" button of the failure dialog ends it.
 * - [DONE]: everything is erased and the activity has not restarted the app on the onboarding yet.
 */
enum class VaultWipeState { IDLE, RUNNING, FAILED, DONE }

/**
 * The state of the wipe, and the one question the rest of the app asks of it: is the app closed
 * ([isClosed])? Without any dependency, so that every component that must refuse its work during a
 * wipe can take it without a cycle.
 *
 * Closed means every state but [VaultWipeState.IDLE], [VaultWipeState.DONE] included: between the end
 * of the erase and the restart of the activity the app is erased but not yet restarted, and nothing
 * may open a session, start a tunnel, sync, or ask a question to a person in that gap.
 *
 * What a closed app refuses: a prompt that needs a person ([fr.techtical.nextsh.core.UnlockedAppVisibility]),
 * a new SSH session, a tunnel start, a sync, a write to the known hosts, a new transfer, a new
 * terminal tab and a launcher shortcut.
 */
@Singleton
class VaultWipeGate @Inject constructor() {

    private val _state = MutableStateFlow(VaultWipeState.IDLE)
    val state: StateFlow<VaultWipeState> = _state.asStateFlow()

    private val _closeBlocked = MutableStateFlow(false)

    /**
     * True when the user asked to close the failure dialog but the marker of the wipe could not be cleared:
     * leaving would let the next start wipe a NEW vault, so the failure stays. The dialog says why.
     */
    val closeBlocked: StateFlow<Boolean> = _closeBlocked.asStateFlow()

    internal fun setCloseBlocked(blocked: Boolean) {
        _closeBlocked.value = blocked
    }

    /** True while a wipe is under way, failed or just done: see the class comment. */
    fun isClosed(): Boolean = _state.value != VaultWipeState.IDLE

    internal fun compareAndSet(expect: VaultWipeState, update: VaultWipeState): Boolean =
        _state.compareAndSet(expect, update)

    internal fun set(state: VaultWipeState) {
        _state.value = state
    }

    /**
     * The activity that restarts the app after a wipe takes the [VaultWipeState.DONE] once: true for
     * one caller only, which then restarts the app, and the gate is [VaultWipeState.IDLE] again.
     */
    fun acknowledgeDone(): Boolean = compareAndSet(VaultWipeState.DONE, VaultWipeState.IDLE)
}
