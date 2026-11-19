// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.net.Uri
import fr.techtical.nextsh.core.ssh.TransferDirection
import fr.techtical.nextsh.core.ssh.TransferRequest
import fr.techtical.nextsh.ui.SourceGuardSupport
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The service cannot run on the JVM, and the guarantee that an upload never replaces a file on its
 * own sits in a few lines of its source: the replacement is read from the intent, false when the
 * extra is missing, carried by the request, and handed to the upload only. This guard reads them (like
 * [SftpTransferServiceHoldGuardTest]) so that a refactor cannot lose one silently. The behaviour of the
 * writing is tested in `SftpManagerUploadTest`, and what the view model puts in the intent in
 * `SftpBrowserViewModelTest`.
 */
class SftpTransferServiceReplaceGuardTest {

    private val source = SourceGuardSupport.mainSource("service/SftpTransferService.kt")

    /** The text of `fun name(` up to the closing brace of its body. */
    private fun functionBody(name: String): String {
        val start = Regex("""fun $name\(""").find(source)?.range?.first
            ?: throw AssertionError("fun $name( not found: the guard cannot check it")
        val open = source.indexOf('{', source.indexOf(')', start))
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        throw AssertionError("unbalanced braces after fun $name(")
    }

    /** Collapses the alignment spaces, so that the guard does not depend on them. */
    private fun flat(text: String) = text.replace(Regex("""\s+"""), " ")

    @Test
    fun `the extra is a named constant of the service, spelled once`() {
        assertTrue(flat(source).contains("const val EXTRA_REPLACE_EXISTING = \"replace_existing\""), "the extra is not declared")
        assertEquals(1, Regex("\"replace_existing\"").findAll(source).count(), "the key is spelled more than once")
    }

    @Test
    fun `an intent without the extra never replaces, and only an upload reads it`() {
        val transfer = flat(functionBody("onStartCommand").substringAfter("else -> {"))
        assertTrue(
            transfer.contains("direction == TransferDirection.UPLOAD && intent.getBooleanExtra(EXTRA_REPLACE_EXISTING, false)"),
            "the replacement is not read as false by default, for an upload only: $transfer",
        )
        assertEquals(1, Regex("""getBooleanExtra\(EXTRA_REPLACE_EXISTING""").findAll(source).count())
    }

    @Test
    fun `the request carries what the intent said`() {
        val transfer = flat(functionBody("onStartCommand").substringAfter("else -> {"))
        val request = transfer.substringAfter("TransferRequest(", "").substringBefore(")")
        assertTrue(request.contains("replaceExisting = replaceExisting"), "the request does not carry the replacement: $request")
    }

    @Test
    fun `the upload gets the replacement of its request, the download gets none`() {
        val run = flat(functionBody("runTransfer"))
        val upload = run.substringAfter("TransferDirection.UPLOAD -> uploadFileUseCase(", "").substringBefore(")")
        assertTrue(upload.contains("replaceExisting = request.replaceExisting"), "the upload does not get the replacement: $upload")
        val download = run.substringAfter("TransferDirection.DOWNLOAD -> downloadFileUseCase(", "").substringBefore(")")
        assertTrue(download.isNotEmpty(), "the download call is not found")
        assertFalse(download.contains("replaceExisting"), download)
    }

    @Test
    fun `a request made without the replacement does not replace`() {
        // The default of the request is the safe one: whoever builds a request and forgets it does not overwrite.
        val request = TransferRequest(
            sessionId = "s",
            remotePath = "/a",
            localUri = mockk<Uri>(),
            direction = TransferDirection.UPLOAD,
            fileSize = 0L,
            displayName = "a",
        )
        assertFalse(request.replaceExisting)
    }
}
