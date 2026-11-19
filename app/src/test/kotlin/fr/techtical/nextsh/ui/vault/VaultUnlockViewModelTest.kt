// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.crypto.BiometricHelper
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.ssh.BiometricFailure
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.BiometricPromptResponse
import fr.techtical.nextsh.core.ssh.VaultAuthPurpose
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.core.vault.VaultMigration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The unlock of the app, at launch and behind the lock screen: the dialog of the coordinator, then the
 * opening of the vault in the authentication window, then the unlock of the app, in that order. The
 * screen shows our sentences, never the text of the system dialog nor of an exception, and says
 * nothing when the user closes the dialog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultUnlockViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val biometricHelper = mockk<BiometricHelper>()
    private val vaultMigration = mockk<VaultMigration>()
    private val vaultManager = mockk<VaultManager>()
    private val biometricPrompts = mockk<BiometricPromptCoordinator>()

    /** The real lock on a clock that never moves; it starts locked, like the app. */
    private val appLock = AppLock { 0L }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { biometricHelper.canAuthenticate() } returns true
        every { vaultMigration.needsMigration() } returns false
        every { vaultManager.open() } returns Unit
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Authorized
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = VaultUnlockViewModel(
        biometricHelper = biometricHelper,
        vaultMigration = vaultMigration,
        vaultManager = vaultManager,
        biometricPrompts = biometricPrompts,
        appLock = appLock,
        ioDispatcher = testDispatcher,
    )

    // ── The dialog ────────────────────────────────────────────────────────────

    @Test
    fun `the state starts with what the phone can do and nothing else`() {
        every { biometricHelper.canAuthenticate() } returns false

        val state = newViewModel().state.value

        assertFalse(state.biometricAvailable)
        assertFalse(state.isUnlocked)
        assertFalse(state.isAuthenticating)
        assertNull(state.errorRes)
    }

    @Test
    fun `the unlock asks the unlock prompt of the coordinator, never a prompt of its own`() = runTest {
        newViewModel().authenticate()

        coVerify(exactly = 1) { biometricPrompts.authenticateVault(VaultAuthPurpose.UNLOCK) }
    }

    @Test
    fun `a second tap while the dialog is up is ignored, the button waits`() = runTest {
        val answer = CompletableDeferred<BiometricPromptResponse?>()
        coEvery { biometricPrompts.authenticateVault(any()) } coAnswers { answer.await() }
        val viewModel = newViewModel()

        viewModel.authenticate()
        assertTrue(viewModel.state.value.isAuthenticating, "the button is disabled while the dialog is up")
        viewModel.authenticate()

        coVerify(exactly = 1) { biometricPrompts.authenticateVault(any()) }
        answer.complete(BiometricPromptResponse.Cancelled)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isAuthenticating, "and back once it is over")
    }

    // ── A success opens the vault, then unlocks the app ───────────────────────

    @Test
    fun `a success opens the vault, then unlocks the app, in that order`() = runTest {
        val viewModel = newViewModel()
        assertTrue(appLock.isLocked())

        viewModel.authenticate()
        advanceUntilIdle()

        verify(exactly = 1) { vaultManager.open() }
        assertFalse(appLock.isLocked())
        assertTrue(viewModel.state.value.isUnlocked)
        assertNull(viewModel.state.value.errorRes)
        assertFalse(viewModel.state.value.isAuthenticating)
    }

    @Test
    fun `the app is not unlocked while the vault is still opening`() = runTest {
        var lockedWhenTheVaultOpened: Boolean? = null
        every { vaultManager.open() } answers { lockedWhenTheVaultOpened = appLock.isLocked() }
        val viewModel = newViewModel()

        viewModel.authenticate()
        advanceUntilIdle()

        assertEquals(true, lockedWhenTheVaultOpened, "the lock goes away after the opening, not before")
        assertFalse(appLock.isLocked())
    }

    @Test
    fun `a migration that is due runs before the vault is opened`() = runTest {
        every { vaultMigration.needsMigration() } returns true
        every { vaultMigration.migrate() } returns Unit

        newViewModel().authenticate()
        advanceUntilIdle()

        verifyOrder {
            vaultMigration.migrate()
            vaultManager.open()
        }
        assertFalse(appLock.isLocked())
    }

    @Test
    fun `no migration when none is due`() = runTest {
        newViewModel().authenticate()
        advanceUntilIdle()

        verify(exactly = 0) { vaultMigration.migrate() }
    }

    @Test
    fun `the same view model unlocks the app again after a lock, through the same steps`() = runTest {
        // The lock screen keeps one instance for the life of the activity. That opening the vault a
        // second time costs nothing is a property of VaultManager, see VaultManagerProbeTest.
        val viewModel = newViewModel()
        viewModel.authenticate()
        advanceUntilIdle()
        appLock.lock()
        assertTrue(appLock.isLocked())

        viewModel.refresh()
        viewModel.authenticate()
        advanceUntilIdle()

        coVerify(exactly = 2) { biometricPrompts.authenticateVault(VaultAuthPurpose.UNLOCK) }
        verify(exactly = 2) { vaultManager.open() }
        assertFalse(appLock.isLocked())
    }

    // ── What stops the unlock ─────────────────────────────────────────────────

    @Test
    fun `a window that expired between the dialog and the opening asks to try again, the app stays locked`() = runTest {
        every { vaultManager.open() } throws VaultAuthExpiredException()
        val viewModel = newViewModel()

        viewModel.authenticate()
        advanceUntilIdle()

        assertEquals(R.string.vault_unlock_error_auth_expired, viewModel.state.value.errorRes)
        assertFalse(viewModel.state.value.isUnlocked)
        assertTrue(appLock.isLocked())
        assertFalse(viewModel.state.value.isAuthenticating, "the button is back")
    }

    @Test
    fun `a key that the biometric data invalidated has its own sentence, the app stays locked`() = runTest {
        every { vaultManager.open() } throws KeyPermanentlyInvalidatedException("key invalidated")
        val viewModel = newViewModel()

        viewModel.authenticate()
        advanceUntilIdle()

        assertEquals(R.string.vault_unlock_error_key_invalidated, viewModel.state.value.errorRes)
        assertTrue(appLock.isLocked())
    }

    @Test
    fun `any other failure of the vault is a sentence of ours, what the exception says is never shown`() = runTest {
        every { vaultManager.open() } throws IllegalStateException("AEADBadTagException in /data/user/0/fr.techtical.nextsh/shared_prefs")
        val viewModel = newViewModel()

        viewModel.authenticate()
        advanceUntilIdle()

        assertEquals(R.string.vault_unlock_error_open_failed, viewModel.state.value.errorRes)
        assertTrue(appLock.isLocked())
    }

    @Test
    fun `a failure of the migration stops the unlock before the vault is opened`() = runTest {
        every { vaultMigration.needsMigration() } returns true
        every { vaultMigration.migrate() } throws VaultAuthExpiredException()
        val viewModel = newViewModel()

        viewModel.authenticate()
        advanceUntilIdle()

        assertEquals(R.string.vault_unlock_error_auth_expired, viewModel.state.value.errorRes)
        verify(exactly = 0) { vaultManager.open() }
        assertTrue(appLock.isLocked())
    }

    // ── A dialog that did not authorize ───────────────────────────────────────

    @Test
    fun `a dialog the user closes says nothing and leaves the app locked`() = runTest {
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Cancelled
        val viewModel = newViewModel()

        viewModel.authenticate()
        advanceUntilIdle()

        assertNull(viewModel.state.value.errorRes)
        assertFalse(viewModel.state.value.isUnlocked)
        assertTrue(appLock.isLocked())
        verify(exactly = 0) { vaultManager.open() }
    }

    @Test
    fun `a dialog that failed says why, in a sentence of ours per reason, and opens nothing`() = runTest {
        val expected = mapOf(
            BiometricFailure.LOCKOUT to R.string.vault_unlock_error_lockout,
            BiometricFailure.LOCKOUT_PERMANENT to R.string.vault_unlock_error_lockout_permanent,
            BiometricFailure.NO_BIOMETRICS to R.string.vault_unlock_error_none_enrolled,
            BiometricFailure.HW_UNAVAILABLE to R.string.vault_unlock_error_sensor_unavailable,
            BiometricFailure.HW_NOT_PRESENT to R.string.vault_unlock_error_no_sensor,
            BiometricFailure.OTHER to R.string.vault_unlock_error_prompt_failed,
        )
        assertEquals(BiometricFailure.entries.toSet(), expected.keys, "every reason has a sentence")
        for ((reason, sentence) in expected) {
            coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Failed(reason)
            val viewModel = newViewModel()

            viewModel.authenticate()
            advanceUntilIdle()

            assertEquals(sentence, viewModel.state.value.errorRes, "$reason")
            assertTrue(appLock.isLocked())
        }
        verify(exactly = 0) { vaultManager.open() }
    }

    @Test
    fun `the sentences of the reasons are distinct, except the generic one for what has no name`() {
        val sentences = BiometricFailure.entries.map { vaultPromptFailureRes(BiometricPromptResponse.Failed(it)) }

        assertEquals(sentences.size, sentences.toSet().size, "$sentences")
        assertEquals(R.string.vault_unlock_error_prompt_failed, vaultPromptFailureRes(BiometricPromptResponse.Failed(BiometricFailure.OTHER)))
    }

    @Test
    fun `a dialog nobody answered in time is the generic sentence, a dialog closed or a success says nothing`() = runTest {
        assertEquals(R.string.vault_unlock_error_prompt_failed, vaultPromptFailureRes(null))
        assertEquals(R.string.vault_unlock_error_prompt_failed, vaultPromptFailureRes(BiometricPromptResponse.NoScreen))
        assertNull(vaultPromptFailureRes(BiometricPromptResponse.Cancelled))
        assertNull(vaultPromptFailureRes(BiometricPromptResponse.Authorized))

        coEvery { biometricPrompts.authenticateVault(any()) } returns null
        val viewModel = newViewModel()
        viewModel.authenticate()
        advanceUntilIdle()

        assertEquals(R.string.vault_unlock_error_prompt_failed, viewModel.state.value.errorRes)
        assertTrue(appLock.isLocked())
    }

    @Test
    fun `the message of a failed try goes away with the next try`() = runTest {
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Failed(BiometricFailure.OTHER)
        val viewModel = newViewModel()
        viewModel.authenticate()
        advanceUntilIdle()
        assertEquals(R.string.vault_unlock_error_prompt_failed, viewModel.state.value.errorRes)

        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Authorized
        viewModel.authenticate()
        advanceUntilIdle()

        assertNull(viewModel.state.value.errorRes)
        assertFalse(appLock.isLocked())
    }

    @Test
    fun `the message can be dismissed`() = runTest {
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Failed(BiometricFailure.OTHER)
        val viewModel = newViewModel()
        viewModel.authenticate()
        advanceUntilIdle()

        viewModel.dismissError()

        assertNull(viewModel.state.value.errorRes)
    }

    // ── The lock screen starts clean each time ────────────────────────────────

    @Test
    fun `refresh asks the phone again what it can do, each time the lock screen is resumed`() = runTest {
        every { biometricHelper.canAuthenticate() } returns false
        val viewModel = newViewModel()
        assertFalse(viewModel.state.value.biometricAvailable)

        every { biometricHelper.canAuthenticate() } returns true // a lock was set up in the settings meanwhile
        viewModel.refresh()
        assertTrue(viewModel.state.value.biometricAvailable)

        every { biometricHelper.canAuthenticate() } returns false // and removed again
        viewModel.refresh()
        assertFalse(viewModel.state.value.biometricAvailable)
    }

    @Test
    fun `refresh does not end an authentication that is under way, the resume of the code screen is no end of it`() = runTest {
        val answer = CompletableDeferred<BiometricPromptResponse?>()
        coEvery { biometricPrompts.authenticateVault(any()) } coAnswers { answer.await() }
        val viewModel = newViewModel()
        viewModel.authenticate()
        assertTrue(viewModel.state.value.isAuthenticating)

        viewModel.refresh() // the activity is resumed when the phone code screen closes
        viewModel.refresh()

        assertTrue(viewModel.state.value.isAuthenticating, "the button still waits")
        viewModel.authenticate()
        coVerify(exactly = 1) { biometricPrompts.authenticateVault(any()) }
        answer.complete(BiometricPromptResponse.Authorized)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isAuthenticating)
        assertFalse(appLock.isLocked())
    }

    @Test
    fun `refresh leaves the message of the last try, which goes with the next try or with the lock screen`() = runTest {
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Failed(BiometricFailure.LOCKOUT)
        val viewModel = newViewModel()
        viewModel.authenticate()
        advanceUntilIdle()

        viewModel.refresh()

        assertEquals(R.string.vault_unlock_error_lockout, viewModel.state.value.errorRes)
        viewModel.dismissError()
        assertNull(viewModel.state.value.errorRes)
    }
}
