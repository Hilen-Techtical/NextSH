// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.core.network.NetworkEvent
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.core.network.isLocalDeviceHost
import fr.techtical.nextsh.core.ssh.DeletedTunnelStopper
import fr.techtical.nextsh.core.ssh.LiveSessionCheck
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TUNNEL_AUTH_REQUIRED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_LISTENER_STOPPED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_NETWORK_LOST_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_NO_ANSWER_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_SESSION_LOST_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_SOCKS_STOPPED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_RECONNECT_INTERRUPTED_MESSAGE
import fr.techtical.nextsh.core.ssh.UnexpectedStop
import fr.techtical.nextsh.core.ssh.isStartWithdrawn
import fr.techtical.nextsh.core.ssh.needsUserPresence
import fr.techtical.nextsh.core.ssh.tunnelMessageRes
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.service.AuthRequiredNotifier
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.model.TunnelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class ReconnectTunnelsUseCase @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val tunnelManager: SshTunnelManager,
    private val startTunnel: StartTunnelUseCase,
    private val liveTunnels: LiveTunnelLookup,
    private val deletedTunnelStopper: DeletedTunnelStopper,
    private val appVisibility: AppVisibility,
    private val authRequiredNotifier: AuthRequiredNotifier,
    private val liveSessions: LiveSessionCheck,
    private val settings: SettingsDataStore,
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val MAX_RETRIES = 5
        private const val INITIAL_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 30_000L

        /** A tunnel that held at least this long before it was cut was healthy: its cut is a new incident. */
        internal const val STABLE_MS = 60_000L

        /**
         * How many cuts in a row, each before [STABLE_MS], the reconnection puts up with. The next one
         * is not answered: the tunnel is restored only to drop again as soon as it is, which a server
         * that accepts the connection and then drops it (a limit, a rule, a restart loop) does for
         * as long as it is asked to, one login after the other.
         */
        internal const val MAX_QUICK_CUTS = 3

        /**
         * The messages of a tunnel that say why it was cut: the only stored messages worth showing as
         * the reason of a reconnection. Any other (the final verdict of an earlier loop, an auth
         * failure) is not.
         */
        internal val CUT_REASONS: Set<String> = setOf(
            TUNNEL_SESSION_LOST_MESSAGE,
            TUNNEL_NO_ANSWER_MESSAGE,
            TUNNEL_LISTENER_STOPPED_MESSAGE,
            TUNNEL_SOCKS_STOPPED_MESSAGE,
            TUNNEL_NETWORK_LOST_MESSAGE,
        )
    }

    /** What the offline gate decided before an attempt. */
    private enum class Gate {
        /** A network is there (or the host is on this device): go on with the attempt. */
        OPEN,

        /** The tunnel no longer waits for a reconnection: the loop ends. */
        GONE,

        /** The loop waited for the network: attempts start over. */
        WAITED,
    }

    /** What to do with a tunnel waiting for a reconnection, decided just before each step. */
    private sealed interface Verdict {
        /** Someone stopped it, restarted it or it is gone from the manager: leave it alone. */
        data object Abandon : Verdict

        /** Its row or its host was deleted meanwhile: stop it for good. */
        data object Deleted : Verdict

        /**
         * The user turned the automatic reconnection off, for all hosts (Settings) or for the host
         * of this tunnel: it stays in error with its reason, as it is, and nothing else happens (no
         * attempt, no hold, no notification). Not held out of the reconnection: turning the option
         * on again makes the tunnel a candidate at the next event.
         */
        data object Disabled : Verdict

        /** Reconnect from [config], the row as stored now; [hostname] is the address of its host, as stored now. */
        data class Proceed(val config: TunnelConfig, val hostname: String) : Verdict
    }

    /**
     * The tunnels with a reconnection under way, by id: one at a time per tunnel, whichever event
     * started it. A tunnel is back in ERROR between two attempts (a failed start puts it there), so
     * the list of the candidates of a network event, or the stop of a tunnel that was just
     * restarted, would otherwise start a second back-off on it. The set is the one place that says.
     * (A failed start leaves the card in ERROR for an instant, then the loop puts it back in
     * RECONNECTING with the reason, see [reconnect].)
     */
    private val reconnecting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Per tunnel, how many cuts in a row came before [STABLE_MS] of uptime. A cut after a stable run
     * starts the count again, so does a start that is not the reconnection's own (by hand, the
     * auto-start, the resume from the notification: see [reconnectedInstances]), and a network
     * connection clears them all, as it is a new situation. At [MAX_QUICK_CUTS] the reconnection
     * stops answering the cuts and the delay before the first attempt grows with the count.
     */
    private val quickCuts = ConcurrentHashMap<String, Int>()

    /** Per tunnel, the instance (`ActiveTunnel.instanceId`) that this reconnection started last. */
    private val reconnectedInstances = ConcurrentHashMap<String, Long>()

    /**
     * The tunnels cut while a reconnection of theirs was under way (the one it just brought up is cut
     * before the attempt is over): remembered, once, and answered when that reconnection ends. Without
     * it the cut found the tunnel guarded, was dropped, and the tunnel stayed in error until the next
     * network event.
     */
    private val rerun: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Reconnects the tunnels, from two kinds of event, until the caller cancels it:
     * - the network comes back ([NetworkEvent.Connected]): every tunnel in ERROR (or left in RECONNECTING by a cut nobody answered) is tried, one
     *   after the other;
     * - a tunnel stops by itself ([SshTunnelManager.unexpectedStops]: the session was lost, its
     *   listener ended): that tunnel is tried right away, after a short wait (see
     *   [reconnectAfterStop]), in a coroutine of its own so that its
     *   back-off does not hold up the others. Without it a tunnel cut by a server restart or an
     *   idle drop, with the network unchanged, waited for the next network event.
     *
     * Both go through the same steps ([reconnect]) and the same gates, and a tunnel is never
     * reconnected twice at the same time (see [reconnecting]).
     */
    suspend fun observe() {
        try {
            observeEvents()
        } finally {
            // Cancelled (the service is destroyed) with a cut still in the buffer, in the read of the
            // setting or in the first verdict, before any loop had the card in its `try`: the amber
            // card would stay for ever, with nobody to reconnect it. Every loop has ended by now
            // ([reconnecting] is empty), and this suspends nothing: back to red with its reason.
            for ((id, state) in tunnelManager.tunnelStates.value) {
                if (state.status == TunnelStatus.RECONNECTING) settleAsError(id, ownsLoop = true)
            }
        }
    }

    private suspend fun observeEvents() {
        coroutineScope {
            launch {
                networkMonitor.networkEvents.collect { event ->
                    if (event is NetworkEvent.Connected) {
                        // Not inline: a pass can last minutes (the attempts of each tunnel, one
                        // after the other), and the events that come meanwhile would be lost to
                        // every collector (the flow has a small buffer and drops, never waits).
                        // Two passes at once are safe: a tunnel is reconnected once ([reconnecting]).
                        launch { reconnectErrorTunnels() }
                    }
                }
            }
            launch {
                tunnelManager.unexpectedStops.collect { stop ->
                    launch { reconnectAfterStop(stop) }
                }
            }
        }
    }

    /**
     * The "Reconnexion automatique" option of the Settings, read at each event so that a change
     * applies at once. It governs the automatic reconnection only: a start by hand, the auto-start
     * and the resume from the notification do not come through here. Unreadable (a storage error):
     * on, which is the default of the option.
     */
    private suspend fun autoReconnectEnabled(): Boolean = try {
        settings.settingsFlow.first().autoReconnect
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("Réglage de reconnexion automatique illisible (${e::class.java.simpleName})")
        true
    }

    internal suspend fun reconnectErrorTunnels() {
        // A network connection is a new situation: the tunnels that were given up on for dropping
        // again as soon as they were restored get their chances back.
        quickCuts.clear()
        if (!autoReconnectEnabled()) {
            Timber.i("Reconnexion automatique désactivée dans les réglages")
            // A card left amber by a cut would wait for a loop that never comes: red, with its reason.
            for (candidate in tunnelManager.getTunnelsForReconnect()) settleAsError(candidate.id)
            return
        }
        val tunnels = tunnelManager.getTunnelsForReconnect()
        if (tunnels.isEmpty()) return

        Timber.i("Tentative de reconnexion de ${tunnels.size} tunnel(s)")

        for (candidate in tunnels) reconnectGuarded(candidate, INITIAL_DELAY_MS)
    }

    /**
     * A tunnel has just stopped by itself: reconnects it, with the back-off, the gates and the
     * final message of any reconnection, after a wait that grows with the cuts that came before
     * [STABLE_MS] of uptime (1 s, then 2 s, then 4 s) and is not answered at all from
     * [MAX_QUICK_CUTS] of them. Nothing if the option is off in the Settings, or if the tunnel is
     * no longer a candidate (stopped by hand, held, started again, gone).
     */
    internal suspend fun reconnectAfterStop(stop: UnexpectedStop) {
        // Counted first, before the one suspension of this function (the read of the setting): the
        // tunnel cut right after it was restored is still found in the reconnection that restored it,
        // which is what tells it from a cut of the user's run. Counted after, the reconnection could
        // have ended meanwhile, and the series was started again by a cut it did not recognise.
        recordCut(stop)
        if (!autoReconnectEnabled()) {
            // Nothing is restored while the option is off: there is no series of restorations.
            quickCuts.remove(stop.tunnelId)
            // The cut put the card in amber: no loop will follow, so it says so, in red.
            settleAsError(stop.tunnelId)
            return
        }
        reconnectForCut(stop.tunnelId)
    }

    /**
     * A card left in RECONNECTING by a cut, with no loop of ours behind it, goes to ERROR with the
     * reason it carries (or the generic one): an amber card must always have a reconnection under way.
     * With a loop under way (it owns the card): nothing now, but a rerun is asked ([rerun]), so that
     * the loop, once over, comes back through the checks that settle it. Nothing when the tunnel is
     * not waiting any more (stopped, restarted: [SshTunnelManager.markIfAwaitingReconnect] decides, under
     * its lock).
     */
    private fun settleAsError(tunnelId: String, ownsLoop: Boolean = false) {
        // [ownsLoop]: the caller is the loop of this tunnel itself (it holds the guard).
        if (!ownsLoop && tunnelId in reconnecting) {
            // A loop is under way (the tunnel just restored may be cut again before its start
            // returns): its answer to a cut comes when it ends, by the same checks, and the first
            // of them finds the option off and settles the card here.
            rerun.add(tunnelId)
            return
        }
        val state = tunnelManager.getTunnelState(tunnelId) ?: return
        if (state.status != TunnelStatus.RECONNECTING) return
        tunnelManager.markIfAwaitingReconnect(
            tunnelId,
            TunnelStatus.ERROR,
            state.errorMessage?.takeIf { it.isNotBlank() } ?: TUNNEL_RECONNECT_INTERRUPTED_MESSAGE,
        )
    }

    /** Counts [stop] among the cuts in a row of its tunnel, or starts the count again. */
    private fun recordCut(stop: UnexpectedStop) {
        val id = stop.tunnelId
        // An instance this reconnection did not start is the user's (by hand, the auto-start, the
        // resume from the notification): whatever came before, it has its chances. While a
        // reconnection of the tunnel is under way, the instance cut may be the one it is starting and
        // has not noted yet.
        if (reconnectedInstances[id] != stop.instanceId && id !in reconnecting) quickCuts.remove(id)
        if (stop.uptimeMs < STABLE_MS) quickCuts.merge(id, 1, Int::plus) else quickCuts.remove(id)
    }

    private suspend fun reconnectForCut(tunnelId: String) {
        // ERROR or RECONNECTING: the cut put the card straight in amber when someone was listening.
        val candidate = tunnelManager.getTunnelForCut(tunnelId) ?: return
        val cuts = quickCuts[tunnelId] ?: 0
        if (cuts >= MAX_QUICK_CUTS) {
            giveUpOnQuickCuts(candidate)
            return
        }
        Timber.i("Tunnel coupé : reconnexion")
        reconnectGuarded(candidate, INITIAL_DELAY_MS shl cuts, answersACut = true)
    }

    /**
     * The tunnel dropped [MAX_QUICK_CUTS] times in a row as soon as it was restored: it stays in
     * error with a message that says so. Not held: the next network connection (or a start by hand)
     * gives it its chances back.
     */
    private fun giveUpOnQuickCuts(candidate: TunnelConfig) {
        Timber.w("Tunnel coupé à répétition : reconnexion suspendue")
        // One decision under the manager's lock: a tunnel the user stopped or restarted is left alone.
        tunnelManager.markIfAwaitingReconnect(
            candidate.id,
            TunnelStatus.ERROR,
            context.getString(R.string.tunnel_error_quick_cuts),
        )
    }

    /**
     * [reconnect] for one tunnel, if no other reconnection of it is under way, and whatever happens
     * to it: one tunnel failing (database, vault) must not end the loop for the others, nor leave
     * this one marked as reconnecting for ever. [initialDelayMs] is the wait before the first
     * attempt; the next ones double it, up to 30 s.
     *
     * With another reconnection of the tunnel under way, nothing is started; if what asks is a cut
     * ([answersACut]) it is remembered ([rerun]) and answered, once, when the one under way ends. A
     * network event that finds the tunnel guarded asks for nothing: the reconnection under way is
     * already that.
     */
    private suspend fun reconnectGuarded(candidate: TunnelConfig, initialDelayMs: Long, answersACut: Boolean = false) {
        if (!reconnecting.add(candidate.id)) {
            if (answersACut) rerun.add(candidate.id)
            return
        }
        try {
            reconnect(candidate, initialDelayMs)
        } catch (e: CancellationException) {
            // The state of a cancelled reconnection is put right by reconnect() itself, which
            // knows which state is its own.
            throw e
        } catch (e: Exception) {
            // Class name only.
            Timber.w("Reconnexion interrompue par une erreur (${e::class.java.simpleName})")
            tunnelManager.markIfAwaitingReconnect(
                candidate.id,
                TunnelStatus.ERROR,
                context.getString(R.string.tunnel_error_reconnect_failed_internal),
            )
        } finally {
            reconnecting.remove(candidate.id)
        }
        // Not reached when cancelled. A cut that came during the attempt is answered now, by the same
        // rules as any cut (the series of quick cuts counts it).
        if (rerun.remove(candidate.id)) reconnectForCut(candidate.id)
    }

    private suspend fun reconnect(candidate: TunnelConfig, initialDelayMs: Long) {
        // Before marking it as reconnecting: a tunnel stopped or deleted since the list was
        // taken must not come back.
        when (verdict(candidate)) {
            Verdict.Abandon -> return
            // Turned off for this host: no loop starts, so a card put in amber by the cut goes red.
            Verdict.Disabled -> return settleAsError(candidate.id, ownsLoop = true)
            Verdict.Deleted -> return stopDeleted(candidate)
            is Verdict.Proceed -> Unit
        }
        // Why the tunnel is in error now, and later why the last attempt failed: shown on the card while
        // it reconnects, and kept for the message the tunnel ends with if every attempt fails.
        // Only a reason for a cut, or the message of an attempt of THIS loop: the stored message may be
        // the final verdict of an earlier loop ("failed after 5 attempts", "interrupted", "cut again
        // and again"), which would contradict the amber card of a loop that starts again.
        var lastReason: String? = tunnelManager.getTunnelState(candidate.id)?.errorMessage
            ?.takeIf { it in CUT_REASONS }
        // One decision under the manager's lock: a tunnel stopped, held or restarted by hand since the
        // verdict is not turned back into RECONNECTING, and then there is nothing to reconnect.
        if (!tunnelManager.markIfAwaitingReconnect(candidate.id, TunnelStatus.RECONNECTING, lastReason)) return

        // The row passed to the attempt in flight, null between attempts. The shared start marks the
        // tunnel STARTING with that very instance, which is how a cancelled reconnection tells its
        // own STARTING from one the user started meanwhile.
        var inFlight: TunnelConfig? = null
        // The row of the last reservation taken with the manager, given back on every way out (see the
        // `finally`): the reconnection held for the user, an exception of a lookup, a cancellation.
        var reserved: TunnelConfig? = null
        try {
            var delayMs = initialDelayMs
            // Attempts really made. Waiting for the network does not count: nothing was tried.
            var attempt = 0

            while (attempt < MAX_RETRIES) {
                delay(delayMs)

                // The wait is long (1 to 16 s): the user may have stopped the tunnel, started it
                // again, or a sync may have deleted it or its host. Decide again, from the stored
                // row, so that an edit made while the tunnel was in error applies too.
                val proceed = when (val verdict = verdict(candidate)) {
                    Verdict.Abandon -> return
                    Verdict.Deleted -> return stopDeleted(candidate)
                    // Turned off during the wait: back to error with the reason it had, if the card
                    // still says reconnecting (after a failed attempt it already says error).
                    Verdict.Disabled -> return tunnelManager.markReconnectInterrupted(
                        candidate.id,
                        null,
                        lastReason?.takeIf { it.isNotBlank() } ?: TUNNEL_RECONNECT_INTERRUPTED_MESSAGE,
                    )
                    is Verdict.Proceed -> verdict
                }
                val config = proceed.config

                // Offline gate: with no usable network every attempt would fail, and the five of them
                // would be burnt in half a minute, ending on a misleading "host unreachable" until the
                // network is back. No attempt is consumed: the card says so and the loop waits for the
                // network, then starts over with the initial wait. A host on this very device
                // (loopback) does not need one. Read on the spot (`hasUsableNetwork`), not from the
                // `isConnected` flag, which goes false when any network is lost.
                // The subscription to the network events is taken BEFORE the network is read (an event
                // between the two would be lost: no replay). The `Connected` that ends the wait is not
                // picked up by `reconnectErrorTunnels` (the tunnel is RECONNECTING with this loop under way: guarded), so
                // this loop is what restarts: with all its attempts again, none having been tried.
                val gate = coroutineScope {
                    val connected = async(start = CoroutineStart.UNDISPATCHED) {
                        // Bounded: the verdict and the gate are read again after each wait.
                        withTimeoutOrNull(MAX_DELAY_MS) {
                            networkMonitor.networkEvents.first { it is NetworkEvent.Connected }
                        }
                    }
                    when {
                        networkMonitor.hasUsableNetwork() || isLocalDeviceHost(proceed.hostname) -> {
                            connected.cancel()
                            Gate.OPEN
                        }
                        !tunnelManager.markIfAwaitingReconnect(
                            candidate.id,
                            TunnelStatus.RECONNECTING,
                            TUNNEL_NETWORK_LOST_MESSAGE,
                        ) -> {
                            connected.cancel()
                            Gate.GONE
                        }
                        else -> {
                            Timber.i("Reconnexion en attente du reseau")
                            connected.await()
                            Gate.WAITED
                        }
                    }
                }
                when (gate) {
                    Gate.OPEN -> Unit
                    Gate.GONE -> return
                    Gate.WAITED -> {
                        attempt = 0
                        delayMs = INITIAL_DELAY_MS
                        continue
                    }
                }

                // Reserved with the manager, in one decision with "does it still wait for a
                // reconnection". A stop by hand that lands after this (during the lookups below, or
                // while the shared StartTunnelUseCase reads the host, before its `markStarting`) is
                // seen by the manager: that start then lifts no exclusion, writes no state, and
                // fades out at the manager's `launch*` (the connection will have been opened, then
                // given back, which a few milliseconds of window can cost). The reservation is also
                // what makes a stop during the connection itself win: see
                // `SshTunnelManager.markStarting`. A deletion that lands after the verdict is still
                // for TunnelReconciler to stop, if the tunnel comes back.
                // The reason rides on the reservation: the card keeps saying why while the attempt connects.
                if (!tunnelManager.reserveReconnectStart(config, lastReason)) return
                reserved = config
                attempt++

                // Before anything that could open a prompt, the sheet of a security key included: a
                // tunnel that came from the sync or an import can carry a port that cannot be used.
                // It goes to error with the reason and is held out of the reconnection, a port does
                // not mend by itself (starting it by hand lifts the hold).
                if (tunnelManager.refuseInvalidPorts(config) != null) {
                    // The loop ends: nothing to wait for, and the port is not in the log.
                    Timber.w("Reconnexion abandonnée : port invalide (tentative $attempt)")
                    return
                }

                // After the wait, so that a user who opened the app meanwhile is prompted normally.
                if (holdUntilUserReturns(config, attempt)) return

                // A cut told so far (the tunnel restarted by hand during the wait, then cut) is
                // answered by the attempt that starts now. One that comes during it is not.
                rerun.remove(candidate.id)
                inFlight = config
                val result = startTunnel(config)
                inFlight = null
                // Stopped by hand (or replaced by a start the user made) while it connected: the
                // tunnel is not this loop's any more. Nothing is written, nothing is counted as the
                // reason of a failed attempt, nothing is held, and no other attempt follows: the
                // next verdict would find the tunnel not waiting anyway. The result of a start that
                // faded out says so by itself, even when its replacement has already finished
                // (see isStartWithdrawn).
                if (result is SshResult.Error && tunnelManager.isStartWithdrawn(config, result)) return
                if (result is SshResult.Success) {
                    // Remembered, to tell a cut of this very run from one of a run the user started.
                    // The last one registered, not the active one: a tunnel cut before the start
                    // returned is no longer active, and its cut still has to be recognised.
                    tunnelManager.lastInstanceId(candidate.id)?.let { reconnectedInstances[candidate.id] = it }
                    Timber.i("Tunnel reconnecté (tentative $attempt)")
                    return
                }
                if (result is SshResult.Error) lastReason = result.message

                // A refused credential, a missing or unreadable key, a security key prompt the user
                // cancelled or let expire: another attempt gets the same answer, and with a FIDO2
                // host it would open the key sheet again over whatever screen the user is on. A
                // host key that no longer matches is a security event, not a hiccup. Stop here. The
                // tunnel stays in error with the reason, and no network event will retry it until
                // the user starts it again (see isNonRetryable).
                if (result is SshResult.Error && tunnelManager.holdReconnectIfNonRetryable(candidate.id, result)) {
                    // The code only, a name from a closed list: the message can name a host or a port.
                    // Not always an authentication failure (a host key that changed is held too).
                    Timber.w("Reconnexion abandonnée : ${result.code} non retentable (tentative $attempt)")
                    // The user may have left while the prompt was up (or before it could start):
                    // the hold above is then as silent as the timeout it comes from.
                    tellUserWhoLeft(config, result)
                    return
                }

                // An authentication window that ran out: the vault's (VaultAuthExpiredException, which
                // the connection turns into AUTH_EXPIRED) or a biometric key's. Only the user, with
                // the fingerprint, renews it, so the next attempts of the back-off (up to 16 s apart)
                // would get the same answer. Stop at the first one. Not held, unlike the failures
                // above: nothing is wrong with the credential, and the next network event may find
                // the tunnel startable again. The tunnel is left as the shared start left it: in
                // error with the reason (it calls markError before returning), so nothing is
                // written here, which also cannot flag a tunnel the user restarted meanwhile.
                if (result is SshResult.Error && result.code == SshErrorCode.AUTH_EXPIRED) {
                    Timber.w("Reconnexion abandonnée : fenêtre d'authentification expirée (tentative $attempt)")
                    return
                }

                Timber.w("Échec reconnexion tentative $attempt/$MAX_RETRIES")
                delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
                // Between two attempts the card says RECONNECTING with the reason of the failure (the
                // failed start left it in ERROR), red only at the very end. A stop or a restart by hand
                // since is seen here and ends the loop.
                if (attempt < MAX_RETRIES &&
                    !tunnelManager.markIfAwaitingReconnect(candidate.id, TunnelStatus.RECONNECTING, lastReason)
                ) {
                    return
                }
            }

            // Only if still waiting: the user may have restarted it during the last attempt.
            tunnelManager.markIfAwaitingReconnect(candidate.id, TunnelStatus.ERROR, reconnectFailedMessage(lastReason))
        } catch (e: CancellationException) {
            // Cancelled in the wait or in the attempt (the scope that observes the network ends,
            // the service stops): the card would stay RECONNECTING or STARTING for ever. Back to
            // error, and still a candidate: unlike a start the user abandoned, a reconnection cut
            // short is retried at the next network event. Only the state that is its own is
            // touched: not a STARTING that the user started meanwhile.
            tunnelManager.markReconnectInterrupted(candidate.id, inFlight, TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)
            throw e
        } finally {
            // A reservation that no start announced is a start "on its way" for ever: the card could
            // not be cleared, and nothing would replace it. A no-op once the start was announced or
            // finished, which is what every way out of an attempt leaves behind.
            reserved?.let(tunnelManager::releaseReservation)
        }
    }

    /**
     * A host that needs the user in front of the phone (a security key to touch, a fingerprint to
     * present, see [needsUserPresence]) cannot be reconnected while the app is not on screen: its
     * prompt would open for nobody, wait 60 s and end as a timeout, with the tunnel left in error
     * and no word to the user. So no attempt is made. The tunnel is put in error with a message
     * that says what to do, held out of the automatic reconnection like any non-retryable failure
     * (no network event retries it; starting it again lifts the hold), and the notification is
     * asked to tell the user, who taps it to open the app and restart the tunnels there, where the
     * prompt shows. With the app on screen the attempt goes on as always, prompt included.
     *
     * Not held up front, because no prompt is certain: password and ordinary key hosts, and a host
     * that already has a live session, which the shared start reuses (see [LiveSessionCheck]). Such
     * a host can still ask for something, the confirmation of the key of a server it does not know
     * (the user cleared the known hosts): that prompt ends at once when nobody is on screen, the
     * tunnel is held, and the user is told by [tellUserWhoLeft].
     *
     * Returns true when the tunnel is dealt with and the loop must stop.
     */
    private suspend fun holdUntilUserReturns(config: TunnelConfig, attempt: Int): Boolean {
        if (appVisibility.isVisible()) return false
        // The host as stored now: an edit of how it signs in applies. A host that is gone is left
        // to the attempt, which says so (the verdict above had found it live a moment ago).
        val host = liveTunnels.findHost(config.hostId) ?: return false
        if (!host.needsUserPresence()) return false
        if (liveSessions.hasLiveSession(host)) return false

        // Both lookups are suspensions (the database, the session registry): the user may have
        // opened the app in them, and is then prompted normally.
        if (appVisibility.isVisible()) return false

        // And may have stopped or restarted the tunnel: then nobody is waiting for this
        // reconnection any more, nor for a notification.
        if (!tunnelManager.holdReconnect(config.id, TUNNEL_AUTH_REQUIRED_MESSAGE)) return true

        authRequiredNotifier.notifyAuthRequired(config.id, config.label)
        // No tunnel, host or label in the log, only the step.
        Timber.w("Reconnexion suspendue : authentification requise, app hors écran (tentative $attempt)")
        return true
    }

    /**
     * An attempt made with the app on screen can still end in front of nobody: the user left during
     * the handshake (the prompt then ends at once, see `NoScreen`), or after the prompt came up (the
     * system dialog of a fingerprint is dismissed when the app leaves, a security key sheet times
     * out). The code is the timeout or the cancellation of a prompt, non-retryable, and the tunnel
     * was held silently. When that happened with the app not on screen, the user is told exactly as
     * by [holdUntilUserReturns]. A cancellation with the app on screen is the user's own and says
     * nothing: nobody pressed Cancel from a hidden app.
     *
     * Not only for a host that needs a key or a finger: the host key of any host can be unknown (the
     * user cleared the known hosts), and its confirmation is a prompt too. Nobody on screen, or
     * nobody answering in time, ends it with the timeout code (see `hostKeyPromptEndCode`), and for
     * a host that needs no key or finger that is the only prompt there is, so the timeout code alone
     * says it. The cancellation is not reported there: it is the end of a connection that was
     * cancelled, whose caller is gone.
     */
    private suspend fun tellUserWhoLeft(config: TunnelConfig, error: SshResult.Error) {
        if (error.code != SshErrorCode.FIDO2_TIMEOUT && error.code != SshErrorCode.FIDO2_USER_CANCELLED) return
        if (appVisibility.isVisible()) return
        // Only if the hold really happened: the user may have restarted the tunnel meanwhile.
        if (!tunnelManager.isReconnectHeld(config.id)) return
        val host = liveTunnels.findHost(config.hostId) ?: return
        if (!host.needsUserPresence() && error.code != SshErrorCode.FIDO2_TIMEOUT) return

        // The lookup is a suspension (the database): the user may have come back in it, and is
        // then on the card that shows the error, or may have started the tunnel again.
        if (appVisibility.isVisible() || !tunnelManager.isReconnectHeld(config.id)) return
        authRequiredNotifier.notifyAuthRequired(config.id, config.label)
    }

    /**
     * Reads the database first, then asks the manager: the answer to the second question is the
     * freshest, and it is the one that tells a stop by hand from a deletion.
     *
     * A tunnel reconnects by itself only if the option is on for both: in the Settings (all hosts)
     * and on the host of the tunnel, as stored now. Both are read again before each step, so that
     * turning one off during the back-off stops the reconnection at the next one.
     */
    private suspend fun verdict(candidate: TunnelConfig): Verdict {
        val fresh = liveTunnels.findLive(candidate.id)
        if (!tunnelManager.isAwaitingReconnect(candidate.id)) return Verdict.Abandon
        if (fresh == null) return Verdict.Deleted
        if (!fresh.host.autoReconnect || !autoReconnectEnabled()) return Verdict.Disabled
        return Verdict.Proceed(fresh.tunnel, fresh.host.hostname)
    }

    /**
     * What a tunnel that ran out of attempts says: how many, and why the last one failed. The reason
     * is the message of that attempt, already in the language of the user (a connection builds its
     * messages with the application `Context`), so this one is resolved here the same way and the
     * card shows it as it is. Without a reason (a blank message) the count says it alone.
     */
    private fun reconnectFailedMessage(reason: String?): String =
        if (reason.isNullOrBlank()) {
            context.getString(R.string.tunnel_error_reconnect_failed, MAX_RETRIES.toString())
        } else {
            // A fixed text of the tunnel manager (connection lost...) is said in the user's language too.
            val said = tunnelMessageRes(reason)?.let { context.getString(it) } ?: reason
            context.getString(R.string.tunnel_error_reconnect_failed_reason, MAX_RETRIES.toString(), said)
        }

    private suspend fun stopDeleted(candidate: TunnelConfig) {
        // Gone for good: what is remembered of it goes too, or it stays for as long as the service
        // lives. Before the stop, which suspends and can be cancelled.
        reconnectedInstances.remove(candidate.id)
        quickCuts.remove(candidate.id)
        rerun.remove(candidate.id)
        // The config frozen in the state is the one that was running: it carries the label.
        val config = tunnelManager.getTunnelState(candidate.id)?.config ?: candidate
        deletedTunnelStopper.stop(listOf(config))
    }
}
