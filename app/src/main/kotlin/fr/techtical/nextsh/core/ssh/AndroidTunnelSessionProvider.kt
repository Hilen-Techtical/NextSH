// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.ssh.TunnelSessionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a host already has a session that can carry a tunnel, so that starting one needs no new
 * connection and therefore no prompt. Behind an interface so that the code that decides whether a
 * background reconnection is worth trying can be tested without the session machinery.
 */
fun interface LiveSessionCheck {
    suspend fun hasLiveSession(host: Host): Boolean
}

/**
 * Android's answer to "which SSH session does this tunnel run on": a live session of the host if
 * there is one, otherwise a new connection through the app's own [ConnectSessionUseCase]. Live means
 * CONNECTED in the registry and still connected and authenticated on the SSH client, see
 * [liveSessionId]: a registry entry whose client died is not reused.
 *
 * References. A session is counted by the [SshSessionManager] (see its note), and the tunnel that
 * will run on it is one more user. Every request gets a reference of its own on the session it is
 * answered with, taken here ([SshSessionManager.acquire]) whether the session is reused or newly
 * connected, and hands it over with the id: the shared use case passes both to the tunnel manager,
 * which owns that reference from then on and gives it back when the tunnel ends. A terminal tab
 * closing on the same session therefore leaves the tunnel alone, and a session opened only for
 * tunnels closes with the last of them.
 *
 * The connection of an attempt comes with one reference too, the attempt's own. It is not given
 * back at once but after a short linger ([LINGER_MS], 10 s), counted from the moment the result
 * has reached its waiters (each of which took its own meanwhile) or has nobody left to receive it.
 * It is released from the scope of the attempts, which outlives every caller. The linger is what
 * keeps the session for the tunnels of the same wave that have not started yet: a tunnel that fails
 * to start (its local port is in use) gives its reference back, and when that reference was the
 * last one, the session of a serial auto-start would close under the tunnels that follow, which
 * would then connect again and, for a FIDO2 or biometric host, ask the user again. The linger holds
 * the session open for them. The price is that stopping the last tunnel of a session opened for
 * tunnels closes it up to [LINGER_MS] later, not at once, if that stop comes within the linger.
 *
 * That use case is the one that knows the authentication modes of the app. The shared start of a
 * tunnel could not use its own copy: a FIDO2 host needs the security key sheet, which only the
 * Android layer can show. FIDO2 hosts and biometric key hosts are both supported: the use case
 * connects them with the security key sheet or the fingerprint dialog, which `MainActivity` shows
 * through their prompt coordinators. With the app not on screen those prompts end at once (see
 * `AppVisibility`) and the connection fails as "authentication required", unless a live session of
 * the host makes the connection, and the prompt, unnecessary ([hasLiveSession]).
 *
 * One attempt at a time per host, shared by everyone who asks meanwhile. Two tunnels of the same
 * host started together (auto-start, a reconnection after the network came back, two taps) would
 * each find no session and each open one, which for a FIDO2 host means two security key requests.
 * So the first request starts an attempt, and every request that arrives while it runs waits for
 * that same attempt and gets its outcome: the session if it succeeded, the same error if it
 * failed, including a security key sheet cancelled by the user. Nobody retries on behalf of the
 * others, so the key is asked for once per wave. A request that arrives after the attempt ended
 * is a new wave: it reuses the CONNECTED session if the attempt left one, otherwise it starts a
 * new attempt. Hosts do not wait for each other.
 *
 * Cancellation. The attempt runs in a scope of its own, not in the coroutine of the request that
 * started it, so that this request giving up does not cancel the attempt of the others. Each
 * request counts as a waiter. A cancelled request leaves, and the attempt goes on as long as
 * another request waits for it. When the last waiter leaves, the attempt is cancelled: nothing
 * keeps running for a start nobody waits for any more, and from Android 1.1.0 on no key sheet
 * stays open either (the SSH connection then attaches its pending key request to its coroutine).
 * The attempt does not run in the context of its callers: it runs on a dispatcher of its own (IO
 * in the app) and can outlive any of them, which is why this singleton owns a scope that lives as
 * long as the app.
 *
 * A cancelled attempt can take a while to stop (a blocking TCP connect does not observe the
 * cancellation). The connections of one host therefore run one at a time: every attempt holds a
 * lock of its host for the whole connection. That lock is fair and cancellable, so an attempt
 * cancelled while it waits for its turn leaves the queue at once, and each attempt waits for the
 * one still running, however many requests came and went in between. There is never more than
 * one connection in progress per host.
 *
 * An attempt can also have succeeded just as its last waiter left: its value is thrown away, but
 * the session stays registered, held by the attempt's reference for the linger. The next attempt
 * looks for a live session again once it holds the lock, before connecting, and reuses that one
 * instead of opening a second: the key is not asked twice for a connection that went through.
 */
