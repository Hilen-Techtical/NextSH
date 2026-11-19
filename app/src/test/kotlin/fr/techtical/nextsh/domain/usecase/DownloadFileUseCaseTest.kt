// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import fr.techtical.nextsh.core.logging.LogRendering
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.FileNotFoundException

/** What the download use case writes to the log when the destination cannot be opened. */
class DownloadFileUseCaseTest {

    private val sftpManager = mockk<SftpManager>()
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()

    @Test
    fun `a destination that cannot be opened is logged with fixed text and the class only`() = runTest {
        // A word the log cleaner would not mask: if it is missing from the log, the call did not put it there.
        val marker = "carnet-de-notes"
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(uri) } throws FileNotFoundException("open failed for $marker")

        val tree = RecordingTree()
        Timber.plant(tree)
        val result = try {
            DownloadFileUseCase(sftpManager, context)("session-1", "/home/user/$marker", uri) { _, _ -> }
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(result is SshResult.Error)
        val entry = tree.entries.single()
        assertEquals("DownloadFileUseCase: SAF access error (FileNotFoundException)", entry.message)
        assertNull(entry.throwable)
        for (line in listOf(LogRendering.release(entry.message, entry.throwable), LogRendering.debug(entry.message, entry.throwable))) {
            assertFalse(line.contains(marker), line)
            assertTrue(line.contains("FileNotFoundException"), line)
        }
    }
}
