// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TransferState
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [DesktopTransferManager.cancelAll] and [DesktopTransferManager.clearAll], what the vault wipe relies on:
 * every transfer is cancelled and awaited (bounded), then the list, which carries host labels and paths,
 * is emptied. Real time, like [DesktopTransferManagerTest].
 */
class DesktopTransferManagerCancelAllTest {

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = coroutineScope.cancel()
    }

    private val app = TestAppScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    private val sftpManager = mockk<DesktopSftpManager>()
    private val manager = DesktopTransferManager(sftpManager, app)
    private val tempDir: File = Files.createTempDirectory("nextsh-cancel-all-test").toFile()

    @AfterTest
    fun tearDown() {
        app.onDestroy()
        tempDir.deleteRecursively()
    }

    private fun localFile(name: String): File = File(tempDir, name).also { it.writeText("hello") }

    private suspend fun awaitInProgress(vararg ids: String) {
        withTimeout(5_000L) {
            manager.transfers.first { map -> ids.all { map[it] is TransferState.InProgress } }
        }
    }

    @Test
    fun `cancelAll cancels the transfers under way, waits for them, and clearAll empties the list`() = runBlocking<Unit> {
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        val first = manager.enqueueUpload("s1", "/srv", localFile("a.txt"), replaceExisting = false)
        val second = manager.enqueueUpload("s1", "/srv", localFile("b.txt"), replaceExisting = false)
        awaitInProgress(first, second)

        val allEnded = manager.cancelAll(timeoutMs = 3_000L)

        assertTrue(allEnded)
        assertIs<TransferState.Cancelled>(manager.transfers.value.getValue(first))
        assertIs<TransferState.Cancelled>(manager.transfers.value.getValue(second))

        manager.clearAll()

        assertTrue(manager.transfers.value.isEmpty())
    }

    @Test
    fun `cancelAll gives the hand back at the delay when a transfer does not end`() = runBlocking<Unit> {
        // A transfer stuck on a dead route: it ignores the cancellation for two seconds.
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { delay(2_000L) }
            SshResult.Success(Unit)
        }
        val id = manager.enqueueUpload("s1", "/srv", localFile("a.txt"), replaceExisting = false)
        awaitInProgress(id)

        val started = System.currentTimeMillis()
        val allEnded = manager.cancelAll(timeoutMs = 200L)
        val elapsed = System.currentTimeMillis() - started

        assertFalse(allEnded)
        assertTrue(elapsed < 1_500L, "the wait is bounded, it took $elapsed ms")
    }

    @Test
    fun `cancelAll with nothing under way ends at once`() = runBlocking<Unit> {
        assertTrue(manager.cancelAll(timeoutMs = 200L))
        assertEquals(0, manager.transfers.value.size)
    }

    @Test
    fun `clearAll empties finished entries too`() = runBlocking<Unit> {
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } returns SshResult.Success(Unit)
        val id = manager.enqueueUpload("s1", "/srv", localFile("a.txt"), replaceExisting = false)
        withTimeout(5_000L) { manager.transfers.first { it[id] is TransferState.Completed } }

        manager.clearAll()

        assertTrue(manager.transfers.value.isEmpty())
    }

    @Test
    fun `a transfer that outlives the wait does not write its entry back after clearAll`() = runBlocking<Unit> {
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { delay(600L) }
            SshResult.Success(Unit)
        }
        val id = manager.enqueueUpload("s1", "/srv", localFile("a.txt"), replaceExisting = false)
        awaitInProgress(id)
        assertFalse(manager.cancelAll(timeoutMs = 100L))

        manager.clearAll()
        delay(1_200L) // the stuck transfer ends now and would write Cancelled

        assertTrue(manager.transfers.value.isEmpty(), "the list stays empty: ${manager.transfers.value.keys}")
    }
}
