// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import fr.techtical.nextsh.core.logging.LogRendering
import fr.techtical.nextsh.core.ssh.SFTP_SOURCE_UNAVAILABLE_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_SOURCE_VIRTUAL_MESSAGE
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Un document virtuel (Google Docs de Drive) ne s'ouvre pas : son fournisseur lève
 * `FileNotFoundException: File is virtual`, comme pour un fichier disparu. Le cas d'utilisateur
 * les distingue par les drapeaux du document pour dire d'exporter le document, et laisse le
 * reste (autres erreurs, annulation, chemin heureux) comme il était.
 */
class UploadFileUseCaseTest {

    private val sftpManager = mockk<SftpManager>()
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()
    private val useCase = UploadFileUseCase(sftpManager, context)

    private val virtualFlag = DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT
    private val writeFlag = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
    private val thumbnailFlag = DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL

    @BeforeEach
    fun setUp() {
        mockkStatic(DocumentsContract::class)
        every { context.contentResolver } returns resolver
        // Aucune colonne n'est connue du fournisseur, sauf celle que le test déclare : la taille
        // reste inconnue (0), les drapeaux aussi.
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(DocumentsContract::class)
    }

    private fun isFlagsProjection(projection: Array<String>?): Boolean =
        projection?.contentEquals(arrayOf(DocumentsContract.Document.COLUMN_FLAGS)) == true

