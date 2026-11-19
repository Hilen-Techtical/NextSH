// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Le nom d'un fichier envoyé vient d'un `DocumentsProvider`, donc d'une autre
 * application : [uploadEntryName] ne doit jamais laisser sortir du dossier choisi.
 */
class SftpUploadNameTest {

    // sanitizeFilename et sanitizePath ne lisent aucun état. Le gestionnaire de sessions est
    // relâché parce que le constructeur de SftpManager s'abonne à la fin des sessions.
    private fun cp(vararg codePoints: Int): String = String(codePoints, 0, codePoints.size)

    private val sftpManager = SftpManager(mockk(relaxed = true))

    // ── Traversée de chemin ──────────────────────────────────────────────────────

    @Test
    fun `parent traversal is neutralised`() {
        assertEquals(".._.._.profile", uploadEntryName("../../.profile"))
    }

    @Test
    fun `traversal towards authorized_keys is neutralised`() {
        assertEquals(".._.ssh_authorized_keys", uploadEntryName("../.ssh/authorized_keys"))
    }

    @Test
    fun `forward slash is replaced`() {
        assertEquals("a_b.txt", uploadEntryName("a/b.txt"))
    }

    @Test
    fun `backslash is replaced`() {
        assertEquals("a_b", uploadEntryName("a\\b"))
    }

    @Test
    fun `absolute path is flattened`() {
        assertEquals("_etc_passwd", uploadEntryName("/etc/passwd"))
    }

    @Test
    fun `separators only name is kept as underscores`() {
        assertEquals("___", uploadEntryName("///"))
    }

    @Test
    fun `url encoded separator is left untouched`() {
        // Les chemins SFTP sont envoyés tels quels, sans décodage : %2F n'est qu'un texte.
        assertEquals("a%2Fb", uploadEntryName("a%2Fb"))
    }

    // ── Caractères de contrôle ───────────────────────────────────────────────────

    @Test
    fun `NUL is replaced`() {
        assertEquals("a_b", uploadEntryName("a\u0000b"))
    }

    @Test
    fun `control characters are replaced`() {
        assertEquals("a_b_c_d", uploadEntryName("a\u0001b\u007Fc\nd"))
    }

    @Test
    fun `C1 control characters are replaced`() {
        // \p{Cntrl} ne couvre que l'ASCII : U+0085 (NEL) et U+009B (CSI) passeraient.
        assertEquals("a_b", uploadEntryName("a\u0085b"))
        assertEquals("a_b", uploadEntryName("a\u009Bb"))
    }

    @Test
    fun `trailing control character is replaced and not trimmed`() {
        // Remplacement d'abord, rognage ensuite : le contrôle final devient un `_` conservé.
        assertEquals("a_", uploadEntryName("a\n"))
    }

    // ── Caractères de format invisibles ──────────────────────────────────────────

    @Test
    fun `right to left override is replaced and shows the real extension`() {
        assertEquals("photo_gpj.exe", uploadEntryName("photo\u202Egpj.exe"))
    }

    @Test
    fun `every invisible format character is replaced`() {
        val all = listOf('\u00AD', '\u061C', '\u200B', '\u200E', '\u200F', '\u2028', '\u2029', '\uFEFF') +
            ('\u202A'..'\u202E') + ('\u2060'..'\u206F') + ('\uFFF9'..'\uFFFB')
        all.forEach { c ->
            assertEquals("a_b", uploadEntryName("a${c}b"), "U+%04X".format(c.code))
        }
    }

    @Test
    fun `leading byte order mark is replaced and not removed`() {
        assertEquals("_report.pdf", uploadEntryName("\uFEFFreport.pdf"))
    }

    @Test
    fun `line separator inside the name is replaced`() {
        assertEquals("a.pdf_.exe", uploadEntryName("a.pdf\u2028.exe"))
    }

    @Test
    fun `a name made of invisible characters only is kept as underscores`() {
        assertEquals("__", uploadEntryName("\u200B\u200B"))
    }

