package fr.techtical.nextsh.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source guard: no en dash, em dash, raw invisible format character or raw control
 * character in the desktop sources (code, resources, tests). Such characters are
 * unreadable in review and can hide text (bidi controls, zero-width spaces).
 *
 * The forbidden code points are built from numbers here, never written raw, so the
 * guard cannot trip over its own source.
 */
class SourceCharactersGuardTest {

    private val extensions = setOf("kt", "kts", "xml", "java", "properties", "sq", "sqm", "desktop", "txt")

    private val srcDir: File by lazy { locateSrcDir() }

    private val files: List<File> by lazy {
        srcDir.walkTopDown().filter { it.isFile && it.extension in extensions }.sortedBy { it.path }.toList()
    }

    @Test
    fun `the desktop sources are found and the scan is not vacuous`() {
        assertTrue(files.size >= 100, "only ${files.size} source files under $srcDir: wrong directory?")
        assertTrue(files.any { it.extension == "kt" && it.path.contains("desktopMain") })
        assertTrue(files.any { it.extension == "xml" }, "no resource file scanned")
        assertTrue(files.any { it.extension == "sq" }, "no SQLDelight file scanned")
        assertTrue(
            files.any { it.name == "SourceCharactersGuardTest.kt" },
            "this guard does not scan its own file",
        )
    }

    @Test
    fun `no desktop source contains a forbidden character`() {
        val violations = files.flatMap { file ->
            findForbidden(file.readText(Charsets.UTF_8)).map { (line, codePoint) ->
                "${file.relativeTo(srcDir).invariantSeparatorsPath}:$line has U+%04X".format(codePoint)
            }
        }
        assertEquals(emptyList(), violations, "forbidden characters in the desktop sources")
    }

    @Test
    fun `the check refuses dashes, invisible format characters and controls`() {
        val forbidden = listOf(
            0x2013, 0x2014, 0x2015, 0x17B4, 0x17B5, 0x00AD, 0x061C, 0x200B, 0x200E, 0x200F, 0x2028, 0x2029, 0x202A, 0x202E,
            0x2060, 0x2066, 0x206F, 0xFEFF, 0xFFF9, 0xFFFB, 0x0000, 0x001B, 0x007F,
        )
        for (cp in forbidden) {
            val text = "a" + Char(cp) + "b"
            assertEquals(listOf(1 to cp), findForbidden(text), "U+%04X must be refused".format(cp))
        }
    }

    @Test
    fun `the check accepts ordinary text, tabs and line endings`() {
        val ok = "fun a() {\r\n\tval s = \"caf" + Char(0x00E9) + " - 0-9\"\n}\n" + Char(0x2026) + Char(0x00A0)
        assertEquals(emptyList(), findForbidden(ok))
    }

    @Test
    fun `the check reports the line number`() {
        val text = "one\ntwo\nth" + Char(0x2013) + "ree\n"
        assertEquals(listOf(3 to 0x2013), findForbidden(text))
    }

    private fun findForbidden(text: String): List<Pair<Int, Int>> {
        val found = mutableListOf<Pair<Int, Int>>()
        var line = 1
        for (c in text) {
            if (c == '\n') {
                line++
                continue
            }
            if (c != '\r' && c != '\t' && isForbidden(c.code)) found += line to c.code
        }
        return found
    }

    private fun isForbidden(cp: Int): Boolean =
        cp in 0x2013..0x2015 ||
            cp == 0x17B4 || cp == 0x17B5 ||
            cp == 0x00AD || cp == 0x061C ||
            cp in 0x200B..0x200F ||
            cp in 0x2028..0x202E ||
            cp in 0x2060..0x206F ||
            cp == 0xFEFF ||
            cp in 0xFFF9..0xFFFB ||
            cp < 0x20 || cp in 0x7F..0x9F

    private companion object {
        fun locateSrcDir(): File {
            val candidates = listOf("src", "desktop/src")
            return candidates.map { File(it) }.firstOrNull { File(it, "desktopMain/kotlin").isDirectory }
                ?: error(
                    "desktop sources not found (working directory ${File(".").absoluteFile.normalize()}, " +
                        "tried $candidates): the source characters guard cannot run",
                )
        }
    }
}
