// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import com.yubico.yubikit.core.application.CommandState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs a blocking call of YubiKit on [dispatcher], off the calling thread, and stops it when the
 * caller is cancelled. A coroutine cancellation alone cannot interrupt a read on the key: [state]
 * is cancelled too, which sends CTAPHID_CANCEL over USB so that the wait for the touch ends (the
 * key then answers within the next keep-alive, about 100 ms) instead of blinking until its own
 * timeout. The call is waited for, so that nothing still uses the connection when the caller goes
 * on to close it.
 *
 * What the call throws travels as a value and is rethrown once the wait is over, not as the
 * failure of a child of the scope. A call that is stopped by the cancellation ends with an error
 * (CTAP2_ERR_KEEPALIVE_CANCEL, or the IOException of a link that goes down). As the failure of a
 * child it would become the root cause of the scope, which is thrown in place of the
 * CancellationException, and the caller would take the end of its own cancellation for a failure
 * of the key.
 */
internal suspend fun <T> blockingKeyCall(
    dispatcher: CoroutineDispatcher,
    state: CommandState? = null,
    block: () -> T,
): T {
    val outcome = coroutineScope {
        val work = async(dispatcher) { runCatching(block) }
        try {
            work.await()
        } catch (e: CancellationException) {
            state?.cancel()
            throw e
        }
    }
    return outcome.getOrThrow()
}
