// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import fr.techtical.nextsh.desktop.core.vault.FakeVaultManagerMem
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Deleting a key from the vault removes what the key owns (private key, passphrase,
 * certificate) and nothing else: the password stored under the same id belongs to a
 * host that was switched from key to password and kept the key id as its credentialId.
 */
class VaultScreenViewModelDeleteKeyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = Unit
    }

    private val vault = FakeVaultManagerMem()
    private val keyRepository = mockk<SshKeyRepository>(relaxed = true)

    private fun viewModel(): VaultScreenViewModel {
        every { keyRepository.observeAll() } returns flowOf(emptyList())
        val hostRepository = mockk<HostRepository>()
        every { hostRepository.observeAll() } returns flowOf(emptyList())
        return VaultScreenViewModel(
            appScope = TestAppScope(scope),
            keyRepository = keyRepository,
            hostRepository = hostRepository,
            vaultManager = vault,
            generate = mockk(relaxed = true),
            exporter = mockk(relaxed = true),
            importer = mockk(relaxed = true),
        )
    }

    private fun sshKey(id: String) = SshKey(
        id = id,
        label = "key $id",
        keyType = SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAA",
    )

    @Test
    fun `deleteKey removes the private key, its passphrase and its certificate but not the password stored under the same id`() =
        runTest {
            vault.storePassword("key-id", "host-password".toCharArray())
            vault.storePrivateKey("key-id", "PEM")
            vault.storeCertificate("key-id", "CERT")
            vault.storeKeyPassphrase("key-id", "passphrase".toCharArray())
            // Another key must be left alone.
            vault.storePrivateKey("other-key", "OTHER-PEM")
            vault.storeCertificate("other-key", "OTHER-CERT")

            viewModel().deleteKey(sshKey("key-id"))

            assertNull(vault.getPrivateKey("key-id"))
            assertNull(vault.getKeyPassphrase("key-id"))
            assertNull(vault.getCertificate("key-id"))
            assertEquals(
                "host-password",
                vault.getPassword("key-id")?.concatToString(),
                "the password of a host switched from key to password must survive the key deletion",
            )
            assertEquals("OTHER-PEM", vault.getPrivateKey("other-key"))
            assertEquals("OTHER-CERT", vault.getCertificate("other-key"))
            coVerify(exactly = 1) { keyRepository.delete("key-id") }
        }
}
