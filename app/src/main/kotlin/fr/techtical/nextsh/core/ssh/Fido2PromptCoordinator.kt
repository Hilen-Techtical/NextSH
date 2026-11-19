// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.core.auth.HwKeyError
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton coordinating the FIDO2 (sk-*) signature prompt across the whole app.
 *
 * Why this exists: the SSH layer asks for a signature through
 * [SshSessionManager.fido2ChallengeCallback], and that callback used to be installed in
 * `SessionViewModel.init` only. A connection started from the host list, from the SFTP entry or
 * from a tunnel never creates a `SessionViewModel`, so the callback was null and the connection
 * failed with "FIDO2 callback not configured". When the ViewModel had been created and was gone
 * since, the callback pointed at a dead ViewModel whose sheet was not on screen, and the
 * connection waited 60 s for nothing. Same story as [HostKeyPromptCoordinator], whose app-wide dialog
 * this sheet was modeled on; that coordinator has since taken what this one has (the shared turn, the
 * deadline, the end at once when the app is not on screen).
 *
 * The callback is now installed once, by `App.onCreate` ([install]), and the bottom sheet is
 * rendered at `MainActivity` level, over the whole NavGraph, from [state].
 *
 * One prompt at a time: a second request (two FIDO2 connections started together, for example
 * two tunnels) waits for its turn behind the first one, then gets its own sheet and its own 60 s.
 * A request that waits is cancelled with its coroutine, nothing is shown for it.
 *
 * Sensitive data: the request carries the data to sign and the rpId. Nothing of it is logged, and
 * the data to sign is zeroed as soon as the prompt ends, whatever the outcome (signed, cancelled,
 * timed out, coroutine cancelled).
 *
 * When the app is not on screen at the moment of the request (a reconnect driven by a service),
 * nobody can answer: the request ends at once with [Fido2ChallengeResponse.NoScreen], without a
 * sheet and without taking the turn, instead of waiting for a deadline nobody can beat. A prompt
 * that is already on screen when the app leaves it still ends by its deadline
 * ([PROMPT_TIMEOUT_MS] after it appeared).
 *
 * The deadline is part of the state ([Fido2Prompt.deadlineMs]), and it is the only clock: the sheet
 * draws its countdown from it, so a sheet recreated by a rotation shows the real time left, and it
 * cannot vanish while the countdown still reads seconds. Entering a PIN takes time the user needs:
 * the sheet asks for more through [extendDeadline].
 *
 * The turn is shared with the fingerprint prompt ([BiometricPromptCoordinator]) through
 * [InteractivePromptTurn]: a FIDO2 sheet and a fingerprint dialog are never on screen together.
 *
 * The clock and the visibility are injectable for tests; the app uses the monotonic clock of the
 * JVM and the real [AppVisibility].
 */
