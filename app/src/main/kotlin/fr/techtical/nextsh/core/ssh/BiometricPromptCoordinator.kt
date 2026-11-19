// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import androidx.biometric.BiometricPrompt
import fr.techtical.nextsh.core.AppVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton coordinating the system biometric dialog across the whole app: the fingerprint prompt of
 * the biometric (Android Keystore) SSH keys, and the proof of the owner that the vault asks for (the
 * unlock, the confirmation of a save, see [VaultAuthRequest]). Same story, same cure as
 * [Fido2PromptCoordinator] and [HostKeyPromptCoordinator],
 * which share its turn, its deadline and its end when the app is not on screen:
 * the SSH layer asks through [SshSessionManager.biometricSignatureCallback], which `App.onCreate`
 * installs once ([install]), and the dialog is started by `BiometricPromptHost` at `MainActivity`
 * level from [state], so a connection started from the host list, the terminal, SFTP or a tunnel
 * all get their fingerprint prompt, and a connection that is cancelled takes its prompt away.
 *
 * One prompt at a time, and one turn shared with the FIDO2 sheet ([InteractivePromptTurn]): a
 * request that waits for its turn is cancelled with its coroutine and shows nothing, and when its
 * turn comes it gets its own [PROMPT_TIMEOUT_MS], counted from the moment it appears.
 *
 * Answers are accepted for the request on screen only ([authorize], [cancel], [fail], [timeOut],
 * [reportError]): the callback of a dialog that is already gone cannot answer for the next one.
 *
 * The deadline is part of the state ([BiometricPromptState.deadlineMs]) and the only clock: when it
 * passes, the prompt ends with a null answer and the host takes the system dialog away. A request
 * is published with [PROMPT_TIMEOUT_MS] to be launched (a prompt published while the app was on
 * screen and left before its dialog started ends here; one requested while the app is hidden never
 * gets this far, see [BiometricPromptResponse.NoScreen]); when the host launches the dialog
 * ([markLaunched]) the deadline moves to a full [PROMPT_TIMEOUT_MS] from that moment, never past
 * [MAX_PROMPT_TOTAL_MS] after the publication. So a request published while the app was hidden and
 * launched at second 59 does not flash for one second, and the SSH transport wait, sized on the
 * longest FIDO2 prompt, still covers the longest life of a request. There is no other extension,
 * unlike the FIDO2 sheet: no PIN is typed here, a finger takes seconds.
 *
 * The dialog is started once per request ([markLaunched]), not once per composition, and the
 * coordinator remembers which dialog is believed to be up ([openDialog], [markClosed]) even after
 * its request ended: the terminal allows rotation, `MainActivity` is recreated while the system
 * dialog stays up, and the new host must neither start a second dialog for the request nor forget
 * a dialog it has to close. A new dialog is not launched while the previous one is not confirmed
 * closed: androidx keeps one `BiometricPrompt` per activity, and a second `authenticate()` while
 * the first is still going away is silently dropped.
 *
 * Known limit, rare: the time a request spends in the queue is spent with the SSH session waiting at
 * PK_OK: a server's `LoginGraceTime` (120 s by default with OpenSSH) can drop it behind two
 * unanswered prompts. Every dialog of the app goes through here now, so nothing else steps on the
 * single callback slot of androidx (`BiometricHelper` no longer shows any prompt).
 *
 * Sensitive data: a signature request carries a Keystore handle and the labels for the dialog.
 * Nothing of it is logged ([BiometricSignRequest.toString] hides the labels). The data to sign never
 * reaches this class.
 *
 * When the app is not on screen at the moment of a signature request (a reconnect driven by a
 * service, or the lock screen up, see [AppVisibility]), nobody can present a finger: the request
 * ends at once with [BiometricPromptResponse.NoScreen], without a dialog and without taking the
 * turn. The vault request does not ask: [authenticateVault] is called by someone who has just
 * touched the screen, and the lock screen is itself the one asking for the unlock.
 *
 * The clock and the visibility are injectable for tests; the app uses the monotonic clock of the
 * JVM and the real [AppVisibility].
 */
