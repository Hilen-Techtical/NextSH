// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exceptions thrown by the sync codecs are logged by callers (message and stack trace), so they
 * must never carry decrypted or decoded sync data (host labels, hostnames, usernames, tunnel
 * paths). A kotlinx.serialization error message quotes an excerpt of the JSON it failed on.
 *
 * Every test pairs with a "premise" test showing the raw library error does contain the marker,
 * so the assertions cannot pass vacuously if a library upgrade stops quoting the input.
 */
class SyncErrorMessageLeakTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val otherSecret = ByteArray(32) { (it + 1).toByte() }

    /** A well-formed bundle whose decrypted content contains [LEAK_MARKER]. */
    private fun bundleWithMarker(): SyncBundle {
        val host = Host(
            id = "h1",
            label = "Test Server",
            hostname = LEAK_MARKER,
            port = 22,
            username = "admin",
            authType = AuthType.PASSWORD,
            credentialId = "cred-h1",
        )
        return SyncBundle(
            hosts = listOf(SyncEntry(id = "h1", payload = host, clock = VectorClock.EMPTY, updatedAt = 1L)),
            tunnels = emptyList(),
            sshKeys = emptyList(),
            snippets = emptyList(),
        )
    }

    /** Encrypts [plaintext] as-is (not necessarily valid JSON) into a well-formed wire payload. */
    private fun payloadWithPlaintext(plaintext: String): SyncPayload {
        val iv = ByteArray(12) { (it + 7).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return SyncPayload(
            senderDeviceId = "device-1",
            timestamp = 1L,
            iv = encoder.encodeToString(iv),
            ciphertext = encoder.encodeToString(ciphertext),
        )
    }

    // Plaintexts that decrypt fine but are not a valid SyncBundle. Each carries the marker as a
    // hostname so a "JSON input: ..." excerpt would expose it.
    private val malformedBundles = mapOf(
        "truncated json" to
            """{"hosts":[{"id":"h1","payload":{"id":"h1","label":"x","hostname":"$LEAK_MARKER","port":22,"username":"adm""",
        "wrong field type" to
            """{"hosts":[{"id":"h1","payload":{"id":"h1","label":"x","hostname":"$LEAK_MARKER","port":"not-a-number"}}]}""",
        "not json at all" to "$LEAK_MARKER is not a sync bundle",
    )

    // Stored conflict JSON that cannot be decoded. Each form fails for every entity type.
    private val malformedEntries = mapOf(
        "truncated json" to
            """{"id":"h1","payload":{"label":"$LEAK_MARKER","hostname":"$LEAK_MARKER","port":22,"usernam""",
        "wrong type for id" to """{"id":{"nested":"$LEAK_MARKER"},"payload":null}""",
        "not json at all" to "$LEAK_MARKER is not a conflict entry",
    )

    private val conflictDecoders: Map<String, (String) -> Any> = mapOf(
        "host" to ConflictSerializer::decodeHost,
        "tunnel" to ConflictSerializer::decodeTunnel,
        "sshKey" to ConflictSerializer::decodeSshKey,
        "snippet" to ConflictSerializer::decodeSnippet,
        "credential" to ConflictSerializer::decodeCredential,
        "customTerminalTheme" to ConflictSerializer::decodeCustomTerminalTheme,
    )

    // ---- SyncBundleCodec ----

    @Test
    fun `premise - raw kotlinx error on a malformed bundle does expose the payload`() {
        val lenient = Json { ignoreUnknownKeys = true }
        malformedBundles.forEach { (name, plaintext) ->
            val raw = assertFailsWith<SerializationException>(name) {
                lenient.decodeFromString(SyncBundle.serializer(), plaintext)
            }
            assertTrue(raw.message.orEmpty().contains(LEAK_MARKER), "$name: premise broken, raw message: ${raw.message}")
        }
    }

    @Test
    fun `decrypt of a malformed decrypted bundle throws SyncCodecException without the payload`() {
        malformedBundles.forEach { (name, plaintext) ->
            val e = assertFailsWith<SyncCodecException>(name) {
                SyncBundleCodec.decrypt(payloadWithPlaintext(plaintext), secret)
            }
            assertNoPayloadLeak(e)
            // Fixed text plus the failing class name only, and no cause that could print more.
            assertTrue(e.message.orEmpty().startsWith("Sync bundle decoding failed ("), "$name: ${e.message}")
            assertNull(e.cause, "$name: the decode failure must not keep a cause")
        }
    }

    @Test
    fun `decrypt crypto failures keep a fixed message and never expose the plaintext`() {
        // The plaintext of every payload below contains the marker: a failed tag check or a wrong
        // key must not release any of it into the exception.
        val tampered = SyncBundleCodec.encrypt(bundleWithMarker(), "device-1", secret).let { p ->
            val raw = Base64.getUrlDecoder().decode(p.ciphertext)
            raw[0] = (raw[0].toInt() xor 0x01).toByte()
            p.copy(ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
        }
        val wrongKey = SyncBundleCodec.encrypt(bundleWithMarker(), "device-1", secret)
        val badBase64 = wrongKey.copy(iv = "***not base64***")

        listOf(
            "tampered ciphertext" to { SyncBundleCodec.decrypt(tampered, secret) },
            "wrong key" to { SyncBundleCodec.decrypt(wrongKey, otherSecret) },
            "malformed base64" to { SyncBundleCodec.decrypt(badBase64, secret) },
        ).forEach { (name, block) ->
            val e = assertFailsWith<SyncCodecException>(name) { block() }
            assertNoPayloadLeak(e)
            assertTrue(e.message.orEmpty().startsWith("Decryption failed ("), "$name: ${e.message}")
            assertTrue(e.message.orEmpty().endsWith(")"), "$name: ${e.message}")
            assertNotNull(e.cause, "$name: crypto failures keep their cause")
        }
    }

    // ---- ConflictSerializer ----

    @Test
    fun `premise - raw kotlinx error on malformed conflict json does expose the payload`() {
        val lenient = Json { ignoreUnknownKeys = true }
        malformedEntries.forEach { (name, text) ->
            val raw = assertFailsWith<SerializationException>(name) {
                lenient.decodeFromString(SyncEntry.serializer(Host.serializer()), text)
            }
            assertTrue(raw.message.orEmpty().contains(LEAK_MARKER), "$name: premise broken, raw message: ${raw.message}")
        }
    }

    @Test
    fun `conflict decoders throw SerializationException without the payload`() {
        conflictDecoders.forEach { (type, decode) ->
            malformedEntries.forEach { (name, text) ->
                val e = assertFailsWith<SerializationException>("$type / $name") { decode(text) }
                assertNoPayloadLeak(e)
                assertTrue(e.message.orEmpty().startsWith("Conflict entry decoding failed ("), "$type / $name: ${e.message}")
                assertNull(e.cause, "$type / $name: the failure must not keep a cause")
            }
        }
    }
}
