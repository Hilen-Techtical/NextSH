// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Same package as ImportFormatDetector, so no cross-package import of the
// nested enum is needed (spelling out "...core.import.ImportFormatDetector..."
// in an import statement would require backtick-escaping the `import` package
// segment: see the existing pattern in HostImportViewModel.kt). A local
// typealias sidesteps that entirely.
private typealias ImportFormat = ImportFormatDetector.ImportFormat

class ImportFormatDetectorTest {

    @Test
    fun `detects native export via format marker`() {
        val nativeWithMarker = """
            {"format":"nextsh-hosts","version":1,"hosts":[{"hostname":"h.example"}]}
        """.trimIndent()
        assertEquals(ImportFormat.NEXTSH, ImportFormatDetector.detect(nativeWithMarker))
    }

    @Test
    fun `detects native export marker with extra whitespace around colon`() {
        val spaced = """{ "format"   :   "nextsh-hosts" , "hosts": [] }"""
        assertEquals(ImportFormat.NEXTSH, ImportFormatDetector.detect(spaced))
    }

    @Test
    fun `detects native bare array export without marker`() {
        val nativeBareArray = """[{"hostname":"bare.example","username":"op"}]"""
        assertEquals(ImportFormat.NEXTSH, ImportFormatDetector.detect(nativeBareArray))
    }

    @Test
    fun `detects native export that lost its format marker via hosts plus hostname shape`() {
        // A hand-edited native export: envelope kept, "format" field dropped.
        val handEdited = """{"hosts":[{"hostname":"h.example"}]}"""
        assertEquals(ImportFormat.NEXTSH, ImportFormatDetector.detect(handEdited))
    }

    @Test
    fun `detects realistic termius export via signature collections`() {
        val termiusExport = """
            {
              "hosts": [
                { "id": "h1", "label": "web-01", "address": "10.0.0.1", "identity": "i1" }
              ],
              "identities": [
                { "id": "i1", "username": "root", "password": "s3cr3t" }
              ]
            }
        """.trimIndent()
        assertEquals(ImportFormat.TERMIUS, ImportFormatDetector.detect(termiusExport))
    }

    @Test
    fun `detects termius bare array export falling back to the JSON default`() {
        // Same shape as TermiusImporterTest's bare-array case: no "hostname" field,
        // no signature collection either, resolved by the final JSON fallback.
        val termiusBareArray = """[{"label":"solo","address":"host.example","port":22,"username":"me"}]"""
        assertEquals(ImportFormat.TERMIUS, ImportFormatDetector.detect(termiusBareArray))
    }

    @Test
    fun `detects keepassxc csv export`() {
        val csv = "Group,Title,Username,Password,URL\nHome,router,admin,admin123,192.168.1.1:22\n"
        assertEquals(ImportFormat.KEEPASSXC, ImportFormatDetector.detect(csv))
    }

    @Test
    fun `detects keepassxc xml export`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><KeePassFile><Root></Root></KeePassFile>"""
        assertEquals(ImportFormat.KEEPASSXC, ImportFormatDetector.detect(xml))
    }

    @Test
    fun `falls back to keepassxc for non-json unrelated content`() {
        assertEquals(ImportFormat.KEEPASSXC, ImportFormatDetector.detect("not json, not xml, not csv either"))
        assertEquals(ImportFormat.KEEPASSXC, ImportFormatDetector.detect(""))
    }

    @Test
    fun `tolerates leading BOM and whitespace before the marker`() {
        val withBom = "﻿  \n{\"format\":\"nextsh-hosts\",\"hosts\":[]}"
        assertEquals(ImportFormat.NEXTSH, ImportFormatDetector.detect(withBom))
    }

    @Test
    fun `isFileTooLarge gates on the documented byte cap`() {
        val cap = ImportFormatDetector.MAX_IMPORT_FILE_BYTES
        assertFalse(ImportFormatDetector.isFileTooLarge(0))
        assertFalse(ImportFormatDetector.isFileTooLarge(cap))
        assertTrue(ImportFormatDetector.isFileTooLarge(cap + 1))
    }

    // ── Key-position false positive regression (review fix) ─────────────────

    @Test
    fun `a minimal termius export whose payload merely contains the word hostname as a value is not misdetected as NEXTSH`() {
        // "script":"hostname" is a VALUE, not a field name, so it must not trip the
        // NextSH hostname heuristic. No termius signature key present either,
        // so this resolves via the JSON default fallback (still TERMIUS).
        val termiusMinimal = """{"hosts":[{"address":"h.example","script":"hostname"}]}"""
        assertEquals(ImportFormat.TERMIUS, ImportFormatDetector.detect(termiusMinimal))
    }

    @Test
    fun `a termius export identified only by the user_name signature key is detected as TERMIUS`() {
        val termiusUserName = """{"hosts":[{"user_name":"root","address":"h.example"}]}"""
        assertEquals(ImportFormat.TERMIUS, ImportFormatDetector.detect(termiusUserName))
    }

    @Test
    fun `a native bare array export is still detected as NEXTSH after the key-position fix`() {
        val nativeBareArray = """[{"hostname":"bare.example","username":"op"}]"""
        assertEquals(ImportFormat.NEXTSH, ImportFormatDetector.detect(nativeBareArray))
    }
}
