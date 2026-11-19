// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.di

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.data.db.DesktopDatabase
import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.desktop.sync.LanSyncServerLifecycle
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two halves of "Wipe the vault": [stopVaultDependentWork] closes everything first, in a fixed order,
 * [eraseVaultData] then erases. A failing closing step never prevents the erase.
 */
class VaultWipeTest {

    private val syncLifecycle = mockk<LanSyncServerLifecycle>(relaxed = true)
    private val tunnelService = mockk<DesktopTunnelService>(relaxed = true)
    private val transferManager = mockk<DesktopTransferManager>(relaxed = true)
    private val sessionManager = mockk<DesktopSessionManager>(relaxed = true)

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("nextsh-vault-wipe-test").toFile()
        // The two steps that report whether they finished in time.
        coEvery { transferManager.cancelAll(any()) } returns true
        coEvery { sessionManager.closeAllSessionsAndWait(any()) } returns true
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    // -- Closing, in order -------------------------------------------------------

    @Test
    fun `sync, host keys, tunnels, transfers, sessions, list then temporaries, in that order`() = runTest {
        val done = stopVaultDependentWork(syncLifecycle, tunnelService, transferManager, sessionManager)

        assertTrue(done)
        coVerifyOrder {
            syncLifecycle.onVaultLocked()
            sessionManager.setHostKeyLockdown(true)
            tunnelService.onVaultWiped()
            transferManager.cancelAll(any())
            sessionManager.closeAllSessionsAndWait(any())
            transferManager.clearAll()
            transferManager.deleteTemporaries()
        }
    }

    @Test
    fun `the transfer list is emptied after the sessions are closed`() = runTest {
        stopVaultDependentWork(syncLifecycle, tunnelService, transferManager, sessionManager)

        coVerifyOrder {
            sessionManager.closeAllSessionsAndWait(any())
            transferManager.clearAll()
        }
    }

    @Test
    fun `a failing step does not prevent the following ones`() = runTest {
        coEvery { tunnelService.onVaultWiped() } throws IllegalStateException("boom")
        coEvery { transferManager.cancelAll(any()) } throws IllegalStateException("boom")

        val done = stopVaultDependentWork(syncLifecycle, tunnelService, transferManager, sessionManager)

        assertFalse(done)
        coVerify(exactly = 1) { sessionManager.closeAllSessionsAndWait(any()) }
        verify(exactly = 1) { transferManager.deleteTemporaries() }
    }

    @Test
    fun `sessions that did not close in time are reported but the next steps still run`() = runTest {
        coEvery { sessionManager.closeAllSessionsAndWait(any()) } returns false

        val done = stopVaultDependentWork(syncLifecycle, tunnelService, transferManager, sessionManager)

        assertFalse(done)
        verify(exactly = 1) { transferManager.clearAll() }
        verify(exactly = 1) { transferManager.deleteTemporaries() }
    }

    @Test
    fun `a cancellation is not swallowed`() = runTest {
        coEvery { tunnelService.onVaultWiped() } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            stopVaultDependentWork(syncLifecycle, tunnelService, transferManager, sessionManager)
        }
        coVerify(exactly = 0) { sessionManager.closeAllSessionsAndWait(any()) }
    }

    // -- Erase -------------------------------------------------------------------

    private val vaultManager = mockk<VaultManager>(relaxed = true)
    private val database = mockk<DesktopDatabase>(relaxed = true)
    private val knownHostsStore = mockk<DesktopKnownHostsStore>(relaxed = true)
    private val settingsStore = mockk<DesktopSettingsStore>(relaxed = true)

    @Test
    fun `the erase empties the vault, the tables, the known hosts, the sync switch and the metadata files`() = runTest {
        val meta = File(dir, "credential-meta.json").apply { writeText("{}") }
        val tmp = File(dir, "credential-meta.json.tmp").apply { writeText("{}") }
        // What the store leaves behind when it cannot read its file: it holds hashes of secrets.
        val corrupt = File(dir, "credential-meta.json.corrupt.1700000000000").apply { writeText("{") }
        val unrelated = File(dir, "credential-meta.json.keep-me").apply { writeText("x") }

        val failed = eraseVaultData(vaultManager, database, knownHostsStore, settingsStore, meta)

        assertTrue(failed.isEmpty())
        coVerify(exactly = 1) { vaultManager.wipeVault() }
        verify(exactly = 1) { database.wipeAllTables() }
        verify(exactly = 1) { knownHostsStore.deleteAll() }
        verify(exactly = 1) { settingsStore.updateSyncEnabled(false) }
        assertFalse(meta.exists(), "credential-meta.json is deleted")
        assertFalse(tmp.exists(), "and so is the temporary sibling of an interrupted write")
        assertFalse(corrupt.exists(), "and the corrupt copies")
        assertTrue(unrelated.exists(), "but a file that only looks alike is left alone")
    }

    @Test
    fun `an absent metadata file is fine`() = runTest {
        val failed = eraseVaultData(vaultManager, database, knownHostsStore, settingsStore, File(dir, "credential-meta.json"))

        assertTrue(failed.isEmpty())
        coVerify(exactly = 1) { vaultManager.wipeVault() }
    }

    @Test
    fun `a failing erase step does not stop the others and is reported`() = runTest {
        every { database.wipeAllTables() } throws IllegalStateException("boom")
        val meta = File(dir, "credential-meta.json").apply { writeText("{}") }

        val failed = eraseVaultData(vaultManager, database, knownHostsStore, settingsStore, meta)

        assertEquals(setOf(EraseStep.TABLES), failed)
        coVerify(exactly = 1) { vaultManager.wipeVault() }
        verify(exactly = 1) { knownHostsStore.deleteAll() }
        verify(exactly = 1) { settingsStore.updateSyncEnabled(false) }
        assertFalse(meta.exists())
    }

    @Test
    fun `a vault that cannot be deleted is reported as a failed step`() = runTest {
        coEvery { vaultManager.wipeVault() } throws java.io.IOException("Vault file could not be deleted")

        val failed = eraseVaultData(vaultManager, database, knownHostsStore, settingsStore, File(dir, "m.json"))

        assertEquals(setOf(EraseStep.VAULT), failed)
    }

    @Test
    fun `only the asked steps run, for a retry`() = runTest {
        val failed = eraseVaultData(
            vaultManager, database, knownHostsStore, settingsStore, File(dir, "m.json"),
            steps = setOf(EraseStep.TABLES),
        )

        assertTrue(failed.isEmpty())
        verify(exactly = 1) { database.wipeAllTables() }
        coVerify(exactly = 0) { vaultManager.wipeVault() }
        verify(exactly = 0) { knownHostsStore.deleteAll() }
    }

    @Test
    fun `a cancellation during the erase is not swallowed`() = runTest {
        coEvery { vaultManager.wipeVault() } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            eraseVaultData(vaultManager, database, knownHostsStore, settingsStore, File(dir, "m.json"))
        }
    }

    @Test
    fun `the vault is erased even if the closing steps all failed`() = runTest {
        coEvery { tunnelService.onVaultWiped() } throws IllegalStateException("boom")
        coEvery { sessionManager.closeAllSessionsAndWait(any()) } throws IllegalStateException("boom")

        val done = stopVaultDependentWork(syncLifecycle, tunnelService, transferManager, sessionManager)
        eraseVaultData(vaultManager, database, knownHostsStore, settingsStore, File(dir, "credential-meta.json"))

        assertFalse(done)
        coVerify(exactly = 1) { vaultManager.wipeVault() }
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
}
