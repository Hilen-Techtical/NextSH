// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshTerminalSession
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.core.ssh.RemoteEntryKind
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_copy_path_refused
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_download_target_is_folder
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_target_is_folder
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_target_not_file
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.desktop.sessions.PaneSlot
import fr.techtical.nextsh.desktop.sessions.SessionTab
import fr.techtical.nextsh.desktop.sessions.SftpTabState
import fr.techtical.nextsh.desktop.sessions.SplitOrientation
import fr.techtical.nextsh.desktop.sessions.TabContent
import fr.techtical.nextsh.desktop.sessions.TerminalTabStatus
import fr.techtical.nextsh.desktop.theme.ResolvedTheme
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SftpFile
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TransferDirection
import fr.techtical.nextsh.shared.domain.model.TransferRequest
import fr.techtical.nextsh.shared.domain.model.TransferState
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Upload and download in [SftpBrowserViewModel] over a mocked [DesktopSftpManager] and a
 * mocked [DesktopTransferManager]: nothing existing is replaced without the user's
 * confirmation, whatever the order of the events, and a question or a refusal message is
 * not lost when the screen (and its view model) is created again.
 *
 * Real time on purpose, as in [SftpBrowserViewModelCreateFileTest]: the ViewModel launches in
 * the app scope and the messages come from `getString`, so the tests wait on flows and on
 * mock verifications instead of advancing a virtual clock. Messages are compared with the
 * resources, never with a literal: the text depends on the machine language.
 */
