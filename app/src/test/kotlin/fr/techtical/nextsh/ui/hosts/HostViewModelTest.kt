// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.coJustRun
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class HostViewModelTest {

    @MockK
    private lateinit var hostRepository: HostRepository

    @MockK
    private lateinit var connectSession: ConnectSessionUseCase

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var sshKeyRepository: SshKeyRepository

    @MockK
    private lateinit var customThemeRepository: CustomTerminalThemeRepository

    @MockK
    private lateinit var syncScheduler: SyncScheduler

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: HostViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { hostRepository.observeAll() } returns flowOf(emptyList())
        every { hostRepository.observeGroups() } returns flowOf(emptyList())
        every { sshKeyRepository.observeAll() } returns flowOf(emptyList())
        every { customThemeRepository.observeAll() } returns flowOf(emptyList())
        every { syncScheduler.syncState } returns MutableStateFlow(SyncState(SyncStatus.IDLE, null))

        viewModel = HostViewModel(
            hostRepository = hostRepository,
            connectSession = connectSession,
            vaultManager = vaultManager,
            sshKeyRepository = sshKeyRepository,
            customThemeRepository = customThemeRepository,
            syncScheduler = syncScheduler,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Test helpers ─────────────────────────────────────────────────────────

    private fun makeHost(id: String, isFavorite: Boolean) = Host(
        id = id,
        label = "Host $id",
        hostname = "host$id.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
        isFavorite = isFavorite,
    )

    // ── toggleFavorite tests ─────────────────────────────────────────────────

    @Test
    fun `toggleFavorite calls setFavorite with true when host is not a favorite`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        coJustRun { hostRepository.setFavorite(host.id, true) }

        viewModel.toggleFavorite(host)

        advanceUntilIdle()
        coVerify(exactly = 1) { hostRepository.setFavorite(host.id, true) }
    }

    @Test
    fun `toggleFavorite calls setFavorite with false when host is already a favorite`() = runTest {
        val host = makeHost("h1", isFavorite = true)
        coJustRun { hostRepository.setFavorite(host.id, false) }

        viewModel.toggleFavorite(host)

        advanceUntilIdle()
        coVerify(exactly = 1) { hostRepository.setFavorite(host.id, false) }
    }

    // ── saveHost favorite preservation tests ────────────────────────────────

    @Test
    fun `saveHost preserves isFavorite when updating an existing favorite host`() = runTest {
        val existingHost = makeHost("h1", isFavorite = true)
        coEvery { hostRepository.getById("h1") } returns existingHost
        coJustRun { hostRepository.update(any()) }

        // Load host for edit
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        // Save without changing anything
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertTrue(hostSlot.captured.isFavorite, "isFavorite should be true after update")
    }

    @Test
    fun `saveHost sets isFavorite to false for new host`() = runTest {
        coJustRun { hostRepository.save(any()) }
        coJustRun { vaultManager.storePassword(any(), any()) }

        // Fill minimal form for a new host
        viewModel.updateForm {
            copy(
                label = "New Host",
                hostname = "new.example.com",
                username = "user",
                authType = AuthType.PASSWORD,
                password = "pass",
            )
        }

        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertFalse(hostSlot.captured.isFavorite, "isFavorite should be false for new host")
    }
}
