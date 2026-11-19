// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SftpFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The name of the temporary file of an upload that replaces a file ([uploadTempPath]): next to the file,
 * hidden, and always within the limits of the file system, whatever the name of the file is.
 */
class SftpUploadTempTest {

    /** The last segment of a path, the part a file system limits to 255 bytes. */
    private fun lastName(path: String) = path.substringAfterLast('/')

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size

    @Test
    fun `the path is the folder, a dot, the start of the name, the marker and the identifier`() {
        assertEquals(
            "/home/user/.report.pdf.nextsh-part-1a2b3c4d",
            uploadTempPath("/home/user", "report.pdf", "1a2b3c4d"),
        )
    }

    @Test
    fun `the root gives a single slash, and so does a folder that ends with one`() {
        assertEquals("/.a.txt.nextsh-part-00000000", uploadTempPath("/", "a.txt", "00000000"))
        assertEquals("/home/.a.txt.nextsh-part-00000000", uploadTempPath("/home/", "a.txt", "00000000"))
    }

    @Test
    fun `a name that starts with a dot gives a name with two dots, which is not the parent folder`() {
        val path = uploadTempPath("/home/user", ".env", "1a2b3c4d")

        assertEquals("/home/user/..env.nextsh-part-1a2b3c4d", path)
        assertNotEquals("..", lastName(path))
    }

    @Test
    fun `a short name is kept whole`() {
        assertEquals("report.pdf", temporaryNamePart("report.pdf"))
        assertEquals("", temporaryNamePart(""))
    }

    @Test
    fun `a name of 255 bytes gives a temporary name of 255 bytes at the most`() {
        val name = "a".repeat(255)

        val last = lastName(uploadTempPath("/home/user", name, "1a2b3c4d"))

        assertEquals(1 + 120 + UPLOAD_TEMP_MARKER.length + TEMP_ID_CHARS, bytes(last))
        assertTrue(bytes(last) <= 255)
    }

    @Test
    fun `a name of three-byte characters is cut by bytes, not by characters`() {
        val name = "漢".repeat(100)

        val part = temporaryNamePart(name)

        assertEquals(40, part.length)
        assertTrue(bytes(part) <= TEMP_NAME_MAX_BYTES)
        assertTrue(bytes(lastName(uploadTempPath("/home/user", name, "1a2b3c4d"))) <= 255)
    }

