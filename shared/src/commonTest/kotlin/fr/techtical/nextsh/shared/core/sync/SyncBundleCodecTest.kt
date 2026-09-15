// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SyncBundleCodecTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val otherSecret = ByteArray(32) { (it + 1).toByte() }

    private fun sampleBundle(): SyncBundle {
        val clock = VectorClock.EMPTY
        val host = Host(
            id = "h1",
            label = "Test Server",
            hostname = "192.168.1.1",
            port = 22,
            username = "admin",
            authType = AuthType.PASSWORD,
            credentialId = "cred-h1",
        )
        val snippet = Snippet(
            id = "s1",
            label = "ls",
            command = "ls -la",
            createdAt = 1_700_000_000_000L,
        )
        return SyncBundle(
            hosts = listOf(SyncEntry(id = "h1", payload = host, clock = clock, updatedAt = 1_700_000_000_000L)),
            tunnels = emptyList(),
            sshKeys = emptyList(),
            snippets = listOf(SyncEntry(id = "s1", payload = snippet, clock = clock, updatedAt = 1_700_000_000_000L)),
        )
    }

    @Test
    fun `encrypt then decrypt round trip returns identical bundle`() {
        val original = sampleBundle()
        val payload = SyncBundleCodec.encrypt(original, "device-1", secret)
        val decoded = SyncBundleCodec.decrypt(payload, secret)
        assertEquals(original, decoded)
    }

    @Test
    fun `encrypt produces different IVs for same input`() {
        val bundle = sampleBundle()
        val payload1 = SyncBundleCodec.encrypt(bundle, "device-1", secret)
        val payload2 = SyncBundleCodec.encrypt(bundle, "device-1", secret)
        assertNotEquals(payload1.iv, payload2.iv)
    }

    @Test
    fun `decrypt fails when ciphertext tampered`() {
        val bundle = sampleBundle()
        val payload = SyncBundleCodec.encrypt(bundle, "device-1", secret)

        val decoder = Base64.getUrlDecoder()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val raw = decoder.decode(payload.ciphertext)
        raw[0] = (raw[0].toInt() xor 0x01).toByte()
        val tampered = payload.copy(ciphertext = encoder.encodeToString(raw))

        assertFailsWith<SyncCodecException> {
            SyncBundleCodec.decrypt(tampered, secret)
        }
    }

    @Test
    fun `decrypt fails with wrong key`() {
        val bundle = sampleBundle()
        val payload = SyncBundleCodec.encrypt(bundle, "device-1", secret)

        assertFailsWith<SyncCodecException> {
            SyncBundleCodec.decrypt(payload, otherSecret)
        }
    }

    @Test
    fun `encrypt then decrypt round trip preserves customThemes and totalCount`() {
        val clock = VectorClock.EMPTY
        val ansi16 = List(16) { i -> 0xFF000000.toInt() or (i * 0x111111) }
        val theme = CustomTerminalTheme(
            id = "ct-1",
            name = "WS7 Test Theme",
            background = 0xFF101010.toInt(),
            foreground = 0xFFEEEEEE.toInt(),
            cursor = 0xFF00FF00.toInt(),
            selectionBg = 0x33FFFFFF.toInt(),
            ansi = ansi16,
            createdAt = 1_700_000_100_000L,
        )
        val themeEntry = SyncEntry(
            id = "ct-1",
            payload = theme,
            clock = clock,
            updatedAt = 1_700_000_100_000L,
        )
        val bundleWithTheme = sampleBundle().copy(customThemes = listOf(themeEntry))

        val payload = SyncBundleCodec.encrypt(bundleWithTheme, "device-1", secret)
        val decoded = SyncBundleCodec.decrypt(payload, secret)

        assertEquals(bundleWithTheme, decoded)
        assertEquals(1, decoded.customThemes.size)
        assertEquals(theme, decoded.customThemes[0].payload)
        assertEquals(16, decoded.customThemes[0].payload!!.ansi.size)
        // totalCount must include the customThemes entry (1 host + 1 snippet + 1 theme = 3)
        assertEquals(3, decoded.totalCount)
    }
}
