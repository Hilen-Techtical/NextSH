// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import fr.techtical.nextsh.core.ssh.BiometricFailure
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.BiometricRequest
import fr.techtical.nextsh.core.ssh.isAuthorizedBy
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.security.Signature

/** The system dialog (one `BiometricPrompt` of androidx), as [BiometricDialogController] sees it. */
internal interface BiometricDialog {
    /**
     * Starts the dialog for [request]: a signature request authorizes its signature through a
     * `CryptoObject`, a vault request has none. May throw.
     */
    fun show(request: BiometricRequest)

    /** Asks the system to close the dialog. It answers later, with an error, if it answers at all. */
    fun cancel()
}

/** What a [BiometricDialog] reports back, on the main thread. */
internal interface BiometricDialogEvents {
    /**
     * The owner was recognized. [authorizedSignature] is the one the `CryptoObject` came back with,
     * null for a dialog that had none (the vault).
     */
    fun onSucceeded(authorizedSignature: Signature?)

    /** The dialog ended with the `BiometricPrompt.ERROR_*` code [errorCode] (the text is never passed on). */
    fun onError(errorCode: Int)

    /** A finger was not recognized: no end, the dialog lets the user try again. */
    fun onNotRecognized()
}

/** Runs a task later on the main thread. */
internal interface Delayer {
    /** Runs [task] in [delayMs]; the function returned cancels it. */
    fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit
}

/**
 * The one object that drives the system biometric dialog of an activity, in place of a
 * `BiometricPrompt` per request, whether the request is the signature of a biometric key or the proof
 * of the owner for the vault ([BiometricRequest]). A plain class, so that it can be tested with a
 * fake dialog.
 *
 * Why one controller for the activity. androidx has a single callback slot and a single
 * "prompt showing" flag per activity, and three things follow from it:
 * - a dialog that is cancelled reports its `ERROR_CANCELED` later, through whichever callback holds
 *   the slot then. With a callback per request, the error of the dialog that ended landed in the
 *   callback of the next request and cancelled it before it ever had a dialog;
 * - until that report comes, the flag stays up and an `authenticate()` is silently dropped;
 * - a dialog kept across a rotation belongs to a request the new activity has to find.
 *
 * So there is one callback (a [DialogEventForwarder] in front of this class), a small state, and
 * two rules: **a dialog is never launched while the previous one is not confirmed closed**, and
 * **a launch never runs inside a callback of androidx**.
 *
 * - Idle: no dialog. When the coordinator publishes a request that has not been launched and the
 *   activity is resumed, a launch is scheduled; when it fires, [BiometricPromptCoordinator.markLaunched]
 *   decides and the dialog starts.
 * - Showing: the dialog of [shown] is up. Its answers go to the coordinator. If the coordinator's
 *   state no longer holds [shown] (deadline, connection cancelled), the dialog is cancelled.
 * - Cancelling: the cancel was asked and not yet acknowledged. The next request waits. The
 *   acknowledgement is the next terminal callback of the dialog (success or error), whatever it is:
 *   it belongs to the cancelled request and is dropped. An OS that sends none is waited for
 *   [CANCEL_ACK_TIMEOUT_MS], then the dialog is declared closed.
 *
 * Why a launch is scheduled, [LAUNCH_SETTLE_MS] ahead, and never run at once. The terminal callback
 * of a closing dialog comes first in the main queue, and the removal of its fragment right behind
 * it. A launch run inside that callback (the acknowledgement of a cancel is exactly that) builds a
 * `BiometricPrompt` while the old fragment is still attached and found by its tag: the new dialog
 * would show through a fragment that is then destroyed, its observers dying with it, and hang to its
 * deadline. Some phones also delay showing a prompt after a dismissal themselves. The same goes for
 * the resume of the activity, where androidx flushes what it held while stopped. So the launch waits
 * for the queue to settle and re-checks, when it fires, that it can still go.
 *
 * Why the acknowledgement timer does not run while the activity is not resumed. A stopped activity
 * does not get the error of the dialog it cancelled: the observers of the fragment are inactive
 * until it starts again. Closing the dialog on a timer then would let the next request launch on
 * return, and the error that androidx flushes at that moment would land on it. The timer is started
 * again at the resume, and the launch it allows is scheduled behind the flush. Residual risk: an
 * error that comes later than [CANCEL_ACK_TIMEOUT_MS] after a cancel in the foreground still lands
 * on the request behind it (cancelled, and it must be started again); no signal tells that error
 * from the answer of a new dialog.
 *
 * A new `BiometricPrompt` is built for each launch even though the controller is the same: building
 * it is what puts our callback in the callback slot of the activity, so a dialog never answers
 * through the callback of an earlier launch. `BiometricHelper` used to take that slot for the vault
 * prompts; those go through the coordinator now, and this keeps the rule cheap and safe.
 *
 * Rotation. [BiometricPromptCoordinator.openDialog] outlives the request, so a new controller finds
 * the dialog its predecessor started ([start]): it builds a `BiometricPrompt` for the new activity,
 * which reconnects the callback of the dialog androidx kept (the activity-scoped view model holds
 * the old callback through the configuration change, but only a prompt built for the new activity
 * can cancel it), and it can still cancel the dialog when its request ends. A [dispose] caused by a
 * configuration change leaves the dialog and the request alone; any other [dispose] closes both.
 *
 * What androidx holds, and the activity. That view model keeps the callback it was given until
 * another prompt is built, past the destruction of the activity. The callback therefore goes through
 * the [DialogEventForwarder], which holds the coordinator and, until [dispose], this controller,
 * never an activity: after a rotation the destroyed activity is not reachable through it. A result
 * that comes between the [dispose] of a rotation and the [start] of the next controller is routed to
 * the coordinator by the forwarder, so it is not lost.
 *
 * Everything runs on the main thread: the lifecycle, the state collection, the dialog callbacks and
 * the [Delayer] are all there.
 *
 * @param canLaunch whether the activity is resumed: `authenticate()` is refused once the state is
 * saved, and nobody would see a dialog started in the background
 * @param isChangingConfigurations whether the activity is being recreated for a configuration change
 * @param newDialog builds a dialog whose callbacks are the given events
 */
