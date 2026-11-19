// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.AppVisibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of the host-key TOFU prompt: exposed app-wide via the
 * [HostKeyPromptCoordinator] so that **any** screen in the app can
 * render the dialog when an SSH connection is initiated.
 *
 * It is answered through [HostKeyPromptCoordinator.respond] with this very instance: an answer is
 * taken only for the prompt on screen, so a dialog that is on its way out cannot answer for the
 * next one. Compare it by identity.
 *
 * Two prompts for the same host, port and key are two prompts: [requestId] tells them apart. A data
 * class compares its fields, and `StateFlow` skips a value equal to the current one, `collectAsState`
 * too: without the id, the second prompt would never replace the first in the dialog, which would
 * keep a state the coordinator has already forgotten, and every tap on it would be ignored.
 *
 * Only a key never seen has a prompt. A key that differs from the stored one is refused without
 * asking (see [InteractiveHostKeyVerifier]): there is no state, and no dialog, for it.
 *
 * @property hostname `host:port` for display.
 * @property algorithm Key algorithm (e.g., "ssh-ed25519").
 * @property fingerprint SHA-256 fingerprint to confirm.
 * @property requestId Number of the prompt, new for each one the coordinator shows.
 */
data class HostKeyDialogState(
    val hostname: String,
    val algorithm: String,
    val fingerprint: String,
    val requestId: Long = 0L,
)

/** How a host-key prompt ended, as the verifier of the connection reads it. */
sealed interface HostKeyPromptResponse {

    /** The user trusts the key: it is stored, the handshake goes on. */
    data object Trusted : HostKeyPromptResponse

    /**
     * The key was trusted while the request waited for its turn (the prompt before it, for the same
     * host and key, stored it): nothing to ask, nothing to store again, the handshake goes on.
     */
    data object AlreadyTrusted : HostKeyPromptResponse

    /**
     * The user refused the key (the button, or the dialog dismissed): the handshake stops. Also the
     * answer, without a dialog, when another key was trusted for the same host while the request
     * waited for its turn: the server is no longer the one the user just validated.
     */
    data object Refused : HostKeyPromptResponse

    /**
     * The app was not on screen when the prompt was asked for, or when its turn came after another
     * prompt: no dialog was shown, nobody could have answered. Neither a refusal nor a key that
     * does not match (see [HostKeyPromptEnd]).
     */
    data object NoScreen : HostKeyPromptResponse

    /** Nobody answered within [HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS] of the dialog appearing. */
    data object TimedOut : HostKeyPromptResponse
}

/**
 * Singleton coordinating the host-key TOFU prompt across the whole app.
 *
 * Why this exists : the SSH host-key verifier callback used to be
 * installed in `SessionViewModel.init` only. As a result, if the user
 * tapped "Connect" from the HostList screen on a fresh install (no
 * `SessionViewModel` ever created), the callback was null and SSHJ
 * silently rejected the unknown host with `Could not verify <algo>
 * host key with fingerprint <SHA256:...>`. The TOFU dialog never had
 * a chance to appear, because there was no `Sessions` entry in the
 * Android nav to instantiate the ViewModel beforehand.
 *
 * Now installed at App level (`App.onCreate`) on the singleton
 * `SshSessionManager`, the callback is always live regardless of
 * navigation history. The dialog is rendered at MainActivity level,
 * overlaying the entire NavGraph.
 *
 * It has the same discipline as [Fido2PromptCoordinator] and [BiometricPromptCoordinator], whose
 * story it shares (a connection started anywhere, a prompt that must reach a person):
 *
 * - One prompt at a time, and one turn shared with the security key sheet and the fingerprint
 *   dialog ([InteractivePromptTurn]): two connections to two unknown hosts (two tunnels, an
 *   auto-start) no longer overwrite each other's dialog, and the first one's end no longer clears
 *   the second's. The second waits, unseen, and gets its own [PROMPT_TIMEOUT_MS] when its dialog
 *   appears. A request that waits is cancelled with its coroutine and shows nothing.
 * - A deadline: [PROMPT_TIMEOUT_MS] after the dialog appeared, the prompt ends with
 *   [HostKeyPromptResponse.TimedOut]. The SSH key exchange that waits behind it is given the time
 *   (see `keyExchangeTimeoutMs`), so the user really has the whole minute to read a fingerprint.
 * - Nobody to answer: when the app is not on screen at the moment of the request (a reconnection
 *   driven by a service), or once the turn is taken, the request ends at once with
 *   [HostKeyPromptResponse.NoScreen], without a dialog and without taking the turn. A prompt that is
 *   already on screen when the app leaves it still ends by its deadline.
 * - An answer counts only for the prompt on screen ([respond]): a dialog being closed, or tapped
 *   twice, cannot trust the key of the next one.
 *
 * The clock is the one of the coroutine that asks (a virtual one in the tests); the visibility is
 * injectable for tests, the app uses the real [AppVisibility].
 */
