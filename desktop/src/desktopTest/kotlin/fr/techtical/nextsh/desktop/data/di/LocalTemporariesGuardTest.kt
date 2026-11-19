// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The temporary files of the downloads (`.name.nextsh-part-<id>`) sit next to the destination the user chose,
 * outside the folders the purge deletes. The process ends right after the purge or the quit, so the downloads
 * cannot clean up after themselves: both paths must call `transferManager.deleteTemporaries()` once the
 * sessions are closed. Neither [DesktopContainer.purgeAllLocalData] nor [DesktopContainer.shutdown] may ever
 * run in a test (they delete the real data folder and the keyring entry), so this guard reads the source.
 */
class LocalTemporariesGuardTest {

    private fun desktopSrc(): File {
        val candidates = listOf("src", "desktop/src")
        return candidates.map { File(it) }.firstOrNull { File(it, "desktopMain/kotlin").isDirectory }
            ?: error(
                "desktop sources not found (working directory ${File(".").absoluteFile.normalize()}, " +
                    "tried $candidates)",
            )
    }

    private fun containerSource(): String {
        val file = File(desktopSrc(), "desktopMain/kotlin/fr/techtical/nextsh/desktop/data/di/DesktopContainer.kt")
        assertTrue(file.isFile, "source not found: $file")
        return file.readText(Charsets.UTF_8)
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    /** The body of the function [signature], from its opening brace to the matching closing one. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "$signature is not found in DesktopContainer.kt")
        val open = source.indexOf('{', start)
        assertTrue(open >= 0, "$signature has no body")
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("$signature: unbalanced braces")
    }

    private fun assertDeletesAfterClosing(signature: String) {
        val body = bodyOf(containerSource(), signature)
        assertTrue(body.length > 200, "$signature: the guard reads nothing")
        val closing = body.indexOf("sessionManager.closeAllSessions()")
        val deleting = body.indexOf("transferManager.deleteTemporaries()")
        assertTrue(closing >= 0, "$signature does not close the sessions")
        assertTrue(deleting >= 0, "$signature does not delete the temporary files of the downloads")
        assertTrue(deleting > closing, "$signature deletes the temporary files before the sessions are closed")
    }

    @Test
    fun `the purge deletes the temporary files of the downloads after closing the sessions`() {
        assertDeletesAfterClosing("fun purgeAllLocalData()")
    }

    @Test
    fun `quitting deletes the temporary files of the downloads after closing the sessions`() {
        assertDeletesAfterClosing("fun shutdown()")
    }
}