    @Test
    fun `a name of four-byte characters is never cut inside a surrogate pair`() {
        // U+1F600, a pair of two chars in UTF-16 and four bytes in UTF-8.
        val name = String(Character.toChars(0x1F600)).repeat(50)

        val part = temporaryNamePart(name)

        assertEquals(30 * 2, part.length)
        assertEquals(120, bytes(part))
        // An unpaired surrogate would not survive the encoding to UTF-8.
        assertEquals(part, String(part.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `a character that does not fit the last bytes is left out whole`() {
        // 119 one-byte characters, then a two-byte one: 121 bytes, one over the limit.
        val name = "a".repeat(119) + "é"

        assertEquals("a".repeat(119), temporaryNamePart(name))
    }

    @Test
    fun `the limit is a parameter`() {
        assertEquals("rep", temporaryNamePart("report.pdf", maxBytes = 3))
        assertEquals("", temporaryNamePart("report.pdf", maxBytes = 0))
    }

    @Test
    fun `the identifier is eight hex characters, drawn again every time`() {
        val ids = List(20) { newUploadTempId() }

        ids.forEach { assertTrue(Regex("[0-9a-f]{8}").matches(it), it) }
        assertTrue(ids.toSet().size > 1, "the identifier does not change")
    }

    @Test
    fun `the constants are the ones the design says`() {
        assertEquals(".nextsh-part-", UPLOAD_TEMP_MARKER)
        assertEquals(3, TEMP_CREATE_ATTEMPTS)
        // 0600: the owner reads and writes, nobody else.
        assertEquals(384, TEMP_PERMISSIONS)
    }

    // ── The leftover of an interrupted upload ────────────────────────────────────

    private val id = "1a2b3c4d"

    private fun file(name: String, dir: String = "/home/user", isDirectory: Boolean = false, isSymlink: Boolean = false) =
        SftpFile(
            name = name,
            path = "${dir.trimEnd('/')}/$name",
            size = 10L,
            permissions = 384,
            isDirectory = isDirectory,
            isSymlink = isSymlink,
        )

    /** Same table on Desktop: the two platforms read a leftover name the same way. */
    @Test
    fun `a leftover name is read strictly, with the table shared with Desktop`() {
        val marker = UPLOAD_TEMP_MARKER
        val accepted = listOf(
            ".report.pdf$marker$id" to UploadLeftoverName("report.pdf", cut = false),
            "..env$marker$id" to UploadLeftoverName(".env", cut = false),
            ".a${marker}x${marker}00000000" to UploadLeftoverName("a${marker}x", cut = false),
            "." + "a".repeat(120) + "$marker$id" to UploadLeftoverName("a".repeat(120), cut = true),
            "." + "a".repeat(117) + "$marker$id" to UploadLeftoverName("a".repeat(117), cut = true),
            "." + "a".repeat(116) + "$marker$id" to UploadLeftoverName("a".repeat(116), cut = false),
        )
        accepted.forEach { (name, expected) -> assertEquals(expected, uploadLeftoverName(name), name) }

        val refused = listOf(
            "." + "a".repeat(121) + "$marker$id",
            ".report.pdf${marker}1A2B3C4D",
            ".report.pdf${marker}1a2b3c4",
            ".report.pdf${marker}1a2b3c4d5",
            ".report.pdf${marker}1a2b3c4g",
            "report.pdf$marker$id",
            "..nextsh-part-$id",
            "...nextsh-part-$id",
            "....nextsh-part-$id",
            ".report.pdf$marker$id\n",
            ".a/b$marker$id",
            ".a\\b$marker$id",
            ".a\u0007b$marker$id",
            ".a\u0000b$marker$id",
            // A C1 control, built from its code point.
            ".a${0x85.toChar()}b$marker$id",
            ".report.pdf$marker$id.bak",
            "$marker$id",
            "",
            ".",
            "..",
        )
        refused.forEach { assertNull(uploadLeftoverName(it), it) }
    }

    @Test
    fun `a start of multi byte characters is judged by its bytes, not by its characters`() {
        val kanji = String(Character.toChars(0x6F22))
        // 40 kanji = 120 bytes: whole at the limit, and so possibly cut.
        assertEquals(
            UploadLeftoverName(kanji.repeat(40), cut = true),
            uploadLeftoverName("." + kanji.repeat(40) + UPLOAD_TEMP_MARKER + id),
        )
        // 41 kanji = 123 bytes: never written by NextSH.
        assertNull(uploadLeftoverName("." + kanji.repeat(41) + UPLOAD_TEMP_MARKER + id))
    }

    @Test
    fun `a name the generator made is read back, for names of every length and script`() {
        val names = listOf(
            "report.pdf",
            ".env",
            "漢".repeat(100),
            String(Character.toChars(0x1F600)).repeat(50),
            "a".repeat(255),
        )
        names.forEach { name ->
            val read = uploadLeftoverName(lastName(uploadTempPath("/home/user", name, newUploadTempId())))

            assertNotNull(read, name)
            assertEquals(temporaryNamePart(name), read!!.original, name)
            // The cut mark only when the name was cut (or may have been: 117 bytes or more).
            assertEquals(bytes(name) > 116, read.cut, name)
        }
    }

    @Test
    fun `only an ordinary file is a leftover, not a folder or a link of that name`() {
        val name = ".report.pdf$UPLOAD_TEMP_MARKER$id"

        assertTrue(isUploadLeftover(file(name)))
        assertFalse(isUploadLeftover(file(name, isDirectory = true)))
        assertFalse(isUploadLeftover(file(name, isSymlink = true)))
        assertFalse(isUploadLeftover(file(".report.pdf")))
    }
}
