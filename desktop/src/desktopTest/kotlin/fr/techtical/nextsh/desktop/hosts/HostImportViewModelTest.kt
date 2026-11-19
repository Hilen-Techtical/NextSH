// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.shared.core.`import`.HostImportService
import fr.techtical.nextsh.shared.core.`import`.ImportFormatDetector
import fr.techtical.nextsh.shared.core.`import`.ImportResult
import fr.techtical.nextsh.shared.core.`import`.NextShImporter
import fr.techtical.nextsh.shared.core.`import`.ParsedHost
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    // ── The screen is left (discard) ─────────────────────────────────────────

    /** The entries (and their secrets) the ViewModel keeps for the preview: private, read for the assertions. */
    @Suppress("UNCHECKED_CAST")
    private fun retainedHosts(viewModel: HostImportViewModel): List<ParsedHost> =
        (HostImportViewModel::class.java.getDeclaredField("parsedHosts")
            .apply { isAccessible = true }
            .get(viewModel) as AtomicReference<List<ParsedHost>>).get()

    /** ParsedHost.wipeSecrets overwrites the characters in place with a blank (Char(32)). */
    private fun CharArray.isWiped() = all { it == Char(32) }

    private fun parsedHostWithPassword(hostname: String) = ParsedHost(
        label = hostname, hostname = hostname, port = 22, username = "root",
        authType = AuthType.PASSWORD, group = null, password = "hunter2".toCharArray(),
    )

    private fun secretFile(hostname: String) =
        tempFile(nextshContent(hostname, extraFields = """, "password": "hunter2""""))

    @Test
    fun `discard wipes the secrets of a preview nobody confirmed`() {
        val viewModel = newViewModel()
        viewModel.parseFile(secretFile("abandoned.example"))
        awaitState(viewModel) { it is HostImportState.Preview }
        val password = retainedHosts(viewModel).single().password!!
        assertEquals("hunter2", String(password), "the secret is retained while the preview is up")

        viewModel.discard()

        assertEquals(HostImportState.Idle, viewModel.state.value)
        assertTrue(retainedHosts(viewModel).isEmpty())
        assertTrue(password.isWiped(), "the password must be overwritten, not just dropped")
    }

    @Test
    fun `discard leaves a running import alone, which then finishes with its secrets intact`() {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val handedToService = AtomicReference<List<ParsedHost>>()
        val service: HostImportService = mockk()
        coEvery { service.import(any(), any(), any(), any()) } coAnswers {
            handedToService.set(firstArg())
            entered.complete(Unit)
            gate.await()
            ImportResult(imported = 1, skipped = 0, errors = emptyList())
        }
        val viewModel = newViewModel(importService = service)
        viewModel.parseFile(secretFile("running.example"))
        awaitState(viewModel) { it is HostImportState.Preview }
        viewModel.setAllSelected(true)
        viewModel.confirmImport()
        runBlocking { withTimeout(5_000) { entered.await() } }

        viewModel.discard() // the user leaves the screen while the service is storing the credentials

        assertEquals(HostImportState.Importing, viewModel.state.value)
        assertEquals(
            "hunter2",
            String(handedToService.get().single().password!!),
            "wiping the entries now would store a blank password",
        )
        gate.complete(Unit)
        awaitState(viewModel) { it is HostImportState.Done }
        assertTrue(retainedHosts(viewModel).isEmpty())
    }

    @Test
    fun `a screen left while a file is being parsed keeps nothing once the parse ends`() {
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val host = parsedHostWithPassword("midparse.example")
        val password = host.password!!
        mockkObject(NextShImporter)
        try {
            every { NextShImporter.parse(any()) } answers {
                parsing.countDown()
                release.await(5, TimeUnit.SECONDS)
                listOf(host)
            }
            val viewModel = newViewModel()
            viewModel.parseFile(tempFile(nextshContent("midparse.example")))
            assertTrue(parsing.await(5, TimeUnit.SECONDS), "the parse must have started")

            viewModel.discard() // the user leaves the screen while the parse is running
            release.countDown()

            // discard() found nothing to wipe yet: the wipe is the one at the end of the parse.
            awaitTrue("the parsed password is wiped") { password.isWiped() }
            awaitState(viewModel) { it is HostImportState.Idle }
            assertTrue(retainedHosts(viewModel).isEmpty())
        } finally {
            release.countDown()
            unmockkObject(NextShImporter)
        }
    }

    @Test
    fun `a parse that fails after the entries were retained still wipes them`() {
        val real = parsedHostWithPassword("boom.example")
        val password = real.password!!
        // Fails while the preview rows are built: after the entries were stored, before the Preview exists.
        val host = spyk(real)
        every { host.label } throws IllegalStateException("cannot render the row")
        mockkObject(NextShImporter)
        try {
            every { NextShImporter.parse(any()) } returns listOf(host)
            val viewModel = newViewModel()

            viewModel.parseFile(tempFile(nextshContent("boom.example")))

            val state = awaitState(viewModel) { it is HostImportState.Error }
            assertEquals(ImportErrorKind.Generic, (state as HostImportState.Error).messageKey)
            assertTrue(password.isWiped(), "the secrets must not outlive the failed parse")
            assertTrue(retainedHosts(viewModel).isEmpty())
        } finally {
            unmockkObject(NextShImporter)
        }
    }

    @Test
    fun `reset and discard racing on the same preview wipe it once and leave nothing`() {
        repeat(20) {
            val viewModel = newViewModel()
            viewModel.parseFile(secretFile("race.example"))
            awaitState(viewModel) { it is HostImportState.Preview }
            val password = retainedHosts(viewModel).single().password!!
            val start = CountDownLatch(1)
            val threads = List(4) { index ->
                Thread {
                    start.await()
                    if (index % 2 == 0) viewModel.reset() else viewModel.discard()
                }.apply { start() }
            }

            start.countDown()
            threads.forEach { it.join(5_000) }

            assertTrue(password.isWiped())
            assertTrue(retainedHosts(viewModel).isEmpty())
            assertEquals(HostImportState.Idle, viewModel.state.value)
        }
    }

    /** Bounded real-time poll on a condition that is not a ViewModel state. */
    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() <= deadline) { "Timed out waiting for: $what" }
            Thread.sleep(5)
        }
    }
}