@Singleton
class BiometricPromptCoordinator internal constructor(
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

    private val _state = MutableStateFlow<BiometricPromptState?>(null)

    /**
     * The prompt to show now, or null. The UI starts the system dialog for it and answers through
     * [authorize], [cancel], [fail], [timeOut] or [reportError]; the request inside stays the same
     * for the whole prompt.
     */
    val state: StateFlow<BiometricPromptState?> = _state.asStateFlow()

    private val _vaultRequestEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Says, once per request, that a vault request ended, however it did: answered, cancelled, timed out,
     * or given up by its caller. A signature request does not say it. The lock listens to it to start its
     * clock when the owner's dialog ends while the app is not on screen (see `OwnerPromptEndedAway`).
     */
    val vaultRequestEnded: SharedFlow<Unit> = _vaultRequestEnded.asSharedFlow()

    /** Guards the fields below and [_state] against an answer racing the end of the prompt. */
    private val lock = Any()
    private var pending: CompletableDeferred<BiometricPromptResponse?>? = null

    /** When the prompt on screen was published, on [clock]: [MAX_PROMPT_TOTAL_MS] counts from it. */
    private var shownAtMs = 0L

    /** The last request whose dialog was started (see [markLaunched]): the dialog of a request starts once. */
    private var launchedFor: BiometricRequest? = null

    /**
     * The request whose system dialog is believed to be up, until a host confirms it closed
     * ([markClosed]). Unlike the state, it survives the end of its request: a dialog left up
     * swallows every later `authenticate()` of the activity, so whoever hosts next must find it.
     */
    private var openRequest: BiometricRequest? = null


    /** Installs this coordinator as the fingerprint callback of [sessionManager]. Called once at app start. */
    fun install(sessionManager: SshSessionManager) {
        sessionManager.biometricSignatureCallback = { request -> prompt(request) }
    }

    /**
     * Shows the prompt for [request] and suspends until it is answered, its deadline passes, or the
     * calling coroutine is cancelled. Returns the answer, or null when nobody answered in time
     * (including the system's own timeout, see [timeOut]). When the app is not on screen at the
     * time of the call, returns [BiometricPromptResponse.NoScreen] at once: no dialog is started and
     * the turn is not taken (a FIDO2 sheet, or another request, is not held up by it). The same is
     * checked again once the turn is taken: a request that waited behind another prompt may find the
     * app left by the time its turn comes.
     */
    suspend fun prompt(request: BiometricSignRequest): BiometricPromptResponse? {
        if (!appVisibility.isVisible()) return BiometricPromptResponse.NoScreen
        return promptOnScreen(request, checkVisibility = true)
    }

    /**
     * Asks the owner of the phone to prove it, for the vault, and suspends until the dialog is
     * answered, its deadline passes, or the calling coroutine is cancelled. Same turn and same
     * deadline as [prompt], and the same answers: [BiometricPromptResponse.Authorized] on a success,
     * [BiometricPromptResponse.Cancelled] when the user closed the dialog (or the system did, the app
     * having left), [BiometricPromptResponse.Failed], null when nobody answered in time.
     *
     * No visibility test, neither at the call nor when the turn comes: the caller is a screen the user
     * has just touched, and the lock screen, which makes the app count as absent for every other
     * prompt, is itself the one asking here. A request whose activity is not resumed when its turn
     * comes is never launched and ends at its deadline.
     */
    suspend fun authenticateVault(purpose: VaultAuthPurpose): BiometricPromptResponse? =
        promptOnScreen(VaultAuthRequest(purpose), checkVisibility = false)

    private suspend fun promptOnScreen(
        request: BiometricRequest,
        checkVisibility: Boolean,
    ): BiometricPromptResponse? = interactiveTurn.withTurn {
        // The wait for the turn can be long (another prompt lasts up to a minute), and the user may
        // have left in it. A prompt published now would hold the turn, and the SSH transport behind
        // it, for a deadline nobody can beat.
        if (checkVisibility && !appVisibility.isVisible()) return@withTurn BiometricPromptResponse.NoScreen
        val deferred = CompletableDeferred<BiometricPromptResponse?>()
        synchronized(lock) {
            pending = deferred
            shownAtMs = clock()
            _state.value = BiometricPromptState(request, deadlineMs = shownAtMs + PROMPT_TIMEOUT_MS)
        }
        try {
            // The host starts the dialog and answers the deferred; nobody answering means the
            // deadline ends the prompt.
            awaitAnswerUntilDeadline(deferred, request)
        } catch (e: CancellationException) {
            // The caller gave up: the prompt goes with it.
            retire(deferred, request)
            throw e
        }
        // Even when the deadline passed, an answer that landed before the prompt was retired is kept.
        retire(deferred, request)
    }

    /**
     * Waits for the answer, or for the deadline of [request], which can move while we wait (see
     * [markLaunched]): when a wait ends on the deadline it had, the deadline is read again before
     * giving up.
     */
    private suspend fun awaitAnswerUntilDeadline(
        deferred: CompletableDeferred<BiometricPromptResponse?>,
        request: BiometricRequest,
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
     * True the first time it is called for the prompt of [request] while it is on screen and no
     * other dialog is believed to be up, false otherwise. The host starts the system dialog only
     * when this says true: a host recreated by a rotation sees the same request again and the
     * dialog it started is still showing; a request must wait for the dialog before it to be
     * confirmed closed ([markClosed]).
     *
     * When it says true the dialog gets its own time: the deadline moves to [PROMPT_TIMEOUT_MS] from
     * now, within [MAX_PROMPT_TOTAL_MS] of the publication, and never closer than it was. For a vault
     * request it is [VAULT_DIALOG_TIMEOUT_MS] from now, within [MAX_VAULT_PROMPT_TOTAL_MS].
     */
    fun markLaunched(request: BiometricRequest): Boolean = synchronized(lock) {
        val current = _state.value
        if (pending == null || current == null || current.request !== request || launchedFor === request || openRequest != null) {
            false
        } else {
            launchedFor = request
            openRequest = request
            // A vault dialog takes the phone code, typed at the pace of the person, and nobody waits
            // behind it with a transport: it has [VAULT_DIALOG_TIMEOUT_MS] once launched.
            val deadline = if (request is VaultAuthRequest) {
                minOf(clock() + VAULT_DIALOG_TIMEOUT_MS, shownAtMs + MAX_VAULT_PROMPT_TOTAL_MS)
            } else {
                minOf(clock() + PROMPT_TIMEOUT_MS, shownAtMs + MAX_PROMPT_TOTAL_MS)
            }
            if (deadline > current.deadlineMs) _state.value = current.copy(deadlineMs = deadline)
            true
        }
    }

    /**
     * The request whose system dialog is believed to be up, or null. A host that takes over after a
     * rotation adopts it: the dialog is still showing, and it is the new host that must close it if
     * its request has ended in the meantime. It outlives the request on purpose, see [markClosed].
     */
    fun openDialog(): BiometricRequest? = synchronized(lock) { openRequest }

    /**
     * The host confirms that the system dialog of [request] is closed (it answered, or it was
     * cancelled and said so, or the host gave up waiting for it): the next request may launch its
     * own. Anything but the request believed open is ignored.
     */
    fun markClosed(request: BiometricRequest) {
        synchronized(lock) { if (openRequest === request) openRequest = null }
    }

    /** The owner was recognized for [request]: the signature may sign now, or the vault may open. */
    fun authorize(request: BiometricRequest) {
        answer(request, BiometricPromptResponse.Authorized)
    }

    /** The user cancelled [request], or the system did when the app was left. */
    fun cancel(request: BiometricRequest) {
        answer(request, BiometricPromptResponse.Cancelled)
    }

    /**
     * Nobody is in front of the app any more, though it is on screen: the lock screen came up (see
     * `PromptsEndedByLock`). A signature prompt that is up ends at once as if the app had been left
     * when it was asked for, with [BiometricPromptResponse.NoScreen]: the system dialog is taken away,
     * and the turn is released instead of being held, with the SSH transport behind it, for a
     * deadline nobody can beat. The vault request is not concerned: it is the lock screen's own
     * prompt, or the confirmation of a save that the user is in the middle of, and it goes on. Nothing
     * is up, or it is a vault request: nothing happens.
     */
    fun endSignaturesForAbsence() {
        synchronized(lock) {
            val request = _state.value?.request
            if (request is BiometricSignRequest) answer(request, BiometricPromptResponse.NoScreen)
        }
    }

    /**
     * The fingerprint cannot be asked for [request], for [reason]. The connection fails as an
     * authentication failure that says why, not as a cancellation the user never made.
     */
    fun fail(request: BiometricRequest, reason: BiometricFailure) {
        answer(request, BiometricPromptResponse.Failed(reason))
    }

    /** The system gave up waiting for a finger on [request]: the connection reads it as a prompt nobody answered. */
    fun timeOut(request: BiometricRequest) {
        answer(request, null)
    }

    /**
     * The system dialog of [request] ended with the `BiometricPrompt.ERROR_*` code [errorCode]:
     * answers with what it means, see [biometricResponseForError]. Only the number goes through
     * here, never the text the system gives with it.
     */
    fun reportError(request: BiometricRequest, errorCode: Int) {
        answer(request, biometricResponseForError(errorCode))
    }

    /** Answers the prompt only if [request] is still the one on screen: a late answer of a closed dialog is dropped. */
    private fun answer(request: BiometricRequest, response: BiometricPromptResponse?): Boolean =
        synchronized(lock) {
            val deferred = pending
            deferred != null && _state.value?.request === request && deferred.complete(response)
        }

    /** Ends the prompt: hides it, returns the answer that arrived, if any. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun retire(
        deferred: CompletableDeferred<BiometricPromptResponse?>,
        request: BiometricRequest,
    ): BiometricPromptResponse? = synchronized(lock) {
        if (pending === deferred) pending = null
        if (_state.value?.request === request) _state.value = null
        // Its dialog was started once, and a request that ended cannot be launched again (it is not
        // the one on screen any more): nothing is left to remember, and the request, with its labels
        // and its authorized Signature, must not stay reachable from here. `openRequest` is another
        // matter, it outlives the request on purpose until a host confirms the dialog closed.
        if (launchedFor === request) launchedFor = null
        if (request is VaultAuthRequest) _vaultRequestEnded.tryEmit(Unit)
        if (deferred.isCompleted) deferred.getCompleted() else null
    }

    companion object {
        /**
         * Time a request has to be launched when it is published, and the user has to present a
         * finger once it is launched.
         */
        const val PROMPT_TIMEOUT_MS = 60_000L

        /**
         * Longest a request lives, counted from its publication: waiting for the host, then the
         * dialog. Well under the FIDO2 maximum, which the SSH transport wait is sized on
         * (`FIDO2_AUTH_TRANSPORT_TIMEOUT_MS`).
         */
        const val MAX_PROMPT_TOTAL_MS = 2 * PROMPT_TIMEOUT_MS

        /**
         * Time a vault dialog has once it is launched. It takes the phone code as well as a finger, and
         * a code typed slowly must not be closed under the fingers of the person after a minute. Only a
         * dialog that is up gets it: a vault request that is not launched ends at [PROMPT_TIMEOUT_MS]
         * after its publication, as any other, and the turn it holds is still protected by that.
         */
        const val VAULT_DIALOG_TIMEOUT_MS = 3 * 60_000L

        /**
         * Longest a vault request lives, counted from its publication: [PROMPT_TIMEOUT_MS] to be
         * launched, then [VAULT_DIALOG_TIMEOUT_MS]. No SSH transport waits on it, so it is not bound
         * by the size of [MAX_PROMPT_TOTAL_MS].
         */
        const val MAX_VAULT_PROMPT_TOTAL_MS = PROMPT_TIMEOUT_MS + VAULT_DIALOG_TIMEOUT_MS

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * The system dialog on screen: the [request] to authorize (a signature, or the owner for the vault),
 * and the [deadlineMs] after which it gives up, on the monotonic clock of the coordinator. Only the
 * deadline changes during a prompt, once, when the dialog is launched
 * ([BiometricPromptCoordinator.markLaunched]). The system dialog has no countdown, so the host never
 * needs the time left: the coordinator ends the prompt itself.
 */
data class BiometricPromptState(
    val request: BiometricRequest,
    val deadlineMs: Long,
)

/**
 * What a `BiometricPrompt.ERROR_*` code means for the connection, or null for the system's own
 * timeout (the prompt reads as one nobody answered).
 *
 * - The user pressed the negative button or went back (`ERROR_NEGATIVE_BUTTON`, `ERROR_USER_CANCELED`),
 *   or the system cancelled the dialog because the app left the screen (`ERROR_CANCELED`):
 *   [BiometricPromptResponse.Cancelled].
 * - `ERROR_TIMEOUT`: null.
 * - A sensor locked for a moment, or until the phone is unlocked with its code, no enrolled
 *   fingerprint, a sensor busy for now (`ERROR_HW_UNAVAILABLE`) or one the phone does not have
 *   (`ERROR_HW_NOT_PRESENT`, permanent, its own reason): [BiometricPromptResponse.Failed] with
 *   the matching [BiometricFailure].
 * - Everything else the system may report (unable to process, no space, vendor, security update
 *   needed, no device credential): [BiometricFailure.OTHER].
 *
 * A finger that is not recognized is not an error code: `onAuthenticationFailed` fires and the
 * dialog stays up for another try.
 */
internal fun biometricResponseForError(errorCode: Int): BiometricPromptResponse? = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
    -> BiometricPromptResponse.Cancelled

    BiometricPrompt.ERROR_TIMEOUT -> null

    BiometricPrompt.ERROR_LOCKOUT -> BiometricPromptResponse.Failed(BiometricFailure.LOCKOUT)
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricPromptResponse.Failed(BiometricFailure.LOCKOUT_PERMANENT)
    BiometricPrompt.ERROR_NO_BIOMETRICS -> BiometricPromptResponse.Failed(BiometricFailure.NO_BIOMETRICS)

    BiometricPrompt.ERROR_HW_UNAVAILABLE -> BiometricPromptResponse.Failed(BiometricFailure.HW_UNAVAILABLE)
    BiometricPrompt.ERROR_HW_NOT_PRESENT -> BiometricPromptResponse.Failed(BiometricFailure.HW_NOT_PRESENT)

    else -> BiometricPromptResponse.Failed(BiometricFailure.OTHER)
}
