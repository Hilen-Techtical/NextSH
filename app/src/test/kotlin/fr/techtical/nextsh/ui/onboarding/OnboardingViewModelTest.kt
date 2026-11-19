// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.onboarding

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
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Unit tests for [OnboardingViewModel].
 *
 * All Android-dependent collaborators (BiometricHelper, VaultMigration,
 * SettingsDataStore) are mocked so these run on the JVM without an emulator.
 *
 * Tested seams:
 *  - Initial state defaults (biometricAvailable derived from BiometricHelper)
 *  - dismissError clears both errorMessage and errorRes
 *  - onboardingCompleted StateFlow settles to the value emitted by the DataStore
 *  - completeOnboarding persists the flag and then calls the onPersisted callback
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class OnboardingViewModelTest {

    @MockK
    private lateinit var biometricHelper: BiometricHelper

    @MockK
    private lateinit var vaultMigration: VaultMigration

    @MockK
    private lateinit var settingsDataStore: SettingsDataStore

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var biometricPrompts: BiometricPromptCoordinator

    /** The real lock on a clock that never moves; it starts locked, like the app. */
    private val appLock = AppLock { 0L }

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: OnboardingViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Constructs the ViewModel with the given [biometricAvailable] stub and an
     * [onboardingCompletedFlow] backed by [completedValue].
     */
    private fun buildViewModel(
        biometricAvailable: Boolean = false,
        completedValue: Boolean = false,
    ): OnboardingViewModel {
        every { biometricHelper.canAuthenticate() } returns biometricAvailable
        every { settingsDataStore.onboardingCompletedFlow } returns flowOf(completedValue)
        return newViewModel()
    }

    private fun newViewModel() = OnboardingViewModel(
        biometricHelper = biometricHelper,
        vaultMigration = vaultMigration,
        vaultManager = vaultManager,
        settingsDataStore = settingsDataStore,
        biometricPrompts = biometricPrompts,
        appLock = appLock,
        ioDispatcher = testDispatcher,
    )

    // ── Initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial state - biometricAvailable reflects BiometricHelper canAuthenticate`() {
        viewModel = buildViewModel(biometricAvailable = true)
        assertTrue(viewModel.state.value.biometricAvailable)
    }

    @Test
    fun `initial state - biometricAvailable is false when canAuthenticate returns false`() {
        viewModel = buildViewModel(biometricAvailable = false)
        assertFalse(viewModel.state.value.biometricAvailable)
    }

    @Test
    fun `initial state - vaultReady is false, isAuthenticating is false, no errors`() {
        viewModel = buildViewModel()
        val state = viewModel.state.value
        assertFalse(state.vaultReady)
        assertFalse(state.isAuthenticating)
        assertNull(state.errorRes)
    }

    // ── dismissError ─────────────────────────────────────────────────────────

    @Test
    fun `dismissError clears the error`() = runTest {
        viewModel = buildViewModel()
        coEvery { biometricPrompts.authenticateVault(VaultAuthPurpose.UNLOCK) } returns BiometricPromptResponse.Failed(BiometricFailure.OTHER)
        viewModel.createVault()
        assertNotNull(viewModel.state.value.errorRes)

        viewModel.dismissError()

        assertNull(viewModel.state.value.errorRes)
    }

    // ── createVault: the vault prompt goes through the coordinator ───────────

    @Test
    fun `createVault asks the unlock prompt of the coordinator, never a prompt of its own`() = runTest {
        viewModel = buildViewModel()
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Cancelled

        viewModel.createVault()

        coVerify(exactly = 1) { biometricPrompts.authenticateVault(VaultAuthPurpose.UNLOCK) }
    }

    @Test
    fun `a success migrates if due, opens the vault, and marks the step ready`() = runTest {
        viewModel = buildViewModel()
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Authorized
        every { vaultMigration.needsMigration() } returns true
        every { vaultMigration.migrate() } returns Unit
        every { vaultManager.open() } returns Unit

        viewModel.createVault()

        assertTrue(viewModel.state.value.vaultReady)
        assertFalse(viewModel.state.value.isAuthenticating)
        assertNull(viewModel.state.value.errorRes)
        verifyOrder {
            vaultMigration.migrate()
            vaultManager.open()
        }
    }

    @Test
    fun `a success without migration still opens the vault`() = runTest {
        viewModel = buildViewModel()
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Authorized
        every { vaultMigration.needsMigration() } returns false
        every { vaultManager.open() } returns Unit

        viewModel.createVault()

        verify(exactly = 1) { vaultManager.open() }
        verify(exactly = 0) { vaultMigration.migrate() }
        assertTrue(viewModel.state.value.vaultReady)
    }

    @Test
    fun `a dialog the user closes says nothing`() = runTest {
        viewModel = buildViewModel()
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Cancelled

        viewModel.createVault()

        assertNull(viewModel.state.value.errorRes)
        assertFalse(viewModel.state.value.vaultReady)
        assertFalse(viewModel.state.value.isAuthenticating, "the button is back")
        verify(exactly = 0) { vaultManager.open() }
    }

    @Test
    fun `a dialog that fails says why with a sentence of ours, one that times out is generic`() = runTest {
        val cases = listOf(
            BiometricPromptResponse.Failed(BiometricFailure.LOCKOUT) to R.string.vault_unlock_error_lockout,
            BiometricPromptResponse.Failed(BiometricFailure.NO_BIOMETRICS) to R.string.vault_unlock_error_none_enrolled,
            BiometricPromptResponse.Failed(BiometricFailure.OTHER) to R.string.vault_unlock_error_prompt_failed,
            null to R.string.vault_unlock_error_prompt_failed,
        )
        for ((response, expected) in cases) {
            viewModel = buildViewModel()
            coEvery { biometricPrompts.authenticateVault(any()) } returns response

            viewModel.createVault()

            assertEquals(expected, viewModel.state.value.errorRes, "$response")
            assertFalse(viewModel.state.value.vaultReady)
            assertFalse(viewModel.state.value.isAuthenticating)
        }
    }

    @Test
    fun `a vault whose window expired or whose key is gone has its own sentence`() = runTest {
        val cases = listOf(
            VaultAuthExpiredException() to R.string.firstlaunch_error_auth_expired,
            KeyPermanentlyInvalidatedException("key invalidated") to R.string.firstlaunch_error_key_invalidated,
            IllegalStateException("secret path /data/x") to R.string.vault_unlock_error_open_failed,
        )
        for ((failure, expected) in cases) {
            viewModel = buildViewModel()
            coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Authorized
            every { vaultMigration.needsMigration() } returns false
            every { vaultManager.open() } throws failure

            viewModel.createVault()

            assertEquals(expected, viewModel.state.value.errorRes, "$failure")
            assertFalse(viewModel.state.value.vaultReady)
            assertFalse(viewModel.state.value.isAuthenticating)
        }
    }

    @Test
    fun `a second tap while the dialog is up is ignored`() = runTest {
        viewModel = buildViewModel()
        val answer = CompletableDeferred<BiometricPromptResponse?>()
        coEvery { biometricPrompts.authenticateVault(any()) } coAnswers { answer.await() }

        viewModel.createVault()
        viewModel.createVault()

        coVerify(exactly = 1) { biometricPrompts.authenticateVault(any()) }
        answer.complete(BiometricPromptResponse.Cancelled)
    }

    // ── onboardingCompleted StateFlow ────────────────────────────────────────

    @Test
    fun `onboardingCompleted settles to false when DataStore emits false`() = runTest {
        viewModel = buildViewModel(completedValue = false)
        advanceUntilIdle()
        // null is the initialValue before the flow delivers: after advanceUntilIdle
        // with UnconfinedTestDispatcher the flowOf value is consumed immediately.
        val completed = viewModel.onboardingCompleted.value
        // With SharingStarted.WhileSubscribed the StateFlow may still hold null if
        // there are no subscribers. Collect it to trigger emission.
        val collected = mutableListOf<Boolean?>()
        val job = launch(testDispatcher) {
            viewModel.onboardingCompleted.collect { collected.add(it) }
        }
        advanceUntilIdle()
        job.cancel()
        // The first non-null emission must be false (not yet completed).
        val firstNonNull = collected.firstOrNull { it != null }
        assertEquals(false, firstNonNull)
    }

    @Test
    fun `onboardingCompleted settles to true when DataStore emits true`() = runTest {
        viewModel = buildViewModel(completedValue = true)
        val collected = mutableListOf<Boolean?>()
        val job = launch(testDispatcher) {
            viewModel.onboardingCompleted.collect { collected.add(it) }
        }
        advanceUntilIdle()
        job.cancel()
        val firstNonNull = collected.firstOrNull { it != null }
        assertEquals(true, firstNonNull)
    }

    @Test
    fun `onboardingCompleted reflects dynamic DataStore updates`() = runTest {
        val completedSource = MutableStateFlow(false)
        every { biometricHelper.canAuthenticate() } returns false
        every { settingsDataStore.onboardingCompletedFlow } returns completedSource
        viewModel = newViewModel()

        val collected = mutableListOf<Boolean?>()
        val job = launch(testDispatcher) {
            viewModel.onboardingCompleted.collect { collected.add(it) }
        }
        advanceUntilIdle()

        // Simulate the DataStore flag being set to true (as completeOnboarding does).
        completedSource.value = true
        advanceUntilIdle()

        job.cancel()

        val nonNullValues = collected.filterNotNull()
        assertTrue(nonNullValues.contains(false), "Should have seen false first")
        assertTrue(nonNullValues.contains(true), "Should have seen true after update")
        assertEquals(false, nonNullValues.first(), "First non-null value should be false")
        assertEquals(true, nonNullValues.last(), "Last value should be true")
    }

    // ── completeOnboarding ───────────────────────────────────────────────────

    @Test
    fun `completeOnboarding calls setOnboardingCompleted(true) on DataStore`() = runTest {
        viewModel = buildViewModel()
        coJustRun { settingsDataStore.setOnboardingCompleted(true) }

        viewModel.completeOnboarding(onPersisted = {})
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsDataStore.setOnboardingCompleted(true) }
    }

    @Test
    fun `completeOnboarding invokes onPersisted callback after writing flag`() = runTest {
        viewModel = buildViewModel()
        coJustRun { settingsDataStore.setOnboardingCompleted(true) }

        var callbackInvoked = false
        viewModel.completeOnboarding(onPersisted = { callbackInvoked = true })
        advanceUntilIdle()

        assertTrue(callbackInvoked, "onPersisted callback must be invoked after DataStore write")
    }

    // ── completeOnboarding unlocks the app ───────────────────────────────────

    @Test
    fun `completing the onboarding after the vault step unlocks the app, before it moves on`() = runTest {
        viewModel = buildViewModel()
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Authorized
        every { vaultMigration.needsMigration() } returns false
        every { vaultManager.open() } returns Unit
        coJustRun { settingsDataStore.setOnboardingCompleted(true) }
        viewModel.createVault()
        assertTrue(appLock.isLocked(), "a process that starts is locked, the vault step alone does not unlock")

        var unlockedWhenItMovedOn: Boolean? = null
        viewModel.completeOnboarding(onPersisted = { unlockedWhenItMovedOn = !appLock.isLocked() })

        assertEquals(true, unlockedWhenItMovedOn, "the host list must not open under the lock screen")
    }

    @Test
    fun `completing the onboarding without the vault step leaves the app locked`() = runTest {
        viewModel = buildViewModel()
        coJustRun { settingsDataStore.setOnboardingCompleted(true) }

        viewModel.completeOnboarding(onPersisted = {})

        assertTrue(appLock.isLocked())
    }
}