internal class BiometricDialogController(
    private val coordinator: BiometricPromptCoordinator,
    private val canLaunch: () -> Boolean,
    private val isChangingConfigurations: () -> Boolean,
    private val delayer: Delayer,
    private val newDialog: (BiometricDialogEvents) -> BiometricDialog,
) : BiometricDialogEvents {

    /** What the dialogs report to: keeps no activity, see the KDoc. */
    private val forwarder = DialogEventForwarder(coordinator).also { it.attach(this) }

    /** The request whose dialog is up (Showing, or Cancelling), or null (Idle). */
    private var shown: BiometricRequest? = null
    private var dialog: BiometricDialog? = null

    /** True between the cancel and its acknowledgement (Cancelling). */
    private var cancelling = false

    /** Cancels the acknowledgement timer; null when it does not run. */
    private var cancelTimeout: (() -> Unit)? = null

    /** True while a launch waits for the queue to settle; it makes [sync] schedule one launch, not many. */
    private var launchScheduled = false
    private var cancelScheduledLaunch: (() -> Unit)? = null
    private var disposed = false

    /**
     * Takes over a dialog that a previous controller started and did not close (a rotation), then
     * looks at what the coordinator holds. Called once, when the host is created, before the
     * lifecycle observer that calls [onResumed] is added.
     */
    fun start() {
        val open = coordinator.openDialog()
        if (open != null && shown == null && !disposed) {
            shown = open
            dialog = tryNewDialog()
        }
        sync()
    }

    /** The activity is resumed (or was, when the observer was added): the timer and the launch may go. */
    fun onResumed() {
        if (disposed) return
        if (cancelling && cancelTimeout == null) startAcknowledgementTimer()
        sync()
    }

    /**
     * Brings the dialog in line with the coordinator: schedules the launch of the request it
     * publishes, cancels the request it no longer holds. Called when the coordinator's state
     * changes, when the activity resumes and after a dialog closed. Idempotent, and it never starts
     * a dialog itself.
     */
    fun sync() {
        if (disposed) return
        val current = coordinator.state.value?.request
        val showing = shown
        when {
            showing == null -> if (current != null) scheduleLaunch()
            cancelling -> Unit // the next dialog waits for the acknowledgement of this close
            current !== showing -> beginCancel()
        }
    }

    private fun scheduleLaunch() {
        if (launchScheduled || !canLaunch()) return
        launchScheduled = true
        cancelScheduledLaunch = delayer.postDelayed(LAUNCH_SETTLE_MS) {
            launchScheduled = false
            cancelScheduledLaunch = null
            launchNow()
        }
    }

    /** The settle time has passed: launch what the coordinator holds now, if it still can be launched. */
    private fun launchNow() {
        if (disposed || shown != null || !canLaunch()) return
        val current = coordinator.state.value?.request ?: return
        launch(current)
    }

    private fun launch(request: BiometricRequest) {
        if (!coordinator.markLaunched(request)) return
        shown = request
        try {
            val created = newDialog(forwarder)
            dialog = created
            created.show(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class only: nothing the exception says belongs in the log.
            Timber.w("Biometric dialog could not start (${errorClass(e)})")
            finish(request)
            coordinator.fail(request, BiometricFailure.OTHER)
        }
    }

    private fun beginCancel() {
        cancelling = true
        // Not while the activity is stopped: the error of the dialog is held back until it starts.
        if (canLaunch()) startAcknowledgementTimer()
        try {
            dialog?.cancel()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Biometric dialog could not be cancelled (${errorClass(e)})")
        }
    }

    private fun startAcknowledgementTimer() {
        cancelTimeout?.invoke()
        cancelTimeout = delayer.postDelayed(CANCEL_ACK_TIMEOUT_MS) {
            cancelTimeout = null
            // Paused in the meantime: the timer stops, [onResumed] starts it again.
            if (canLaunch()) acknowledgeClose()
        }
    }

    /** The cancelled dialog is closed (it said so, or nothing came in time): the next request may launch. */
    private fun acknowledgeClose() {
        if (disposed) return
        val request = shown ?: return
        finish(request)
        sync()
    }

    /** Forgets the dialog of [request] and tells the coordinator it is closed. */
    private fun finish(request: BiometricRequest) {
        cancelTimeout?.invoke()
        cancelTimeout = null
        cancelling = false
        shown = null
        dialog = null
        coordinator.markClosed(request)
    }

    override fun onSucceeded(authorizedSignature: Signature?) {
        if (disposed) return
        val request = shown ?: return
        if (cancelling) {
            // The finger came in as the cancel did: the request is over, nobody uses this authorization.
            acknowledgeClose()
            return
        }
        // Defense in depth: what the system authorized must be the very Signature we handed over
        // (a vault request has none, see isAuthorizedBy).
        if (request.isAuthorizedBy(authorizedSignature)) {
            coordinator.authorize(request)
        } else {
            Timber.w("Biometric dialog succeeded with another signature than the request's")
            coordinator.fail(request, BiometricFailure.OTHER)
        }
        finish(request)
        // The coordinator still holds the request until its connection retires it: it is launched
        // for good, and nothing starts again for it.
        sync()
    }

    override fun onError(errorCode: Int) {
        if (disposed) return
        val request = shown ?: return
        if (cancelling) {
            // Belongs to the request that was cancelled, not to the one waiting behind it.
            Timber.d("Biometric dialog closed after the cancel, code=$errorCode")
            acknowledgeClose()
            return
        }
        // The numeric code only: the text is a system string meant for the user, not for the log.
        Timber.w("Biometric dialog error code=$errorCode")
        coordinator.reportError(request, errorCode)
        finish(request)
        sync()
    }

    override fun onNotRecognized() {
        // A finger that was not recognized is not an end: the system dialog lets the user try again.
        Timber.d("Biometric dialog: not recognized (retry possible)")
    }

    /**
     * The host is gone. For a configuration change the dialog stays up and the request keeps
     * waiting: the forwarder keeps answering for them until the controller of the new activity takes
     * them over ([start]). Otherwise the dialog is cancelled and the request ends as cancelled:
     * nobody can show it.
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        cancelTimeout?.invoke()
        cancelTimeout = null
        cancelScheduledLaunch?.invoke()
        cancelScheduledLaunch = null
        launchScheduled = false
        val request = shown
        if (isChangingConfigurations()) {
            forwarder.orphan(request, cancelling)
            return
        }
        forwarder.detach()
        if (request == null) return
        shown = null
        if (!cancelling) {
            try {
                dialog?.cancel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Biometric dialog could not be cancelled (${errorClass(e)})")
            }
            coordinator.cancel(request)
        }
        dialog = null
        coordinator.markClosed(request)
    }

    private fun tryNewDialog(): BiometricDialog? =
        try {
            newDialog(forwarder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Biometric dialog could not be taken over (${errorClass(e)})")
            null
        }

    companion object {
        /**
         * How long a cancelled dialog is waited for before it is declared closed. A real one
         * acknowledges within a few frames; an OS that sends nothing must not block the next request.
         */
        const val CANCEL_ACK_TIMEOUT_MS = 1_500L

        /**
         * How long a launch waits, after what triggered it, for the main queue to settle: the
         * removal of the fragment of a dialog that just closed, the flush of what androidx held back
         * while the activity was stopped. A few frames.
         */
        const val LAUNCH_SETTLE_MS = 250L
    }
}

