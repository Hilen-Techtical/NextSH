// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.shared.core.`import`.HostImportService
import fr.techtical.nextsh.shared.core.`import`.ImportFormatDetector
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the Desktop [HostImportViewModel], review fixes:
 *  - the re-entrance guard at the top of [HostImportViewModel.parseFile] (a
 *    second parseFile call while a flow is already in progress used to
 *    reassign [ImportPreviewRow]-backing state out from under an in-flight
 *    [HostImportViewModel.confirmImport] coroutine, wiping the WRONG
 *    preview's secrets);
 *  - [HostImportViewModel.confirmImport] now wrapping its coroutine body in
 *    try/catch so an unexpected failure wipes every retained secret and
 *    surfaces [HostImportState.Error] instead of leaving plaintext
 *    credentials sitting in memory.
 *
 * [HostImportViewModel.parseFile] and [HostImportViewModel.confirmImport]
 * both hop onto the real [Dispatchers.IO] (hardcoded in production code, not
 * injectable): plain [kotlinx.coroutines.test.runTest] virtual time cannot
 * observe that background work, so these tests use a real [CoroutineScope]
 * for [AppScope] and a small bounded real-time poll ([awaitState]) instead of
 * `advanceUntilIdle()`. [NextShImporter] / [TermiusImporter] /
 * [KeePassXcImporter] / [ImportFormatDetector] are plain objects (hard to
 * mock cleanly): the "routing" tests below feed real, minimal content for
 * each format rather than mocking them, per the batch instructions.
 */
class HostImportViewModelTest {

    private lateinit var testScope: CoroutineScope

    @AfterTest
    fun tearDown() {
        if (::testScope.isInitialized) testScope.cancel()
    }

    // ── Test double ──────────────────────────────────────────────────────────

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private fun newViewModel(
        hostRepository: HostRepository = mockk(relaxed = true),
        vaultManager: VaultManager = mockk(relaxed = true),
        sshKeyRepository: SshKeyRepository = mockk(relaxed = true),
        importService: HostImportService = HostImportService(),
    ): HostImportViewModel {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return HostImportViewModel(
            hostRepository = hostRepository,
            vaultManager = vaultManager,
            sshKeyRepository = sshKeyRepository,
            appScope = TestAppScope(testScope),
            importService = importService,
        )
    }

    private fun tempFile(content: String, suffix: String = ".json"): File {
        val file = File.createTempFile("host-import-test", suffix)
        file.deleteOnExit()
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    private fun nextshContent(hostname: String, extraFields: String = ""): String =
        """{ "format": "nextsh-hosts", "hosts": [ { "hostname": "$hostname"$extraFields } ] }"""

    /** Bounded real-time poll: see the class doc for why virtual time cannot be used here. */
    private fun awaitState(
        viewModel: HostImportViewModel,
        timeoutMs: Long = 5_000,
        predicate: (HostImportState) -> Boolean,
    ): HostImportState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val current = viewModel.state.value
            if (predicate(current)) return current
            check(System.currentTimeMillis() <= deadline) { "Timed out waiting for expected state; last state=$current" }
            Thread.sleep(5)
        }
    }

    // ── Routing / detected format exposed ───────────────────────────────────

    @Test
    fun `parseFile routes native NextSH content to the NEXTSH importer and exposes the detected format`() {
        val viewModel = newViewModel()
        val file = tempFile(nextshContent("nextsh-routing.example"))

        viewModel.parseFile(file)

        val preview = awaitState(viewModel) { it is HostImportState.Preview } as HostImportState.Preview
        assertEquals(ImportFormatDetector.ImportFormat.NEXTSH, preview.format)
        assertEquals("nextsh-routing.example", preview.rows.single().hostname)
    }

    @Test
    fun `parseFile routes a Termius signature export to the TERMIUS importer and exposes the detected format`() {
        val viewModel = newViewModel()
        val content = """
            {
              "identities": [ { "id": "i1", "username": "root", "password": "s3cr3t" } ],
              "hosts": [ { "id": "h1", "label": "web-01", "address": "termius-routing.example", "identity": "i1" } ]
            }
        """.trimIndent()
        val file = tempFile(content)

        viewModel.parseFile(file)

        val preview = awaitState(viewModel) { it is HostImportState.Preview } as HostImportState.Preview
        assertEquals(ImportFormatDetector.ImportFormat.TERMIUS, preview.format)
        assertEquals("termius-routing.example", preview.rows.single().hostname)
    }

    @Test
    fun `parseFile routes a KeePassXC CSV export to the KEEPASSXC importer and exposes the detected format`() {
        val viewModel = newViewModel()
        val content = "Group,Title,Username,Password,URL\nHome,router,admin,admin123,keepassxc-routing.example:22\n"
        val file = tempFile(content, suffix = ".csv")

        viewModel.parseFile(file)

        val preview = awaitState(viewModel) { it is HostImportState.Preview } as HostImportState.Preview
        assertEquals(ImportFormatDetector.ImportFormat.KEEPASSXC, preview.format)
        assertEquals("keepassxc-routing.example", preview.rows.single().hostname)
    }

    // ── Secret-bearing rows start unchecked ─────────────────────────────────

    @Test
    fun `parseFile unchecks rows carrying a secret and leaves the others checked`() {
        val viewModel = newViewModel()
        val content = """
            {
              "format": "nextsh-hosts",
              "hosts": [
                { "hostname": "secret.example", "password": "hunter2" },
                { "hostname": "plain.example" }
              ]
            }
        """.trimIndent()
        val file = tempFile(content)

        viewModel.parseFile(file)

        val preview = awaitState(viewModel) { it is HostImportState.Preview } as HostImportState.Preview
        val secretRow = preview.rows.single { it.hostname == "secret.example" }
        val plainRow = preview.rows.single { it.hostname == "plain.example" }
        assertTrue(secretRow.hasSecret)
        assertFalse(secretRow.selected)
        assertFalse(plainRow.hasSecret)
        assertTrue(plainRow.selected)
    }

    // ── Re-entrance guard (item 3a) ──────────────────────────────────────────

    @Test
    fun `parseFile ignores a second call while a preview is already showing`() {
        val viewModel = newViewModel()
        val fileA = tempFile(nextshContent("guarded-original.example"))
        val fileB = tempFile(nextshContent("guarded-second.example"))

        viewModel.parseFile(fileA)
        val preview = awaitState(viewModel) { it is HostImportState.Preview } as HostImportState.Preview
        assertEquals("guarded-original.example", preview.rows.single().hostname)

        // State is Preview (not Idle): the guard must make this a no-op.
        viewModel.parseFile(fileB)
        Thread.sleep(50) // safety margin in case a regression makes this async

        val stateAfter = viewModel.state.value
        assertTrue(stateAfter is HostImportState.Preview)
        assertEquals("guarded-original.example", (stateAfter as HostImportState.Preview).rows.single().hostname)
    }

    // ── File too large ───────────────────────────────────────────────────────

    @Test
    fun `parseFile rejects a file exceeding the size cap without reading its content`() {
        val viewModel = newViewModel()
        val bigFile = File.createTempFile("host-import-toolarge", ".json")
        bigFile.deleteOnExit()
        // Sparse file: reports the desired length via File#length() without
        // actually writing MAX_IMPORT_FILE_BYTES + 1 bytes to disk.
        RandomAccessFile(bigFile, "rw").use { it.setLength(ImportFormatDetector.MAX_IMPORT_FILE_BYTES + 1) }

        viewModel.parseFile(bigFile)

        val state = awaitState(viewModel) { it is HostImportState.Error }
        assertEquals(ImportErrorKind.FileTooLarge, (state as HostImportState.Error).messageKey)
    }

    // ── Zero selection wipes and resets ──────────────────────────────────────

    @Test
    fun `confirmImport with nothing selected resets to Idle without persisting anything`() {
        val hostRepository: HostRepository = mockk(relaxed = true)
        val viewModel = newViewModel(hostRepository = hostRepository)
        val file = tempFile(nextshContent("unselected.example"))
        viewModel.parseFile(file)
        awaitState(viewModel) { it is HostImportState.Preview }
        viewModel.setAllSelected(false)

        viewModel.confirmImport()

        assertEquals(HostImportState.Idle, viewModel.state.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
    }

    // ── Import failure wipes and surfaces Error (item 3b) ────────────────────

    @Test
    fun `confirmImport wipes parsedHosts and surfaces Error when the import service throws`() {
        val failingService: HostImportService = mockk()
        coEvery { failingService.import(any(), any(), any(), any()) } throws RuntimeException("boom")
        val viewModel = newViewModel(importService = failingService)
        val file = tempFile(nextshContent("will-fail.example"))
        viewModel.parseFile(file)
        awaitState(viewModel) { it is HostImportState.Preview }

        viewModel.confirmImport()

        val state = awaitState(viewModel) { it is HostImportState.Error }
        assertEquals(ImportErrorKind.Generic, (state as HostImportState.Error).messageKey)
    }
}
