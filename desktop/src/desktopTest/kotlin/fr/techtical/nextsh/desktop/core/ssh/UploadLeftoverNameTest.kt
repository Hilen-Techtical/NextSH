// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.SftpFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recognition of the leftover temporary files of interrupted uploads (`.name.nextsh-part-xxxxxxxx`).
 * The table of names is the same, entry for entry, as in the Android test (`SftpUploadTempTest`): the two
 * platforms write the same shape and must read it the same way. A name that merely looks alike is refused,
 * which misses a leftover and never invents one.
 */
class UploadLeftoverNameTest {

    private val id = "1a2b3c4d"
    private val marker = ".nextsh-part-"

    private fun file(name: String, dir: String = "/home/t", isDirectory: Boolean = false, isSymlink: Boolean = false) =
        SftpFile(
            name = name,
            path = (if (dir == "/") "" else dir) + "/" + name,
            size = 10L,
            permissions = 0b110_000_000,
            isDirectory = isDirectory,
            isSymlink = isSymlink,
        )

    // ── The table of names ────────────────────────────────────────────────────

    @Test
    fun `an ordinary leftover gives the name of the file it was to replace`() {
        assertEquals(UploadLeftoverName("report.pdf", cut = false), DesktopSftpManager.uploadLeftoverName(".report.pdf$marker$id"))
    }

    @Test
    fun `the leftover of a hidden file keeps the dot of the original name`() {
        assertEquals(UploadLeftoverName(".env", cut = false), DesktopSftpManager.uploadLeftoverName("..env$marker$id"))
    }

    @Test
    fun `the last marker wins when the original name holds one`() {
        assertEquals(
            UploadLeftoverName("a.nextsh-part-x", cut = false),
            DesktopSftpManager.uploadLeftoverName(".a.nextsh-part-x${marker}00000000"),
        )
    }

    @Test
    fun `an original name of 120 bytes is marked as possibly cut and one of 116 or less is not`() {
        assertEquals(UploadLeftoverName("a".repeat(120), cut = true), DesktopSftpManager.uploadLeftoverName("." + "a".repeat(120) + marker + id))
        assertEquals(UploadLeftoverName("a".repeat(117), cut = true), DesktopSftpManager.uploadLeftoverName("." + "a".repeat(117) + marker + id))
        assertEquals(UploadLeftoverName("a".repeat(116), cut = false), DesktopSftpManager.uploadLeftoverName("." + "a".repeat(116) + marker + id))
    }

    @Test
    fun `an original name of more than 120 bytes is not a leftover`() {
        assertNull(DesktopSftpManager.uploadLeftoverName("." + "a".repeat(121) + marker + id))
    }

    @Test
    fun `the names that are not the shape are refused`() {
        val refused = listOf(
            ".report.pdf.nextsh-part-1A2B3C4D", // uppercase
            ".report.pdf.nextsh-part-1a2b3c4", // 7 characters
            ".report.pdf.nextsh-part-1a2b3c4d5", // 9 characters
            ".report.pdf.nextsh-part-1a2b3c4g", // not hexadecimal
            "report.pdf.nextsh-part-1a2b3c4d", // no leading dot
            "..nextsh-part-1a2b3c4d", // empty start
            "...nextsh-part-1a2b3c4d", // the start is "."
            "....nextsh-part-1a2b3c4d", // the start is ".."
            ".report.pdf.nextsh-part-1a2b3c4d\n", // a trailing line feed
            ".a/b.nextsh-part-1a2b3c4d", // a separator
            ".a\\b.nextsh-part-1a2b3c4d", // a backslash
            ".a\u0007b.nextsh-part-1a2b3c4d", // a control
            ".a\u0000b.nextsh-part-1a2b3c4d", // a NUL
            ".a\u0085b.nextsh-part-1a2b3c4d", // a C1 control
            ".report.pdf.nextsh-part-1a2b3c4d.bak", // something after the identifier
            ".nextsh-part-1a2b3c4d", // no start at all
            "",
            ".",
            "..",
        )
        for (name in refused) {
            assertNull(DesktopSftpManager.uploadLeftoverName(name), "'${name.replace("\n", "\\n")}' was recognized")
        }
    }

    @Test
    fun `a start of multi byte characters is judged by its bytes, not by its characters`() {
        val kanji = String(Character.toChars(0x6F22))
        // 40 kanji = 120 bytes: whole at the limit, and so possibly cut.
        assertEquals(UploadLeftoverName(kanji.repeat(40), cut = true), DesktopSftpManager.uploadLeftoverName("." + kanji.repeat(40) + marker + id))
        // 41 kanji = 123 bytes: never written by NextSH.
        assertNull(DesktopSftpManager.uploadLeftoverName("." + kanji.repeat(41) + marker + id))
    }

    // ── Round trip with the generator ─────────────────────────────────────────

    @Test
    fun `the name written by the generator is read back`() {
        val kanji = String(Character.toChars(0x6F22))
        val emoji = String(Character.toChars(0x1F600))
        val targets = listOf("report.pdf", ".env", kanji.repeat(100), emoji.repeat(50), "a".repeat(255))
        for (target in targets) {
            val path = DesktopSftpManager.uploadTempPath("/home/t", target, id)
            val read = assertNotNull(DesktopSftpManager.uploadLeftoverName(path.substringAfterLast('/')), "no leftover for $target")
            assertEquals(DesktopTransferManager.temporaryNamePart(target), read.original)
            // The start is shown with an ellipsis only when it was cut (or may have been).
            assertEquals(target.toByteArray(Charsets.UTF_8).size > 116, read.cut, "cut for a target of ${target.length} characters")
        }
    }

    // ── Which entries are reported ────────────────────────────────────────────

    @Test
    fun `an ordinary file with the shape is reported`() {
        assertTrue(DesktopSftpManager.isUploadLeftover(file(".report.pdf$marker$id")))
    }

    @Test
    fun `a folder or a link with the shape is not reported`() {
        assertFalse(DesktopSftpManager.isUploadLeftover(file(".report.pdf$marker$id", isDirectory = true)))
        assertFalse(DesktopSftpManager.isUploadLeftover(file(".report.pdf$marker$id", isSymlink = true)))
    }

    @Test
    fun `an ordinary hidden file is not reported`() {
        assertFalse(DesktopSftpManager.isUploadLeftover(file(".bashrc")))
    }
}
