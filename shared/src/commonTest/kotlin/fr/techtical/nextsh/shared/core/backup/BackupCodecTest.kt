// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackupCodecTest {

    private val v1Core = """
        "exportedAt": 1700000000000,
        "hosts": [{"id":"h1","label":"Web","hostname":"web.example.test","username":"u","authType":"PASSWORD","credentialId":"c1"}],
        "sshKeys": [],
        "tunnels": [],
        "credentials": {"c1":"FIXTURE-password"},
        "privateKeys": {}
    """.trimIndent()

    @Test
    fun `a v1 file without version reads as version 1 with empty new sections`() {
        val decoded = BackupCodec.decode("{ $v1Core }").decodedOrFail()
        assertEquals(1, decoded.document.version)
        assertEquals(1, decoded.document.hosts.size)
        assertTrue(decoded.document.keyPassphrases.isEmpty())
        assertTrue(decoded.document.certificates.isEmpty())
        assertTrue(decoded.document.snippets.isEmpty())
        assertTrue(decoded.document.customThemes.isEmpty())
        assertTrue(decoded.document.knownHosts.isEmpty())
        assertEquals(null, decoded.document.settings)
        assertEquals(null, decoded.document.producer)
        assertFalse(decoded.hasUnknownSections)
    }

    @Test
    fun `an explicit version 1 and an explicit version 2 are accepted`() {
        assertEquals(1, BackupCodec.decode("{ \"version\": 1, $v1Core }").decodedOrFail().document.version)
        assertEquals(2, BackupCodec.decode("{ \"version\": 2, $v1Core }").decodedOrFail().document.version)
    }

    @Test
    fun `all sections survive an encode and decode round trip`() {
        val original = sampleDocument()
        val decoded = BackupCodec.decode(BackupCodec.encode(original)).decodedOrFail()
        assertEquals(original.copy(version = 2), decoded.document)
        assertFalse(decoded.hasUnknownSections)
    }

    @Test
    fun `the bytes variants round trip too`() {
        val original = sampleDocument()
        val decoded = BackupCodec.decode(BackupCodec.encodeToBytes(original)).decodedOrFail()
        assertEquals(original.copy(version = 2), decoded.document)
    }

    @Test
    fun `the written json always contains version 2 even when the document holds the default`() {
        val json = BackupCodec.encode(sampleDocument(version = 1))
        assertTrue(json.contains("\"version\":2"), "version 2 must be written explicitly")
        assertEquals(2, BackupCodec.decode(json).decodedOrFail().document.version)
        // A document built with the default must not leave the key out either.
        val minimal = sampleDocument(snippets = emptyList(), customThemes = emptyList(), knownHosts = emptyList(), settings = null)
        assertTrue(BackupCodec.encode(minimal).contains("\"version\":2"))
    }

    @Test
    fun `version 3 or more is refused as unsupported even if the rest has another structure`() {
        val result = BackupCodec.decode("""{"version": 3, "hosts": "something-new", "other": 1}""")
        val unsupported = assertIs<BackupDecodeResult.UnsupportedVersion>(result)
        assertEquals(3, unsupported.version)
        assertEquals(99, assertIs<BackupDecodeResult.UnsupportedVersion>(BackupCodec.decode("{\"version\":99}")).version)
    }

    @Test
    fun `version 0 or negative or not a number is malformed`() {
        assertEquals(BackupDecodeResult.Malformed, BackupCodec.decode("{ \"version\": 0, $v1Core }"))
        assertEquals(BackupDecodeResult.Malformed, BackupCodec.decode("{ \"version\": -1, $v1Core }"))
        assertEquals(BackupDecodeResult.Malformed, BackupCodec.decode("{ \"version\": \"2\", $v1Core }"))
        assertEquals(BackupDecodeResult.Malformed, BackupCodec.decode("{ \"version\": null, $v1Core }"))
        assertEquals(BackupDecodeResult.Malformed, BackupCodec.decode("{ \"version\": 2.5, $v1Core }"))
        assertEquals(BackupDecodeResult.Malformed, BackupCodec.decode("{ \"version\": [2], $v1Core }"))
    }

    @Test
    fun `unknown top level keys are reported and the known data is still decoded`() {
        val decoded = BackupCodec.decode(
            "{ \"version\": 2, $v1Core, \"futureSection\": {\"a\": 1}, \"other\": [] }",
        ).decodedOrFail()
        assertTrue(decoded.hasUnknownSections)
        assertEquals(setOf("futureSection", "other"), decoded.unknownTopLevelKeys)
        assertEquals(1, decoded.document.hosts.size)
    }

    @Test
    fun `unknown keys inside known objects are ignored without warning`() {
        val json = """
            {"exportedAt":1,"hosts":[{"id":"h1","label":"a","hostname":"b","username":"u","authType":"PASSWORD","credentialId":"c","newField":1}],
             "sshKeys":[],"tunnels":[],"credentials":{},"privateKeys":{}}
        """.trimIndent()
        val decoded = BackupCodec.decode(json).decodedOrFail()
        assertFalse(decoded.hasUnknownSections)
    }

    @Test
    fun `structure errors are malformed`() {
        val cases = listOf(
            "",
            "not json",
            "[]",
            "\"text\"",
            "null",
            "{}",
            "{ \"exportedAt\": 1 }",
            "{ \"exportedAt\": \"x\", \"hosts\": [], \"sshKeys\": [], \"tunnels\": [], \"credentials\": {}, \"privateKeys\": {} }",
            "{ \"exportedAt\": 1, \"hosts\": [{\"id\":\"h\"}], \"sshKeys\": [], \"tunnels\": [], \"credentials\": {}, \"privateKeys\": {} }",
            "{ \"exportedAt\": 1, \"hosts\": [], \"sshKeys\": [], \"tunnels\": [], \"credentials\": {}, \"privateKeys\": {}, \"snippets\": {} }",
            "{ \"exportedAt\": 1, \"hosts\": [], \"sshKeys\": [], \"tunnels\": [], \"credentials\": {}, \"privateKeys\": {}, \"snippets\": [{\"label\":\"no id\"}] }",
        )
        for (case in cases) {
            assertEquals(BackupDecodeResult.Malformed, BackupCodec.decode(case), "case: ${case.take(40)}")
        }
    }

    @Test
    fun `a snippet without id is a structure error and never gets a random id`() {
        val json = "{ $v1Core, \"snippets\": [{\"label\":\"x\",\"command\":\"ls\",\"createdAt\":1}] }"
        assertEquals(BackupDecodeResult.Malformed, BackupCodec.decode(json))
    }

    @Test
    fun `invalid utf8 bytes are malformed`() {
        val bytes = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte())
        assertEquals(BackupDecodeResult.Malformed, BackupCodec.decode(bytes))
    }

    @Test
    fun `results never carry content of the file`() {
        val marker = "LEAKED-SECRET-MARKER-42"
        val broken = listOf(
            "{ \"exportedAt\": \"$marker\", \"hosts\": [], \"sshKeys\": [], \"tunnels\": [], \"credentials\": {}, \"privateKeys\": {} }",
            "{ \"exportedAt\": 1, \"hosts\": [{\"id\":\"$marker\",\"authType\":\"$marker\"}], \"sshKeys\": [], \"tunnels\": [], \"credentials\": {\"c\":\"$marker\"}, \"privateKeys\": {} ",
            "{ \"version\": \"$marker\" }",
            "{ \"version\": 7, \"credentials\": {\"c\":\"$marker\"} }",
        )
        for (json in broken) {
            val result = BackupCodec.decode(json)
            assertFalse(result.toString().contains(marker), "result text must not hold file content")
        }
        // A good decode must not print the secrets either.
        val ok = BackupCodec.decode("{ $v1Core }").decodedOrFail()
        assertFalse(ok.toString().contains("FIXTURE-password"))
    }

    @Test
    fun `the written json contains no key of the new sections when they are empty`() {
        val json = BackupCodec.encode(
            sampleDocument(snippets = emptyList(), customThemes = emptyList(), knownHosts = emptyList(), settings = null)
                .copy(certificates = emptyMap(), keyPassphrases = emptyMap(), producer = null),
        )
        for (key in listOf("snippets", "customThemes", "knownHosts", "settings", "certificates", "producer")) {
            assertFalse(json.contains("\"$key\""), "$key must be omitted when empty")
        }
    }
}
