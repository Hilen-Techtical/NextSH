// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets the auto-start of the tunnels run once per app process.
 *
 * The screen of the tunnel list asks for it with each new ViewModel, and that ViewModel is created
 * again at every navigation entry. A guard kept in the ViewModel would therefore start the tunnels
 * again at every visit of the list, and ask again for the key or the fingerprint the user has just
 * cancelled. Auto-start is a start-up feature: it runs the first time the list opens in the process.
 * A tunnel that is flagged auto-start later, or whose start was cut short by leaving the screen (it
 * stays in error, held out of the reconnection), starts by hand or with the next process.
 */
@Singleton
class AutoStartTunnelsGate @Inject constructor() {

    private val claimed = AtomicBoolean(false)

    /** True for the first caller of the process, false for every later one. */
    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)
}