/**
 * What the `BiometricPrompt` callback of androidx talks to. androidx keeps that callback in the view
 * model of the activity until another prompt is built, past the destruction of the activity, so it
 * must hold no activity: this class holds the [coordinator] (a singleton) and, while it is attached,
 * the controller, whose own references (the lambdas that read the activity) it drops at [detach]
 * and [orphan].
 *
 * Three modes:
 * - attached: everything goes to the controller;
 * - orphaned, after the [BiometricDialogController.dispose] of a configuration change: the dialog
 *   is still up, nobody is the controller yet, and the first result is routed to the coordinator by
 *   request, as the controller would have. The next controller finds the dialog closed if it comes
 *   later, or builds its own callback if it comes first;
 * - detached: everything is dropped.
 */
internal class DialogEventForwarder(private val coordinator: BiometricPromptCoordinator) : BiometricDialogEvents {

    private var target: BiometricDialogEvents? = null
    private var orphanRequest: BiometricRequest? = null
    private var orphanCancelling = false

    fun attach(controller: BiometricDialogEvents) {
        target = controller
    }

    /** Drops everything: the controller is gone for good. */
    fun detach() {
        target = null
        orphanRequest = null
    }

    /** The controller is gone for a rotation; [request] (null if none) is up, [cancelling] if it was being closed. */
    fun orphan(request: BiometricRequest?, cancelling: Boolean) {
        target = null
        orphanRequest = request
        orphanCancelling = cancelling
    }

    override fun onSucceeded(authorizedSignature: Signature?) {
        target?.let {
            it.onSucceeded(authorizedSignature)
            return
        }
        val request = takeOrphan() ?: return
        if (!orphanCancelling) {
            if (request.isAuthorizedBy(authorizedSignature)) {
                coordinator.authorize(request)
            } else {
                coordinator.fail(request, BiometricFailure.OTHER)
            }
        }
        coordinator.markClosed(request)
    }

    override fun onError(errorCode: Int) {
        target?.let {
            it.onError(errorCode)
            return
        }
        val request = takeOrphan() ?: return
        if (!orphanCancelling) coordinator.reportError(request, errorCode)
        coordinator.markClosed(request)
    }

    override fun onNotRecognized() {
        target?.onNotRecognized()
    }

    /** The request of the orphaned dialog, once: its first result settles it. */
    private fun takeOrphan(): BiometricRequest? {
        val request = orphanRequest
        orphanRequest = null
        return request
    }
}
