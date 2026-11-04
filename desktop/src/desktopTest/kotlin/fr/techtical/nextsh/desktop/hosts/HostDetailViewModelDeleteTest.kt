// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.desktop.core.vault.FakeVaultManagerMem
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostFolderRepository
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
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
import kotlin.test.assertTrue

/**
 * Deleting a host must only purge what the host owns, and only that type: a vault id
 * can hold a password, a private key, a certificate and a passphrase at once.
 */
class HostDetailViewModelDeleteTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = Unit
    }

    private val hostRepository = mockk<HostRepository>(relaxed = true)
    private val vault = FakeVaultManagerMem()

    private fun host(authType: AuthType, credentialId: String) = Host(
        id = "host-1",
        label = "web",
        hostname = "web.example",
        username = "root",
        authType = authType,
        credentialId = credentialId,
    )

    /** Builds the ViewModel, loads [host] into its form state and deletes it. */
    private fun loadAndDelete(host: Host): Boolean {
        coEvery { hostRepository.getById(host.id) } returns host
        val sshKeyRepository = mockk<SshKeyRepository>(relaxed = true)
        every { sshKeyRepository.observeAll() } returns flowOf(emptyList())
        val hostFolderRepository = mockk<DesktopHostFolderRepository>()
        every { hostFolderRepository.observeAllGroups() } returns flowOf(emptyList())
        val customThemeRepository = mockk<CustomTerminalThemeRepository>()
        every { customThemeRepository.observeAll() } returns flowOf(emptyList())

        val viewModel = HostDetailViewModel(
            repository = hostRepository,
            sshKeyRepository = sshKeyRepository,
            hostFolderRepository = hostFolderRepository,
            vaultManager = vault,
            customThemeRepository = customThemeRepository,
            appScope = TestAppScope(scope),
        )
        viewModel.load(host.id)
        var done = false
        viewModel.delete { done = true }
        return done
    }

    /** Vault holding every type under [id], like a key whose host was switched to password. */
    private suspend fun storeEveryTypeUnder(id: String) {
        vault.storePassword(id, "s3cr3t".toCharArray())
        vault.storePrivateKey(id, "PEM")
        vault.storeCertificate(id, "CERT")
        vault.storeKeyPassphrase(id, "passphrase".toCharArray())
    }

    @Test
    fun `deleting a password host removes the host row and its password`() = runTest {
        vault.storePassword("cred-1", "s3cr3t".toCharArray())
        vault.storePassword("cred-2", "other".toCharArray())

        assertTrue(loadAndDelete(host(AuthType.PASSWORD, "cred-1")))

        coVerify(exactly = 1) { hostRepository.delete("host-1") }
        assertNull(vault.getPassword("cred-1"))
        assertEquals("other", vault.getPassword("cred-2")?.concatToString(), "another host's password must survive")
    }

    @Test
    fun `deleting a password host that kept a key id as credentialId keeps the key, its passphrase and its certificate`() =
        runTest {
            // Host created on Android, switched from SSH key to password: credentialId is the KEY id.
            storeEveryTypeUnder("key-id")

            assertTrue(loadAndDelete(host(AuthType.PASSWORD, "key-id")))

            assertNull(vault.getPassword("key-id"), "the host's own password is removed")
            assertEquals("PEM", vault.getPrivateKey("key-id"), "the private key must survive")
            assertEquals("CERT", vault.getCertificate("key-id"), "the certificate must survive")
            assertEquals("passphrase", vault.getKeyPassphrase("key-id")?.concatToString(), "the passphrase must survive")
        }

    @Test
    fun `deleting a certificate host keeps the key and the certificate shared with the other hosts of that key`() =
        runTest {
            storeEveryTypeUnder("key-id")

            assertTrue(loadAndDelete(host(AuthType.CERTIFICATE, "key-id")))

            coVerify(exactly = 1) { hostRepository.delete("host-1") }
            assertEquals("s3cr3t", vault.getPassword("key-id")?.concatToString())
            assertEquals("PEM", vault.getPrivateKey("key-id"))
            assertEquals("CERT", vault.getCertificate("key-id"), "the certificate belongs to the key, not to the host")
            assertEquals("passphrase", vault.getKeyPassphrase("key-id")?.concatToString())
        }

    @Test
    fun `deleting an ssh key host does not touch the vault`() = runTest {
        storeEveryTypeUnder("key-id")

        assertTrue(loadAndDelete(host(AuthType.SSH_KEY, "key-id")))

        coVerify(exactly = 1) { hostRepository.delete("host-1") }
        assertEquals("s3cr3t", vault.getPassword("key-id")?.concatToString())
        assertEquals("PEM", vault.getPrivateKey("key-id"))
        assertEquals("CERT", vault.getCertificate("key-id"))
        assertEquals("passphrase", vault.getKeyPassphrase("key-id")?.concatToString())
    }
}
