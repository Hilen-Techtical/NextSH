// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Wire format and cryptographic helpers for LAN discovery (C4).
 *
 * A querying device (Android, wave 2) broadcasts a UDP [DiscoveryRequest] on
 * [DISCOVERY_PORT]. A Desktop peer that holds the matching per-device shared
 * secret (the same ECDH-derived secret used for HMAC-signed sync requests,
 * see [HmacSigner]) answers with a unicast [DiscoveryResponse] carrying the
 * TCP sync port to connect to. This lets Android re-discover a Desktop whose
 * IP changed since the QR pairing scan, without any stable identifier
 * (deviceId, hostname, fingerprint) ever appearing on the wire.
 *
 * Both message types are authenticated with a truncated HMAC-SHA256 "tag"
 * computed over a small pipe-delimited canonical string, mirroring the
 * design of [HmacSigner] (constant-time comparison, bounded timestamp skew)
 * but with its own canonical format since discovery has no HTTP method/path/
 * body to sign over.
 *
 * All functions here are pure (they take the shared secret, nonce and
 * timestamp as explicit parameters) so both platforms can unit-test the
 * protocol without any socket I/O. Parsing is strict: bounded packet size,
 * exact non-polymorphic data classes (no generic/duck-typed decoding), and
 * an explicit protocol-version + message-type check on every parse.
 */
object DiscoveryProtocol {

    /** UDP port the Desktop discovery responder listens on. Distinct port space from the TCP sync port range. */
    const val DISCOVERY_PORT: Int = 47731

    /** Hard cap on the size of any discovery datagram, request or response. */
    const val MAX_PACKET_BYTES: Int = 512

    /** Current wire format version. Messages with a different [DiscoveryRequest.v]/[DiscoveryResponse.v] are rejected. */
    const val PROTOCOL_VERSION: Int = 1

    const val TYPE_REQUEST: String = "disco-req"
    const val TYPE_RESPONSE: String = "disco-resp"

    /** Nonce length in bytes, before Base64 encoding into the wire field. */
    private const val NONCE_BYTES = 16

    /** Truncated HMAC-SHA256 tag length in bytes (32 hex chars on the wire). */
    private const val TAG_BYTES = 16

    /** Same skew tolerance as [HmacSigner]: kept as an independent constant since that one is private there. */
    const val TIMESTAMP_SKEW_MS: Long = 5L * 60_000L

