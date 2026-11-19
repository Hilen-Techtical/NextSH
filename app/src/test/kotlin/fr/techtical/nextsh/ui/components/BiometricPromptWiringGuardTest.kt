// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Smoke alarm on the wiring of the fingerprint prompt, the one thing the unit tests cannot run: a
 * composable that starts a system dialog, in an Activity that a rotation recreates. What can be run
 * is run (`BiometricDialogControllerTest` drives the controller with a fake dialog,
 * `BiometricPromptCoordinatorTest`, `SshSessionManagerBiometricConnectTest`); this checks that the
 * Android side still has the properties the design rests on, and that the app installs it.
 *
 * Same compromise and same rule as `SshSessionManagerWiringGuardTest`: a cheap source check. A
 * source that cannot be found FAILS the test (see [ProjectFiles]).
 */
class BiometricPromptWiringGuardTest {

    /** The code of a main source file of the app, comments blanked. */
    private fun code(path: String): String = blankComments(ProjectFiles.mainSource(path).readText())

    private val host get() = code("ui/components/BiometricPromptHost.kt")
    private val controller get() = code("ui/components/BiometricDialogController.kt")

    private fun count(code: String, needle: String) = Regex(Regex.escape(needle)).findAll(code).count()

    /** The text between the braces of the function that starts with [signature]; fails if there is none. */
    private fun bodyOf(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(start >= 0, "$signature not found")
        val open = code.indexOf('{', start)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        fail<Unit>("$signature has no end")
        return ""
    }

    // ── What the dialog asks of the system ────────────────────────────────────

    @Test
    fun `the signature dialog accepts strong biometrics only, never the device credential`() {
        val host = host
        val signature = bodyOf(host, "private fun showSignature(")

        assertTrue(signature.contains("BiometricManager.Authenticators.BIOMETRIC_STRONG"), "BIOMETRIC_STRONG")
        // The key is bound to a fingerprint: a phone code could not unlock it, and the dialog would promise it.
        assertFalse(signature.contains("DEVICE_CREDENTIAL"), "DEVICE_CREDENTIAL must not be allowed")
        assertFalse(signature.contains("vaultAuthenticators"), "nor the combination of the vault")
        assertFalse(signature.contains("setDeviceCredentialAllowed"), "the deprecated way of allowing it either")
        assertTrue(signature.contains("setNegativeButtonText("), "without a device credential the dialog needs its own Cancel button")
    }

    @Test
    fun `the vault dialog accepts a biometric or the device credential, with no negative button and no CryptoObject`() {
        val host = host
        val vault = bodyOf(host, "private fun showVault(")

        assertTrue(
            Regex("""\.setAllowedAuthenticators\(\s*vaultAuthenticators\(\s*Build\.VERSION\.SDK_INT\s*\)\s*\)""")
                .containsMatchIn(vault),
            "the combination of the vault, for the API of the phone",
        )
        // A combination written out here brings back the Android 10 bug (see the next test).
        assertFalse(vault.contains("DEVICE_CREDENTIAL"), "no combination written out in the dialog")
        // The system forbids a negative button with DEVICE_CREDENTIAL: the dialog is closed with the back gesture.
        assertFalse(vault.contains("setNegativeButtonText("), "no negative button")
        assertFalse(vault.contains("CryptoObject"), "a success refreshes the authentication window of the key, nothing is signed")
        assertTrue(Regex("""prompt\.authenticate\(\s*promptInfo\s*\)""").containsMatchIn(vault), "authenticate(promptInfo)")
        // The title and subtitle are those of the purpose: the dialog says what it is for.
        assertTrue(vault.contains("request.purpose.titleRes") && vault.contains("request.purpose.subtitleRes"))
    }

    @Test
    fun `the combination of the vault depends on the API, in one place`() {
        val helper = code("core/crypto/BiometricHelper.kt")

        // androidx rejects BIOMETRIC_STRONG or DEVICE_CREDENTIAL (and DEVICE_CREDENTIAL alone) on API 28
        // and 29: a single constant left Android 10 with no way to unlock the vault. What the function
        // returns for each API is tested in BiometricHelperTest.
        assertTrue(helper.contains("internal fun vaultAuthenticators(sdkInt: Int)"), "one function of the API")
        // The tests give the helper its API by hand: only this checks that the app gives it the real one.
        assertTrue(
            Regex("""sdkInt\s*=\s*Build\.VERSION\.SDK_INT""").containsMatchIn(helper),
            "the injected helper checks the capability for the API of the phone",
        )
        assertFalse(helper.contains("VAULT_AUTHENTICATORS"), "no constant combination left")
        assertTrue(
            Regex("""canAuthenticateWith\(\s*vaultAuthenticators\(\s*sdkInt\s*\)\s*\)""").containsMatchIn(helper),
            "the capability check and the dialog agree",
        )
    }

