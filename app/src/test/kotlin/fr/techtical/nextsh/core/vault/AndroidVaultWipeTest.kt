// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.content.Context
import fr.techtical.nextsh.core.PendingAuthResume
import fr.techtical.nextsh.core.crypto.KeystoreManager
import fr.techtical.nextsh.core.lock.PromptsEndedByLock
import fr.techtical.nextsh.core.ssh.KnownHostsVerifier
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TransferTracker
import fr.techtical.nextsh.core.sync.AndroidSyncScheduler
import fr.techtical.nextsh.data.db.AppDatabase
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.service.AuthRequiredNotifier
import fr.techtical.nextsh.service.DeletedTunnelNotifier
import fr.techtical.nextsh.service.SftpTransferServiceControl
import fr.techtical.nextsh.ui.browser.BrowserSessionHolder
import fr.techtical.nextsh.ui.browser.WebDataEraser
import fr.techtical.nextsh.ui.sessions.TerminalTabStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * What each step of the closing and of the erase does to the real singletons ([AndroidVaultWipeOps]), and
 * the facade ([AndroidVaultWipe]) that starts the wipe, resumes it after the death of the process and
 * closes the failure. The order and the failures of the steps are `VaultWipeRunnerTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidVaultWipeTest {

    @TempDir
    private lateinit var filesDir: Path

    private val gate = VaultWipeGate()
    private val context = mockk<Context>()
    private val settings = mockk<SettingsDataStore>(relaxed = true)
    private val syncScheduler = mockk<AndroidSyncScheduler>(relaxed = true)
    private val prompts = mockk<PromptsEndedByLock>(relaxed = true)
    private val tunnels = mockk<SshTunnelManager>(relaxed = true)
    private val authNotifier = mockk<AuthRequiredNotifier>(relaxed = true)
    private val authResume = mockk<PendingAuthResume>(relaxed = true)
    private val browser = mockk<BrowserSessionHolder>(relaxed = true)
    private val transfers = TransferTracker(gate)
    private val tabs = mockk<TerminalTabStore>(relaxed = true)
    private val sessions = mockk<SshSessionManager>(relaxed = true)
    private val vault = mockk<VaultManager>(relaxed = true)
    private val keystore = mockk<KeystoreManager>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val knownHosts = mockk<KnownHostsVerifier>(relaxed = true)
    private val deletedTunnels = mockk<DeletedTunnelNotifier>(relaxed = true)
    private val transferService = mockk<SftpTransferServiceControl>(relaxed = true)
    private val webData = mockk<WebDataEraser>(relaxed = true)
    private val marker = mockk<VaultWipeMarker>(relaxed = true)

    private fun ops() = AndroidVaultWipeOps(
        context, settings, syncScheduler, prompts, tunnels, authNotifier, authResume, browser, transfers, tabs,
        sessions, vault, keystore, database, knownHosts, deletedTunnels, transferService, webData, marker,
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.filesDir } returns filesDir.toFile()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Closing ──────────────────────────────────────────────────────────────

    @Test
    fun `the sync is turned off first, then stopped and waited for`() = runTest {
        coEvery { syncScheduler.stopAndAwait(any()) } returns true

        val ended = ops().stopSync()

        assertTrue(ended)
        coVerifyOrder {
            settings.updateSyncEnabled(false)
            syncScheduler.stopAndAwait(WIPE_SYNC_WAIT_MS)
        }
    }

    @Test
    fun `a switch that cannot be written does not skip the stop of the sync, and the failure is raised after`() = runTest {
        coEvery { settings.updateSyncEnabled(false) } throws IOException("disk")
        coEvery { syncScheduler.stopAndAwait(any()) } returns true

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { ops().stopSync() } }

        coVerify(exactly = 1) { syncScheduler.stopAndAwait(WIPE_SYNC_WAIT_MS) }
    }

    @Test
    fun `a sync that does not end in time is reported`() = runTest {
        coEvery { syncScheduler.stopAndAwait(any()) } returns false

        assertFalse(ops().stopSync())
    }

    @Test
    fun `the prompts are ended through the lock mechanism`() = runTest {
        ops().endPrompts()

        verify(exactly = 1) { prompts.endPrompts() }
    }

    @Test
    fun `the tunnels are stopped for good, the notifications and the browser go too`() = runTest {
        ops().stopTunnels()

        verify(exactly = 1) { tunnels.stopAllTunnels() }
        verify(exactly = 1) { authNotifier.cancel() }
        verify(exactly = 1) { authResume.retainWaiting(emptySet()) }
        verify(exactly = 1) { browser.close() }
    }

    @Test
    fun `a failure while stopping the tunnels does not skip the rest, and is raised after`() = runTest {
        every { tunnels.stopAllTunnels() } throws IllegalStateException("boom")

        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { ops().stopTunnels() } }

        verify(exactly = 1) { authNotifier.cancel() }
        verify(exactly = 1) { authResume.retainWaiting(emptySet()) }
        verify(exactly = 1) { browser.close() }
    }

    @Test
    fun `the flags of manual stop are not touched, only stopAllTunnels is asked of the manager`() = runTest {
        ops().stopTunnels()

        verify(exactly = 1) { tunnels.stopAllTunnels() }
        io.mockk.confirmVerified(tunnels)
    }

    @Test
    fun `the transfers are cancelled in the tracker and in the service, and the wait is on the copies`() = runTest {
        val request = fr.techtical.nextsh.core.ssh.TransferRequest(
            id = "a",
            sessionId = "s1",
            remotePath = "/x",
            localUri = mockk(relaxed = true),
            direction = fr.techtical.nextsh.core.ssh.TransferDirection.UPLOAD,
            fileSize = 1L,
            displayName = "x",
        )
        transfers.enqueue(request)

        val ended = ops().cancelTransfers()

        assertTrue(ended, "no copy is running")
        verify(exactly = 1) { transferService.cancelAll() }
        assertEquals(fr.techtical.nextsh.core.ssh.TransferStatus.CANCELLED, transfers.transfers.value["a"]?.status)
    }

    @Test
    fun `a copy that does not end in time is reported after the bound`() = runTest {
        transfers.copyStarted("stuck")

        val ended = ops().cancelTransfers()

        assertFalse(ended)
        transfers.copyEnded("stuck")
    }

    @Test
    fun `the tabs are closed, the sessions are closed with their bound, the transfer list is emptied`() = runTest {
        coEvery { sessions.closeAllAndWait(any()) } returns true
        val ops = ops()

        ops.closeTabs()
        val closed = ops.closeSessions()
        ops.clearTransferList()

        verify(exactly = 1) { tabs.closeAll() }
        coVerify(exactly = 1) { sessions.closeAllAndWait(WIPE_SESSIONS_WAIT_MS) }
        assertTrue(closed)
        assertTrue(transfers.transfers.value.isEmpty())
    }

    @Test
    fun `sessions that do not close in time are reported`() = runTest {
        coEvery { sessions.closeAllAndWait(any()) } returns false

        assertFalse(ops().closeSessions())
    }

    // ── Erase ────────────────────────────────────────────────────────────────

    @Test
    fun `the vault is erased on the raw preferences, and the biometric keys with it`() = runTest {
        every { vault.wipeAllEntries() } returns true

        ops().eraseVault()

        verify(exactly = 1) { keystore.deleteAllBiometricKeys() }
        verify(exactly = 1) { vault.wipeAllEntries() }
        // Never the opening of the vault, nor a decrypting read.
        verify(exactly = 0) { vault.open() }
        verify(exactly = 0) { vault.isInitialized() }
    }

    @Test
    fun `a vault that is not confirmed erased is a failure, the biometric keys are still deleted`() = runTest {
        every { vault.wipeAllEntries() } returns false

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { ops().eraseVault() } }

        verify(exactly = 1) { keystore.deleteAllBiometricKeys() }
    }

    @Test
    fun `a biometric key that cannot be deleted does not skip the erase of the vault`() = runTest {
        every { keystore.deleteAllBiometricKeys() } throws java.security.KeyStoreException("fixed")
        every { vault.wipeAllEntries() } returns true

        assertThrows(java.security.KeyStoreException::class.java) { kotlinx.coroutines.runBlocking { ops().eraseVault() } }

        verify(exactly = 1) { vault.wipeAllEntries() }
    }

    @Test
    fun `the tables are cleared in one call of the database`() = runTest {
        ops().clearTables()

        verify(exactly = 1) { database.clearAllTables() }
    }

    @Test
    fun `the known hosts and the web data and the notifications are cleared`() = runTest {
        val ops = ops()

        ops.clearKnownHosts()
        ops.clearWebData()
        ops.cancelNotifications()

        verify(exactly = 1) { knownHosts.clearAll() }
        coVerify(exactly = 1) { webData.eraseAll() }
        verify(exactly = 1) { deletedTunnels.cancelAll() }
    }

    @Test
    fun `the sync switch is turned off and the onboarding flag reset`() = runTest {
        val ops = ops()

        ops.disableSync()
        ops.resetOnboarding()

        coVerify(exactly = 1) { settings.updateSyncEnabled(false) }
        coVerify(exactly = 1) { settings.setOnboardingCompleted(false) }
    }

    @Test
    fun `the credential metadata file, its temporary sibling and its corrupt copies are deleted`() = runTest {
        val dir = filesDir.toFile()
        val files = listOf(
            File(dir, CREDENTIAL_META_FILE_NAME),
            File(dir, "$CREDENTIAL_META_FILE_NAME.tmp"),
            File(dir, "$CREDENTIAL_META_FILE_NAME.corrupt.1700000000000"),
            File(dir, "$CREDENTIAL_META_FILE_NAME.corrupt.1700000000001"),
        )
        files.forEach { it.writeText("hashes of secrets") }
        val other = File(dir, "known_hosts").also { it.writeText("kept") }

        ops().deleteCredentialMeta()

        files.forEach { assertFalse(it.exists(), it.name) }
        assertTrue(other.exists(), "nothing else is touched")
    }

    @Test
    fun `a credential metadata file that stays is an error with a fixed text, after every deletion was tried`() {
        val dir = filesDir.toFile()
        val stuck = File(dir, CREDENTIAL_META_FILE_NAME).also {
            it.mkdirs()
            File(it, "child").writeText("x")
        }
        val corrupt = File(dir, "$CREDENTIAL_META_FILE_NAME.corrupt.1").also { it.writeText("x") }

        val failure = assertThrows(IOException::class.java) { deleteCredentialMetaFile(stuck) }

        assertEquals("Credential metadata file could not be deleted", failure.message)
        assertFalse(corrupt.exists(), "the others were deleted anyway")
    }

    @Test
    fun `the marker that cannot be cleared is an error, and the vault is checked on the raw preferences`() = runTest {
        every { marker.clear() } returns false
        every { vault.isWiped() } returns true
        val ops = ops()

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { ops.clearMarker() } }
        assertTrue(ops.vaultGone())
    }

    // ── The facade ───────────────────────────────────────────────────────────

    private fun TestScope.facade(ops: AndroidVaultWipeOps): AndroidVaultWipe {
        coEvery { ops.stopSync() } returns true
        coEvery { ops.cancelTransfers() } returns true
        coEvery { ops.closeSessions() } returns true
        coEvery { ops.vaultGone() } returns true
        every { marker.set() } returns true
        return AndroidVaultWipe(ops, marker, gate, this)
    }

    @Test
    fun `start begins by the marker, wipes, and ends in DONE`() = runTest {
        val ops = mockk<AndroidVaultWipeOps>(relaxed = true)
        val wipe = facade(ops)

        wipe.start()
        advanceUntilIdle()

        assertEquals(VaultWipeState.DONE, wipe.state.value)
        coVerifyOrder {
            ops.beginWipe()
            ops.stopSync()
            ops.eraseVault()
            ops.resetOnboarding()
            ops.clearMarker()
        }
    }

    @Test
    fun `no marker, nothing to resume`() = runTest {
        every { marker.isPending() } returns false
        val wipe = facade(mockk(relaxed = true))

        assertFalse(wipe.resumeIfPending())

        assertEquals(VaultWipeState.IDLE, wipe.state.value)
    }

    @Test
    fun `a marker left by a process that died resumes the wipe, and the app is closed at once`() = runTest {
        every { marker.isPending() } returns true
        val ops = mockk<AndroidVaultWipeOps>(relaxed = true)
        val wipe = facade(ops)

        assertTrue(wipe.resumeIfPending())

        assertTrue(gate.isClosed(), "closed before anything that follows in Application.onCreate can start")
        advanceUntilIdle()
        assertEquals(VaultWipeState.DONE, wipe.state.value)
        coVerify(exactly = 1) { ops.eraseVault() }
        coVerify(exactly = 1) { ops.clearMarker() }
    }

    @Test
    fun `the work runs in the scope it was given, not in a view model nor an activity`() = runTest {
        val ops = mockk<AndroidVaultWipeOps>(relaxed = true)
        val wipe = facade(ops)

        wipe.start()

        // Nothing ran yet: it is queued in the scope of the app, and only the scope runs it.
        coVerify(exactly = 0) { ops.stopSync() }
        advanceUntilIdle()
        coVerify(exactly = 1) { ops.stopSync() }
    }

    @Test
    fun `closing the failure of a wipe whose vault is gone restarts the app on the creation of the vault`() = runTest {
        val ops = mockk<AndroidVaultWipeOps>(relaxed = true)
        val wipe = facade(ops)
        coEvery { ops.clearTables() } throws IOException("fixed")
        wipe.start()
        advanceUntilIdle()
        assertEquals(VaultWipeState.FAILED, wipe.state.value)

        wipe.dismissFailure()
        advanceUntilIdle()

        assertEquals(VaultWipeState.DONE, wipe.state.value, "the vault is gone: the app restarts on its creation")
    }

    // ── The marker of a wipe under way ───────────────────────────────────────

    @Test
    fun `the wipe begins by writing the marker, and goes on when it cannot be written`() = runTest {
        every { marker.set() } returns true
        ops().beginWipe()
        verify(exactly = 1) { marker.set() }

        every { marker.set() } returns false
        ops().beginWipe() // no exception: the user asked for the wipe
    }
}
