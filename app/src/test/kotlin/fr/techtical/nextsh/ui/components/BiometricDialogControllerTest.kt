// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.biometric.BiometricPrompt
import fr.techtical.nextsh.core.ssh.BiometricFailure
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.BiometricPromptResponse
import fr.techtical.nextsh.core.ssh.BiometricRequest
import fr.techtical.nextsh.core.ssh.BiometricSignRequest
import fr.techtical.nextsh.core.ssh.VaultAuthPurpose
import fr.techtical.nextsh.core.ssh.VaultAuthRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.Signature

/**
 * The controller that drives the system fingerprint dialog, with a fake dialog and a fake clock for
 * the two timers it uses (the settle time before a launch, the acknowledgement of a cancel): the
 * dialog is launched once, never inside a callback and never before the previous one is confirmed
 * closed, cancelled when its request ends, and the answer of a dialog that was cancelled never
 * reaches the request behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricDialogControllerTest {

    private lateinit var coordinator: BiometricPromptCoordinator

    private fun controllerTest(block: suspend TestScope.() -> Unit) = runTest {
        coordinator = BiometricPromptCoordinator { testScheduler.currentTime }
        block()
    }

    /** The dialog the controller drives: it records what it is asked, the test plays the system. */
    private class FakeDialog(val events: BiometricDialogEvents) : BiometricDialog {
        val shown = mutableListOf<BiometricRequest>()
        var cancels = 0
        var failToShow: Exception? = null
        var failToCancel: Exception? = null

        override fun show(request: BiometricRequest) {
            failToShow?.let { throw it }
            shown += request
        }

        override fun cancel() {
            cancels++
            failToCancel?.let { throw it }
        }
    }

    /** Runs the tasks it is given when the test says so. */
    private class FakeDelayer : Delayer {
        class Task(val delayMs: Long, val task: () -> Unit) {
            var cancelled = false
            var ran = false
        }

        val tasks = mutableListOf<Task>()

        override fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit {
            val entry = Task(delayMs, task)
            tasks += entry
            return { entry.cancelled = true }
        }

        /** Fires every task of [delayMs] still waiting (those a task schedules while it runs wait for the next call). */
        fun fire(delayMs: Long) {
            tasks.filter { it.delayMs == delayMs && !it.cancelled && !it.ran }.forEach {
                it.ran = true
                it.task()
            }
        }

        fun waiting(delayMs: Long) = tasks.count { it.delayMs == delayMs && !it.cancelled && !it.ran }

        val waiting get() = tasks.count { !it.cancelled && !it.ran }
    }

    private val delayer = FakeDelayer()
    private val dialogs = mutableListOf<FakeDialog>()
    private var resumed = true
    private var changingConfigurations = false
    private var failNextShow: Exception? = null
    private var failNewDialog: Exception? = null

    private fun newController() = BiometricDialogController(
        coordinator = coordinator,
        canLaunch = { resumed },
        isChangingConfigurations = { changingConfigurations },
        delayer = delayer,
        newDialog = { events ->
            failNewDialog?.let { throw it }
            FakeDialog(events).also { dialog ->
                dialogs += dialog
                dialog.failToShow = failNextShow
            }
        },
    )

    /** The settle time of a launch has passed. */
    private fun settle() = delayer.fire(BiometricDialogController.LAUNCH_SETTLE_MS)

    /** The acknowledgement timer of a cancel has run out. */
    private fun ackTimeout() = delayer.fire(BiometricDialogController.CANCEL_ACK_TIMEOUT_MS)

    private fun request(label: String = "Host") = BiometricSignRequest(
        signature = Signature.getInstance("SHA256withECDSA"),
        hostLabel = label,
        keyLabel = "Key",
    )

    private val BiometricSignRequest.dialogsShowing get() = dialogs.count { this in it.shown }

    // ── Launching ─────────────────────────────────────────────────────────────

    @Test
    fun `a published request schedules one launch, which starts one dialog once the queue has settled`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()

        controller.sync()
        controller.sync()
        controller.sync()

        assertTrue(dialogs.isEmpty(), "nothing starts inside the call that noticed the request")
        assertEquals(1, delayer.waiting(BiometricDialogController.LAUNCH_SETTLE_MS), "one launch, not three")
        settle()

        assertEquals(1, dialogs.size)
        assertEquals(listOf(request), dialogs.single().shown)
        assertSame(request, coordinator.openDialog())
        job.cancel()
    }

    @Test
    fun `nothing is scheduled while the activity is not resumed, the launch comes when it is`() = controllerTest {
        val controller = newController()
        controller.start()
        resumed = false
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()

        controller.sync()
        assertEquals(0, delayer.waiting, "authenticate() is refused once the state is saved")

        resumed = true
        controller.onResumed()
        assertTrue(dialogs.isEmpty(), "the resume does not start the dialog either")
        settle()

        assertEquals(listOf(request), dialogs.single().shown)
        job.cancel()
    }

    @Test
    fun `a launch checks again when it fires that the activity is still resumed`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        controller.sync()

        resumed = false // the user left during the settle time
        settle()
        assertTrue(dialogs.isEmpty())
        assertNull(coordinator.openDialog())

        resumed = true
        controller.onResumed()
        settle()

        assertEquals(listOf(request), dialogs.single().shown)
        job.cancel()
    }

    @Test
    fun `a launch checks again when it fires that the coordinator still holds the request`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        controller.sync()

        job.cancel() // the connection ended during the settle time
        runCurrent()
        settle()

        assertTrue(dialogs.isEmpty(), "no dialog for a request that is gone")
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `the launch gives the dialog its own minute`() = controllerTest {
        val controller = newController()
        controller.start()
        resumed = false
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        advanceTimeBy(59_000)

        resumed = true
        controller.onResumed()
        settle()

        assertEquals(59_000L + BiometricPromptCoordinator.PROMPT_TIMEOUT_MS, coordinator.state.value!!.deadlineMs)
        job.cancel()
    }

    @Test
    fun `a dialog that cannot start fails the request instead of crashing, and the next one starts`() = controllerTest {
        val controller = newController()
        controller.start()
        failNextShow = IllegalStateException("fragment manager is gone")
        val first = request("First")
        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()

        controller.sync()
        settle()
        runCurrent()

        assertEquals(BiometricPromptResponse.Failed(BiometricFailure.OTHER), firstResponse.await())
        assertNull(coordinator.openDialog(), "nothing is up")
        failNextShow = null
        val second = request("Second")
        val secondJob = launch { coordinator.prompt(second) }
        runCurrent()
        controller.sync()
        settle()
        assertEquals(listOf(second), dialogs.last().shown)
        secondJob.cancel()
    }

    @Test
    fun `a controller that goes away drops the launch it was waiting to make`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        controller.sync()
        assertEquals(1, delayer.waiting)

        controller.dispose()

        assertEquals(0, delayer.waiting)
        settle()
        assertTrue(dialogs.isEmpty())
        job.cancel()
    }

    // ── Answers ───────────────────────────────────────────────────────────────

    @Test
    fun `the signature of the request coming back authorizes it, and the dialog is not started again`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        controller.sync()
        settle()

        dialogs.single().events.onSucceeded(request.signature)
        // The coordinator still holds the request until the connection retires it: a launch is
        // scheduled, and finds the request launched for good.
        settle()

        assertEquals(1, dialogs.size, "the request is launched for good")
        assertEquals(BiometricPromptResponse.Authorized, response.await())
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `an authorization that comes back with another signature, or none, fails the request`() = controllerTest {
        for (returned in listOf<Signature?>(Signature.getInstance("SHA256withECDSA"), null)) {
            val controller = newController()
            controller.start()
            val request = request()
            val response = async { coordinator.prompt(request) }
            runCurrent()
            controller.sync()
            settle()

            dialogs.last().events.onSucceeded(returned)

            assertEquals(BiometricPromptResponse.Failed(BiometricFailure.OTHER), response.await(), "$returned")
            assertNull(coordinator.openDialog())
            controller.dispose()
        }
    }

    @Test
    fun `the error codes of the dialog are the answers of the request`() = controllerTest {
        val cases = listOf(
            BiometricPrompt.ERROR_NEGATIVE_BUTTON to BiometricPromptResponse.Cancelled,
            BiometricPrompt.ERROR_USER_CANCELED to BiometricPromptResponse.Cancelled,
            BiometricPrompt.ERROR_LOCKOUT to BiometricPromptResponse.Failed(BiometricFailure.LOCKOUT),
            BiometricPrompt.ERROR_HW_NOT_PRESENT to BiometricPromptResponse.Failed(BiometricFailure.HW_NOT_PRESENT),
            BiometricPrompt.ERROR_TIMEOUT to null,
        )
        for ((code, expected) in cases) {
            val controller = newController()
            controller.start()
            val request = request()
            val response = async { coordinator.prompt(request) }
            runCurrent()
            controller.sync()
            settle()

            dialogs.last().events.onError(code)

            assertEquals(expected, response.await(), "code $code")
            assertNull(coordinator.openDialog(), "code $code: the dialog is over")
            controller.dispose()
        }
    }

    @Test
    fun `a finger that is not recognized ends nothing`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        controller.sync()
        settle()

        dialogs.single().events.onNotRecognized()
        controller.sync()

        assertFalse(response.isCompleted)
        assertSame(request, coordinator.openDialog())
        assertEquals(0, dialogs.single().cancels)
        response.cancel()
    }

    @Test
    fun `callbacks with no dialog up are ignored`() = controllerTest {
        val controller = newController()
        controller.start()

        controller.onSucceeded(Signature.getInstance("SHA256withECDSA"))
        controller.onError(BiometricPrompt.ERROR_CANCELED)
        controller.onNotRecognized()

        assertTrue(dialogs.isEmpty())
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `an authorization that arrives after the coordinator moved on to the next request answers nobody and ends nobody`() = controllerTest {
        val controller = newController()
        controller.start()
        val a = request("A")
        val b = request("B")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        controller.sync()
        settle()
        val bResponse = async { coordinator.prompt(b) }
        runCurrent()

        aJob.cancel() // A ends, B is published, the controller has not looked yet
        runCurrent()
        assertSame(b, coordinator.state.value?.request)
        dialogs.single().events.onSucceeded(a.signature)

        assertFalse(bResponse.isCompleted, "the authorization of A is not B's")
        assertNull(coordinator.openDialog(), "the dialog of A is over")
        settle()
        assertEquals(listOf(b), dialogs[1].shown, "B gets its own dialog, once the queue has settled")
        dialogs[1].events.onSucceeded(b.signature)
        assertEquals(BiometricPromptResponse.Authorized, bResponse.await())
    }

    // ── The request ends on the coordinator side ──────────────────────────────

    @Test
    fun `when the request ends on its own the dialog is cancelled and the controller waits for the acknowledgement`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        controller.sync()
        settle()

        job.cancel() // the connection was cancelled
        runCurrent()
        controller.sync()
        controller.sync()

        assertEquals(1, dialogs.single().cancels, "asked once")
        assertSame(request, coordinator.openDialog(), "not closed until the system says so")
        assertEquals(1, delayer.waiting(BiometricDialogController.CANCEL_ACK_TIMEOUT_MS))

        dialogs.single().events.onError(BiometricPrompt.ERROR_CANCELED)

        assertNull(coordinator.openDialog())
        assertEquals(0, delayer.waiting(BiometricDialogController.CANCEL_ACK_TIMEOUT_MS), "the timeout went with the acknowledgement")
    }

    @Test
    fun `when the deadline ends the request the dialog is cancelled too`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        controller.sync()
        settle()

        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS + 1) // nobody presented a finger
        runCurrent()
        assertNull(response.await())
        assertNull(coordinator.state.value)
        controller.sync()

        assertEquals(1, dialogs.single().cancels)
        dialogs.single().events.onError(BiometricPrompt.ERROR_CANCELED)
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `the next request never gets the cancellation of the dialog that ended before it`() = controllerTest {
        // A shown, B queued, A ends: the cancel of A answers later, through the callback.
        val controller = newController()
        controller.start()
        val a = request("A")
        val b = request("B")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        controller.sync()
        settle()
        val bResponse = async { coordinator.prompt(b) }
        runCurrent()
        assertSame(a, coordinator.state.value?.request)

        aJob.cancel()
        runCurrent()
        assertSame(b, coordinator.state.value?.request, "Compose sees A, then B, in one pass")
        controller.sync()

        assertEquals(1, dialogs.size, "B waits: the dialog of A is not confirmed closed")
        assertEquals(1, dialogs.single().cancels)
        assertFalse(bResponse.isCompleted)

        // The late ERROR_CANCELED of A: it belongs to A.
        dialogs.single().events.onError(BiometricPrompt.ERROR_CANCELED)
        runCurrent()

        assertFalse(bResponse.isCompleted, "B is not cancelled by the end of A")
        assertEquals(1, dialogs.size, "and B is not launched inside the callback that closed A")
        assertEquals(1, delayer.waiting(BiometricDialogController.LAUNCH_SETTLE_MS))
        settle()
        assertEquals(2, dialogs.size, "B has its own dialog now")
        assertEquals(listOf(b), dialogs[1].shown)
        assertSame(b, coordinator.openDialog())

        dialogs[1].events.onSucceeded(b.signature)
        assertEquals(BiometricPromptResponse.Authorized, bResponse.await())
    }

    @Test
    fun `an operating system that sends no acknowledgement does not block the next request`() = controllerTest {
        val controller = newController()
        controller.start()
        val a = request("A")
        val b = request("B")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        controller.sync()
        settle()
        val bJob = launch { coordinator.prompt(b) }
        runCurrent()
        aJob.cancel()
        runCurrent()
        controller.sync()
        assertEquals(1, dialogs.size)

        ackTimeout() // 1.5 s later
        assertEquals(1, dialogs.size, "closed, and the launch waits for the queue like any other")
        settle()

        assertEquals(2, dialogs.size)
        assertEquals(listOf(b), dialogs[1].shown)
        assertSame(b, coordinator.openDialog())
        bJob.cancel()
    }

    @Test
    fun `an authorization that arrives while the dialog is being cancelled is not used, and is the acknowledgement`() = controllerTest {
        val controller = newController()
        controller.start()
        val a = request("A")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        controller.sync()
        settle()
        aJob.cancel()
        runCurrent()
        controller.sync()

        dialogs.single().events.onSucceeded(a.signature)

        assertNull(coordinator.openDialog())
        assertEquals(1, dialogs.size)
    }

    @Test
    fun `a cancel that fails is survived, the timeout closes the dialog`() = controllerTest {
        val controller = newController()
        controller.start()
        val a = request("A")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        controller.sync()
        settle()
        dialogs.single().failToCancel = IllegalStateException("no fragment")
        aJob.cancel()
        runCurrent()

        controller.sync()
        assertSame(a, coordinator.openDialog())
        ackTimeout()

        assertNull(coordinator.openDialog())
    }

    @Test
    fun `requests that end while a dialog is closing are skipped, the one that is current then is launched`() = controllerTest {
        val controller = newController()
        controller.start()
        val a = request("A")
        val b = request("B")
        val c = request("C")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        controller.sync()
        settle()
        val bJob = launch { coordinator.prompt(b) }
        runCurrent()
        val cResponse = async { coordinator.prompt(c) }
        runCurrent()

        aJob.cancel() // A ends, B is published: the dialog of A is cancelled
        runCurrent()
        controller.sync()
        assertEquals(1, dialogs.single().cancels)
        bJob.cancel() // B ends while A is still closing, C is published
        runCurrent()
        assertSame(c, coordinator.state.value?.request)
        controller.sync()
        assertEquals(1, dialogs.single().cancels, "not cancelled twice")

        dialogs.single().events.onError(BiometricPrompt.ERROR_CANCELED)
        settle()

        assertEquals(0, b.dialogsShowing, "B was never current when the dialog could start")
        assertEquals(listOf(c), dialogs[1].shown)
        assertFalse(cResponse.isCompleted)
        dialogs[1].events.onSucceeded(c.signature)
        assertEquals(BiometricPromptResponse.Authorized, cResponse.await())
    }

    // ── The acknowledgement waits for the activity to be there to receive it ──

    @Test
    fun `no acknowledgement timer runs while the activity is stopped, and the error flushed on return is the acknowledgement`() = controllerTest {
        val controller = newController()
        controller.start()
        val a = request("A")
        val b = request("B")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        controller.sync()
        settle()
        val bResponse = async { coordinator.prompt(b) }
        runCurrent()

        resumed = false // the user leaves the app with the dialog up
        aJob.cancel()
        runCurrent()
        controller.sync()

        assertEquals(1, dialogs.single().cancels, "the cancel is asked")
        assertEquals(0, delayer.waiting, "but no timer: the fragment cannot report while stopped")
        assertSame(a, coordinator.openDialog(), "so the dialog is not declared closed")

        resumed = true
        controller.onResumed() // ON_START has flushed the error, ON_RESUME is here
        assertEquals(1, delayer.waiting(BiometricDialogController.CANCEL_ACK_TIMEOUT_MS), "the timer starts again")
        assertEquals(0, delayer.waiting(BiometricDialogController.LAUNCH_SETTLE_MS), "B is not launched yet")
        dialogs.single().events.onError(BiometricPrompt.ERROR_CANCELED) // the flushed, stale error of A

        assertFalse(bResponse.isCompleted, "the stale error does not cancel B")
        settle()
        assertEquals(listOf(b), dialogs[1].shown, "B has a dialog of its own, launched behind the flush")
        dialogs[1].events.onSucceeded(b.signature)
        assertEquals(BiometricPromptResponse.Authorized, bResponse.await())
    }

    @Test
    fun `a timer that runs out while the activity is stopped does not close the dialog, it starts again on return`() = controllerTest {
        val controller = newController()
        controller.start()
        val a = request("A")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        controller.sync()
        settle()
        aJob.cancel()
        runCurrent()
        controller.sync()
        assertEquals(1, delayer.waiting(BiometricDialogController.CANCEL_ACK_TIMEOUT_MS))

        resumed = false // the activity stops with the timer pending
        ackTimeout()

        assertSame(a, coordinator.openDialog(), "not closed on a timer the stopped activity could not see through")
        assertEquals(0, delayer.waiting)

        resumed = true
        controller.onResumed()
        assertEquals(1, delayer.waiting(BiometricDialogController.CANCEL_ACK_TIMEOUT_MS))
        ackTimeout()
        assertNull(coordinator.openDialog())
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    @Test
    fun `a rotation leaves the dialog and the request alone, and the new controller takes them over`() = controllerTest {
        val old = newController()
        old.start()
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        old.sync()
        settle()
        assertEquals(1, dialogs.size)

        changingConfigurations = true
        old.dispose()
        changingConfigurations = false

        assertEquals(0, dialogs.single().cancels, "the system dialog is kept")
        assertFalse(response.isCompleted, "the request is not cancelled by the destruction of the activity")
        assertSame(request, coordinator.openDialog())

        val fresh = newController()
        fresh.start()
        fresh.sync()
        settle()

        assertEquals(2, dialogs.size, "a prompt for the new activity reconnects the callback")
        assertTrue(dialogs[1].shown.isEmpty(), "but no second dialog is launched for the request")

        dialogs[1].events.onSucceeded(request.signature)
        assertEquals(BiometricPromptResponse.Authorized, response.await())
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `after a rotation the new controller still cancels the dialog when its request ends`() = controllerTest {
        val old = newController()
        old.start()
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        old.sync()
        settle()
        changingConfigurations = true
        old.dispose()
        changingConfigurations = false
        val fresh = newController()
        fresh.start()

        job.cancel() // the connection ends after the rotation
        runCurrent()
        fresh.sync()

        // Before: the new effect never knew the dialog was up, and left it there.
        assertEquals(1, dialogs[1].cancels)
        dialogs[1].events.onError(BiometricPrompt.ERROR_CANCELED)
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `a request that ended during the rotation has its dialog cancelled by the new controller`() = controllerTest {
        val old = newController()
        old.start()
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        old.sync()
        settle()
        changingConfigurations = true
        old.dispose()
        job.cancel() // ends while there is no host
        runCurrent()
        changingConfigurations = false
        assertNull(coordinator.state.value)
        assertSame(request, coordinator.openDialog())

        val fresh = newController()
        fresh.start()

        assertEquals(1, dialogs[1].cancels)
        ackTimeout()
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `a new request is launched by the new controller once it has closed the dialog it took over`() = controllerTest {
        val old = newController()
        old.start()
        val a = request("A")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        old.sync()
        settle()
        changingConfigurations = true
        old.dispose()
        changingConfigurations = false
        val fresh = newController()
        fresh.start()
        val b = request("B")
        val bJob = launch { coordinator.prompt(b) }
        runCurrent()
        aJob.cancel()
        runCurrent()
        fresh.sync()

        assertEquals(1, dialogs[1].cancels)
        assertEquals(0, b.dialogsShowing, "B waits for the dialog of A to close")
        dialogs[1].events.onError(BiometricPrompt.ERROR_CANCELED)
        settle()

        assertEquals(1, b.dialogsShowing)
        bJob.cancel()
    }

    @Test
    fun `a rotation while a dialog is closing hands the closing to the new controller`() = controllerTest {
        val old = newController()
        old.start()
        val a = request("A")
        val b = request("B")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        old.sync()
        settle()
        val bResponse = async { coordinator.prompt(b) }
        runCurrent()
        aJob.cancel()
        runCurrent()
        old.sync()
        assertEquals(1, dialogs.single().cancels, "the old controller asked for the close")

        changingConfigurations = true
        old.dispose()
        changingConfigurations = false
        val fresh = newController()
        fresh.start()

        assertEquals(1, dialogs[1].cancels, "and the new one asks again, on a prompt of the new activity")
        assertSame(a, coordinator.openDialog(), "not closed yet")
        assertTrue(b.dialogsShowing == 0)
        dialogs[1].events.onError(BiometricPrompt.ERROR_CANCELED)
        settle()

        assertEquals(1, b.dialogsShowing)
        assertFalse(bResponse.isCompleted, "B is not cancelled by the end of A")
        dialogs[1].events.onSucceeded(b.signature)
        assertEquals(BiometricPromptResponse.Authorized, bResponse.await())
    }

    @Test
    fun `a result that comes between the old controller and the new one is routed to the request`() = controllerTest {
        val old = newController()
        old.start()
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        old.sync()
        settle()
        val callbackOfTheKeptDialog = dialogs.single().events

        changingConfigurations = true
        old.dispose()
        // The finger is accepted while the activity is being recreated: nobody is the controller.
        callbackOfTheKeptDialog.onSucceeded(request.signature)
        changingConfigurations = false

        assertEquals(BiometricPromptResponse.Authorized, response.await())
        assertNull(coordinator.openDialog(), "the dialog is closed")
        val fresh = newController()
        fresh.start()
        assertEquals(1, dialogs.size, "so the new controller has nothing to take over")
    }

    @Test
    fun `an error that comes between the two controllers answers the request as the controller would`() = controllerTest {
        val old = newController()
        old.start()
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        old.sync()
        settle()
        val callbackOfTheKeptDialog = dialogs.single().events
        changingConfigurations = true
        old.dispose()

        callbackOfTheKeptDialog.onError(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        callbackOfTheKeptDialog.onError(BiometricPrompt.ERROR_LOCKOUT) // settled once: a second one is dropped

        assertEquals(BiometricPromptResponse.Cancelled, response.await())
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `an error between the two controllers for a dialog that was being closed answers nobody`() = controllerTest {
        val old = newController()
        old.start()
        val a = request("A")
        val b = request("B")
        val aJob = launch { coordinator.prompt(a) }
        runCurrent()
        old.sync()
        settle()
        val bResponse = async { coordinator.prompt(b) }
        runCurrent()
        aJob.cancel()
        runCurrent()
        old.sync() // Cancelling
        val callbackOfTheKeptDialog = dialogs.single().events
        changingConfigurations = true
        old.dispose()

        callbackOfTheKeptDialog.onError(BiometricPrompt.ERROR_CANCELED) // the acknowledgement of A

        assertFalse(bResponse.isCompleted, "the error of A is not B's")
        assertNull(coordinator.openDialog())
        changingConfigurations = false
        val fresh = newController()
        fresh.start()
        settle()
        assertEquals(listOf(b), dialogs.last().shown, "B is launched by the new controller")
    }

    @Test
    fun `a dialog that cannot be built for the new activity does not break the take-over`() = controllerTest {
        val old = newController()
        old.start()
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        old.sync()
        settle()
        changingConfigurations = true
        old.dispose()
        changingConfigurations = false
        failNewDialog = IllegalStateException("fragment manager is gone")

        val fresh = newController()
        fresh.start()

        assertEquals(1, dialogs.size, "no dialog could be built")
        assertSame(request, coordinator.openDialog(), "the dialog is still believed up")
        job.cancel() // the request ends, and there is nothing to cancel it with
        runCurrent()
        fresh.sync()
        assertEquals(1, delayer.waiting(BiometricDialogController.CANCEL_ACK_TIMEOUT_MS))
        ackTimeout()

        assertNull(coordinator.openDialog(), "the timeout closes it, the next request is not blocked for ever")
    }

    // ── The host goes away for good ───────────────────────────────────────────

    @Test
    fun `a host that goes away for another reason cancels the dialog and ends the request as cancelled`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        controller.sync()
        settle()

        controller.dispose()

        assertEquals(1, dialogs.single().cancels)
        assertEquals(BiometricPromptResponse.Cancelled, response.await())
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `a disposed controller ignores the dialog and the state`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        controller.sync()
        settle()
        controller.dispose()
        val dialog = dialogs.single()

        dialog.events.onSucceeded(request.signature)
        dialog.events.onError(BiometricPrompt.ERROR_CANCELED)
        controller.sync()
        settle()

        assertEquals(1, dialogs.size)
        job.cancel()
    }

    @Test
    fun `disposing while a cancel is pending drops the timeout and closes the dialog`() = controllerTest {
        val controller = newController()
        controller.start()
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        controller.sync()
        settle()
        job.cancel()
        runCurrent()
        controller.sync()
        assertEquals(1, delayer.waiting)

        controller.dispose()

        assertEquals(0, delayer.waiting)
        assertNull(coordinator.openDialog())
        assertEquals(1, dialogs.single().cancels, "not asked twice")
    }

    @Test
    fun `disposing twice is harmless`() = controllerTest {
        val controller = newController()
        controller.start()

        controller.dispose()
        controller.dispose()

        assertTrue(dialogs.isEmpty())
    }

    @Test
    fun `a configuration change with nothing up leaves nothing behind`() = controllerTest {
        val controller = newController()
        controller.start()
        changingConfigurations = true

        controller.dispose()

        assertNull(coordinator.openDialog())
        assertEquals(0, delayer.waiting)
    }

    // ── The request of the vault ──────────────────────────────────────────────

    /** The vault prompt on screen, once the controller launched its dialog. */
    private fun TestScope.vaultPromptShown(
        controller: BiometricDialogController,
        purpose: VaultAuthPurpose = VaultAuthPurpose.UNLOCK,
    ): Pair<kotlinx.coroutines.Deferred<BiometricPromptResponse?>, VaultAuthRequest> {
        val response = async { coordinator.authenticateVault(purpose) }
        runCurrent()
        val request = coordinator.state.value!!.request as VaultAuthRequest
        controller.sync()
        settle()
        return response to request
    }

    @Test
    fun `a vault request is launched like a signature request, with the request itself`() = controllerTest {
        val controller = newController()
        controller.start()

        val (response, request) = vaultPromptShown(controller, VaultAuthPurpose.CONFIRM_SAVE)

        assertEquals(listOf<BiometricRequest>(request), dialogs.single().shown)
        assertEquals(VaultAuthPurpose.CONFIRM_SAVE, request.purpose)
        assertSame(request, coordinator.openDialog())
        response.cancel()
    }

    @Test
    fun `a success of the vault dialog authorizes it, there is no signature to check`() = controllerTest {
        val controller = newController()
        controller.start()
        val (response, _) = vaultPromptShown(controller)

        dialogs.single().events.onSucceeded(null)

        assertEquals(BiometricPromptResponse.Authorized, response.await())
        assertNull(coordinator.openDialog(), "the dialog is over")
        settle()
        assertEquals(1, dialogs.size, "and is not started again")
    }

    @Test
    fun `a success that comes back with a signature does not authorize a vault request, it had handed none over`() = controllerTest {
        val controller = newController()
        controller.start()
        val (response, _) = vaultPromptShown(controller)

        dialogs.single().events.onSucceeded(Signature.getInstance("SHA256withECDSA"))

        assertEquals(BiometricPromptResponse.Failed(BiometricFailure.OTHER), response.await())
        assertNull(coordinator.openDialog(), "the dialog is over")
    }

    @Test
    fun `a vault dialog the user closes is a cancellation, which the screen shows no message for`() = controllerTest {
        for (code in listOf(
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED,
        )) {
            val controller = newController()
            controller.start()
            val (response, _) = vaultPromptShown(controller)

            dialogs.last().events.onError(code)

            assertEquals(BiometricPromptResponse.Cancelled, response.await(), "code $code")
            assertNull(coordinator.openDialog(), "code $code: the dialog is over")
            controller.dispose()
        }
    }

    @Test
    fun `a vault dialog that ends in error answers with what the code means`() = controllerTest {
        val cases = listOf(
            BiometricPrompt.ERROR_LOCKOUT to BiometricPromptResponse.Failed(BiometricFailure.LOCKOUT),
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL to BiometricPromptResponse.Failed(BiometricFailure.OTHER),
            BiometricPrompt.ERROR_TIMEOUT to null,
        )
        for ((code, expected) in cases) {
            val controller = newController()
            controller.start()
            val (response, _) = vaultPromptShown(controller)

            dialogs.last().events.onError(code)

            assertEquals(expected, response.await(), "code $code")
            controller.dispose()
        }
    }

    @Test
    fun `a vault dialog that cannot start fails the request instead of crashing`() = controllerTest {
        val controller = newController()
        controller.start()
        failNextShow = IllegalArgumentException("Authenticator combination is unsupported on API 29")
        val response = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()

        controller.sync()
        settle()
        runCurrent()

        assertEquals(BiometricPromptResponse.Failed(BiometricFailure.OTHER), response.await())
        assertNull(coordinator.openDialog(), "nothing is up")
    }

    @Test
    fun `a not recognized finger ends nothing for the vault either`() = controllerTest {
        val controller = newController()
        controller.start()
        val (response, request) = vaultPromptShown(controller)

        dialogs.single().events.onNotRecognized()
        controller.sync()

        assertFalse(response.isCompleted)
        assertSame(request, coordinator.openDialog())
        assertEquals(0, dialogs.single().cancels)
        response.cancel()
    }

    @Test
    fun `when the vault request ends on its own the dialog is cancelled and waited for`() = controllerTest {
        val controller = newController()
        controller.start()
        val (response, request) = vaultPromptShown(controller)

        response.cancel() // the screen that asked is gone
        runCurrent()
        controller.sync()

        assertEquals(1, dialogs.single().cancels)
        assertSame(request, coordinator.openDialog(), "not closed until the system says so")
        dialogs.single().events.onError(BiometricPrompt.ERROR_CANCELED)
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `a vault request behind a signature request waits for its dialog to be confirmed closed`() = controllerTest {
        val controller = newController()
        controller.start()
        val signature = request("A")
        val signatureResponse = async { coordinator.prompt(signature) }
        runCurrent()
        controller.sync()
        settle()
        val vault = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        assertSame(signature, coordinator.state.value?.request, "one dialog at a time")

        dialogs.single().events.onSucceeded(signature.signature)
        assertEquals(BiometricPromptResponse.Authorized, signatureResponse.await())
        runCurrent()
        controller.sync()
        assertEquals(1, dialogs.size, "not launched inside the callback that closed the first one")
        settle()

        assertEquals(2, dialogs.size)
        val vaultRequest = dialogs[1].shown.single()
        assertTrue(vaultRequest is VaultAuthRequest)
        dialogs[1].events.onSucceeded(null)
        assertEquals(BiometricPromptResponse.Authorized, vault.await())
    }

    @Test
    fun `a signature request behind a vault request is still authorized by its own signature only`() = controllerTest {
        val controller = newController()
        controller.start()
        val (vault, vaultRequest) = vaultPromptShown(controller)
        val signature = request("B")
        val signatureResponse = async { coordinator.prompt(signature) }
        runCurrent()

        dialogs.single().events.onSucceeded(null)
        assertEquals(BiometricPromptResponse.Authorized, vault.await())
        runCurrent()
        controller.sync()
        settle()
        assertEquals(listOf<BiometricRequest>(signature), dialogs[1].shown)
        assertNotEquals(vaultRequest, signature)

        dialogs[1].events.onSucceeded(null) // no signature: the request of a key is not authorized
        assertEquals(BiometricPromptResponse.Failed(BiometricFailure.OTHER), signatureResponse.await())
    }

    @Test
    fun `a rotation leaves the vault dialog alone, and a success between the controllers is routed to its request`() = controllerTest {
        val old = newController()
        old.start()
        val (response, _) = vaultPromptShown(old)
        val callbackOfTheKeptDialog = dialogs.single().events
        changingConfigurations = true
        old.dispose()
        assertEquals(0, dialogs.single().cancels, "the system dialog is kept")
        assertFalse(response.isCompleted)

        callbackOfTheKeptDialog.onSucceeded(null)
        changingConfigurations = false

        assertEquals(BiometricPromptResponse.Authorized, response.await())
        assertNull(coordinator.openDialog(), "the dialog is closed")
    }

    @Test
    fun `a rotation, then the new controller takes the vault dialog over and its success authorizes`() = controllerTest {
        val old = newController()
        old.start()
        val (response, request) = vaultPromptShown(old)
        changingConfigurations = true
        old.dispose()
        changingConfigurations = false

        val fresh = newController()
        fresh.start()
        fresh.sync()
        settle()

        assertEquals(2, dialogs.size, "a prompt for the new activity reconnects the callback")
        assertTrue(dialogs[1].shown.isEmpty(), "but no second dialog is launched for the request")
        assertSame(request, coordinator.openDialog())
        dialogs[1].events.onSucceeded(null)
        assertEquals(BiometricPromptResponse.Authorized, response.await())
        assertNull(coordinator.openDialog())
    }
}
