// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.AppVisibility
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The prompt for the key of an unknown server must work whichever screen, or none, starts the
 * connection: one prompt at a time and in turn (never one over another), a deadline, and an end
 * at once when nobody is on screen to read it. Time is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostKeyPromptCoordinatorTest {

    /** Whether the app is on screen; the coordinator reads it through [AppVisibility]. */
    private var visible = true
    private val turn = InteractivePromptTurn()

    /** What the known hosts hold by now for a request; a test makes it so when a prompt "trusts" a key. */
    private var stored: (HostKeyVerifyResult.Unknown) -> StoredKeyState = { StoredKeyState.NONE }

    /** The virtual clock of the running test: the coordinator measures the gap and the arming on it. */
    private var scheduler: TestCoroutineScheduler? = null
    private val virtualNow: () -> Long = { scheduler?.currentTime ?: 0L }

    /**
     * The coordinator under test, with a confirm button that is armed at once: most tests answer the
     * moment a dialog is up. The arming has its own tests, on [armedCoordinator].
     */
    private val coordinator = HostKeyPromptCoordinator(turn, virtualNow, AppVisibility { visible }, { stored(it) }, confirmArmMs = 0L)

    /** The coordinator as the app has it: the "trust" answer is taken only after [HostKeyPromptCoordinator.CONFIRM_ARM_MS]. */
    private val armedCoordinator = HostKeyPromptCoordinator(turn, virtualNow, AppVisibility { visible }, { stored(it) })

    private fun coordinatorTest(block: suspend TestScope.() -> Unit) = runTest {
        scheduler = testScheduler
        block()
    }

    /** The gap between two dialogs goes by: the prompt next in line is up. */
    private fun TestScope.nextDialogUp() {
        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_GAP_MS)
        runCurrent()
    }

    private fun unknown(host: String = "prod.example.org:22", fingerprint: String = "SHA256:abc") =
        HostKeyVerifyResult.Unknown(hostname = host, algorithm = "ssh-ed25519", fingerprint = fingerprint)

    private fun onScreen(): HostKeyDialogState? = coordinator.state.value

    // ── One prompt, every way it ends ─────────────────────────────────────────

    @Test
    fun `no dialog is shown before a request`() {
        assertNull(onScreen())
    }

    @Test
    fun `the dialog shows the host and the fingerprint, and trusting it answers Trusted`() = coordinatorTest {
        val response = async { coordinator.promptUnknown(unknown("db.example.org:2222", "SHA256:xyz")) }
        runCurrent()

        val prompt = onScreen()
        assertNotNull(prompt)
        assertEquals("db.example.org:2222", prompt!!.hostname)
        assertEquals("SHA256:xyz", prompt.fingerprint)
        assertEquals("ssh-ed25519", prompt.algorithm)
        coordinator.respond(prompt, accepted = true)

        assertEquals(HostKeyPromptResponse.Trusted, response.await())
        assertNull(onScreen(), "the dialog goes with the prompt")
    }

    @Test
    fun `refusing the key, or dismissing the dialog, answers Refused`() = coordinatorTest {
        val refused = async { coordinator.promptUnknown(unknown()) }
        runCurrent()
        coordinator.respond(onScreen()!!, accepted = false)
        assertEquals(HostKeyPromptResponse.Refused, refused.await())
        assertNull(onScreen())

        val dismissed = async { coordinator.promptUnknown(unknown()) }
        runCurrent()
        // Right after the first dialog went away: the break, then the next one.
        nextDialogUp()
        coordinator.dismiss(onScreen()!!)
        assertEquals(HostKeyPromptResponse.Refused, dismissed.await())
        assertNull(onScreen())
    }

    // ── The deadline ──────────────────────────────────────────────────────────

    @Test
    fun `an unanswered prompt ends after 60 seconds with TimedOut and the dialog goes`() = coordinatorTest {
        val response = async { coordinator.promptUnknown(unknown()) }
        runCurrent()
        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS - 1)
        runCurrent()
        assertNotNull(onScreen(), "the user still has the whole minute but one millisecond")
        assertTrue(response.isActive)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(HostKeyPromptResponse.TimedOut, response.await())
        assertNull(onScreen())
    }

    @Test
    fun `the deadline is a minute, like the other prompts of the app`() {
        assertEquals(60_000L, HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS)
        assertEquals(Fido2PromptCoordinator.PROMPT_TIMEOUT_MS, HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS)
        assertEquals(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS, HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS)
    }

    @Test
    fun `an answer a moment before the deadline is kept`() = coordinatorTest {
        val response = async { coordinator.promptUnknown(unknown()) }
        runCurrent()
        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS - 1)
        runCurrent()

        coordinator.respond(onScreen()!!, accepted = true)
        runCurrent()

        assertEquals(HostKeyPromptResponse.Trusted, response.await())
    }

    @Test
    fun `an answer after the deadline changes nothing`() = coordinatorTest {
        val response = async { coordinator.promptUnknown(unknown()) }
        runCurrent()
        val prompt = onScreen()!!
        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS)
        runCurrent()

        coordinator.respond(prompt, accepted = true)

        assertEquals(HostKeyPromptResponse.TimedOut, response.await())
    }

    // ── Nobody on screen ──────────────────────────────────────────────────────

    @Test
    fun `with the app hidden the request ends at once with NoScreen, and no dialog is published`() = coordinatorTest {
        visible = false

        val response = coordinator.promptUnknown(unknown())

        assertEquals(HostKeyPromptResponse.NoScreen, response)
        assertNull(onScreen())
        assertEquals(0L, testScheduler.currentTime, "no wait for a deadline nobody can beat")
    }

    @Test
    fun `a hidden app does not take the turn, a prompt on screen is not held up by it`() = coordinatorTest {
        val onScreenNow = async { coordinator.promptUnknown(unknown("a.example.org:22")) }
        runCurrent()
        val shown = onScreen()
        visible = false

        // The turn is held by the first prompt: a request without a screen does not wait for it.
        val hidden = coordinator.promptUnknown(unknown("b.example.org:22"))

        assertEquals(HostKeyPromptResponse.NoScreen, hidden)
        assertSame(shown, onScreen(), "the dialog on screen is still the first one")
        coordinator.respond(shown!!, accepted = true)
        assertEquals(HostKeyPromptResponse.Trusted, onScreenNow.await())
    }

    @Test
    fun `an app left while the request waited for its turn ends with NoScreen, without ever showing a dialog`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        val firstPrompt = onScreen()!!
        val second = async { coordinator.promptUnknown(unknown("second.example.org:22")) }
        runCurrent()
        assertSame(firstPrompt, onScreen(), "the second waits, unseen")

        // The user leaves, then the first prompt ends: the second one's turn comes to an empty screen.
        visible = false
        coordinator.respond(firstPrompt, accepted = true)
        runCurrent()

        assertEquals(HostKeyPromptResponse.Trusted, first.await())
        assertEquals(HostKeyPromptResponse.NoScreen, second.await())
        assertNull(onScreen(), "a dialog published now would hold the handshake for nobody")
    }

    @Test
    fun `a prompt already on screen when the app leaves still ends by its deadline`() = coordinatorTest {
        val response = async { coordinator.promptUnknown(unknown()) }
        runCurrent()
        visible = false

        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS)
        runCurrent()

        assertEquals(HostKeyPromptResponse.TimedOut, response.await())
        assertNull(onScreen())
    }

    // ── One at a time ─────────────────────────────────────────────────────────

    @Test
    fun `a second request waits for its turn, it does not overwrite the first one's dialog`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22", "SHA256:first")) }
        runCurrent()
        val firstPrompt = onScreen()!!
        val second = async { coordinator.promptUnknown(unknown("second.example.org:22", "SHA256:second")) }
        runCurrent()

        assertSame(firstPrompt, onScreen(), "the first is still the one the user reads")
        assertEquals("SHA256:first", onScreen()!!.fingerprint)
        assertTrue(second.isActive)

        coordinator.respond(firstPrompt, accepted = true)
        runCurrent()

        assertEquals(HostKeyPromptResponse.Trusted, first.await())
        nextDialogUp()
        assertEquals("SHA256:second", onScreen()?.fingerprint, "now it is the second one's turn, and its dialog")
        coordinator.respond(onScreen()!!, accepted = false)
        assertEquals(HostKeyPromptResponse.Refused, second.await())
        assertNull(onScreen())
    }

    @Test
    fun `the end of the first prompt does not clear the dialog of the second`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22", "SHA256:first")) }
        runCurrent()
        val firstPrompt = onScreen()!!
        val second = async { coordinator.promptUnknown(unknown("second.example.org:22", "SHA256:second")) }
        runCurrent()

        coordinator.respond(firstPrompt, accepted = false)
        runCurrent()

        assertEquals(HostKeyPromptResponse.Refused, first.await())
        nextDialogUp()
        assertNotNull(onScreen(), "the second one's dialog is up")
        assertNotSame(firstPrompt, onScreen())
        assertTrue(second.isActive)
        coordinator.respond(onScreen()!!, accepted = true)
        assertEquals(HostKeyPromptResponse.Trusted, second.await())
    }

    @Test
    fun `the second prompt gets its own minute, counted from the moment its dialog appears`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        val second = async { coordinator.promptUnknown(unknown("second.example.org:22")) }
        runCurrent()

        // The first one is answered after 50 s, the second one's dialog appears then.
        advanceTimeBy(50_000)
        runCurrent()
        coordinator.respond(onScreen()!!, accepted = true)
        runCurrent()
        assertEquals(HostKeyPromptResponse.Trusted, first.await())
        nextDialogUp()

        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue(second.isActive, "50 s of waiting in the queue, and the gap, do not count against it")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(HostKeyPromptResponse.TimedOut, second.await())
    }

    // ── Two prompts for the very same key ─────────────────────────────────────

    @Test
    fun `two prompts for the same host and key are two different states`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("same.example.org:22", "SHA256:same")) }
        runCurrent()
        val firstPrompt = onScreen()!!
        val second = async { coordinator.promptUnknown(unknown("same.example.org:22", "SHA256:same")) }
        runCurrent()
        coordinator.respond(firstPrompt, accepted = false)
        runCurrent()
        nextDialogUp()
        val secondPrompt = onScreen()!!

        assertEquals(firstPrompt.copy(requestId = 0), secondPrompt.copy(requestId = 0), "same host, port and key")
        assertNotEquals(firstPrompt, secondPrompt, "yet not equal: the dialog and the flow must see a new prompt")
        assertNotEquals(firstPrompt.requestId, secondPrompt.requestId)
        assertEquals(HostKeyPromptResponse.Refused, first.await())
        second.cancel()
    }

    @Test
    fun `the second of two identical prompts answers when it is tapped`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("same.example.org:22", "SHA256:same")) }
        runCurrent()
        val second = async { coordinator.promptUnknown(unknown("same.example.org:22", "SHA256:same")) }
        runCurrent()
        coordinator.respond(onScreen()!!, accepted = false)
        runCurrent()
        assertEquals(HostKeyPromptResponse.Refused, first.await())
        nextDialogUp()

        // What the dialog does: it shows the state the flow gave it, and answers with it.
        val shown = onScreen()
        assertNotNull(shown, "the second dialog is up, not the stale first one")
        coordinator.respond(shown!!, accepted = true)

        assertEquals(HostKeyPromptResponse.Trusted, second.await())
        assertNull(onScreen(), "and it goes away once answered")
    }

    @Test
    fun `the state flow announces the second of two identical prompts`() = coordinatorTest {
        val seen = mutableListOf<HostKeyDialogState?>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.state.collect { seen += it } }
        val first = async { coordinator.promptUnknown(unknown()) }
        runCurrent()
        val second = async { coordinator.promptUnknown(unknown()) }
        runCurrent()
        coordinator.respond(onScreen()!!, accepted = true)
        runCurrent()
        nextDialogUp()

        // null, first, null, second: a StateFlow would have skipped the second if it equalled the first.
        assertEquals(2, seen.filterNotNull().size, "$seen")
        coordinator.respond(onScreen()!!, accepted = false)
        first.await()
        second.await()
        collector.cancel()
    }

    // ── A key trusted while the request waited ────────────────────────────────

    @Test
    fun `a key that the previous prompt just trusted is not asked again`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("same.example.org:22", "SHA256:same")) }
        runCurrent()
        val second = async { coordinator.promptUnknown(unknown("same.example.org:22", "SHA256:same")) }
        runCurrent()

        // The user trusts it: the verifier stores it as soon as the answer reaches it.
        stored = { if (it.hostname == "same.example.org:22" && it.fingerprint == "SHA256:same") StoredKeyState.SAME else StoredKeyState.NONE }
        coordinator.respond(onScreen()!!, accepted = true)
        runCurrent()

        assertEquals(HostKeyPromptResponse.Trusted, first.await())
        assertEquals(HostKeyPromptResponse.AlreadyTrusted, second.await())
        assertNull(onScreen(), "no second dialog for a key that was just trusted")
    }

    @Test
    fun `another key trusted for the same host meanwhile is refused, not asked as a first connection`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("same.example.org:22", "SHA256:first")) }
        runCurrent()
        val second = async { coordinator.promptUnknown(unknown("same.example.org:22", "SHA256:other")) }
        runCurrent()
        // The first key is stored; the second request is for another key of the same host and port.
        stored = { if (it.fingerprint == "SHA256:first") StoredKeyState.SAME else StoredKeyState.DIFFERENT }
        coordinator.respond(onScreen()!!, accepted = true)
        runCurrent()

        assertEquals(HostKeyPromptResponse.Trusted, first.await())
        nextDialogUp()
        assertEquals(HostKeyPromptResponse.Refused, second.await())
        assertNull(onScreen(), "no dialog: it is no longer a first connection, and a tap would store a line nothing reads")
    }

    @Test
    fun `a key already stored when the turn comes shows no dialog and takes no time`() = coordinatorTest {
        stored = { StoredKeyState.SAME }

        val response = coordinator.promptUnknown(unknown())

        assertEquals(HostKeyPromptResponse.AlreadyTrusted, response)
        assertNull(onScreen())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a key refused by the previous prompt is asked again, a refusal stores nothing`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("same.example.org:22", "SHA256:same")) }
        runCurrent()
        val second = async { coordinator.promptUnknown(unknown("same.example.org:22", "SHA256:same")) }
        runCurrent()

        coordinator.respond(onScreen()!!, accepted = false)
        runCurrent()

        assertEquals(HostKeyPromptResponse.Refused, first.await())
        nextDialogUp()
        assertNotNull(onScreen(), "the second connection asks for itself")
        coordinator.respond(onScreen()!!, accepted = false)
        assertEquals(HostKeyPromptResponse.Refused, second.await())
    }

    // ── A break between two dialogs ───────────────────────────────────────────

    @Test
    fun `nothing is on screen between two dialogs, for 700 milliseconds`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        val second = async { coordinator.promptUnknown(unknown("second.example.org:22")) }
        runCurrent()

        coordinator.respond(onScreen()!!, accepted = true)
        runCurrent()
        assertEquals(HostKeyPromptResponse.Trusted, first.await())
        assertNull(onScreen(), "the first dialog closed, the second has not opened yet")

        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_GAP_MS - 1)
        runCurrent()
        assertNull(onScreen(), "still the break, a tap now lands on nothing")
        advanceTimeBy(1)
        runCurrent()
        assertEquals("second.example.org:22", onScreen()?.hostname, "and the next dialog opens")
        coordinator.respond(onScreen()!!, accepted = false)
        assertEquals(HostKeyPromptResponse.Refused, second.await())
    }

    @Test
    fun `the break is 700 milliseconds`() {
        assertEquals(700L, HostKeyPromptCoordinator.PROMPT_GAP_MS)
    }

    @Test
    fun `the break counts from the moment the first dialog went away, not from when it appeared`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        val second = async { coordinator.promptUnknown(unknown("second.example.org:22")) }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        val answeredAt = testScheduler.currentTime

        coordinator.respond(onScreen()!!, accepted = true)
        runCurrent()
        assertEquals(HostKeyPromptResponse.Trusted, first.await())
        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_GAP_MS)
        runCurrent()

        assertEquals("second.example.org:22", onScreen()?.hostname)
        assertEquals(HostKeyPromptCoordinator.PROMPT_GAP_MS, testScheduler.currentTime - answeredAt)
        coordinator.respond(onScreen()!!, accepted = false)
        second.await()
    }

    @Test
    fun `a dialog that comes long after the previous one goes away needs no break`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        coordinator.respond(onScreen()!!, accepted = true)
        first.await()
        advanceTimeBy(10_000)

        val second = async { coordinator.promptUnknown(unknown("second.example.org:22")) }
        runCurrent()

        assertEquals("second.example.org:22", onScreen()?.hostname, "no wait for a break that is already over")
        coordinator.respond(onScreen()!!, accepted = false)
        second.await()
    }

    @Test
    fun `the first dialog of all needs no break`() = coordinatorTest {
        val response = async { coordinator.promptUnknown(unknown()) }
        runCurrent()

        assertNotNull(onScreen())
        assertEquals(0L, testScheduler.currentTime)
        coordinator.respond(onScreen()!!, accepted = false)
        response.await()
    }

    @Test
    fun `an app left during the break ends the request with NoScreen and opens nothing`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        val second = async { coordinator.promptUnknown(unknown("second.example.org:22")) }
        runCurrent()
        coordinator.respond(onScreen()!!, accepted = true)
        runCurrent()
        assertEquals(HostKeyPromptResponse.Trusted, first.await())

        visible = false
        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_GAP_MS)
        runCurrent()

        assertEquals(HostKeyPromptResponse.NoScreen, second.await())
        assertNull(onScreen())
    }

    @Test
    fun `a request cancelled during the break shows nothing`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        val second = launch { coordinator.promptUnknown(unknown("second.example.org:22")) }
        runCurrent()
        coordinator.respond(onScreen()!!, accepted = true)
        runCurrent()
        first.await()

        second.cancel()
        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_GAP_MS * 2)
        runCurrent()

        assertNull(onScreen(), "the connection is gone, so is its dialog before it opened")
    }

    // ── The trust button is not armed at once ─────────────────────────────────

    @Test
    fun `a trust answer in the first half second is ignored, the dialog stays up`() = coordinatorTest {
        val response = async { armedCoordinator.promptUnknown(unknown()) }
        runCurrent()
        val prompt = armedCoordinator.state.value!!

        // A tap that was on its way when the dialog popped up, over what the user was doing.
        armedCoordinator.respond(prompt, accepted = true)
        runCurrent()
        assertTrue(response.isActive, "nothing was trusted")
        assertSame(prompt, armedCoordinator.state.value, "and the dialog is still there to be read")

        advanceTimeBy(HostKeyPromptCoordinator.CONFIRM_ARM_MS - 1)
        runCurrent()
        armedCoordinator.respond(prompt, accepted = true)
        runCurrent()
        assertTrue(response.isActive, "one millisecond short")

        advanceTimeBy(1)
        runCurrent()
        armedCoordinator.respond(prompt, accepted = true)
        assertEquals(HostKeyPromptResponse.Trusted, response.await())
    }

    @Test
    fun `a refusal is taken at once, the button of it is never disabled`() = coordinatorTest {
        val response = async { armedCoordinator.promptUnknown(unknown()) }
        runCurrent()

        armedCoordinator.respond(armedCoordinator.state.value!!, accepted = false)

        assertEquals(HostKeyPromptResponse.Refused, response.await())
    }

    @Test
    fun `the half second is 500 milliseconds`() {
        assertEquals(500L, HostKeyPromptCoordinator.CONFIRM_ARM_MS)
    }

    @Test
    fun `the arming starts again for each dialog, counted from when it opens`() = coordinatorTest {
        val first = async { armedCoordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        val second = async { armedCoordinator.promptUnknown(unknown("second.example.org:22")) }
        runCurrent()
        advanceTimeBy(HostKeyPromptCoordinator.CONFIRM_ARM_MS)
        runCurrent()
        armedCoordinator.respond(armedCoordinator.state.value!!, accepted = true)
        runCurrent()
        assertEquals(HostKeyPromptResponse.Trusted, first.await())

        // The second dialog opens after the break; the user, tapping on, hits it right away.
        advanceTimeBy(HostKeyPromptCoordinator.PROMPT_GAP_MS)
        runCurrent()
        val secondPrompt = armedCoordinator.state.value!!
        assertEquals("second.example.org:22", secondPrompt.hostname)
        armedCoordinator.respond(secondPrompt, accepted = true)
        runCurrent()
        assertTrue(second.isActive, "the first dialog's arming does not carry over")

        advanceTimeBy(HostKeyPromptCoordinator.CONFIRM_ARM_MS)
        runCurrent()
        armedCoordinator.respond(secondPrompt, accepted = true)
        assertEquals(HostKeyPromptResponse.Trusted, second.await())
    }

    @Test
    fun `a double tap on trust cannot trust two keys, the second host needs a tap of its own`() = coordinatorTest {
        val first = async { armedCoordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        val second = async { armedCoordinator.promptUnknown(unknown("second.example.org:22")) }
        runCurrent()
        advanceTimeBy(HostKeyPromptCoordinator.CONFIRM_ARM_MS)
        runCurrent()
        val firstPrompt = armedCoordinator.state.value!!

        // Two taps in a row on the same button, a few milliseconds apart.
        armedCoordinator.respond(firstPrompt, accepted = true)
        armedCoordinator.respond(firstPrompt, accepted = true)
        advanceTimeBy(50)
        runCurrent()
        armedCoordinator.respond(firstPrompt, accepted = true)
        runCurrent()

        assertEquals(HostKeyPromptResponse.Trusted, first.await())
        assertTrue(second.isActive, "the second key was not trusted by the taps meant for the first")
        assertNull(armedCoordinator.state.value, "the break: nothing to tap yet")
        second.cancel()
    }

    // ── An answer counts for the prompt on screen only ────────────────────────

    @Test
    fun `a second tap on the dialog just answered does not trust the key of the next one`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        val firstPrompt = onScreen()!!
        val second = async { coordinator.promptUnknown(unknown("second.example.org:22")) }
        runCurrent()
        coordinator.respond(firstPrompt, accepted = false)
        runCurrent()
        assertEquals(HostKeyPromptResponse.Refused, first.await())
        nextDialogUp()
        val secondPrompt = onScreen()!!

        // The dialog of the first prompt is still composed for a frame and is tapped again: "trust".
        coordinator.respond(firstPrompt, accepted = true)
        runCurrent()

        assertTrue(second.isActive, "nothing was decided for the second host")
        assertSame(secondPrompt, onScreen())
        coordinator.respond(secondPrompt, accepted = false)
        assertEquals(HostKeyPromptResponse.Refused, second.await())
    }

    @Test
    fun `an answer for a state that was never shown is ignored, even one that looks the same`() = coordinatorTest {
        val response = async { coordinator.promptUnknown(unknown()) }
        runCurrent()
        val shown = onScreen()!!

        coordinator.respond(shown.copy(), accepted = true)
        runCurrent()

        assertTrue(response.isActive, "only the instance on screen answers")
        coordinator.respond(shown, accepted = false)
        assertEquals(HostKeyPromptResponse.Refused, response.await())
    }

    @Test
    fun `an answer with no prompt at all is ignored`() {
        coordinator.respond(HostKeyDialogState("h:22", "ssh-ed25519", "SHA256:abc"), accepted = true)
        assertNull(onScreen())
    }

    // ── The connection goes away ──────────────────────────────────────────────

    @Test
    fun `cancelling the connection takes its dialog away`() = coordinatorTest {
        val job = launch { coordinator.promptUnknown(unknown()) }
        runCurrent()
        assertNotNull(onScreen())

        job.cancel()
        runCurrent()

        assertNull(onScreen())
    }

    @Test
    fun `a request cancelled while it waits for its turn shows nothing, then or later`() = coordinatorTest {
        val first = async { coordinator.promptUnknown(unknown("first.example.org:22")) }
        runCurrent()
        val firstPrompt = onScreen()!!
        val queued = launch { coordinator.promptUnknown(unknown("queued.example.org:22")) }
        runCurrent()

        queued.cancel()
        runCurrent()
        coordinator.respond(firstPrompt, accepted = true)
        runCurrent()

        assertEquals(HostKeyPromptResponse.Trusted, first.await())
        assertNull(onScreen(), "nothing is shown for a connection that is gone")
    }

    @Test
    fun `the turn is the one the key sheet and the fingerprint share`() = coordinatorTest {
        var sheetOnScreen = false
        val sheet = launch {
            turn.withTurn {
                sheetOnScreen = true
                kotlinx.coroutines.awaitCancellation()
            }
        }
        runCurrent()
        assertTrue(sheetOnScreen)

        val response = async { coordinator.promptUnknown(unknown()) }
        runCurrent()

        assertNull(onScreen(), "no dialog under a security key sheet")
        sheet.cancel()
        runCurrent()
        assertNotNull(onScreen(), "its turn came")
        coordinator.respond(onScreen()!!, accepted = true)
        assertEquals(HostKeyPromptResponse.Trusted, response.await())
    }

    // ── The app is closed between the last check and the publication ───────────

    @Test
    fun `an app closed between the last check and the publication ends the prompt at once as for an app that was left`() = coordinatorTest {
        // Test, publish, test again: the lock or the wipe of the vault ended the prompts that were up, and
        // found nothing to end while this one was between its last check and its publication.
        var checks = 0
        val racing = HostKeyPromptCoordinator(turn, virtualNow, AppVisibility { checks++ < 3 }, { stored(it) }, confirmArmMs = 0L)

        val response = racing.promptUnknown(unknown())

        assertEquals(HostKeyPromptResponse.NoScreen, response)
        assertNull(racing.state.value, "no dialog is left on screen for nobody")
        assertEquals(0L, testScheduler.currentTime, "and no wait for a deadline nobody can beat")
    }
}