@Singleton
class Fido2PromptCoordinator internal constructor(
    private val interactiveTurn: InteractivePromptTurn,
    private val clock: () -> Long,
    private val appVisibility: AppVisibility,
) {

    @Inject
    constructor(interactiveTurn: InteractivePromptTurn, appVisibility: AppVisibility) :
        this(interactiveTurn, { System.nanoTime() / NANOS_PER_MILLI }, appVisibility)

    /** For tests: the app is on screen, on the given clock and turn. */
    internal constructor(interactiveTurn: InteractivePromptTurn, clock: () -> Long) :
        this(interactiveTurn, clock, { true })

    /** For tests: an app on screen, a turn of its own, on the monotonic clock. */
    internal constructor(interactiveTurn: InteractivePromptTurn) :
        this(interactiveTurn, { System.nanoTime() / NANOS_PER_MILLI })

    /** For tests: an app on screen, a turn of its own, on the given clock. */
    internal constructor(clock: () -> Long) : this(InteractivePromptTurn(), clock)

    /** For tests: an app on screen, a turn of its own, on the monotonic clock. */
    internal constructor() : this(InteractivePromptTurn())

    private val _state = MutableStateFlow<Fido2Prompt?>(null)

    /**
     * The prompt to show now, or null. The UI renders a sheet for it and answers through
     * [complete] or [cancel]; the request inside stays the same for the whole prompt, only its
     * deadline can move ([extendDeadline]).
     */
    val state: StateFlow<Fido2Prompt?> = _state.asStateFlow()

    /** Guards [pending] and [_state] against an answer racing the end of the prompt. */
    private val lock = Any()
    private var pending: CompletableDeferred<Fido2ChallengeResponse?>? = null

    /** When the prompt on screen appeared, on [clock]: the ceiling of [extendDeadline] counts from it. */
    private var shownAtMs = 0L

    /** The time, in milliseconds, of the clock [Fido2Prompt.deadlineMs] is measured on. */
    fun nowMs(): Long = clock()

    /** Installs this coordinator as the FIDO2 callback of [sessionManager]. Called once at app start. */
    fun install(sessionManager: SshSessionManager) {
        sessionManager.fido2ChallengeCallback = { request -> prompt(request) }
    }

    /**
     * Shows the prompt for [request] and suspends until it is answered, cancelled, its deadline
     * passes, or the calling coroutine is cancelled. Returns the signature,
     * [Fido2ChallengeResponse.Cancelled], or null on timeout. When the app is not on screen at the
     * time of the call, returns [Fido2ChallengeResponse.NoScreen] at once: nothing is shown, the
     * turn is not taken (a fingerprint prompt, or another request, is not held up by it) and the
     * data to sign is zeroed. The same is checked again once the turn is taken: a request that
     * waited behind another prompt may find the app left by the time its turn comes.
     */
    suspend fun prompt(request: Fido2ChallengeRequest): Fido2ChallengeResponse? {
        if (!appVisibility.isVisible()) return endWithoutScreen(request)
        return promptOnScreen(request)
    }

    /** Nobody can answer: nothing was shown, and the data to sign goes like it does when a prompt ends. */
    private fun endWithoutScreen(request: Fido2ChallengeRequest): Fido2ChallengeResponse {
        request.signingData.fill(0)
        return Fido2ChallengeResponse.NoScreen
    }

    private suspend fun promptOnScreen(request: Fido2ChallengeRequest): Fido2ChallengeResponse? = interactiveTurn.withTurn {
        // The wait for the turn can be long (another prompt lasts up to a minute), and the user may
        // have left in it. A prompt published now would hold the turn, and the SSH transport behind
        // it, for a deadline nobody can beat.
        if (!appVisibility.isVisible()) return@withTurn endWithoutScreen(request)
        val deferred = CompletableDeferred<Fido2ChallengeResponse?>()
        synchronized(lock) {
            pending = deferred
            shownAtMs = clock()
            _state.value = Fido2Prompt(request, deadlineMs = shownAtMs + PROMPT_TIMEOUT_MS)
        }
        try {
            // The sheet drives the security key and answers the deferred; nobody answering means
            // the deadline ends the prompt.
            awaitAnswerUntilDeadline(deferred, request)
        } catch (e: CancellationException) {
            // The caller gave up. A signature that slipped in at the last moment has no taker.
            (retire(deferred, request) as? Fido2ChallengeResponse.Signed)?.signature?.close()
            throw e
        }
        // Even when the deadline passed, an answer that landed before the prompt was retired is kept.
        retire(deferred, request)
    }

    /**
     * Waits for the answer, or for the deadline of [request], which can move while we wait: when
     * a wait ends on the deadline it had, the deadline is read again before giving up.
     */
    private suspend fun awaitAnswerUntilDeadline(
        deferred: CompletableDeferred<Fido2ChallengeResponse?>,
        request: Fido2ChallengeRequest,
    ) {
        while (true) {
            val deadline = synchronized(lock) { _state.value?.takeIf { it.request === request }?.deadlineMs } ?: return
            val remaining = deadline - clock()
            if (remaining <= 0L) return
            val answered = withTimeoutOrNull(remaining) { deferred.await() } != null || deferred.isCompleted
            if (answered) return
        }
    }

    /**
     * Gives the prompt of [request] [extensionMs] from now to finish, if that is later than its
     * deadline: the deadline never moves closer. Used while the user types a PIN, which the
     * countdown must not cut short. Only the prompt on screen is concerned: a request that is
     * queued, over, or not the current one is ignored.
     *
     * The deadline never goes past [MAX_PROMPT_TOTAL_MS] after the prompt appeared, however many
     * times it is extended: this is the clock of the user's time with the key, and the SSH
     * transport's wait for the answer is sized on it (`FIDO2_AUTH_TRANSPORT_TIMEOUT_MS` is this
     * ceiling plus the server's 30 s), so it must end.
     */
    fun extendDeadline(request: Fido2ChallengeRequest, extensionMs: Long) {
        synchronized(lock) {
            val current = _state.value ?: return
            if (current.request !== request || pending == null) return
            val extended = minOf(
                maxOf(current.deadlineMs, clock() + extensionMs),
                shownAtMs + MAX_PROMPT_TOTAL_MS,
            )
            if (extended != current.deadlineMs) _state.value = current.copy(deadlineMs = extended)
        }
    }

    /** The security key signed [request]: hands [signature] to the waiting connection. */
    fun complete(request: Fido2ChallengeRequest, signature: SkSshSignature) {
        val accepted = answer(request, Fido2ChallengeResponse.Signed(signature))
        // Nobody is waiting for it any more (timeout, cancel): do not leave the signature around.
        if (!accepted) signature.close()
    }

    /** The user cancelled [request]. */
    fun cancel(request: Fido2ChallengeRequest) {
        answer(request, Fido2ChallengeResponse.Cancelled)
    }

    /**
     * Nobody is in front of the app any more, though it is on screen: the lock screen came up (see
     * `PromptsEndedByLock`). The prompt that is up ends at once as if the app had been left when it
     * was asked for, with [Fido2ChallengeResponse.NoScreen]: the sheet goes away, the data to sign is
     * zeroed, and the turn is released instead of being held, with the SSH transport behind it, for
     * a deadline nobody can beat. Nothing is up: nothing happens.
     */
    fun endForAbsence() {
        synchronized(lock) {
            val request = _state.value?.request ?: return
            answer(request, Fido2ChallengeResponse.NoScreen)
        }
    }

    /**
     * The security key failed for [request], for [reason] (communication error, key removed, PIN
     * blocked, refused by the key; a key that never shows up is no failure of the key, the
     * prompt's deadline ends that wait). The connection fails as an authentication failure, not
     * as a cancellation the user never made, with the message that tells why: the sheet showed
     * the reason for two seconds, the connection keeps it.
     */
    fun failKey(request: Fido2ChallengeRequest, reason: HwKeyError) {
        answer(request, Fido2ChallengeResponse.KeyError(reason))
    }

    /** Answers the prompt only if [request] is still the one on screen: a late answer of a closed sheet is dropped. */
    private fun answer(request: Fido2ChallengeRequest, response: Fido2ChallengeResponse): Boolean =
        synchronized(lock) {
            val deferred = pending
            deferred != null && _state.value?.request === request && deferred.complete(response)
        }

    /** Ends the prompt: hides it, zeroes the data to sign, returns the answer that arrived, if any. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun retire(
        deferred: CompletableDeferred<Fido2ChallengeResponse?>,
        request: Fido2ChallengeRequest,
    ): Fido2ChallengeResponse? {
        val answer = synchronized(lock) {
            if (pending === deferred) pending = null
            if (_state.value?.request === request) _state.value = null
            if (deferred.isCompleted) deferred.getCompleted() else null
        }
        request.signingData.fill(0)
        return answer
    }

    companion object {
        /** Time the user has to touch the key, as before the move to this coordinator. */
        const val PROMPT_TIMEOUT_MS = 60_000L

        /**
         * Longest a prompt can last in all, extensions included, counted from the moment it appears.
         * Several PIN attempts (3 at most per assertion) fit in it with room to spare.
         */
        const val MAX_PROMPT_TOTAL_MS = 5 * 60_000L

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * The FIDO2 prompt on screen: the [request] to sign, and the [deadlineMs] after which it gives up,
 * on the clock of [Fido2PromptCoordinator.nowMs]. Only the deadline changes during a prompt.
 */
data class Fido2Prompt(
    val request: Fido2ChallengeRequest,
    val deadlineMs: Long,
) {
    /** Time left at [nowMs], never negative. */
    fun remainingMs(nowMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0L)
}