    @Test
    fun `the dialog authorizes the signature of the request through a CryptoObject, in one place`() {
        val host = host

        assertTrue(
            Regex("""authenticate\(\s*promptInfo\s*,\s*BiometricPrompt\.CryptoObject\(\s*request\.signature\s*\)\s*\)""")
                .containsMatchIn(host),
            "authenticate(promptInfo, CryptoObject(request.signature))",
        )
        assertEquals(1, count(host, "CryptoObject("), "one CryptoObject")
        // One call per kind of dialog, and no other way to start one.
        assertEquals(2, count(host, ".authenticate("), "one authenticate for the signature, one for the vault")
    }

    @Test
    fun `no other class builds a BiometricPrompt, every dialog goes through the coordinator`() {
        val root = ProjectFiles.mainSourceDir()

        val builders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""(?<![A-Za-z.])BiometricPrompt\(""").containsMatchIn(blankComments(it.readText())) }
            .map { it.name }
            .toList()

        assertEquals(listOf("BiometricPromptHost.kt"), builders, "BiometricHelper no longer shows a prompt of its own")
        assertFalse(code("core/crypto/BiometricHelper.kt").contains("fun authenticate("), "the direct prompt is gone")
    }

    // ── One controller, two rules ─────────────────────────────────────────────

    @Test
    fun `the host builds one controller per activity and gives it the Android side`() {
        val host = host

        assertTrue(host.contains("LaunchedEffect(activity)"), "one controller per activity, not per request")
        assertEquals(1, count(host, "BiometricDialogController("), "a single controller")
        assertTrue(host.contains("coordinator.state.collect { controller.sync() }"), "it follows the coordinator")
        assertTrue(host.contains("Lifecycle.Event.ON_RESUME"), "it is told when the activity resumes")
        assertTrue(host.contains("controller.onResumed()"), "through onResumed, which does not launch by itself")
        assertTrue(host.contains("controller.dispose()"), "and lets go of the dialog when it is left")
    }

    @Test
    fun `the controller is started before the lifecycle observer that replays the resume`() {
        val host = host

        val start = host.indexOf("controller.start()")
        assertTrue(start >= 0, "start() is called")
        assertTrue(start < host.indexOf("addObserver("), "start() before addObserver(): the replayed ON_RESUME finds the adopted dialog")
        assertTrue(host.indexOf("try {") < start, "and inside the try whose finally disposes")
    }

    @Test
    fun `a BiometricPrompt is built for each launch, and only by the Android dialog`() {
        val host = host
        val controller = controller

        assertEquals(1, count(host, "BiometricPrompt("), "one construction, in AndroidBiometricDialog")
        // Building it is what puts the callback in the activity's slot, which BiometricHelper may have taken.
        val launch = bodyOf(controller, "private fun launch(")
        assertTrue(launch.contains("newDialog(forwarder)"), "a new dialog at each launch")
        assertTrue(launch.indexOf("markLaunched(") in 0 until launch.indexOf("newDialog(forwarder)"), "markLaunched first")
        assertTrue(launch.indexOf("newDialog(forwarder)") < launch.indexOf(".show(request)"), "built before it is shown")
    }

    @Test
    fun `a launch never runs inside a callback of androidx, nor inside the resume`() {
        val controller = controller

        // The one place that starts a dialog is launchNow, which only a delayed task calls.
        assertEquals(2, Regex("""\blaunch\(""").findAll(controller).count(), "the definition and the one call")
        assertTrue(bodyOf(controller, "private fun launchNow(").contains("launch(current)"))
        assertEquals(1, count(controller, ".show("), "one show in the controller")
        val scheduled = bodyOf(controller, "private fun scheduleLaunch(")
        assertTrue(scheduled.contains("delayer.postDelayed(LAUNCH_SETTLE_MS)"), "behind the settle time")
        assertTrue(scheduled.contains("launchScheduled"), "one launch at a time")
        assertTrue(scheduled.indexOf("launchNow()") > scheduled.indexOf("postDelayed"), "launchNow runs inside the delayed task")

        // None of the entry points that run in a callback, in the resume or in a state change starts one.
        for (signature in listOf(
            "fun start(", "fun onResumed(", "fun sync(", "private fun acknowledgeClose(", "private fun finish(",
            "override fun onSucceeded(", "override fun onError(", "override fun onNotRecognized(", "private fun beginCancel(",
        )) {
            val body = bodyOf(controller, signature)
            assertFalse(Regex("""\blaunch\(|\blaunchNow\(|\.show\(""").containsMatchIn(body), "$signature must not start a dialog")
        }
        assertTrue(bodyOf(controller, "fun sync(").contains("scheduleLaunch()"), "sync only schedules")
    }

    @Test
    fun `a launch checks again when it fires`() {
        val launchNow = bodyOf(controller, "private fun launchNow(")

        assertTrue(launchNow.contains("canLaunch()"), "the activity is still resumed")
        assertTrue(launchNow.contains("coordinator.state.value"), "the coordinator still holds a request")
        assertTrue(launchNow.contains("shown != null"), "no dialog is up")
        assertTrue(launchNow.contains("disposed"), "the controller is still there")
    }

    @Test
    fun `a dialog is never launched while the previous one is closing`() {
        val sync = bodyOf(controller, "fun sync()")

        assertTrue(
            Regex("""cancelling\s*->\s*Unit""").containsMatchIn(sync),
            "while Cancelling, sync waits for the acknowledgement",
        )
        assertTrue(bodyOf(controller, "private fun scheduleLaunch(").contains("canLaunch()"), "and launches only while the activity is resumed")
    }

    @Test
    fun `the acknowledgement timer runs only while the activity is resumed, and starts again on return`() {
        val controller = controller

        assertTrue(Regex("""CANCEL_ACK_TIMEOUT_MS\s*=\s*1_500L""").containsMatchIn(controller))
        assertTrue(Regex("""LAUNCH_SETTLE_MS\s*=\s*250L""").containsMatchIn(controller))
        assertTrue(bodyOf(controller, "private fun beginCancel(").contains("if (canLaunch()) startAcknowledgementTimer()"), "not started while stopped")
        val timer = bodyOf(controller, "private fun startAcknowledgementTimer(")
        assertTrue(timer.contains("delayer.postDelayed(CANCEL_ACK_TIMEOUT_MS)"))
        assertTrue(timer.contains("if (canLaunch()) acknowledgeClose()"), "a timer that fires while stopped closes nothing")
        val resumed = bodyOf(controller, "fun onResumed(")
        assertTrue(resumed.contains("cancelling && cancelTimeout == null"), "and starts again at the resume")
        assertTrue(resumed.contains("startAcknowledgementTimer()"))
    }

    @Test
    fun `an error delivered while a dialog is cancelled belongs to the cancelled request and is dropped`() {
        val onError = bodyOf(controller, "override fun onError(")

        val dropped = onError.substringAfter("if (cancelling)").substringBefore("return")
        assertTrue(dropped.contains("acknowledgeClose()"), "it is the acknowledgement")
        assertFalse(dropped.contains("coordinator."), "and answers nobody")
        assertTrue(
            onError.indexOf("if (cancelling)") < onError.indexOf("coordinator.reportError("),
            "the check comes before the request is answered",
        )
    }

    @Test
    fun `a rotation does not cancel the request, but a host that goes away for good closes the dialog`() {
        val dispose = bodyOf(controller, "fun dispose()")

        val guard = dispose.indexOf("if (isChangingConfigurations())")
        assertTrue(guard >= 0, "a configuration change is told apart")
        assertTrue(dispose.indexOf("forwarder.orphan(request, cancelling)") > guard, "and hands the dialog to the forwarder")
        val forGood = dispose.indexOf("forwarder.detach()")
        assertTrue(forGood > guard, "anything else drops the forwarder")
        assertTrue(forGood < dispose.indexOf("dialog?.cancel()"), "the dialog is cancelled after the check")
        assertTrue(forGood < dispose.indexOf("coordinator.cancel("), "the request is answered after the check")
        assertTrue(forGood < dispose.indexOf("coordinator.markClosed("), "the dialog is declared closed after the check")
        // The dialog started by whoever hosted before is found again by the next controller.
        assertTrue(bodyOf(controller, "fun start()").contains("coordinator.openDialog()"))
    }

    // ── What androidx keeps, and the activity ─────────────────────────────────

    @Test
    fun `the callback of androidx goes through a forwarder and holds no activity`() {
        val host = host
        val controller = controller

        // The callback is built by a function at the top level of the file, not inside a class that holds an activity.
        assertTrue(
            Regex("""(?m)^private fun forwardingCallback\(events: BiometricDialogEvents\)""").containsMatchIn(host),
            "forwardingCallback is a top-level function",
        )
        val callback = bodyOf(host, "private fun forwardingCallback(")
        assertFalse(callback.contains("activity"), "it mentions no activity")
        assertTrue(host.contains("forwardingCallback(events)"), "and it is what BiometricPrompt gets")
        // The controller hands the dialogs its forwarder, never itself.
        assertFalse(controller.contains("newDialog(this)"), "never the controller itself")
        assertEquals(2, count(controller, "newDialog(forwarder)"), "a launch, and a take-over")
        // The forwarder holds the coordinator, drops the controller at detach and orphan.
        val forwarderCode = controller.substringAfter("class DialogEventForwarder")
        assertTrue(forwarderCode.contains("private val coordinator: BiometricPromptCoordinator"))
        assertFalse(forwarderCode.contains("activity"), "the forwarder holds no activity")
        assertTrue(bodyOf(forwarderCode, "fun detach(").contains("target = null"))
        assertTrue(bodyOf(forwarderCode, "fun orphan(").contains("target = null"))
    }

    @Test
    fun `a result between two controllers is routed to the coordinator, once`() {
        val forwarder = controller.substringAfter("class DialogEventForwarder")

        val onSucceeded = bodyOf(forwarder, "override fun onSucceeded(")
        assertTrue(onSucceeded.contains("takeOrphan()"), "the first result settles the orphaned dialog")
        assertTrue(onSucceeded.contains("coordinator.authorize(request)") && onSucceeded.contains("coordinator.markClosed(request)"))
        assertTrue(onSucceeded.contains("request.isAuthorizedBy(authorizedSignature)"), "with the same identity check")
        val onError = bodyOf(forwarder, "override fun onError(")
        assertTrue(onError.contains("if (!orphanCancelling) coordinator.reportError(request, errorCode)"), "an error of a dialog being closed answers nobody")
        assertTrue(onError.contains("coordinator.markClosed(request)"))
    }

    // ── What the dialog answers ───────────────────────────────────────────────

    @Test
    fun `the identity of the authorized Signature is checked, and a dialog that cannot start fails the request`() {
        val controller = controller

        val onSucceeded = bodyOf(controller, "override fun onSucceeded(")
        assertTrue(onSucceeded.contains("request.isAuthorizedBy(authorizedSignature)"), "the very Signature handed over")
        assertTrue(onSucceeded.contains("coordinator.fail(request, BiometricFailure.OTHER)"), "anything else fails the request")
        // The check itself: a signature request needs its own Signature back, the vault request has none.
        val isAuthorizedBy = bodyOf(code("core/ssh/BiometricRequest.kt"), "fun BiometricRequest.isAuthorizedBy(")
        assertTrue(isAuthorizedBy.contains("authorizedSignature != null && authorizedSignature === signature"), "identity, never equality")
        assertTrue(isAuthorizedBy.contains("is VaultAuthRequest -> authorizedSignature == null"), "only a success without signature is the answer of the vault")

        val launch = bodyOf(controller, "private fun launch(")
        assertTrue(launch.contains("catch (e: CancellationException)"), "a cancellation is not a failure to start")
        assertTrue(launch.contains("catch (e: Exception)"), "authenticate() cannot crash the app")
        assertTrue(launch.contains("errorClass(e)"), "the class only is logged")
        assertTrue(launch.contains("coordinator.fail(request, BiometricFailure.OTHER)"))
    }

    @Test
    fun `the callback answers with the numeric code only and a finger not recognized is no end`() {
        val host = host
        val controller = controller

        assertTrue(host.contains("events.onError(errorCode)"), "the code goes to the controller")
        // The system text is a parameter of the override and goes no further: not to the log, not to the coordinator.
        assertEquals(1, count(host, "errString"))
        assertEquals(0, count(controller, "errString"))
        val notRecognized = bodyOf(controller, "override fun onNotRecognized()")
        assertFalse(notRecognized.contains("coordinator."), "onNotRecognized lets the user try again")
    }

    // ── The app around it ─────────────────────────────────────────────────────

    @Test
    fun `the host is rendered by MainActivity and installed by the App`() {
        val main = code("MainActivity.kt")
        val app = code("App.kt")

        assertTrue(main.contains("BiometricPromptHost(coordinator = biometricPromptCoordinator)"), "rendered next to Fido2PromptHost")
        assertTrue(app.contains("biometricPromptCoordinator.install(sshSessionManager)"), "installed once, at app start")
    }

    @Test
    fun `no screen touches the secure flag for the dialog`() {
        // The app protects its window for good (ScreenCapturePolicy); the host leaves it alone.
        assertFalse(host.contains("FLAG_SECURE"))
        assertFalse(host.contains("clearFlags"))
    }
}