    private val wireJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
    }

    @Serializable
    data class DiscoveryRequest(
        val v: Int,
        val t: String,
        val n: String,
        val ts: Long,
        val tag: String,
    )

    @Serializable
    data class DiscoveryResponse(
        val v: Int,
        val t: String,
        val n: String,
        val ts: Long,
        /** TCP sync port to connect to: NOT the UDP [DISCOVERY_PORT] the datagram arrived on. */
        val port: Int,
        val tag: String,
    )

    /** Generates a fresh random 16-byte nonce for a new discovery request. */
    fun randomNonce(): ByteArray = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * True when [timestamp] is within [TIMESTAMP_SKEW_MS] of [now].
     *
     * Exposed so a responder can drop a stale or far-future datagram *before* spending any
     * secret-material work on it (repository read, vault access, HMAC computation): the check
     * costs one subtraction and leaks nothing, since a rejected packet gets no reply either way.
     * [verifyRequestTag] and [verifyResponseTag] apply the very same window, so a pre-filter can
     * never accept something the full verification would reject.
     */
    fun isTimestampFresh(timestamp: Long, now: Long = System.currentTimeMillis()): Boolean =
        kotlin.math.abs(now - timestamp) <= TIMESTAMP_SKEW_MS

    /**
     * Builds the signed JSON body for a discovery request (querying side).
     * [nonce] is raw bytes; it is Base64-encoded into the wire message.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun buildRequestMessage(secret: ByteArray, nonce: ByteArray, timestamp: Long): String {
        val nonceB64 = Base64.encode(nonce)
        val request = DiscoveryRequest(
            v = PROTOCOL_VERSION,
            t = TYPE_REQUEST,
            n = nonceB64,
            ts = timestamp,
            tag = requestTag(secret, nonceB64, timestamp),
        )
        return wireJson.encodeToString(DiscoveryRequest.serializer(), request)
    }

    /**
     * Strictly parses a request datagram: size-bounded, exact JSON shape, version + type checked.
     * Returns null on any malformed input: callers must not distinguish the failure reason
     * (see the discovery responder's total-silence policy).
     */
    fun parseRequestMessage(bytes: ByteArray): DiscoveryRequest? {
        if (bytes.isEmpty() || bytes.size > MAX_PACKET_BYTES) return null
        val request = runCatching {
            wireJson.decodeFromString(DiscoveryRequest.serializer(), bytes.decodeToString())
        }.getOrNull() ?: return null
        if (request.v != PROTOCOL_VERSION || request.t != TYPE_REQUEST) return null
        if (!isValidNonce(request.n)) return null
        return request
    }

    /**
     * Verifies a parsed request's tag against [secret] and checks the timestamp is within
     * [TIMESTAMP_SKEW_MS] of [now]. Constant-time tag comparison.
     */
    fun verifyRequestTag(
        request: DiscoveryRequest,
        secret: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isTimestampFresh(request.ts, now)) return false
        val expected = requestTag(secret, request.n, request.ts)
        return constantTimeEquals(expected, request.tag)
    }

    /**
     * Builds the signed JSON body for a discovery response (responder side). [echoedNonceB64] must
     * be the exact `n` field copied from the matched request: never a value taken from elsewhere.
     *
     * [port] is the **TCP sync port** the querying device should connect to, not the UDP discovery
     * port this datagram arrived on. It is mandatory on purpose: a default of [DISCOVERY_PORT]
     * would silently ship the wrong port whenever the two ever diverge.
     */
    fun buildResponseMessage(
        secret: ByteArray,
        echoedNonceB64: String,
        timestamp: Long,
        port: Int,
    ): String {
        val response = DiscoveryResponse(
            v = PROTOCOL_VERSION,
            t = TYPE_RESPONSE,
            n = echoedNonceB64,
            ts = timestamp,
            port = port,
            tag = responseTag(secret, echoedNonceB64, timestamp, port),
        )
        return wireJson.encodeToString(DiscoveryResponse.serializer(), response)
    }

    /** Strictly parses a response datagram: same bounded/exact-shape rules as [parseRequestMessage]. */
    fun parseResponseMessage(bytes: ByteArray): DiscoveryResponse? {
        if (bytes.isEmpty() || bytes.size > MAX_PACKET_BYTES) return null
        val response = runCatching {
            wireJson.decodeFromString(DiscoveryResponse.serializer(), bytes.decodeToString())
        }.getOrNull() ?: return null
        if (response.v != PROTOCOL_VERSION || response.t != TYPE_RESPONSE) return null
        if (!isValidNonce(response.n)) return null
        if (response.port !in 1..65535) return null
        return response
    }

    /**
     * Verifies a parsed response (querying side): the echoed nonce must match [expectedNonceB64]
     * exactly (rejects a response replayed/crafted for a different request), the timestamp must be
     * fresh, and the tag must check out against [secret]. Constant-time tag comparison.
     */
    fun verifyResponseTag(
        response: DiscoveryResponse,
        expectedNonceB64: String,
        secret: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (response.n != expectedNonceB64) return false
        if (!isTimestampFresh(response.ts, now)) return false
        val expected = responseTag(secret, response.n, response.ts, response.port)
        return constantTimeEquals(expected, response.tag)
    }

    private fun requestTag(secret: ByteArray, nonceB64: String, ts: Long): String =
        hmacHex("nextsh-disco-req|$PROTOCOL_VERSION|$nonceB64|$ts", secret)

    private fun responseTag(secret: ByteArray, nonceB64: String, ts: Long, port: Int): String =
        hmacHex("nextsh-disco-resp|$PROTOCOL_VERSION|$nonceB64|$ts|$port", secret)

    /** Full HMAC-SHA256, truncated to [TAG_BYTES] bytes, hex-encoded (32 lowercase hex chars). */
    private fun hmacHex(canonical: String, secret: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val full = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return full.copyOf(TAG_BYTES).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    @OptIn(ExperimentalEncodingApi::class)
    private fun isValidNonce(nonceB64: String): Boolean =
        runCatching { Base64.decode(nonceB64).size == NONCE_BYTES }.getOrDefault(false)
}
