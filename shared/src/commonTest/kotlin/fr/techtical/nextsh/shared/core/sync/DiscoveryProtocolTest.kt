// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class DiscoveryProtocolTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val otherSecret = ByteArray(32) { (it + 1).toByte() }
    private val now = 1_700_000_000_000L

    /**
     * TCP sync port advertised by responses. Deliberately NOT [DiscoveryProtocol.DISCOVERY_PORT]:
     * in production both happen to be 47731 (UDP vs TCP), which would make a "the advertised port
     * is the sync port" assertion pass even if the responder shipped the discovery port instead.
     */
    private val syncPort = 51234

    // ── Requests ────────────────────────────────────────────────────────────

    @Test
    fun `request round-trip build, parse and verify succeeds`() {
        val nonce = DiscoveryProtocol.randomNonce()
        val json = DiscoveryProtocol.buildRequestMessage(secret, nonce, now)

        val parsed = DiscoveryProtocol.parseRequestMessage(json.toByteArray(Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals(DiscoveryProtocol.PROTOCOL_VERSION, parsed.v)
        assertEquals(DiscoveryProtocol.TYPE_REQUEST, parsed.t)
        assertEquals(now, parsed.ts)
        assertTrue(DiscoveryProtocol.verifyRequestTag(parsed, secret, now = now))
    }

    @Test
    fun `request tag is truncated to 32 hex chars`() {
        val nonce = DiscoveryProtocol.randomNonce()
        val json = DiscoveryProtocol.buildRequestMessage(secret, nonce, now)
        val parsed = assertNotNull(DiscoveryProtocol.parseRequestMessage(json.toByteArray(Charsets.UTF_8)))

        assertEquals(32, parsed.tag.length, "16-byte truncated tag must hex-encode to 32 chars")
        assertTrue(parsed.tag.all { it in '0'..'9' || it in 'a'..'f' }, "Tag must be lowercase hex")
    }

    @Test
    fun `request verify fails with wrong secret`() {
        val nonce = DiscoveryProtocol.randomNonce()
        val json = DiscoveryProtocol.buildRequestMessage(secret, nonce, now)
        val parsed = assertNotNull(DiscoveryProtocol.parseRequestMessage(json.toByteArray(Charsets.UTF_8)))

        assertFalse(DiscoveryProtocol.verifyRequestTag(parsed, otherSecret, now = now))
    }

    @Test
    fun `request verify fails when timestamp outside skew window`() {
        val nonce = DiscoveryProtocol.randomNonce()
        val json = DiscoveryProtocol.buildRequestMessage(secret, nonce, now)
        val parsed = assertNotNull(DiscoveryProtocol.parseRequestMessage(json.toByteArray(Charsets.UTF_8)))

        val farFuture = now + 10L * 60_000L // 10 min later, outside the ±5 min window
        assertFalse(DiscoveryProtocol.verifyRequestTag(parsed, secret, now = farFuture))
    }

    @Test
    fun `request verify fails when nonce is tampered after signing`() {
        val nonce = DiscoveryProtocol.randomNonce()
        val json = DiscoveryProtocol.buildRequestMessage(secret, nonce, now)
        val parsed = assertNotNull(DiscoveryProtocol.parseRequestMessage(json.toByteArray(Charsets.UTF_8)))

        // Tamper the nonce while keeping the original tag: the tag was computed over the
        // original nonce, so this must fail verification (nonce mismatch / integrity check).
        val tamperedNonce = DiscoveryProtocol.randomNonce()
        val tampered = parsed.copy(n = Base64.encode(tamperedNonce))

        assertFalse(DiscoveryProtocol.verifyRequestTag(tampered, secret, now = now))
    }

    @Test
    fun `parseRequestMessage rejects packet larger than MAX_PACKET_BYTES`() {
        val oversized = ByteArray(DiscoveryProtocol.MAX_PACKET_BYTES + 1) { 'a'.code.toByte() }
        assertNull(DiscoveryProtocol.parseRequestMessage(oversized))
    }

    @Test
    fun `parseRequestMessage rejects empty packet`() {
        assertNull(DiscoveryProtocol.parseRequestMessage(ByteArray(0)))
    }

    @Test
    fun `parseRequestMessage rejects malformed JSON`() {
        assertNull(DiscoveryProtocol.parseRequestMessage("not json at all".toByteArray(Charsets.UTF_8)))
        assertNull(DiscoveryProtocol.parseRequestMessage("{\"v\":1".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `parseRequestMessage rejects unknown extra fields (no polymorphic leniency)`() {
        val nonce = DiscoveryProtocol.randomNonce()
        val json = DiscoveryProtocol.buildRequestMessage(secret, nonce, now)
        val withExtraField = json.dropLast(1) + ",\"extra\":\"injected\"}"
        assertNull(DiscoveryProtocol.parseRequestMessage(withExtraField.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `parseRequestMessage rejects wrong protocol version or message type`() {
        val wrongVersion = """{"v":2,"t":"disco-req","n":"AAAAAAAAAAAAAAAAAAAAAA==","ts":$now,"tag":"00"}"""
        assertNull(DiscoveryProtocol.parseRequestMessage(wrongVersion.toByteArray(Charsets.UTF_8)))

        val wrongType = """{"v":1,"t":"disco-resp","n":"AAAAAAAAAAAAAAAAAAAAAA==","ts":$now,"tag":"00"}"""
        assertNull(DiscoveryProtocol.parseRequestMessage(wrongType.toByteArray(Charsets.UTF_8)))
    }

    // ── Responses ───────────────────────────────────────────────────────────

    @Test
    fun `response round-trip build, parse and verify succeeds`() {
        val nonce = DiscoveryProtocol.randomNonce()
        val nonceB64 = Base64.encode(nonce)
        val json = DiscoveryProtocol.buildResponseMessage(secret, nonceB64, now, port = syncPort)

        val parsed = DiscoveryProtocol.parseResponseMessage(json.toByteArray(Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals(syncPort, parsed.port, "the response must advertise the TCP sync port it was built with")
        assertTrue(DiscoveryProtocol.verifyResponseTag(parsed, expectedNonceB64 = nonceB64, secret = secret, now = now))
    }

    @Test
    fun `response verify fails when timestamp outside skew window`() {
        // Symmetric with the request-side window check: a response that sat around (or was
        // captured and replayed) beyond ±5 min must not authenticate, even with a valid tag.
        val nonce = DiscoveryProtocol.randomNonce()
        val nonceB64 = Base64.encode(nonce)
        val json = DiscoveryProtocol.buildResponseMessage(secret, nonceB64, now, port = syncPort)
        val parsed = assertNotNull(DiscoveryProtocol.parseResponseMessage(json.toByteArray(Charsets.UTF_8)))

        val farFuture = now + 10L * 60_000L
        assertFalse(
            DiscoveryProtocol.verifyResponseTag(parsed, expectedNonceB64 = nonceB64, secret = secret, now = farFuture),
        )
        val farPast = now - 10L * 60_000L
        assertFalse(
            DiscoveryProtocol.verifyResponseTag(parsed, expectedNonceB64 = nonceB64, secret = secret, now = farPast),
        )
        // Just inside the window it still verifies. This proves the rejection above is the window,
        // not some unrelated breakage.
        assertTrue(
            DiscoveryProtocol.verifyResponseTag(
                parsed,
                expectedNonceB64 = nonceB64,
                secret = secret,
                now = now + DiscoveryProtocol.TIMESTAMP_SKEW_MS,
            ),
        )
    }

    @Test
    fun `isTimestampFresh matches the verification window exactly`() {
        assertTrue(DiscoveryProtocol.isTimestampFresh(now, now))
        assertTrue(DiscoveryProtocol.isTimestampFresh(now, now + DiscoveryProtocol.TIMESTAMP_SKEW_MS))
        assertTrue(DiscoveryProtocol.isTimestampFresh(now, now - DiscoveryProtocol.TIMESTAMP_SKEW_MS))
        assertFalse(DiscoveryProtocol.isTimestampFresh(now, now + DiscoveryProtocol.TIMESTAMP_SKEW_MS + 1))
        assertFalse(DiscoveryProtocol.isTimestampFresh(now, now - DiscoveryProtocol.TIMESTAMP_SKEW_MS - 1))
    }

    @Test
    fun `response verify fails when echoed nonce does not match the expected nonce`() {
        val nonce = DiscoveryProtocol.randomNonce()
        val nonceB64 = Base64.encode(nonce)
        val json = DiscoveryProtocol.buildResponseMessage(secret, nonceB64, now, port = syncPort)
        val parsed = assertNotNull(DiscoveryProtocol.parseResponseMessage(json.toByteArray(Charsets.UTF_8)))

        val differentNonceB64 = Base64.encode(DiscoveryProtocol.randomNonce())
        assertFalse(DiscoveryProtocol.verifyResponseTag(parsed, expectedNonceB64 = differentNonceB64, secret = secret, now = now))
    }

    @Test
    fun `response verify fails with wrong secret`() {
        val nonce = DiscoveryProtocol.randomNonce()
        val nonceB64 = Base64.encode(nonce)
        val json = DiscoveryProtocol.buildResponseMessage(secret, nonceB64, now, port = syncPort)
        val parsed = assertNotNull(DiscoveryProtocol.parseResponseMessage(json.toByteArray(Charsets.UTF_8)))

        assertFalse(DiscoveryProtocol.verifyResponseTag(parsed, expectedNonceB64 = nonceB64, secret = otherSecret, now = now))
    }

    @Test
    fun `parseResponseMessage rejects packet larger than MAX_PACKET_BYTES`() {
        val oversized = ByteArray(DiscoveryProtocol.MAX_PACKET_BYTES + 1) { 'a'.code.toByte() }
        assertNull(DiscoveryProtocol.parseResponseMessage(oversized))
    }

    @Test
    fun `parseResponseMessage rejects malformed JSON`() {
        assertNull(DiscoveryProtocol.parseResponseMessage("{ this is not valid".toByteArray(Charsets.UTF_8)))
    }
}