    @Test
    fun `Khmer inherent vowels are replaced`() {
        assertEquals("a_b_c", uploadEntryName(cp(0x61, 0x17B4, 0x62, 0x17B5, 0x63)))
    }

    @Test
    fun `joiners of a script are kept`() {
        val persian = cp(0x0645, 0x06CC, 0x200C, 0x062E, 0x0648, 0x0627, 0x0647, 0x0645)
        val family = cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467)
        assertEquals(persian, uploadEntryName(persian))
        assertEquals(family, uploadEntryName(family))
    }

    // ── Espaces en bordure ───────────────────────────────────────────────────────

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("x", uploadEntryName("  x  "))
    }

    @Test
    fun `inner whitespace is kept`() {
        assertEquals("my file.txt", uploadEntryName("my file.txt"))
    }

    // ── Noms refusés ─────────────────────────────────────────────────────────────

    @Test
    fun `empty blank dot and dotdot are rejected`() {
        assertNull(uploadEntryName(""))
        assertNull(uploadEntryName("   "))
        assertNull(uploadEntryName("."))
        assertNull(uploadEntryName(".."))
        assertNull(uploadEntryName(" .. "))
        assertNull(uploadEntryName(" . "))
    }

    // ── Noms légitimes ───────────────────────────────────────────────────────────

    @Test
    fun `hidden file name is kept`() {
        assertEquals(".bashrc", uploadEntryName(".bashrc"))
    }

    @Test
    fun `three dots name is kept`() {
        assertEquals("...", uploadEntryName("..."))
    }

    @Test
    fun `ordinary names are unchanged`() {
        for (name in listOf("document.pdf", "archive.tar.gz", "photo (1).jpg", "a+b=c.txt", "été.txt")) {
            assertEquals(name, uploadEntryName(name))
        }
    }

    // ── Longueur ─────────────────────────────────────────────────────────────────

    @Test
    fun `name of 300 characters is cut to 255`() {
        val result = uploadEntryName("a".repeat(300))
        assertEquals("a".repeat(255), result)
    }

    @Test
    fun `name of exactly 255 characters is unchanged`() {
        val name = "b".repeat(255)
        assertEquals(name, uploadEntryName(name))
    }

    @Test
    fun `space left at the end by the cut is trimmed`() {
        // Le 255e caractère est une espace : couper après le rognage la laisserait en fin de nom.
        val raw = "a".repeat(254) + " " + "b".repeat(20)
        val result = uploadEntryName(raw)
        assertEquals("a".repeat(254), result)
        assertSanitizeKeepsName(result!!)
    }

    // ── Invariant : sanitizeFilename et sanitizePath laissent le nom intact ──────

    @Test
    fun `result always passes the file name and path rules`() {
        val names = listOf(
            "../../.profile",
            "../.ssh/authorized_keys",
            "a/b.txt",
            "a\\b",
            "primary:Download/foo.txt",
            "raw:/storage/emulated/0/Download/foo.txt",
            "a\u0000b",
            "\u0001\u0002\u0003",
            "a\u0085b\u009Fc",
            "photo\u202Egpj.exe",
            "\u200B\u200B",
            "\uFEFFreport.pdf",
            "  x  ",
            " .. ",
            "...",
            ".hidden",
            "%2e%2e%2f",
            "a%2Fb",
            "a+b c.txt",
            "été 日本語.txt",
            "emoji \uD83D\uDE00.txt",
            "\u00A0nbsp\u00A0",
            "a".repeat(300),
            "a".repeat(254) + " " + "b".repeat(5),
            " ".repeat(300) + "z",
        )
        val results = names.map { assertInvariant(it) }

        // Seul " .. " ne laisse rien d'exploitable : un uploadEntryName qui renverrait
        // toujours null passerait l'invariant sans rien prouver.
        assertEquals(listOf(" .. "), names.filterIndexed { i, _ -> results[i] == null })
    }

    @Test
    fun `result passes the file name and path rules on pseudo random input`() {
        // Graine fixe : le test reste déterministe.
        val random = Random(57)
        val basePool = listOf(
            "a", "b", "Z", "0", ".", "..", "/", "\\", " ", "  ", "\u0000", "\n", "\t", "\u007F",
            "\u0085", "%2F", "+", "é", "日", "\u00A0", "\uD83D\uDE00", "-", "_", "~",
        )
        val pool = basePool + listOf("\u202E", "\u200B", "\u200D", "\u2028")
        val total = 3000
        var accepted = 0
        repeat(total) {
            val raw = buildString {
                repeat(random.nextInt(0, 400)) { append(pool[random.nextInt(pool.size)]) }
            }
            if (assertInvariant(raw) != null) accepted++
        }

        // Presque toutes les entrées donnent un nom ; une implémentation qui refuserait tout
        // laisserait l'invariant vrai par défaut.
        assertTrue(accepted > total * 9 / 10, "seulement $accepted noms acceptés sur $total")
    }

    // ── Nom proposé avant assainissement ─────────────────────────────────────────

    @Test
    fun `provider name wins over the uri segment`() {
        assertEquals("photo.jpg", pickedFileName("photo.jpg", "primary:Download/foo.txt"))
    }

    @Test
    fun `document id segment keeps only the part after the last slash`() {
        assertEquals("foo.txt", pickedFileName(null, "primary:Download/foo.txt"))
    }

    @Test
    fun `raw path segment keeps only the file name`() {
        assertEquals("foo.txt", pickedFileName(null, "raw:/storage/emulated/0/Download/foo.txt"))
    }

    @Test
    fun `segment without slash is kept whole`() {
        assertEquals("msf:1234", pickedFileName(null, "msf:1234"))
    }

    @Test
    fun `segment ending with a slash gives nothing`() {
        assertNull(pickedFileName(null, "primary:Download/"))
    }

    @Test
    fun `blank provider name falls back to the uri segment`() {
        assertEquals("foo.txt", pickedFileName("", "primary:Download/foo.txt"))
        assertEquals("foo.txt", pickedFileName("   ", "primary:Download/foo.txt"))
    }

    @Test
    fun `null provider name and blank segment give nothing`() {
        assertNull(pickedFileName(null, null))
        assertNull(pickedFileName("", ""))
        assertNull(pickedFileName("  ", "   "))
        assertNull(pickedFileName(null, "primary:Download/ "))
    }

    // ── Aides ────────────────────────────────────────────────────────────────────

    /** Vérifie l'invariant sur [raw] et renvoie le nom produit (`null` s'il est refusé). */
    private fun assertInvariant(raw: String): String? {
        val result = uploadEntryName(raw) ?: return null
        assertTrue(result.isNotEmpty(), "résultat vide pour ${raw.escaped()}")
        assertTrue(result != "." && result != "..", "résultat '.' ou '..' pour ${raw.escaped()}")
        assertTrue(result.length <= 255, "résultat trop long pour ${raw.escaped()}")
        assertTrue(result.none { it.isInvisibleFormat() }, "caractère de format invisible pour ${raw.escaped()}")
        assertSanitizeKeepsName(result)
        // Le nom reste un segment unique : ni remontée ni aplatissement.
        val path = "/home/user/$result"
        assertEquals(path, sftpManager.sanitizePath(path), "sanitizePath modifie ${path.escaped()}")
        return result
    }

    private fun assertSanitizeKeepsName(result: String) {
        val sanitized = sftpManager.sanitizeFilename(result)
        assertNotNull(sanitized, "sanitizeFilename rejette ${result.escaped()}")
        assertEquals(result, sanitized, "sanitizeFilename modifie ${result.escaped()}")
    }

    /** Rend visibles les caractères invisibles dans un message d'échec. */
    private fun String.escaped(): String = buildString {
        for (c in this@escaped) {
            if (c.code in 0x20..0x7E) append(c) else append("\\u%04X".format(c.code))
        }
    }.let { if (it.length > 80) it.take(80) + "..." else it }
}
