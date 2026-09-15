// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.vault.VaultExporter
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class ExportVaultUseCaseTest {

    @MockK
    private lateinit var vaultExporter: VaultExporter

    private lateinit var useCase: ExportVaultUseCase

    @BeforeEach
    fun setUp() {
        useCase = ExportVaultUseCase(vaultExporter)
    }

    @Test
    fun `success - exporter returns ByteArray, use case returns Result success`() = runTest {
        val passphrase = "my-secure-passphrase".toCharArray()
        val exportedData = byteArrayOf(1, 2, 3, 4, 5)
        coEvery { vaultExporter.export(passphrase) } returns exportedData

        val result = useCase(passphrase)

        assertTrue(result.isSuccess)
        assertArrayEquals(exportedData, result.getOrNull())
    }

    @Test
    fun `failure - exporter throws exception, use case returns Result failure`() = runTest {
        val passphrase = "my-secure-passphrase".toCharArray()
        val exception = RuntimeException("Export failed")
        coEvery { vaultExporter.export(any()) } throws exception

        val result = useCase(passphrase)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is RuntimeException)
    }

    @Test
    fun `failure - passphrase is wiped when exporter throws exception`() = runTest {
        val passphrase = "my-secure-passphrase".toCharArray()
        coEvery { vaultExporter.export(any()) } throws RuntimeException("Export failed")

        useCase(passphrase)

        assertTrue(
            passphrase.all { it == '\u0000' },
            "Passphrase should be wiped to null characters after failure"
        )
    }

    @Test
    fun `success - passphrase wipe behavior on success path`() = runTest {
        // On the success path, the use case does NOT wipe the passphrase
        // (caller is responsible). This test documents the actual behavior.
        val passphrase = "my-secure-passphrase".toCharArray()
        val originalPassphrase = passphrase.copyOf()
        coEvery { vaultExporter.export(passphrase) } returns byteArrayOf(1, 2, 3)

        val result = useCase(passphrase)

        assertTrue(result.isSuccess)
        // Passphrase is NOT wiped by the use case itself on success
        assertArrayEquals(originalPassphrase, passphrase)
    }
}