@Singleton
class HostKeyPromptCoordinator internal constructor(
    private val interactiveTurn: InteractivePromptTurn,
    /** Monotonic time in milliseconds: the gap between two dialogs and the delay of the confirm button. */
    private val clock: () -> Long,
    private val appVisibility: AppVisibility,
    /**
     * What is stored by now for the host of a request, see [HostKeyPromptResponse.AlreadyTrusted]
     * (the same key) and [HostKeyPromptResponse.Refused] (another one).
     */
    private val storedKey: (HostKeyVerifyResult.Unknown) -> StoredKeyState,
    /** [CONFIRM_ARM_MS] in the app; a test of something else than the arming takes it off. */
    private val confirmArmMs: Long = CONFIRM_ARM_MS,
) {

    @Inject
    constructor(interactiveTurn: InteractivePromptTurn, appVisibility: AppVisibility, knownHosts: KnownHostsVerifier) :
        this(interactiveTurn, { System.nanoTime() / NANOS_PER_MILLI }, appVisibility, { knownHosts.storedKeyState(it) })

    /** For tests: an app on screen, a turn of its own, no key stored. */
    internal constructor() :
        this(InteractivePromptTurn(), { System.nanoTime() / NANOS_PER_MILLI }, { true }, { StoredKeyState.NONE })

    private val _state = MutableStateFlow<HostKeyDialogState?>(null)
    val state: StateFlow<HostKeyDialogState?> = _state.asStateFlow()

    /** Guards [pending], [_state], [confirmArmedAtMs] and [lastDialogEndedAtMs] against an answer racing the end of the prompt. */
    private val lock = Any()
    private var pending: CompletableDeferred<Boolean>? = null

    /** When the dialog on screen starts to take a "trust" answer, on [clock]; see [CONFIRM_ARM_MS]. */
    private var confirmArmedAtMs = 0L

    /** When the last dialog that was shown went away, on [clock]; null before the first one. */
    private var lastDialogEndedAtMs: Long? = null

    /** The number of the next prompt, see [HostKeyDialogState.requestId]. */
    private val requestCounter = AtomicLong(0L)

    /**
     * Suspendable callback invoked by [InteractiveHostKeyVerifier]
     * when an unknown host key is encountered. Surface the dialog,
     * suspend until the user responds via [respond], or until [PROMPT_TIMEOUT_MS] after it appeared,
     * or until the calling coroutine is cancelled.
     *
     * When the app is not on screen at the time of the call, returns [HostKeyPromptResponse.NoScreen]
     * at once: nothing is shown and the turn is not taken (a security key sheet, or another request,
     * is not held up by it). The same is checked again once the turn is taken: a request that waited
     * behind another prompt may find the app left by the time its turn comes.
     *
     * Two dialogs never follow each other without a break: a prompt that starts less than
     * [PROMPT_GAP_MS] after the previous one went away waits for the rest of it, with nothing on
     * screen, so that the dialog is seen to close and to open again. A tap meant for the first
     * dialog, or one on a dialog that changed under the finger, then lands on nothing, not on the
     * key of the next host.
     *
     * Once the gap is over the key is looked up again: two connections to the same new host (two
     * tunnels of one host) ask about the same key, and the user trusted it in the prompt before this
     * one. That answer was stored, so this request is [HostKeyPromptResponse.AlreadyTrusted] and
     * shows nothing, instead of asking again for a key that was just trusted. It is looked up after
     * the gap, not before, because the connection of the first prompt stores the key when the answer
     * reaches it, a moment after the turn is released. If what is stored for the host is another key,
     * the request is [HostKeyPromptResponse.Refused] without a dialog: it is no longer a first
     * connection, and the connection then ends like any key that does not match.
     */
    suspend fun promptUnknown(unknown: HostKeyVerifyResult.Unknown): HostKeyPromptResponse {
        if (!appVisibility.isVisible()) return HostKeyPromptResponse.NoScreen
        return interactiveTurn.withTurn {
            // The wait for the turn can be long (another prompt lasts up to a minute), and the user
            // may have left in it. A dialog published now would hold the turn, and the SSH
            // handshake behind it, for a deadline nobody can beat.
            if (!appVisibility.isVisible()) return@withTurn HostKeyPromptResponse.NoScreen
            waitForGap()
            if (!appVisibility.isVisible()) return@withTurn HostKeyPromptResponse.NoScreen
            when (storedKey(unknown)) {
                StoredKeyState.SAME -> return@withTurn HostKeyPromptResponse.AlreadyTrusted
                StoredKeyState.DIFFERENT -> {
                    // Another key was trusted for this host while the request waited (a server behind
                    // a load balancer, or someone in the middle). To ask would show "first
                    // connection" for a host that has one, and a tap would append a line nothing
                    // reads. Refused like any key that no longer matches; no key, no host in the log.
                    Timber.w("KnownHosts: a different key is stored for this host since the request was made, refused")
                    return@withTurn HostKeyPromptResponse.Refused
                }
                StoredKeyState.NONE -> showAndAwait(unknown)
            }
        }
    }

    /** Waits out what remains of [PROMPT_GAP_MS] since the last dialog went away, if anything does. */
    private suspend fun waitForGap() {
        val endedAt = synchronized(lock) { lastDialogEndedAtMs } ?: return
        val remaining = PROMPT_GAP_MS - (clock() - endedAt)
        if (remaining > 0L) delay(remaining)
    }

    private suspend fun showAndAwait(unknown: HostKeyVerifyResult.Unknown): HostKeyPromptResponse {
        val deferred = CompletableDeferred<Boolean>()
        val prompt = HostKeyDialogState(
            hostname = unknown.hostname,
            algorithm = unknown.algorithm,
            fingerprint = unknown.fingerprint,
            requestId = requestCounter.incrementAndGet(),
        )
        synchronized(lock) {
            pending = deferred
            confirmArmedAtMs = clock() + confirmArmMs
            _state.value = prompt
            // Test, publish, test again: the app can have been closed (the lock screen, the wipe of the
            // vault) between the checks that let this request through and the publication. The ending
            // of the prompts that ran in that gap found nothing to end, and nobody would end this one
            // before its deadline: it ends now as for an app that was left.
            if (!appVisibility.isVisible()) {
                absentPrompt = prompt
                deferred.complete(false)
            }
        }
        return try {
            // The dialog answers the deferred; nobody answering means the deadline ends the prompt.
            // An answer that landed just as the deadline passed, before the prompt was retired, is kept.
            val answer = withTimeoutOrNull(PROMPT_TIMEOUT_MS) { deferred.await() } ?: lateAnswer(deferred)
            when {
                // The lock screen came up: nobody is there, whatever the deferred was completed with.
                endedForAbsence(prompt) -> HostKeyPromptResponse.NoScreen
                answer == true -> HostKeyPromptResponse.Trusted
                answer == false -> HostKeyPromptResponse.Refused
                else -> HostKeyPromptResponse.TimedOut
            }
        } finally {
            // Also when the caller gave up: the dialog goes with the connection that asked for it.
            retire(deferred, prompt)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun lateAnswer(deferred: CompletableDeferred<Boolean>): Boolean? =
        synchronized(lock) { if (deferred.isCompleted) deferred.getCompleted() else null }

    /** Ends the prompt: hides it if it is still the one on screen, and forgets its deferred. */
    private fun retire(deferred: CompletableDeferred<Boolean>, prompt: HostKeyDialogState) {
        synchronized(lock) {
            if (pending === deferred) pending = null
            if (_state.value === prompt) {
                _state.value = null
                lastDialogEndedAtMs = clock()
            }
        }
    }

    /**
     * Called from the dialog UI to answer [prompt], the state it shows. Ignored unless [prompt] is
     * still the one on screen: the answer of a dialog that is already gone, or a second tap on a
     * dialog that was just answered, must not decide for the prompt that follows.
     *
     * A "trust" answer ([accepted]) is not taken in the first [CONFIRM_ARM_MS] after the dialog
     * appeared; the button is disabled for as long (the dialog follows the same constant), and this is
     * the guard behind it. A tap that was on its way when the dialog popped up, over a screen the user
     * was using, must not trust a key nobody has read. A refusal is taken at any time.
     */
    fun respond(prompt: HostKeyDialogState, accepted: Boolean) {
        synchronized(lock) {
            if (_state.value !== prompt) return
            if (accepted && clock() < confirmArmedAtMs) return
            pending?.complete(accepted)
        }
    }

    /**
     * Dismiss [prompt] without accepting (equivalent to refusing).
     */
    fun dismiss(prompt: HostKeyDialogState) {
        respond(prompt, false)
    }

    /**
     * Nobody is in front of the app any more, though it is on screen: the lock screen came up (see
     * `PromptsEndedByLock`). The dialog that is up ends at once as if the app had been left when it
     * was asked for, with [HostKeyPromptResponse.NoScreen]: nothing is trusted, the dialog goes away,
     * and the turn is released instead of being held, with the SSH handshake behind it, for a
     * deadline nobody can beat. Nothing is up: nothing happens.
     */
    fun endForAbsence() {
        synchronized(lock) {
            val prompt = _state.value ?: return
            val deferred = pending ?: return
            absentPrompt = prompt
            // The value is not read: the prompt is marked, see endedForAbsence.
            deferred.complete(false)
        }
    }

    /** The prompt that [endForAbsence] ended, until the next one is shown. */
    private var absentPrompt: HostKeyDialogState? = null

    private fun endedForAbsence(prompt: HostKeyDialogState): Boolean = synchronized(lock) { absentPrompt === prompt }

    companion object {
        /** Time the user has to read the fingerprint and answer, from the moment the dialog appears. */
        const val PROMPT_TIMEOUT_MS = 60_000L

        /** Nothing on screen between two dialogs, so that the user sees one close and the next open. */
        const val PROMPT_GAP_MS = 700L

        /** The "trust" button is disabled, and a trust answer ignored, for this long after a dialog appears. */
        const val CONFIRM_ARM_MS = 500L

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
