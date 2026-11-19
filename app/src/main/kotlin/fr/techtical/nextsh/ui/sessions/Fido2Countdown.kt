// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import fr.techtical.nextsh.core.ssh.Fido2Prompt
import kotlinx.coroutines.delay

/** Refresh period of the countdown on the FIDO2 sheet. */
internal const val FIDO2_COUNTDOWN_TICK_MS = 100L

/**
 * Reports the time left of [prompt] to [onTick] every [FIDO2_COUNTDOWN_TICK_MS], from now until it
 * reaches zero, and returns then.
 *
 * It only reads the clock: it takes no callback to end the prompt. The coordinator ends the prompt
 * at the same deadline, and a second ending decided by the sheet raced it: a timeout became a
 * cancellation ("connexion annulée"), and a prompt that had just been extended for a PIN could be
 * cancelled on its previous deadline. A new [Fido2Prompt] with a later deadline starts a new
 * countdown (the sheet keys it on the deadline).
 *
 * @param nowMs the clock the deadline of [prompt] is measured on.
 */
internal suspend fun runFido2Countdown(
    prompt: Fido2Prompt,
    nowMs: () -> Long,
    onTick: (remainingMs: Long) -> Unit,
) {
    while (true) {
        val remaining = prompt.remainingMs(nowMs())
        onTick(remaining)
        if (remaining <= 0L) return
        delay(FIDO2_COUNTDOWN_TICK_MS)
    }
}
