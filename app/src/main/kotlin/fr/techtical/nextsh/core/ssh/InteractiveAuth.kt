// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import kotlinx.coroutines.Job

/**
 * What an interactive authentication method (the FIDO2 key, the fingerprint) gets from
 * `SshSessionManager.connectInteractively` to ask the user for something in the middle of
 * `client.auth`.
 *
 * Their handlers run in a `runBlocking` on the SSH transport thread, blind to the connecting
 * coroutine and slow by nature (a person is involved), which asks for two things this class holds:
 *
 * - [connection], the Job of the connecting coroutine. The prompt is made a child of it, so that
 *   leaving the screen or cancelling the connection removes the prompt that is shown or queued
 *   (see [requestFido2Signature], [requestBiometricAuthorization]).
 * - [prompting], which puts the [AuthWatchdog] to sleep for the time of the prompt: the user's time
 *   is the prompt's own deadline, not the server's silence.
 */
internal class InteractiveAuth(
    val connection: Job?,
    private val watchdog: AuthWatchdog,
) {
    /**
     * Runs [block], the prompt, with the watchdog disarmed, and arms it again when the prompt ends,
     * whatever the way: with an answer that is on its way to the server, or with none (the
     * authentication then fails at once). [block] gets [connection] to hand to the request helper.
     */
    suspend fun <T> prompting(block: suspend (connection: Job?) -> T): T {
        watchdog.disarm()
        try {
            return block(connection)
        } finally {
            watchdog.arm()
        }
    }
}
