// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.crypto.vaultAuthenticators
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.BiometricRequest
import fr.techtical.nextsh.core.ssh.BiometricSignRequest
import fr.techtical.nextsh.core.ssh.VaultAuthRequest

/**
 * Global host of the system biometric dialog, rendered by MainActivity above the NavGraph (like
 * [Fido2PromptHost] for the FIDO2 key and [HostKeyPromptDialog] for the host key): the fingerprint
 * prompt of the biometric keys, and the proof of the owner that the vault asks for (the unlock screen,
 * the lock screen, the confirmation of a host save).
 * It shows nothing itself: it creates the [BiometricDialogController] of the activity, which
 * follows the singleton [BiometricPromptCoordinator], so the system dialog appears whichever screen
 * started the connection (host list, terminal, SFTP, tunnels) or asked for the unlock.
 *
 * The controller is one per activity, not one per request, and the KDoc of
 * [BiometricDialogController] says why: androidx has a single callback slot and a single "prompt
 * showing" flag per activity, so a dialog is never launched before the previous one is confirmed
 * closed, and the answer of a closed dialog never reaches the request behind it.
 *
 * What it adds to the controller is the Android side: the lifecycle (the dialog is launched while
 * the activity is resumed, `authenticate()` being refused once the state is saved), the state of
 * the coordinator, and [AndroidBiometricDialog].
 *
 * Rotation. The terminal allows rotation: `MainActivity` is recreated while the system dialog stays
 * up. The old controller leaves the dialog and its request alone (`isChangingConfigurations`), and
 * the new one finds the dialog through the coordinator and builds a `BiometricPrompt` for the new
 * activity. The callback of the dialog is not lost in between: androidx attaches the reset of the
 * callback to a `Fragment` host only, and with the `FragmentActivity` constructor the
 * activity-scoped view model keeps the old callback through the configuration change.
 *
 * That has a price, and the callback is built to pay it: the view model keeps the callback past the
 * destruction of the activity, until another prompt is built. [forwardingCallback] therefore holds
 * only the [DialogEventForwarder] given to it, which holds the coordinator and, until the controller
 * is disposed, the controller; it is built by a function outside every class that holds an
 * activity, so that no `this` of one is captured. After a rotation the destroyed `MainActivity` is
 * not reachable through androidx. A result that comes between the two controllers is routed to the
 * coordinator by the forwarder. That the kept dialog then answers to the new controller is to be
 * confirmed on a device, like the identity of the `Signature` it returns.
 *
 * Logging: the number of the error only, never the system text nor the labels.
 */
@Composable
fun BiometricPromptHost(coordinator: BiometricPromptCoordinator) {
    val activity = LocalContext.current.findFragmentActivity() ?: return

    LaunchedEffect(activity) {
        val controller = BiometricDialogController(
            coordinator = coordinator,
            canLaunch = { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
            isChangingConfigurations = { activity.isChangingConfigurations },
            delayer = MainHandlerDelayer(),
            newDialog = { events -> AndroidBiometricDialog(activity, events) },
        )
        // Coming back to the app picks up a request that was published while it was not on screen,
        // and restarts the acknowledgement timer that was paused with the activity.
        val resumeObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.onResumed()
        }
        try {
            // Started before the observer is added: adding it replays ON_RESUME at once when the
            // activity is already resumed, and the controller must know the dialog a rotation left by then.
            controller.start()
            activity.lifecycle.addObserver(resumeObserver)
            coordinator.state.collect { controller.sync() }
        } finally {
            // Also runs when the composition is left for a rotation: the controller checks
            // isChangingConfigurations (still true on the destroyed activity) and leaves the
            // dialog and the request alone then.
            activity.lifecycle.removeObserver(resumeObserver)
            controller.dispose()
        }
    }
}

/**
 * The androidx `BiometricPrompt` of one launch, as a [BiometricDialog]. Built for each launch:
 * building it is what puts its callback in the activity's slot.
 *
 * Two kinds of dialog:
 * - the signature of a biometric key: strong biometrics only, never DEVICE_CREDENTIAL. The key is
 *   bound to a fingerprint, a phone code could not unlock it and the dialog would promise it wrongly.
 *   Confirmation stays at its default;
 * - the owner for the vault: a biometric (strong from API 30, see `vaultAuthenticators`) or the code
 *   of the phone (the master key of the vault accepts both), so no negative button (the system forbids one with DEVICE_CREDENTIAL, the back
 *   gesture closes the dialog) and no `CryptoObject`: a success refreshes the authentication window of
 *   the key, which is all it is for.
 */
private class AndroidBiometricDialog(
    private val activity: FragmentActivity,
    events: BiometricDialogEvents,
) : BiometricDialog {

    private val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), forwardingCallback(events))

    override fun show(request: BiometricRequest) {
        when (request) {
            is BiometricSignRequest -> showSignature(request)
            is VaultAuthRequest -> showVault(request)
        }
    }

    private fun showSignature(request: BiometricSignRequest) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_sign_title, request.hostLabel))
            .setSubtitle(activity.getString(R.string.biometric_sign_subtitle, request.keyLabel))
            // Strong biometrics only, never the device credential: the key is bound to a fingerprint.
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(activity.getString(R.string.action_cancel))
            .build()
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(request.signature))
    }

    private fun showVault(request: VaultAuthRequest) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(request.purpose.titleRes))
            .setSubtitle(activity.getString(request.purpose.subtitleRes))
            // Depends on the API: androidx rejects "strong biometric or code" on API 28 and 29.
            .setAllowedAuthenticators(vaultAuthenticators(Build.VERSION.SDK_INT))
            .build()
        prompt.authenticate(promptInfo)
    }

    override fun cancel() {
        prompt.cancelAuthentication()
    }
}

/**
 * The callback androidx keeps for the dialog. A function of its own, outside [AndroidBiometricDialog]:
 * an object built inside that class would be free to capture it, and with it the activity, in a
 * reference that androidx keeps past the destruction of the activity. This one holds [events] only.
 */
private fun forwardingCallback(events: BiometricDialogEvents): BiometricPrompt.AuthenticationCallback =
    object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            events.onSucceeded(result.cryptoObject?.signature)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // The system text goes no further than this parameter.
            events.onError(errorCode)
        }

        override fun onAuthenticationFailed() {
            events.onNotRecognized()
        }
    }

/** [Delayer] on the main looper. */
private class MainHandlerDelayer : Delayer {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}

/** The [FragmentActivity] behind [this] context, through any wrappers, or null (`BiometricPrompt` needs one). */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