class SftpBrowserViewModelTransferTest {

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = coroutineScope.cancel()
    }

    private val app = TestAppScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    private val sftpManager = mockk<DesktopSftpManager>()
    private val sessionManager = mockk<DesktopSessionManager>(relaxed = true)
    private val transferManager = mockk<DesktopTransferManager>(relaxed = true)
    private val transfersFlow = MutableStateFlow<Map<String, TransferState>>(emptyMap())

    /** Shared by every view model of a test, as the container's one is by the screens. */
    private val inbox = SftpTransferInbox()
    private val tempDir: File = Files.createTempDirectory("nextsh-vm-transfer-test").toFile()

    init {
        every { transferManager.transfers } returns transfersFlow
        every { sessionManager.tabs } returns MutableStateFlow<List<SessionTab>>(emptyList())
    }

    @AfterTest
    fun tearDown() {
        app.onDestroy()
        tempDir.deleteRecursively()
    }

    /** All the browsers of a test live in the same tab, as the panes of one split do. */
    private fun newViewModel(
        initialCwd: String = CWD,
        paneId: String = PANE,
        sessionId: String = SESSION,
    ): SftpBrowserViewModel {
        every { sftpManager.sanitizePath(any()) } answers { firstArg<String>() }
        every { sftpManager.knownUploadTempNames() } returns emptySet()
        return SftpBrowserViewModel(
            sessionId = sessionId,
            tabId = "tab-1",
            paneId = paneId,
            initialCwd = initialCwd,
            sftpManager = sftpManager,
            sessionManager = sessionManager,
            transferManager = transferManager,
            appScope = app,
            inbox = inbox,
        )
    }

    private suspend fun SftpBrowserViewModel.awaitLoaded(
        what: String,
        condition: (SftpBrowserUiState.Loaded) -> Boolean = { true },
    ): SftpBrowserUiState.Loaded {
        val reached = withTimeoutOrNull(5_000L) {
            state.first { it is SftpBrowserUiState.Loaded && condition(it) }
        }
        return reached as? SftpBrowserUiState.Loaded ?: error("timed out waiting for: $what")
    }

    /** The question on screen: the head of the queue, once there is one. */
    private suspend fun SftpBrowserViewModel.awaitPrompt(): ReplacePrompt {
        val queue = withTimeoutOrNull(5_000L) { replaceQueue.first { it.isNotEmpty() } }
            ?: error("timed out waiting for the replace confirmation")
        return queue.first()
    }

    private suspend fun SftpBrowserViewModel.awaitQueueSize(size: Int): List<ReplacePrompt> =
        withTimeoutOrNull(5_000L) { replaceQueue.first { it.size == size } }
            ?: error("timed out waiting for $size questions, got ${replaceQueue.value.size}")

    private suspend fun SftpBrowserViewModel.awaitNotice(): String =
        withTimeoutOrNull(5_000L) { notice.first { it != null } }
            ?: error("timed out waiting for the notice")

    private fun stubListings(vararg listings: List<SftpFile>) {
        coEvery { sftpManager.listDirectory(SESSION, any()) } returnsMany
            listings.map { SshResult.Success(it) }
    }

    private fun stubKind(path: String, kind: RemoteEntryKind) {
        coEvery { sftpManager.entryKind(SESSION, path) } returns SshResult.Success(kind)
    }

    private fun localFile(name: String): File = File(tempDir, name).also { it.writeText("content") }

    private fun remoteFile(name: String, dir: String = CWD) =
        SftpFile(name = name, path = "$dir/$name", size = 7L, permissions = 0b110_100_100, isDirectory = false)

    private fun verifyNoUpload() =
        verify(exactly = 0) { transferManager.enqueueUpload(any(), any(), any(), any(), any()) }

    private fun verifyNoDownload() =
        verify(exactly = 0) { transferManager.enqueueDownload(any(), any(), any(), any(), any(), any(), any()) }

    /** A viewModel on [CWD], loaded, with the given listings stubbed (one empty listing by default). */
    private suspend fun loadedViewModel(vararg listings: List<SftpFile>): SftpBrowserViewModel {
        val stubbed: Array<out List<SftpFile>> = if (listings.isEmpty()) arrayOf(emptyList<SftpFile>()) else listings
        stubListings(*stubbed)
        return newViewModel().also { it.awaitLoaded("initial listing") }
    }

    // ── Upload: nothing on the server ─────────────────────────────────────────

    @Test
    fun `an upload to a free name starts at once, without replacement and without asking`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.ABSENT)
        val vm = loadedViewModel()
        val local = localFile("a.txt")

        vm.uploadFile(local)

        verify(exactly = 1, timeout = 5_000L) { transferManager.enqueueUpload(SESSION, CWD, local, false, any()) }
        assertTrue(vm.replaceQueue.value.isEmpty())
        coVerify(exactly = 1) { sftpManager.entryKind(SESSION, "$CWD/a.txt") }
    }

    @Test
    fun `the check is made on the file at the root when the folder is the root`() = runBlocking<Unit> {
        stubKind("/a.txt", RemoteEntryKind.ABSENT)
        stubListings(emptyList())
        val vm = newViewModel(initialCwd = "/")
        vm.awaitLoaded("initial listing")
        val local = localFile("a.txt")

        vm.uploadFile(local)

        verify(exactly = 1, timeout = 5_000L) { transferManager.enqueueUpload(SESSION, "/", local, false, any()) }
        coVerify(exactly = 1) { sftpManager.entryKind(SESSION, "/a.txt") }
    }

    @Test
    fun `an upload is ignored while the folder is still loading`() = runBlocking<Unit> {
        val gate = CompletableDeferred<SshResult<List<SftpFile>>>()
        coEvery { sftpManager.listDirectory(SESSION, any()) } coAnswers { gate.await() }
        val vm = newViewModel()

        vm.uploadFile(localFile("a.txt"))

        assertTrue(vm.state.value is SftpBrowserUiState.Loading)
        coVerify(exactly = 0) { sftpManager.entryKind(any(), any()) }
        verifyNoUpload()
        gate.complete(SshResult.Success(emptyList()))
        vm.awaitLoaded("initial listing")
    }

    // ── Upload: a file is there ───────────────────────────────────────────────

    @Test
    fun `an existing remote file raises a confirmation and nothing is sent before it`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        val vm = loadedViewModel()
        val local = localFile("a.txt")

        vm.uploadFile(local)
        val prompt = assertIs<ReplacePrompt.Upload>(vm.awaitPrompt())

        assertEquals(local, prompt.localFile)
        assertEquals(CWD, prompt.remoteDir)
        assertEquals("a.txt", prompt.name)
        assertEquals("$CWD/a.txt", prompt.targetPath)
        verifyNoUpload()
    }

    @Test
    fun `a local name with a hidden character is checked and asked about under the name the server will get`() = runBlocking<Unit> {
        // The remote name is the local one with the hidden character replaced: the question names the file
        // of the server that would be replaced, never the raw local name.
        stubKind("$CWD/a_txt.sh", RemoteEntryKind.FILE)
        val vm = loadedViewModel()
        val local = localFile("a\u202Etxt.sh")

        vm.uploadFile(local)
        val prompt = assertIs<ReplacePrompt.Upload>(vm.awaitPrompt())

        assertEquals(local, prompt.localFile)
        assertEquals("a_txt.sh", prompt.name)
        assertEquals("$CWD/a_txt.sh", prompt.targetPath)
        coVerify(exactly = 1) { sftpManager.entryKind(SESSION, "$CWD/a_txt.sh") }
        verifyNoUpload()
    }

    @Test
    fun `a local name with a hidden character starts its upload when the cleaned name is free`() = runBlocking<Unit> {
        stubKind("$CWD/a_txt.sh", RemoteEntryKind.ABSENT)
        val vm = loadedViewModel()
        val local = localFile("a\u202Etxt.sh")

        vm.uploadFile(local)

        verify(exactly = 1, timeout = 5_000L) { transferManager.enqueueUpload(SESSION, CWD, local, false, any()) }
        assertTrue(vm.replaceQueue.value.isEmpty())
    }

    @Test
    fun `the refusal for a folder names the remote file, not the raw local name`() = runBlocking<Unit> {
        stubKind("$CWD/a_txt.sh", RemoteEntryKind.DIRECTORY)
        val vm = loadedViewModel()

        vm.uploadFile(localFile("a\u202Etxt.sh"))

        assertEquals(getString(Res.string.sftp_error_upload_target_is_folder, "a_txt.sh", CWD), vm.awaitNotice())
        verifyNoUpload()
    }

    @Test
    fun `confirming starts one upload with replacement and clears the confirmation`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        val vm = loadedViewModel()
        val local = localFile("a.txt")
        vm.uploadFile(local)
        val prompt = vm.awaitPrompt()

        vm.confirmReplace(prompt)
        vm.confirmReplace(prompt)

        assertTrue(vm.replaceQueue.value.isEmpty())
        verify(exactly = 1) { transferManager.enqueueUpload(SESSION, CWD, local, true, any()) }
        verify(exactly = 1) { transferManager.enqueueUpload(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `refusing drops the confirmation and sends nothing`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        val vm = loadedViewModel()
        vm.uploadFile(localFile("a.txt"))
        val prompt = vm.awaitPrompt()

        vm.dismissReplace(prompt)
        vm.confirmReplace(prompt)

        assertTrue(vm.replaceQueue.value.isEmpty())
        verifyNoUpload()
    }

    @Test
    fun `the confirmation survives a reload of the folder`() = runBlocking<Unit> {
        // A reload rebuilds the Loaded state, with no dialog: the confirmation must not live there.
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        val vm = loadedViewModel(emptyList(), emptyList())
        val local = localFile("a.txt")
        vm.uploadFile(local)
        val prompt = vm.awaitPrompt()

        // refresh() puts the state back to Loading at once, so the Loaded awaited next is the new one.
        vm.refresh()
        coVerify(exactly = 2, timeout = 5_000L) { sftpManager.listDirectory(SESSION, CWD) }
        vm.awaitLoaded("reloaded listing")

        assertEquals(listOf(prompt), vm.replaceQueue.value)
        vm.confirmReplace(prompt)
        verify(exactly = 1) { transferManager.enqueueUpload(SESSION, CWD, local, true, any()) }
    }

    // ── The queue of questions ────────────────────────────────────────────────

    @Test
    fun `a question that arrives late waits behind the one on screen and never takes its place`() = runBlocking<Unit> {
        // The check of the upload is slow (the server is busy): the download question comes first.
        val gate = CompletableDeferred<SshResult<RemoteEntryKind>>()
        coEvery { sftpManager.entryKind(SESSION, "$CWD/a.txt") } coAnswers { gate.await() }
        val vm = loadedViewModel()
        val upload = localFile("a.txt")
        val downloadTarget = localFile("b.txt")
        vm.uploadFile(upload)
        vm.downloadFile(remoteFile("b.txt"), downloadTarget)
        val onScreen = assertIs<ReplacePrompt.Download>(vm.awaitPrompt())

        gate.complete(SshResult.Success(RemoteEntryKind.FILE))
        val queue = vm.awaitQueueSize(2)

        // The one the user is reading is still the first, and the other one is behind it.
        assertEquals(onScreen.id, queue.first().id)
        assertEquals(onScreen.id, vm.awaitPrompt().id)
        val waiting = assertIs<ReplacePrompt.Upload>(queue[1])
        assertEquals("a.txt", waiting.name)

        // An answer for the question that is not on screen does nothing.
        vm.confirmReplace(waiting)
        vm.dismissReplace(waiting)
        assertEquals(listOf(onScreen.id, waiting.id), vm.replaceQueue.value.map { it.id })
        verifyNoUpload()
        verifyNoDownload()

        // Answered in the order of arrival: the download first, then the upload.
        vm.confirmReplace(onScreen)
        verify(exactly = 1) {
            transferManager.enqueueDownload(SESSION, "$CWD/b.txt", 7L, downloadTarget, "b.txt", true, any())
        }
        assertEquals(waiting.id, vm.awaitPrompt().id)
        verifyNoUpload()
        vm.confirmReplace(waiting)
        verify(exactly = 1) { transferManager.enqueueUpload(SESSION, CWD, upload, true, any()) }
        assertTrue(vm.replaceQueue.value.isEmpty())
    }

    @Test
    fun `an answer to a question that is gone does nothing, even if another question has the same file`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        val vm = loadedViewModel()
        val local = localFile("a.txt")
        vm.uploadFile(local)
        val first = vm.awaitPrompt()
        vm.dismissReplace(first)
        vm.uploadFile(local)
        val second = vm.awaitPrompt()

        // Same file, same folder, but another question: the old answer must not be applied to it.
        vm.confirmReplace(first)

        assertEquals(listOf(second.id), vm.replaceQueue.value.map { it.id })
        verifyNoUpload()
    }

    @Test
    fun `a question is still there when the screen, and its view model, are created again`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        val first = loadedViewModel()
        val local = localFile("a.txt")
        first.uploadFile(local)
        val prompt = first.awaitPrompt()

        // The user opened the transfers view and came back.
        val second = newViewModel()

        assertEquals(listOf(prompt.id), second.replaceQueue.value.map { it.id })
        second.confirmReplace(prompt)
        verify(exactly = 1) { transferManager.enqueueUpload(SESSION, CWD, local, true, any()) }
        assertTrue(first.replaceQueue.value.isEmpty())
    }

    @Test
    fun `a slow check that ends after the screen was left still reaches the new one`() = runBlocking<Unit> {
        val gate = CompletableDeferred<SshResult<RemoteEntryKind>>()
        coEvery { sftpManager.entryKind(SESSION, "$CWD/a.txt") } coAnswers { gate.await() }
        val first = loadedViewModel(emptyList(), emptyList())
        first.uploadFile(localFile("a.txt"))
        val second = newViewModel()
        second.awaitLoaded("listing of the new screen")

        gate.complete(SshResult.Success(RemoteEntryKind.FILE))

        assertEquals("a.txt", second.awaitPrompt().name)
        verifyNoUpload()
    }

    @Test
    fun `two panes of the same tab do not see each other's questions`() = runBlocking<Unit> {
        // Two servers side by side: both browsers are in tab-1, on two SSH sessions, and the
        // tab id and the slot cannot tell them apart. Only the pane id does.
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        coEvery { sftpManager.listDirectory(OTHER_SESSION, any()) } returns SshResult.Success(emptyList())
        val paneX = loadedViewModel()
        val paneY = newViewModel(paneId = PANE_Y, sessionId = OTHER_SESSION)
        paneY.awaitLoaded("listing of the other pane")
        val local = localFile("a.txt")

        paneX.uploadFile(local)
        val prompt = paneX.awaitPrompt()

        assertTrue(paneY.replaceQueue.value.isEmpty(), "the other pane must not show the question")
        // Even handed the question, the other pane starts nothing, on its session or on any.
        paneY.confirmReplace(prompt)
        paneY.dismissReplace(prompt)
        verifyNoUpload()
        assertEquals(listOf(prompt.id), paneX.replaceQueue.value.map { it.id })
    }

    @Test
    fun `a browser never answers yes to a question that was not asked by its own session and pane`() = runBlocking<Unit> {
        val vm = loadedViewModel()
        val local = localFile("a.txt")
        val otherSession = ReplacePrompt.Upload(local, CWD, "$CWD/a.txt", sessionId = OTHER_SESSION, paneId = PANE)
        val otherPane = ReplacePrompt.Upload(local, CWD, "$CWD/a.txt", sessionId = SESSION, paneId = PANE_Y)

        // Planted in the queue of this browser, which the inbox itself would never do.
        inbox.enqueue(SftpPaneKey(PANE), otherSession)
        vm.confirmReplace(otherSession)
        vm.dismissReplace(otherSession)
        inbox.removeHead(SftpPaneKey(PANE), otherSession)
        inbox.enqueue(SftpPaneKey(PANE), otherPane)
        vm.confirmReplace(otherPane)
        vm.dismissReplace(otherPane)

        verifyNoUpload()
        verifyNoDownload()
        assertEquals(listOf(otherPane.id), vm.replaceQueue.value.map { it.id })
    }

    @Test
    fun `a question stays with its pane when the pane moves in the tree`() = runBlocking<Unit> {
        // The sibling of the pane was closed: it takes the place of the split and the screen is
        // composed again with a new view model. Only the pane id carries over.
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        val before = loadedViewModel()
        val local = localFile("a.txt")
        before.uploadFile(local)
        val prompt = before.awaitPrompt()

        val after = newViewModel()
        val promoted = newViewModel()

        assertEquals(listOf(prompt.id), after.replaceQueue.value.map { it.id })
        assertEquals(listOf(prompt.id), promoted.replaceQueue.value.map { it.id })
        promoted.confirmReplace(prompt)
        verify(exactly = 1) { transferManager.enqueueUpload(SESSION, CWD, local, true, any()) }
    }

    // ── The host a question is about ──────────────────────────────────────────

    private fun hostLabelled(label: String) = Host(
        id = "host-$label",
        label = label,
        hostname = "$label.example.org",
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$label",
    )

    private fun connectedPane(label: String, sshSessionId: String) = TabContent.Terminal(
        theme = ResolvedTheme(name = "test", palette = mockk(relaxed = true)),
        status = TerminalTabStatus.Connected(
            sshSessionId = sshSessionId,
            terminal = mockk<DesktopSshTerminalSession>(relaxed = true),
        ),
        host = hostLabelled(label),
    )

    /** A tab whose terminal is connected on [sshSessionId], the host of which is labelled [label]. */
    private fun tabOnHost(label: String, sshSessionId: String, tabId: String): SessionTab = SessionTab(
        tabId = tabId,
        host = hostLabelled(label),
        content = connectedPane(label, sshSessionId),
    )

    @Test
    fun `a question from the SFTP pane of a split names the host of that pane`() = runBlocking<Unit> {
        // The tab was opened on prod; the second pane of the split is a terminal on staging
        // (session SESSION) with an SFTP browser beside it.
        val split = TabContent.Split(
            first = connectedPane("prod", OTHER_SESSION),
            second = TabContent.Split(
                first = connectedPane("staging", SESSION),
                second = TabContent.Sftp(
                    linkedSshSessionId = SESSION,
                    state = SftpTabState.Open(CWD),
                    host = hostLabelled("staging"),
                ),
                orientation = SplitOrientation.VERTICAL,
                focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
            ),
            orientation = SplitOrientation.HORIZONTAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        every { sessionManager.tabs } returns MutableStateFlow(
            listOf(SessionTab(tabId = "tab-1", host = hostLabelled("prod"), content = split)),
        )
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        val vm = loadedViewModel()

        vm.uploadFile(localFile("a.txt"))

        assertEquals("staging", vm.awaitPrompt().hostLabel)
    }

    @Test
    fun `a download question carries the label of the host it is about`() = runBlocking<Unit> {
        every { sessionManager.tabs } returns MutableStateFlow(listOf(tabOnHost("prod", SESSION, "tab-1")))
        val vm = loadedViewModel()

        vm.downloadFile(remoteFile("a.txt"), localFile("a.txt"))

        assertEquals("prod", vm.awaitPrompt().hostLabel)
    }

    @Test
    fun `a question about a host that is not known carries no label`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        val vm = loadedViewModel()

        vm.uploadFile(localFile("a.txt"))

        assertNull(vm.awaitPrompt().hostLabel)
    }

    @Test
    fun `two browsers on two servers each name their own host`() = runBlocking<Unit> {
        every { sessionManager.tabs } returns MutableStateFlow(
            listOf(tabOnHost("prod", SESSION, "tab-1"), tabOnHost("staging", OTHER_SESSION, "tab-2")),
        )
        stubKind("$CWD/a.txt", RemoteEntryKind.FILE)
        coEvery { sftpManager.entryKind(OTHER_SESSION, "$CWD/a.txt") } returns SshResult.Success(RemoteEntryKind.FILE)
        coEvery { sftpManager.listDirectory(OTHER_SESSION, any()) } returns SshResult.Success(emptyList())
        val paneX = loadedViewModel()
        val paneY = newViewModel(paneId = PANE_Y, sessionId = OTHER_SESSION)
        paneY.awaitLoaded("listing of the other pane")

        paneX.uploadFile(localFile("a.txt"))
        paneY.uploadFile(localFile("a.txt"))

        assertEquals("prod", paneX.awaitPrompt().hostLabel)
        assertEquals("staging", paneY.awaitPrompt().hostLabel)
    }

    // ── Upload: not a file ────────────────────────────────────────────────────

    @Test
    fun `a folder with that name is refused with a notice that names the folder and nothing is sent`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.DIRECTORY)
        val vm = loadedViewModel()

        vm.uploadFile(localFile("a.txt"))

        assertEquals(getString(Res.string.sftp_error_upload_target_is_folder, "a.txt", CWD), vm.awaitNotice())
        assertTrue(vm.replaceQueue.value.isEmpty())
        verifyNoUpload()
    }

    @Test
    fun `a refused copy of a path posts the notice of the pane`() = runBlocking<Unit> {
        val vm = loadedViewModel()

        vm.onCopyPathRefused()

        assertEquals(getString(Res.string.sftp_copy_path_refused), vm.awaitNotice())
    }

    @Test
    fun `another kind of entry with that name is refused with its own notice`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.OTHER)
        val vm = loadedViewModel()

        vm.uploadFile(localFile("a.txt"))

        assertEquals(getString(Res.string.sftp_error_upload_target_not_file, "a.txt", CWD), vm.awaitNotice())
        assertTrue(vm.replaceQueue.value.isEmpty())
        verifyNoUpload()
    }

    @Test
    fun `the notice names the folder the file was meant for, even when the user moved on meanwhile`() = runBlocking<Unit> {
        val other = "/home/other"
        val gate = CompletableDeferred<SshResult<RemoteEntryKind>>()
        coEvery { sftpManager.entryKind(SESSION, "$CWD/a.txt") } coAnswers { gate.await() }
        val vm = loadedViewModel(emptyList(), emptyList())
        vm.uploadFile(localFile("a.txt"))
        vm.navigateTo(other)
        vm.awaitLoaded("other folder") { it.cwd == other }

        gate.complete(SshResult.Success(RemoteEntryKind.DIRECTORY))

        assertEquals(getString(Res.string.sftp_error_upload_target_is_folder, "a.txt", CWD), vm.awaitNotice())
    }

    @Test
    fun `a notice is not wiped by a reload of the folder and goes away when dismissed`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.DIRECTORY)
        val vm = loadedViewModel(emptyList(), emptyList())
        vm.uploadFile(localFile("a.txt"))
        val message = vm.awaitNotice()

        vm.refresh()
        coVerify(exactly = 2, timeout = 5_000L) { sftpManager.listDirectory(SESSION, CWD) }
        vm.awaitLoaded("reloaded listing")

        assertEquals(message, vm.notice.value)
        vm.dismissNotice()
        assertNull(vm.notice.value)
    }

    @Test
    fun `a notice is still there when the screen, and its view model, are created again`() = runBlocking<Unit> {
        stubKind("$CWD/a.txt", RemoteEntryKind.DIRECTORY)
        val first = loadedViewModel()
        first.uploadFile(localFile("a.txt"))
        val message = first.awaitNotice()

        val second = newViewModel()

        assertEquals(message, second.notice.value)
    }

    @Test
    fun `a notice shows even while the folder is loading`() = runBlocking<Unit> {
        // setError used to be a no-op out of the loaded state, which lost this message.
        val gate = CompletableDeferred<SshResult<RemoteEntryKind>>()
        coEvery { sftpManager.entryKind(SESSION, "$CWD/a.txt") } coAnswers { gate.await() }
        val vm = loadedViewModel(emptyList(), emptyList())
        vm.uploadFile(localFile("a.txt"))
        val reload = CompletableDeferred<SshResult<List<SftpFile>>>()
        coEvery { sftpManager.listDirectory(SESSION, any()) } coAnswers { reload.await() }
        vm.refresh()
        assertTrue(vm.state.value is SftpBrowserUiState.Loading)

        gate.complete(SshResult.Success(RemoteEntryKind.DIRECTORY))

        assertNotNull(vm.awaitNotice())
        reload.complete(SshResult.Success(emptyList()))
    }

    // ── Upload: the check fails ───────────────────────────────────────────────

    @Test
    fun `when the check fails the upload starts anyway but without permission to replace`() = runBlocking<Unit> {
        coEvery { sftpManager.entryKind(SESSION, any()) } returns SshResult.Error(SshErrorCode.UNKNOWN, "stat failed")
        val vm = loadedViewModel()
        val local = localFile("a.txt")

        vm.uploadFile(local)

        verify(exactly = 1, timeout = 5_000L) { transferManager.enqueueUpload(SESSION, CWD, local, false, any()) }
        assertTrue(vm.replaceQueue.value.isEmpty())
        verify(exactly = 0) { transferManager.enqueueUpload(any(), any(), any(), true, any()) }
    }

    // ── Upload: the listing follows the transfer ──────────────────────────────

    @Test
    fun `the listing is reloaded when an upload completes`() = runBlocking<Unit> {
        every { transferManager.enqueueUpload(any(), any(), any(), any(), any()) } returns "t1"
        stubKind("$CWD/a.txt", RemoteEntryKind.ABSENT)
        val vm = loadedViewModel(emptyList(), listOf(remoteFile("a.txt")))
        val local = localFile("a.txt")

        vm.uploadFile(local)
        verify(exactly = 1, timeout = 5_000L) { transferManager.enqueueUpload(SESSION, CWD, local, false, any()) }
        val request = TransferRequest(
            id = "t1",
            sessionId = SESSION,
            remotePath = "$CWD/a.txt",
            localUri = local.absolutePath,
            direction = TransferDirection.UPLOAD,
            fileSize = 7L,
            displayName = "a.txt",
        )
        transfersFlow.value = mapOf("t1" to TransferState.Completed(request))

        coVerify(exactly = 2, timeout = 5_000L) { sftpManager.listDirectory(SESSION, CWD) }
        val loaded = vm.awaitLoaded("reloaded listing") { it.rawFiles.isNotEmpty() }
        assertEquals(listOf(remoteFile("a.txt")), loaded.rawFiles)
    }

    // ── Download ──────────────────────────────────────────────────────────────

    @Test
    fun `a download to a free local name starts at once, without replacement and without asking`() = runBlocking<Unit> {
        val vm = loadedViewModel()
        val remote = remoteFile("a.txt")
        val destination = File(tempDir, "a.txt")

        vm.downloadFile(remote, destination)

        verify(exactly = 1, timeout = 5_000L) {
            transferManager.enqueueDownload(SESSION, "$CWD/a.txt", 7L, destination, "a.txt", false, any())
        }
        assertTrue(vm.replaceQueue.value.isEmpty())
    }

    @Test
    fun `a download over an existing local file raises a confirmation and writes nothing before it`() = runBlocking<Unit> {
        val vm = loadedViewModel()
        val remote = remoteFile("a.txt")
        val destination = localFile("a.txt")

        vm.downloadFile(remote, destination)
        val prompt = assertIs<ReplacePrompt.Download>(vm.awaitPrompt())

        assertEquals(remote, prompt.file)
        assertEquals(destination, prompt.localFile)
        assertEquals("a.txt", prompt.name)
        assertEquals(destination.absolutePath, prompt.targetPath)
        verifyNoDownload()
    }

    @Test
    fun `confirming a download starts one transfer with replacement`() = runBlocking<Unit> {
        val vm = loadedViewModel()
        val remote = remoteFile("a.txt")
        val destination = localFile("a.txt")
        vm.downloadFile(remote, destination)
        val prompt = vm.awaitPrompt()

        vm.confirmReplace(prompt)
        vm.confirmReplace(prompt)

        assertTrue(vm.replaceQueue.value.isEmpty())
        verify(exactly = 1) {
            transferManager.enqueueDownload(SESSION, "$CWD/a.txt", 7L, destination, "a.txt", true, any())
        }
        verify(exactly = 1) { transferManager.enqueueDownload(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `refusing a download drops the confirmation and sends nothing`() = runBlocking<Unit> {
        val vm = loadedViewModel()
        vm.downloadFile(remoteFile("a.txt"), localFile("a.txt"))
        val prompt = vm.awaitPrompt()

        vm.dismissReplace(prompt)

        assertTrue(vm.replaceQueue.value.isEmpty())
        verifyNoDownload()
    }

    @Test
    fun `a download onto a local folder is refused with a notice and nothing is written`() = runBlocking<Unit> {
        val vm = loadedViewModel()
        val folder = File(tempDir, "a.txt").also { it.mkdir() }

        vm.downloadFile(remoteFile("a.txt"), folder)

        assertEquals(getString(Res.string.sftp_error_download_target_is_folder, "a.txt"), vm.awaitNotice())
        assertTrue(vm.replaceQueue.value.isEmpty())
        verifyNoDownload()
    }

    private companion object {
        const val SESSION = "ssh-1"
        const val OTHER_SESSION = "ssh-2"
        const val PANE = "pane-x"
        const val PANE_Y = "pane-y"
        const val CWD = "/home/t"
    }
}
