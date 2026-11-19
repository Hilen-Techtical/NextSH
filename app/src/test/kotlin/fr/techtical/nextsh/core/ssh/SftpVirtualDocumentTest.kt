// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import fr.techtical.nextsh.core.logging.LogRendering
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber

/**
 * Un document virtuel (Google Docs de Drive) n'a pas de contenu brut : [isVirtualDocumentFlags]
 * lit le drapeau, [isVirtualDocument] fait la requête qui le donne. Dans le doute, le document
 * n'est pas tenu pour virtuel, pour qu'un envoi qui aurait pu partir ne soit jamais refusé.
 */
class SftpVirtualDocumentTest {

    private val virtualFlag = DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT
    private val writeFlag = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
    private val deleteFlag = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
    private val thumbnailFlag = DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL

    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()

    @BeforeEach
    fun mockDocumentsContract() {
        mockkStatic(DocumentsContract::class)
        every { context.contentResolver } returns resolver
    }

    @AfterEach
    fun unmockDocumentsContract() {
        unmockkStatic(DocumentsContract::class)
    }

    // ── Le test du drapeau ───────────────────────────────────────────────────────

    @Test
    fun `the virtual flag alone marks a virtual document`() {
        assertTrue(isVirtualDocumentFlags(virtualFlag))
    }

    @Test
    fun `the virtual flag is the bit 9 of the platform`() {
        // Garde-fou : un test qui compare le drapeau à lui-même ne verrait pas une mauvaise constante.
        assertEquals(512, virtualFlag)
    }

    @Test
    fun `flags without the virtual flag do not mark a virtual document`() {
        assertFalse(isVirtualDocumentFlags(0))
        assertFalse(isVirtualDocumentFlags(writeFlag))
        assertFalse(isVirtualDocumentFlags(writeFlag or deleteFlag or thumbnailFlag))
    }

    @Test
    fun `missing flags do not mark a virtual document`() {
        assertFalse(isVirtualDocumentFlags(null))
    }

    @Test
    fun `the virtual flag is found among other flags`() {
        assertTrue(isVirtualDocumentFlags(virtualFlag or thumbnailFlag))
        assertTrue(isVirtualDocumentFlags(writeFlag or virtualFlag or deleteFlag))
        assertTrue(isVirtualDocumentFlags(-1))
    }

    // ── La requête ───────────────────────────────────────────────────────────────

    /** Un curseur à une ligne dont la colonne des drapeaux vaut [flags] (`null` : valeur nulle). */
    private fun flagsCursor(flags: Int?, columnIndex: Int = 0, hasRow: Boolean = true): Cursor =
        mockk<Cursor>(relaxed = true).also { cursor ->
            every { cursor.moveToFirst() } returns hasRow
            every { cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS) } returns columnIndex
            every { cursor.isNull(any()) } returns (flags == null)
            every { cursor.getInt(any()) } returns (flags ?: 0)
        }

    /** La requête des drapeaux, et d'elle seule : une autre colonne demandée ne trouverait pas de réponse. */
    private fun stubFlagsQuery(cursor: Cursor?) {
        every {
            resolver.query(uri, match { it.contentEquals(arrayOf(DocumentsContract.Document.COLUMN_FLAGS)) }, null, null, null)
        } returns cursor
    }

    @Test
    fun `a document uri with the virtual flag is virtual`() {
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        stubFlagsQuery(flagsCursor(virtualFlag or thumbnailFlag))

        assertTrue(isVirtualDocument(context, uri))
    }

    @Test
    fun `a document uri without the virtual flag is not virtual`() {
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        stubFlagsQuery(flagsCursor(writeFlag or deleteFlag))

        assertFalse(isVirtualDocument(context, uri))
    }

    @Test
    fun `an uri that is not a document is not virtual and is not queried`() {
        every { DocumentsContract.isDocumentUri(context, uri) } returns false

        assertFalse(isVirtualDocument(context, uri))
        // Le résolveur est un mock strict, mais l'absence de requête est aussi vérifiée à part.
        verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a provider that gives no cursor does not mark a virtual document`() {
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        stubFlagsQuery(null)

        assertFalse(isVirtualDocument(context, uri))
    }

    @Test
    fun `a cursor without a row does not mark a virtual document`() {
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        stubFlagsQuery(flagsCursor(virtualFlag, hasRow = false))

        assertFalse(isVirtualDocument(context, uri))
    }

    @Test
    fun `a cursor without the flags column does not mark a virtual document`() {
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        stubFlagsQuery(flagsCursor(virtualFlag, columnIndex = -1))

        assertFalse(isVirtualDocument(context, uri))
    }

    @Test
    fun `a null flags value does not mark a virtual document`() {
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        stubFlagsQuery(flagsCursor(null))

        assertFalse(isVirtualDocument(context, uri))
    }

    @Test
    fun `an exception of the provider does not mark a virtual document`() {
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        every { resolver.query(any(), any(), any(), any(), any()) } throws SecurityException("pas de droit sur ce document")

        assertFalse(isVirtualDocument(context, uri))
    }

    @Test
    fun `an exception while the uri is read does not mark a virtual document`() {
        every { DocumentsContract.isDocumentUri(context, uri) } throws IllegalArgumentException("uri illisible")

        assertFalse(isVirtualDocument(context, uri))
    }

    // ── Le journal ───────────────────────────────────────────────────────────────

    /** Ce qui est écrit dans le journal pendant que [block] tourne, avec le throwable de chaque appel. */
    private fun logged(block: () -> Unit): List<RecordingTree.Entry> {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            block()
        } finally {
            Timber.uproot(tree)
        }
        return tree.entries
    }

    @Test
    fun `an exception leaves a trace of its class only, neither the uri nor its message`() {
        // Un mot que le nettoyeur de LogScrubber ne masquerait pas : s'il manque au journal, c'est
        // que l'appel ne l'y a pas mis.
        val marker = "carnet-de-notes"
        every { uri.toString() } returns "content://provider/document/$marker"
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        every { resolver.query(any(), any(), any(), any(), any()) } throws SecurityException("pas de droit sur $marker")

        val entries = logged { assertFalse(isVirtualDocument(context, uri)) }

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(Log.WARN, entry.priority)
        // Aucun throwable : la classe est dans le texte, fixe, et rien d'autre de l'exception.
        assertNull(entry.throwable)
        assertEquals(
            "SftpVirtualDocument: cannot read the document flags (SecurityException)",
            LogRendering.messageWithoutTrace(entry.message, entry.throwable),
        )
        for (line in listOf(LogRendering.release(entry.message, entry.throwable), LogRendering.debug(entry.message, entry.throwable))) {
            assertFalse(line.contains(marker), line)
        }
    }

    @Test
    fun `a document, virtual or not, and an uri that is not a document leave no trace`() {
        every { DocumentsContract.isDocumentUri(context, uri) } returns true
        stubFlagsQuery(flagsCursor(virtualFlag))
        assertTrue(logged { assertTrue(isVirtualDocument(context, uri)) }.isEmpty())

        stubFlagsQuery(flagsCursor(writeFlag))
        assertTrue(logged { assertFalse(isVirtualDocument(context, uri)) }.isEmpty())

        stubFlagsQuery(null)
        assertTrue(logged { assertFalse(isVirtualDocument(context, uri)) }.isEmpty())

        every { DocumentsContract.isDocumentUri(context, uri) } returns false
        assertTrue(logged { assertFalse(isVirtualDocument(context, uri)) }.isEmpty())
    }
}
