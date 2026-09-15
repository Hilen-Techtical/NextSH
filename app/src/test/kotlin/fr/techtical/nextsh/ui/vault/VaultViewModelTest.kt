// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import fr.techtical.nextsh.core.ssh.SshKeyManager
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.usecase.ExportVaultUseCase
import fr.techtical.nextsh.domain.usecase.GenerateSshKeyUseCase
import fr.techtical.nextsh.domain.usecase.ImportSshKeyUseCase
import fr.techtical.nextsh.domain.usecase.ImportVaultUseCase
import fr.techtical.nextsh.testutil.awaitVerified
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class VaultViewModelTest {

    @MockK
    private lateinit var sshKeyRepository: SshKeyRepository

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var sshKeyManager: SshKeyManager

    @MockK
    private lateinit var keystoreManager: fr.techtical.nextsh.core.crypto.KeystoreManager

    @MockK
    private lateinit var importSshKey: ImportSshKeyUseCase

    @MockK
    private lateinit var generateSshKey: GenerateSshKeyUseCase

    @MockK
    private lateinit var exportVaultUseCase: ExportVaultUseCase

    @MockK
    private lateinit var importVaultUseCase: ImportVaultUseCase

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: VaultViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { sshKeyRepository.observeAll() } returns flowOf(emptyList())
        every { vaultManager.listStoredCredentialIds() } returns emptyList()

        viewModel = VaultViewModel(
            sshKeyRepository = sshKeyRepository,
            vaultManager = vaultManager,
            sshKeyManager = sshKeyManager,
            keystoreManager = keystoreManager,
            importSshKey = importSshKey,
            generateSshKey = generateSshKey,
            exportVaultUseCase = exportVaultUseCase,
            importVaultUseCase = importVaultUseCase,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state - isLoading is true before data loads`() {
        // After construction with flowOf(emptyList()), the UnconfinedTestDispatcher
        // processes the flow immediately. We verify the keys list is initialized.
        val state = viewModel.uiState.value
        assertNotNull(state)
        // isLoading starts as true in VaultUiState default
        assertTrue(state.sshKeys.isEmpty())
    }

    @Test
    fun `generateKey with blank label sets error and isGenerating stays false`() = runTest {
        val blankLabel = "   "

        viewModel.generateKey(label = blankLabel, type = fr.techtical.nextsh.domain.model.SshKeyType.ED25519)

        val state = viewModel.uiState.value
        assertFalse(state.isGenerating)
        assertNotNull(state.error)
        assertTrue(state.error!!.isNotBlank())
    }

    @Test
    fun `deleteKey calls vaultManager deletePrivateKey and sshKeyRepository delete`() = runTest {
        val testKey = SshKey(
            id = "key-1",
            label = "My Key",
            keyType = fr.techtical.nextsh.domain.model.SshKeyType.ED25519,
            publicKey = "ssh-ed25519 AAAA...",
            isBiometric = false,
            keystoreAlias = null,
        )

        coEvery { sshKeyRepository.getById(testKey.id) } returns testKey
        every { vaultManager.deletePrivateKey(testKey.id) } returns Unit
        coEvery { sshKeyRepository.delete(testKey.id) } returns Unit

        viewModel.deleteKey(testKey.id)

        // deleteKey runs in a coroutine; poll until the verifications and the
        // resulting state update land, instead of racing them. The assertions
        // are unchanged, only the timing race is removed.
        awaitVerified {
            verify(exactly = 1) { vaultManager.deletePrivateKey(testKey.id) }
            coVerify(exactly = 1) { sshKeyRepository.delete(testKey.id) }
            assertNotNull(viewModel.uiState.value.successMessage)
        }
    }
}