    /** L'URI est celle d'un document dont la colonne des drapeaux vaut [flags]. */
    private fun stubDocument(flags: Int) {
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS) } returns 0
        every { cursor.isNull(0) } returns false
        every { cursor.getInt(0) } returns flags
        every { resolver.query(uri, match { isFlagsProjection(it) }, null, null, null) } returns cursor
    }

    private fun verifyFlagsNeverQueried() {
        verify(exactly = 0) { resolver.query(any(), match { isFlagsProjection(it) }, any(), any(), any()) }
    }

    private suspend fun upload(replaceExisting: Boolean = false): SshResult<Unit> =
        useCase(
            sessionId = "session-1",
            remotePath = "/home/user/rapport",
            localUri = uri,
            replaceExisting = replaceExisting,
            onProgress = { _, _ -> },
        )

    private fun unavailable() = SshResult.Error(SshErrorCode.UNKNOWN, SFTP_SOURCE_UNAVAILABLE_MESSAGE)

    // ── Le document virtuel ──────────────────────────────────────────────────────

    @Test
    fun `a virtual document that the provider refuses to open says it must be exported`() = runTest {
        every { resolver.openInputStream(uri) } throws FileNotFoundException("File is virtual")
        stubDocument(virtualFlag or thumbnailFlag)

        val result = upload()

        assertEquals(SshResult.Error(SshErrorCode.UNKNOWN, SFTP_SOURCE_VIRTUAL_MESSAGE), result)
        // Rien n'est envoyé ni exporté : le gestionnaire est un mock strict sans réponse.
        coVerify(exactly = 0) { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a document that is not virtual and cannot be opened keeps the unreadable file message`() = runTest {
        every { resolver.openInputStream(uri) } throws FileNotFoundException("open failed: ENOENT")
        stubDocument(writeFlag or thumbnailFlag)

        assertEquals(unavailable(), upload())
    }

    @Test
    fun `an uri that is not a document and cannot be opened keeps the unreadable file message`() = runTest {
        every { resolver.openInputStream(uri) } throws FileNotFoundException("open failed: ENOENT")
        every { DocumentsContract.isDocumentUri(context, uri) } returns false

        assertEquals(unavailable(), upload())
        verifyFlagsNeverQueried()
    }

    @Test
    fun `a provider that cannot say whether the document is virtual keeps the unreadable file message`() = runTest {
        every { resolver.openInputStream(uri) } throws FileNotFoundException("File is virtual")
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        every { resolver.query(uri, match { isFlagsProjection(it) }, null, null, null) } throws SecurityException("pas de droit")

        assertEquals(unavailable(), upload())
    }

    @Test
    fun `a provider that gives no flags keeps the unreadable file message`() = runTest {
        every { resolver.openInputStream(uri) } throws FileNotFoundException("File is virtual")
        every { DocumentsContract.isDocumentUri(context, uri) } returns true

        assertEquals(unavailable(), upload())
    }

    // ── Ce qui ne change pas ─────────────────────────────────────────────────────

    @Test
    fun `an error other than a missing file keeps the unreadable file message, whatever the flags`() = runTest {
        // Le drapeau est là, mais l'erreur n'est pas un FileNotFoundException : ce n'est pas lui qui la cause.
        stubDocument(virtualFlag)
        every { resolver.openInputStream(uri) } throws IOException("boom")
        assertEquals(unavailable(), upload())

        every { resolver.openInputStream(uri) } throws SecurityException("pas de droit")
        assertEquals(unavailable(), upload())

        verifyFlagsNeverQueried()
    }

    @Test
    fun `a provider that gives no stream keeps the unreadable file message`() = runTest {
        stubDocument(virtualFlag)
        every { resolver.openInputStream(uri) } returns null

        assertEquals(unavailable(), upload())
        verifyFlagsNeverQueried()
    }

    @Test
    fun `a cancellation is thrown again, not turned into an error`() {
        stubDocument(virtualFlag)
        every { resolver.openInputStream(uri) } throws CancellationException("annulé par l'utilisateur")

        assertThrows(CancellationException::class.java) { runBlocking { upload() } }
        verifyFlagsNeverQueried()
    }

    @Test
    fun `a readable file is sent as before, without asking for the flags`() = runTest {
        val stream = ByteArrayInputStream(ByteArray(3))
        every { resolver.openInputStream(uri) } returns stream
        coEvery { sftpManager.writeFile("session-1", "/home/user/rapport", stream, 0L, false, any()) } returns SshResult.Success(Unit)

        assertEquals(SshResult.Success(Unit), upload())
        coVerify(exactly = 1) { sftpManager.writeFile("session-1", "/home/user/rapport", stream, 0L, false, any()) }
        verifyFlagsNeverQueried()
    }

    @Test
    fun `a confirmed replacement is passed on to the writing`() = runTest {
        val stream = ByteArrayInputStream(ByteArray(3))
        every { resolver.openInputStream(uri) } returns stream
        coEvery { sftpManager.writeFile("session-1", "/home/user/rapport", stream, 0L, true, any()) } returns SshResult.Success(Unit)

        assertEquals(SshResult.Success(Unit), upload(replaceExisting = true))
        coVerify(exactly = 1) { sftpManager.writeFile("session-1", "/home/user/rapport", stream, 0L, true, any()) }
        // Never the other way round: a replacement that was not confirmed is not asked for.
        coVerify(exactly = 0) { sftpManager.writeFile(any(), any(), any(), any(), false, any()) }
    }

    // ── Le journal ───────────────────────────────────────────────────────────────

    @Test
    fun `the log of a refused file holds neither the uri nor the message of the exception`() = runTest {
        // Un mot que le nettoyeur de LogScrubber ne masquerait pas : s'il manque au journal, c'est
        // que l'appel ne l'y a pas mis.
        val marker = "carnet-de-notes"
        every { uri.toString() } returns "content://provider/document/$marker"
        every { resolver.openInputStream(uri) } throws FileNotFoundException("open failed for $marker")
        stubDocument(virtualFlag)

        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            upload()
        } finally {
            Timber.uproot(tree)
        }

        assertEquals(1, tree.entries.size)
        val entry = tree.entries.single()
        assertEquals(
            "UploadFileUseCase: SAF access error (virtual document: true, FileNotFoundException)",
            entry.message,
        )
        assertNull(entry.throwable)
        // Ce qui arrive à logcat, en release comme en debug : la classe de l'exception, jamais son message.
        for (line in listOf(LogRendering.release(entry.message, entry.throwable), LogRendering.debug(entry.message, entry.throwable))) {
            assertFalse(line.contains(marker), line)
            assertTrue(line.contains("FileNotFoundException"), line)
        }
    }
}