@Singleton
class AndroidTunnelSessionProvider internal constructor(
    private val sessionManager: SshSessionManager,
    private val connectSession: ConnectSessionUseCase,
    attemptDispatcher: CoroutineDispatcher,
    /** The wait of the linger, replaceable in tests: [LINGER_MS] of the clock in the app. */
    private val linger: suspend () -> Unit,
) : TunnelSessionProvider, LiveSessionCheck {

    @Inject
    constructor(
        sessionManager: SshSessionManager,
        connectSession: ConnectSessionUseCase,
    ) : this(sessionManager, connectSession, Dispatchers.IO, { delay(LINGER_MS) })

    private val attemptScope = CoroutineScope(SupervisorJob() + attemptDispatcher)

    /**
     * A connection attempt for one host, and how many requests wait for its outcome.
     *
     * [ownedSessionId] is the session the attempt connected itself, whose reference the attempt
     * owns until it gives it back (see [giveBackOwnedReference]): null while there is none, for an
     * attempt that reused a session or failed, and again once it was given back. [done] is true
     * once [outcome] has completed, however it did.
     */
    private class Attempt(val outcome: Deferred<SshResult<String>>, val ownedSessionId: AtomicReference<String?>) {
        var waiters = 0
        var done = false
    }

    /**
     * Guards [attempts], [hostLocks] and the [Attempt.waiters] and [Attempt.done] fields. Never held
     * across a suspension, but [leave] cancels an attempt under it: dropping the last waiter and
     * cancelling must be one atomic step, or a request could join an attempt that is about to be
     * cancelled. The cancellation handlers of that attempt (the ones the connection registered
     * included) therefore run on the thread that cancels, with this lock held. They must stay short
     * and must not wait for anything that needs this provider. The references of an attempt are
     * given back outside of it.
     */
    private val lock = Any()

    // The attempt in progress per host id. An entry leaves the map when its attempt completes,
    // so the map only holds hosts being connected right now.
    private val attempts = HashMap<String, Attempt>()

    // The lock that serializes the connections of a host, see the class comment. One small entry
    // per host id ever asked for: hosts are few, and dropping a lock that an attempt may still
    // hold or wait for would let two connections run at once again.
    private val hostLocks = HashMap<String, Mutex>()

    /**
     * True when [sessionFor] would hand out a session of [host] without connecting, and so without
     * any prompt: the same rule, [liveSessionId], not a second copy of it.
     */
    override suspend fun hasLiveSession(host: Host): Boolean = liveSessionId(host) != null

    /**
     * Answers with a session of [host] and a reference on it, taken for this request and handed
     * over with the id (see the class comment). The caller owns that reference: the shared use case
     * gives it to the tunnel manager, which gives it back when the tunnel ends.
     */
    override suspend fun sessionFor(host: Host): SshResult<String> = try {
        // A live session needs no attempt, even while one is pending for this host. One that dies
        // between the look and the reference (acquire says no) is no live session: the attempt
        // below connects a new one.
        val live = liveSessionId(host)
        if (live != null && sessionManager.acquire(live)) {
            SshResult.Success(live)
        } else {
            val attempt = joinOrStart(host)
            try {
                // The attempt's own reference is given back only when the last waiter leaves (see
                // leave), so every waiter has taken its own by then.
                withReference(attempt.outcome.await())
            } finally {
                leave(attempt)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Last resort: the contract of the provider allows nothing else than a cancellation out.
        unexpectedFailure(e)
    }

    /**
     * [outcome] with a reference of the request's own on its session, or an error if the session
     * died since the attempt answered. An error outcome is passed on as it is, the very same
     * object for every waiter.
     */
    private fun withReference(outcome: SshResult<String>): SshResult<String> = when {
        outcome !is SshResult.Success -> outcome
        sessionManager.acquire(outcome.data) -> outcome
        else -> SshResult.Error(SshErrorCode.HOST_UNREACHABLE, SESSION_LOST_MESSAGE)
    }

    /**
     * The attempt in progress for the host, or a new one if there is none. A request counts as a
     * waiter from here on: the caller must [leave] when it stops waiting.
     */
    private fun joinOrStart(host: Host): Attempt = synchronized(lock) {
        // A cancelled attempt may still be winding down, holding the lock of the host: the new
        // one queues behind it, see startAttempt.
        val current = attempts[host.id]?.takeIf { it.outcome.isActive }
        val attempt = current ?: startAttempt(host)
        attempt.waiters++
        // Registered once the first waiter is counted: an attempt that completes at once must not
        // find itself without waiters and give its session back before anyone has taken it.
        if (current == null) attempt.outcome.invokeOnCompletion { completed(host.id, attempt) }
        attempt
    }

    /** Called with [lock] held. */
    private fun startAttempt(host: Host): Attempt {
        val hostLock = hostLocks.getOrPut(host.id) { Mutex() }
        val owned = AtomicReference<String?>(null)
        val outcome = attemptScope.async { hostLock.withLock { connect(host, owned) } }
        val attempt = Attempt(outcome, owned)
        attempts[host.id] = attempt
        return attempt
    }

    /**
     * A completed attempt leaves the map, unless a newer one already took its place. If no waiter
     * is left to receive its session, whoever it was meant for is gone: its reference goes back.
     */
    private fun completed(hostId: String, attempt: Attempt) {
        val orphaned = synchronized(lock) {
            if (attempts[hostId] === attempt) attempts.remove(hostId)
            attempt.done = true
            attempt.waiters == 0
        }
        if (orphaned) giveBackOwnedReference(attempt.ownedSessionId)
    }

    /**
     * A waiter stops waiting. The last one out cancels the attempt, if it is still running (its
     * completion then gives the session back, see [completed]), and gives the attempt's own
     * reference back if it has completed: the waiters took theirs before they left, so the session
     * is now held by them alone. The cancel stays under [lock], see there.
     */
    private fun leave(attempt: Attempt) {
        val lastOne = synchronized(lock) {
            attempt.waiters--
            val last = attempt.waiters == 0
            if (last && attempt.outcome.isActive) attempt.outcome.cancel()
            last && attempt.done
        }
        if (lastOne) giveBackOwnedReference(attempt.ownedSessionId)
    }

    /**
     * Gives back the reference the attempt owns on the session it connected, once, after the linger
     * (see the class comment). From the scope of the attempts: it must outlive the request that
     * happens to be the last one out, and the attempt itself.
     */
    private fun giveBackOwnedReference(owned: AtomicReference<String?>) {
        val sessionId = owned.getAndSet(null) ?: return
        attemptScope.launch {
            // In a finally: whatever ends the wait, the reference must go back.
            try {
                linger()
            } finally {
                sessionManager.release(sessionId)
            }
        }
    }

    /**
     * Body of an attempt, run with the lock of the host held. Never throws anything but the
     * cancellation of the attempt itself, so that every waiter gets the same result.
     *
     * A session this attempt connects is noted in [owned] at once: from there on the attempt owns
     * its reference, until [giveBackOwnedReference].
     */
    private suspend fun connect(host: Host, owned: AtomicReference<String?>): SshResult<String> = try {
        // Looked up again here, now that the lock is ours: a session of this host may have come
        // up since the request looked, and a second connection would ask for the security key
        // again. That session is not ours: the waiters take their own references on it.
        val live = liveSessionId(host)
        if (live != null) {
            SshResult.Success(live)
        } else {
            // The connection use case folds every exception, a cancellation included, into an
            // error result. When this attempt was cancelled, nobody waits for it any more and its
            // deferred drops whatever is returned here, so there is nothing to tell apart.
            when (val result = connectSession(host)) {
                is SshResult.Success -> {
                    owned.set(result.data.id)
                    // Cancelled while it connected: nobody is left to receive this session. Its
                    // reference starts its linger now, before the lock of the host is given up:
                    // the next attempt of the host then finds the session live for the whole
                    // linger, and never one that is about to close.
                    if (!currentCoroutineContext().isActive) giveBackOwnedReference(owned)
                    SshResult.Success(result.data.id)
                }
                is SshResult.Error -> result
            }
        }
    } catch (e: Exception) {
        // The cancellation of the attempt itself goes on and is not a failure to log. Anything
        // else, a cancellation raised inside the connection (a timeout for instance) included,
        // becomes one error, caught here and not by each waiter, so that they all get the very
        // same outcome and the failure is logged once.
        currentCoroutineContext().ensureActive()
        unexpectedFailure(e)
    }

    /**
     * The id of a session of [host] that can carry a tunnel now, or null. The registered status is
     * not enough: it is updated when the disconnection is noticed, which can trail a client that
     * has just died, and a tunnel started on that session would fail. So the client itself is
     * asked: connected, and authenticated (the second is a cheap defensive check, a client only
     * enters the registry once authenticated, see [SshSessionManager.isAuthenticated]).
     *
     * A session that fails the check is left aside. The session manager drops a dead session from
     * its registry as soon as its transport reports the loss, so this only meets one in the short
     * time before that, and [SshSessionManager.acquire], which the callers use next, drops it for
     * good if the client is really gone.
     *
     * Residual race, accepted: the client can die just after this check. The tunnel then fails
     * with a retryable error, or the loss is caught when SSHJ reports it to the transport listener
     * of the session manager (end of the reader, failed write), which ends the tunnels of the session
     * ([SessionEndListener]), or by the liveness watchdog ([TunnelLivenessWatchdog]) once a tunnel
     * runs on it: a mute link is declared dead after about a minute at the default cadence. SSHJ's own
     * keepalive thread also runs on every session (see SshSessionManager). A session that carries no tunnel is not watched, so a
     * session left over by a terminal alone can still be proposed here while its link is mute: the
     * watchdog then catches it once the new tunnel is active.
     */
    private suspend fun liveSessionId(host: Host): String? =
        sessionManager.sessions.first()
            .firstOrNull {
                it.host.id == host.id &&
                    it.status == SessionStatus.CONNECTED &&
                    sessionManager.isConnected(it.id) &&
                    sessionManager.isAuthenticated(it.id)
            }
            ?.id

    private fun unexpectedFailure(e: Exception): SshResult.Error {
        // Class name only: the message of an exception from the vault or the connection could
        // carry a secret, and neither it nor the host is logged or shown.
        Timber.w("Tunnel session attempt failed: ${e.javaClass.simpleName}")
        return SshResult.Error(SshErrorCode.UNKNOWN, UNEXPECTED_FAILURE_MESSAGE)
    }

    companion object {
        /**
         * How long the reference of an attempt outlives the hand-out of its session, see the class
         * comment. Long enough for the other tunnels of a serial auto-start to find the session,
         * short enough that a session opened for tunnels that were all stopped does not stay.
         */
        const val LINGER_MS = 10_000L

        /** Fixed text, translated at display time by the card of the tunnel: see [tunnelMessageRes]. */
        private const val UNEXPECTED_FAILURE_MESSAGE = TUNNEL_CONNECT_UNEXPECTED_MESSAGE

        /** The session answered with died before the request could take its reference on it. */
        private const val SESSION_LOST_MESSAGE = TUNNEL_SESSION_LOST_MESSAGE
    }
}
