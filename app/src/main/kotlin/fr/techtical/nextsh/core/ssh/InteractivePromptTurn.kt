// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one turn that every interactive prompt of the app takes: the FIDO2 sheet
 * ([Fido2PromptCoordinator]), the fingerprint dialog ([BiometricPromptCoordinator]) and the
 * confirmation of the key of an unknown server ([HostKeyPromptCoordinator]).
 *
 * Why shared. Each coordinator already shows one prompt at a time, but two connections that start
 * together (two tunnels of two hosts, one with a security key and one with a fingerprint key, or one
 * to a server never seen) would each have their own prompt ready at the same moment: a bottom sheet
 * asking for a key touch under a system dialog asking for a finger or over a dialog asking whom to
 * trust, and no way for the user to tell which connection each one is for. With one turn, the second
 * prompt waits, unseen, until the first one is over, and gets its full time (its deadline counts from
 * the moment it appears, not from the moment it asked).
 *
 * A request that waits is cancelled with its coroutine and shows nothing: [withTurn] only returns
 * the lock when its block ends or its coroutine is cancelled.
 */
@Singleton
class InteractivePromptTurn @Inject constructor() {

    private val mutex = Mutex()

    /** Runs [block] once no other prompt is on screen, and keeps the others waiting until it ends. */
    suspend fun <T> withTurn(block: suspend () -> T): T = mutex.withLock { block() }
}
