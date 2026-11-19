// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.shared.util.LogId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The hand-over of a connected session from the host list to the screen that opens it (the
 * terminal, the SFTP browser).
 *
 * The host list connects, so the session comes out of the connection holding one reference, which
 * the list owns. It announces the session in an event, and an event is no guarantee of anyone
 * receiving it: the list can be left meanwhile (the event is dropped), its ViewModel cleared, the
 * receiving screen can fail to show up. A reference given to an event would then be lost with it,
 * and the session would stay open, unseen, until the process ends.
 *
 * So the reference does not travel with the event. The list [offer]s the session here before it
 * announces it, and the receiving screen [claim]s it: whoever claims it owns the reference, and
 * releases it as it would one it had acquired itself. A session nobody claims within
 * [CLAIM_WINDOW_MS] is released here. Exactly one of the two happens, for every offer, whatever the
 * route the event took: claim and expiry compete for the same entry, and only the winner acts.
 *
 * The window only runs while the app is on screen ([AppVisibility]). A receiver cannot show up in
 * an app nobody sees: the user tapped a host and pressed Home, the connection ended in the
 * background, and the screen that claims the session is not composed until the app is back. So the
 * offer is watched about once a second ([POLL_MS]) and the time the app is seen on screen is
 * counted in a row: it starts again from zero whenever the app is seen hidden, and the offer
 * expires when it reaches [CLAIM_WINDOW_MS]. A look that finds the app back after a hidden one
 * counts for nothing, since it cannot tell when during that second the app returned: the receiver
 * always has a full window of a visible app, however close to a look the return came. Releasing at
 * once would have cost the user a second security key or fingerprint prompt on return.
 *
 * A hidden app does not hold a session for ever: an offer expires after [HIDDEN_CAP_MS] of hidden
 * time, counted over all its looks (a return does not give it back). The price is one more prompt
 * after a very long absence. A process that dies ends everything anyway.
 *
 * A claim that fails (the window passed, or the session was never offered, as after a recreation
 * of the process) means the receiver owns nothing: it must not release, and takes a reference with
 * [SshSessionManager.acquire] if it wants to use a session that is still there.
 */
@Singleton
class SessionHandoff internal constructor(
    private val sessionManager: SshSessionManager,
    private val scope: CoroutineScope,
    private val claimWindowMs: Long,
    private val appVisibility: AppVisibility,
    private val hiddenCapMs: Long,
) {

    @Inject
    constructor(sessionManager: SshSessionManager, appVisibility: AppVisibility) : this(
        sessionManager,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        CLAIM_WINDOW_MS,
        appVisibility,
        HIDDEN_CAP_MS,
    )

    /** The expiry timer of each session on offer. An entry leaves the map by being claimed or by expiring. */
    private val offers = ConcurrentHashMap<String, Job>()

    /**
     * The caller, which owns one reference on [sessionId], offers it to whoever claims it next. From
     * here on it must not release that reference itself, nor use the session as its own.
     */
    fun offer(sessionId: String) {
        val expiry = scope.launch(start = CoroutineStart.LAZY) {
            // One window of a visible app in a row, or the cap of hidden time, see the class comment.
            val tick = minOf(claimWindowMs, POLL_MS)
            var visibleMs = 0L
            var hiddenMs = 0L
            var wasHidden = false
            while (true) {
                delay(tick)
                if (appVisibility.isVisible()) {
                    // Back after a hidden look: nothing is counted for this one, see the class comment.
                    visibleMs = if (wasHidden) 0L else visibleMs + tick
                    wasHidden = false
                    if (visibleMs >= claimWindowMs) break
                } else {
                    wasHidden = true
                    visibleMs = 0L
                    hiddenMs += tick
                    if (hiddenMs >= hiddenCapMs) break
                }
            }
            if (offers.remove(sessionId, coroutineContext.job)) {
                Timber.i("Nobody claimed session ${LogId.of(sessionId)}, releasing it")
                sessionManager.release(sessionId)
            }
        }
        // A second offer of the same session is a mistake of the caller, which owns one reference:
        // the earlier timer goes, and the single reference is still released once.
        offers.put(sessionId, expiry)?.cancel()
        expiry.start()
    }

    /**
     * True if [sessionId] was on offer and is now the caller's: it owns the reference the offerer
     * gave up, and must release it. False if nothing is on offer under that id.
     */
    fun claim(sessionId: String): Boolean {
        val expiry = offers.remove(sessionId) ?: return false
        expiry.cancel()
        return true
    }

    companion object {
        /**
         * How long a session waits for its receiver. The event reaches the screen in a few frames,
         * the window only has to cover a slow device and a navigation animation, and it is also
         * how long a session of a left list stays open before it is closed.
         */
        const val CLAIM_WINDOW_MS = 15_000L

        /** How often an offer looks at the visibility of the app, see the class comment. */
        const val POLL_MS = 1_000L

        /** How much time hidden an offer is kept for, over all its looks, see the class comment. */
        const val HIDDEN_CAP_MS = 15 * 60_000L
    }
}
