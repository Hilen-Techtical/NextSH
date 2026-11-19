// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.util.Base64
import fr.techtical.nextsh.core.auth.EnrollFailure
import fr.techtical.nextsh.core.auth.Fido2EnrollManager
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.testutil.LEAK_MARKER
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * What the enrollment screen is told: a reason, never a sentence (the screen turns it into text in
 * the user's language), and never the message of a failure. The PIN is zeroed whatever happens.
 * All PINs are test values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fido2EnrollViewModelTest {

    private val manager = mockk<Fido2EnrollManager>()
    private val repository = mockk<SshKeyRepository>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // android.util.Base64 is a stub in unit tests: the credential id is encoded by this.
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), any()) } returns "Y3JlZGVudGlhbA=="
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel(label: String = "My key") =
        Fido2EnrollViewModel(manager, repository).also { it.onLabelChange(label) }

    private fun result() = Fido2EnrollManager.EnrollResult(
        credentialId = ByteArray(16) { 0xAA.toByte() },
        application = "ssh:",
        publicKey = ByteArray(32) { it.toByte() },
        keyType = SkKeyType.SK_ED25519,
        transport = Fido2EnrollManager.Transport.USB,
    )

    private fun failing(failure: EnrollFailure) {
        coEvery { manager.detectAndEnroll(any(), any(), any(), any()) } throws Fido2EnrollManager.EnrollError(failure)
    }

    private fun Fido2EnrollViewModel.errorReason(): EnrollFailure {
        val state = state.value
        assertTrue(state is Fido2EnrollViewModel.EnrollState.Error, "$state")
        return (state as Fido2EnrollViewModel.EnrollState.Error).reason
    }

    @Test
    fun `every failure of the manager reaches the screen as the reason it carries`() {
        listOf(
            EnrollFailure.NoKeyInTime,
            EnrollFailure.PinRequired,
            EnrollFailure.PinIncorrect(triesRemaining = 3),
            EnrollFailure.PinNotSet,
            EnrollFailure.PinBlocked,
            EnrollFailure.PinAuthBlocked,
            EnrollFailure.UnsupportedKey,
            EnrollFailure.KeyRemoved,
            EnrollFailure.TouchTimeout,
            EnrollFailure.Refused,
            EnrollFailure.Unexpected,
        ).forEach { failure ->
            failing(failure)
            val vm = viewModel()

            vm.startEnroll(null)

            assertEquals(failure, vm.errorReason())
        }
    }

    @Test
    fun `a blocked or unsupported key offers no retry, the others do`() {
        fun retry(failure: EnrollFailure) = Fido2EnrollViewModel.EnrollState.Error(failure).canRetry

        assertFalse(retry(EnrollFailure.PinBlocked))
        assertFalse(retry(EnrollFailure.UnsupportedKey))
        listOf(
            EnrollFailure.NoKeyInTime, EnrollFailure.PinRequired, EnrollFailure.PinIncorrect(2),
            EnrollFailure.PinNotSet, EnrollFailure.PinAuthBlocked, EnrollFailure.KeyRemoved, EnrollFailure.TouchTimeout,
            EnrollFailure.Refused, EnrollFailure.NameMissing, EnrollFailure.NotSaved, EnrollFailure.Unexpected,
        ).forEach { assertTrue(retry(it), "$it") }
    }

    @Test
    fun `only a missing or refused PIN points at the PIN field, not a PIN the key has no use for`() {
        fun pin(failure: EnrollFailure) = Fido2EnrollViewModel.EnrollState.Error(failure).isPinRequired

        assertTrue(pin(EnrollFailure.PinRequired))
        assertTrue(pin(EnrollFailure.PinIncorrect(null)))
        listOf(
            EnrollFailure.PinNotSet, EnrollFailure.PinBlocked, EnrollFailure.PinAuthBlocked, EnrollFailure.NoKeyInTime,
            EnrollFailure.UnsupportedKey, EnrollFailure.Unexpected,
        ).forEach { assertFalse(pin(it), "$it") }
    }

    @Test
    fun `an exception nobody expected shows as an unexpected failure, without its message`() {
        coEvery { manager.detectAndEnroll(any(), any(), any(), any()) } throws IllegalStateException("PIN $LEAK_MARKER")
        val vm = viewModel()

        vm.startEnroll(null)

        assertEquals(EnrollFailure.Unexpected, vm.errorReason())
        assertFalse(vm.state.value.toString().contains(LEAK_MARKER), "the state holds the message")
    }

    @Test
    fun `a blank name is refused before any key is asked, and the PIN is zeroed`() {
        val vm = viewModel(label = "   ")
        val pin = "1234".toCharArray()

        vm.startEnroll(pin)

        assertEquals(EnrollFailure.NameMissing, vm.errorReason())
        assertTrue(pin.all { it == '\u0000' }, "nothing uses the PIN: it must not stay in memory")
        coVerify(exactly = 0) { manager.detectAndEnroll(any(), any(), any(), any()) }
    }

    @Test
    fun `the PIN is zeroed when the enrollment fails, even if the manager did not do it`() {
        failing(EnrollFailure.PinIncorrect(2))
        val pin = "1234".toCharArray()

        viewModel().startEnroll(pin)

        assertTrue(pin.all { it == '\u0000' })
    }

    @Test
    fun `the PIN is zeroed when the enrollment ends with an exception nobody expected`() {
        coEvery { manager.detectAndEnroll(any(), any(), any(), any()) } throws IllegalStateException("boom")
        val pin = "1234".toCharArray()

        viewModel().startEnroll(pin)

        assertTrue(pin.all { it == '\u0000' })
    }

    @Test
    fun `the PIN is zeroed once the key is enrolled and saved`() {
        coEvery { manager.detectAndEnroll(any(), any(), any(), any()) } returns result()
        coEvery { repository.save(any()) } returns Unit
        val pin = "1234".toCharArray()

        viewModel().startEnroll(pin)

        assertTrue(pin.all { it == '\u0000' })
    }

    @Test
    fun `a cancellation shows no error and still zeroes the PIN`() {
        coEvery { manager.detectAndEnroll(any(), any(), any(), any()) } throws CancellationException("left the screen")
        val vm = viewModel()
        val pin = "1234".toCharArray()

        vm.startEnroll(pin)

        assertEquals(Fido2EnrollViewModel.EnrollState.WaitingForKey, vm.state.value)
        assertTrue(pin.all { it == '\u0000' })
    }

    @Test
    fun `an empty PIN is no PIN, the manager is given null`() {
        failing(EnrollFailure.NoKeyInTime)
        val empty = CharArray(0)

        viewModel().startEnroll(empty)

        coVerify(exactly = 1) { manager.detectAndEnroll(any(), any(), any(), isNull()) }
    }

    @Test
    fun `a second press while the key is awaited is ignored and its PIN is zeroed`() {
        val gate = CompletableDeferred<Fido2EnrollManager.EnrollResult>()
        coEvery { manager.detectAndEnroll(any(), any(), any(), any()) } coAnswers { gate.await() }
        coEvery { repository.save(any()) } returns Unit
        val vm = viewModel()
        val first = "1234".toCharArray()
        val second = "5678".toCharArray()

        vm.startEnroll(first)
        assertEquals(Fido2EnrollViewModel.EnrollState.WaitingForKey, vm.state.value)
        vm.startEnroll(second)

        coVerify(exactly = 1) { manager.detectAndEnroll(any(), any(), any(), any()) }
        assertTrue(second.all { it == '\u0000' }, "the PIN of the ignored press must not stay in memory")
        assertFalse(first.all { it == '\u0000' }, "the PIN of the enrollment under way is still in use")

        gate.complete(result())

        assertTrue(vm.state.value is Fido2EnrollViewModel.EnrollState.Success, "${vm.state.value}")
        coVerify(exactly = 1) { repository.save(any()) }
        assertTrue(first.all { it == '\u0000' }, "and zeroed once it is over")
    }

    @Test
    fun `a press once the key is enrolled starts nothing, a press after a reset does`() {
        coEvery { manager.detectAndEnroll(any(), any(), any(), any()) } returns result()
        coEvery { repository.save(any()) } returns Unit
        val vm = viewModel()
        vm.startEnroll(null)
        assertTrue(vm.state.value is Fido2EnrollViewModel.EnrollState.Success)

        vm.startEnroll(null)
        coVerify(exactly = 1) { manager.detectAndEnroll(any(), any(), any(), any()) }

        failing(EnrollFailure.NoKeyInTime)
        vm.reset()
        vm.startEnroll(null)

        coVerify(exactly = 2) { manager.detectAndEnroll(any(), any(), any(), any()) }
        assertEquals(EnrollFailure.NoKeyInTime, vm.errorReason())
    }

    @Test
    fun `a key that is enrolled is saved and the screen is told`() {
        val enrolled = result()
        coEvery { manager.detectAndEnroll(any(), any(), any(), any()) } returns enrolled
        coEvery { repository.save(any()) } returns Unit
        val vm = viewModel(label = "  Work key ")

        vm.startEnroll(null)

        val state = vm.state.value
        assertTrue(state is Fido2EnrollViewModel.EnrollState.Success, "$state")
        assertEquals("Work key", (state as Fido2EnrollViewModel.EnrollState.Success).savedKey.label)
        coVerify(exactly = 1) { repository.save(any()) }
        assertTrue(enrolled.credentialId.all { it == 0.toByte() }, "the credential id does not outlive the save")
    }

    @Test
    fun `a key the vault could not keep is told as not saved, without the message of the failure`() {
        val enrolled = result()
        coEvery { manager.detectAndEnroll(any(), any(), any(), any()) } returns enrolled
        coEvery { repository.save(any()) } throws IOException("disk full $LEAK_MARKER")
        val vm = viewModel()

        vm.startEnroll(null)

        assertEquals(EnrollFailure.NotSaved, vm.errorReason())
        assertFalse(vm.state.value.toString().contains(LEAK_MARKER))
        assertTrue(enrolled.credentialId.all { it == 0.toByte() }, "wiped on the failure path too")
    }
}
