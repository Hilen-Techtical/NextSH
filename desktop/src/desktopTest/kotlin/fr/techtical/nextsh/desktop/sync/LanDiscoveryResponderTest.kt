// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.shared.core.sync.DiscoveryProtocol
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [LanDiscoveryResponder]: real UDP sockets on loopback, bound to an
 * ephemeral port (0) so tests never collide with the production [DiscoveryProtocol.DISCOVERY_PORT]
 * or with each other under parallel test execution. The responder is bound to 127.0.0.1 rather
 * than 0.0.0.0 so a test never listens on the machine's real interfaces.
 *
 * This crosses real OS threads (the receive loop runs on [Dispatchers.IO]) and depends on real
 * wall-clock time for the token-bucket rate limiter, so these tests use plain blocking waits
 * (socket read timeouts) rather than `runTest`/virtual time.
 */
@OptIn(ExperimentalEncodingApi::class)
class LanDiscoveryResponderTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val wrongSecret = ByteArray(32) { (it + 7).toByte() }
    private val testDeviceId = "device-under-test-uuid"

    /**
     * Advertised TCP sync port. Deliberately NOT [DiscoveryProtocol.DISCOVERY_PORT]: both are
     * 47731 in production, so reusing it would make the "responses carry the sync port"
     * assertion pass even if the responder shipped the UDP discovery port instead.
     */
    private val syncPort = 51234

    /** Short wait used to assert an absence of response. */
    private val silenceTimeoutMs = 800

    private val testDevice = EnrolledDevice(
        deviceId = testDeviceId,
        deviceName = "Test Android",
        platform = Platform.ANDROID,
        publicKeyFingerprint = "fp-test",
        tlsCertFingerprint = null,
        lastSyncAt = null,
        enrolledAt = 1_000_000L,
    )

    private val startedResponders = mutableListOf<LanDiscoveryResponder>()
    private val startedScopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        startedResponders.forEach { it.stop() }
        startedScopes.forEach { it.cancel() }
        startedResponders.clear()
        startedScopes.clear()
    }

    private fun buildResponder(devices: List<EnrolledDevice> = listOf(testDevice)): LanDiscoveryResponder {
        val repo = mockk<EnrolledDeviceRepository>()
        coEvery { repo.getAll() } returns devices

        val store = mockk<EnrolledDeviceSecretStore>()
        // Catch-all first, specific override second: mirrors LanSyncServerTest's buildDeps pattern.
        coEvery { store.retrieve(any()) } returns null
        coEvery { store.retrieve(testDeviceId) } answers { secret.copyOf() }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startedScopes += scope
        val appScope = object : AppScope {
            override val coroutineScope: CoroutineScope = scope
            override fun onDestroy() = Unit
        }

        val responder = LanDiscoveryResponder(
            appScope = appScope,
            enrolledDeviceRepository = repo,
            secretStore = store,
            syncPort = syncPort,
            port = 0,
            bindAddress = "127.0.0.1",
        )
        startedResponders += responder
        return responder
    }

    private fun requestBytes(
        signingSecret: ByteArray,
        nonce: ByteArray = DiscoveryProtocol.randomNonce(),
    ): ByteArray = DiscoveryProtocol
        .buildRequestMessage(signingSecret, nonce, System.currentTimeMillis())
        .toByteArray(Charsets.UTF_8)

    private fun send(client: DatagramSocket, port: Int, bytes: ByteArray) {
        client.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), port))
    }

    private fun sendRequest(
        client: DatagramSocket,
        port: Int,
        signingSecret: ByteArray,
        nonce: ByteArray = DiscoveryProtocol.randomNonce(),
    ) = send(client, port, requestBytes(signingSecret, nonce))

    /** True when a datagram arrives before the socket's read timeout. */
    private fun receivedAnything(client: DatagramSocket): Boolean {
        val buf = ByteArray(DiscoveryProtocol.MAX_PACKET_BYTES)
        return runCatching { client.receive(DatagramPacket(buf, buf.size)) }.isSuccess
    }

    @Test
    fun `valid tag produces a correct unicast response carrying the TCP sync port`() {
        val responder = buildResponder()
        assertTrue(responder.start())
        val port = assertNotNull(responder.localPort)

        DatagramSocket().use { client ->
            client.soTimeout = 3_000
            val nonce = DiscoveryProtocol.randomNonce()
            sendRequest(client, port, secret, nonce)

            val buf = ByteArray(DiscoveryProtocol.MAX_PACKET_BYTES)
            val responsePacket = DatagramPacket(buf, buf.size)
            client.receive(responsePacket)

            val responseBytes = responsePacket.data.copyOfRange(responsePacket.offset, responsePacket.offset + responsePacket.length)
            val parsed = assertNotNull(DiscoveryProtocol.parseResponseMessage(responseBytes))
            val expectedNonceB64 = Base64.encode(nonce)
            assertEquals(syncPort, parsed.port, "the response must advertise the TCP sync port, not the UDP discovery port")
            assertTrue(DiscoveryProtocol.verifyResponseTag(parsed, expectedNonceB64 = expectedNonceB64, secret = secret))
        }
    }

    @Test
    fun `invalid tag produces no response at all`() {
        val responder = buildResponder()
        responder.start()
        val port = assertNotNull(responder.localPort)

        DatagramSocket().use { client ->
            client.soTimeout = silenceTimeoutMs
            // Signed with a secret that doesn't match any enrolled device's stored secret.
            sendRequest(client, port, wrongSecret)

            assertFalse(receivedAnything(client), "an invalid tag must never get a reply: total silence")
        }
    }

    @Test
    fun `a stale timestamp is dropped before the enrolled device list is even read`() {
        // The free pre-filter: a datagram outside the ±5 min window can never pass tag
        // verification, so it must be discarded without a repository or vault round-trip.
        val repo = mockk<EnrolledDeviceRepository>()
        coEvery { repo.getAll() } returns listOf(testDevice)
        val store = mockk<EnrolledDeviceSecretStore>()
        coEvery { store.retrieve(any()) } returns null

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startedScopes += scope
        val responder = LanDiscoveryResponder(
            appScope = object : AppScope {
                override val coroutineScope: CoroutineScope = scope
                override fun onDestroy() = Unit
            },
            enrolledDeviceRepository = repo,
            secretStore = store,
            syncPort = syncPort,
            port = 0,
            bindAddress = "127.0.0.1",
        )
        startedResponders += responder
        responder.start()
        val port = assertNotNull(responder.localPort)

        DatagramSocket().use { client ->
            client.soTimeout = silenceTimeoutMs
            val staleTs = System.currentTimeMillis() - DiscoveryProtocol.TIMESTAMP_SKEW_MS - 60_000L
            val stale = DiscoveryProtocol
                .buildRequestMessage(secret, DiscoveryProtocol.randomNonce(), staleTs)
                .toByteArray(Charsets.UTF_8)
            send(client, port, stale)

            assertFalse(receivedAnything(client), "a stale datagram must get total silence")
            coVerify(exactly = 0) { repo.getAll() }
            coVerify(exactly = 0) { store.retrieve(any()) }
        }
    }

    @Test
    fun `a replayed datagram is answered only once`() {
        val responder = buildResponder()
        responder.start()
        val port = assertNotNull(responder.localPort)

        DatagramSocket().use { client ->
            client.soTimeout = 3_000
            val bytes = requestBytes(secret)

            send(client, port, bytes)
            assertTrue(receivedAnything(client), "the first probe must be answered")

            // Wait past the 1/s per-IP response budget so the silence below can only come from
            // the served-nonce cache, not from rate limiting.
            Thread.sleep(1_300)

            client.soTimeout = silenceTimeoutMs
            send(client, port, bytes) // byte-for-byte identical: same nonce, same tag
            assertFalse(receivedAnything(client), "an already-answered nonce must never be answered twice")
        }
    }

    @Test
    fun `malformed packet produces no response and does not crash the loop`() {
        val responder = buildResponder()
        responder.start()
        val port = assertNotNull(responder.localPort)

        DatagramSocket().use { client ->
            client.soTimeout = silenceTimeoutMs
            val garbage = "not even json".toByteArray(Charsets.UTF_8)
            send(client, port, garbage)

            assertFalse(receivedAnything(client))

            // The loop must have survived the garbage packet: a well-formed request right after
            // still gets answered.
            client.soTimeout = 3_000
            sendRequest(client, port, secret)
            assertTrue(receivedAnything(client), "the receive loop must survive a malformed datagram")
        }
    }

    @Test
    fun `per-IP rate limit collapses a same-second burst to at most a couple of responses`() {
        val responder = buildResponder()
        responder.start()
        val port = assertNotNull(responder.localPort)

        DatagramSocket().use { client ->
            client.soTimeout = 2_000
            // 5 distinct, independently valid requests fired back-to-back from the same socket
            // (same source IP:port as seen by the responder).
            repeat(5) { sendRequest(client, port, secret) }

            var received = 0
            val buf = ByteArray(DiscoveryProtocol.MAX_PACKET_BYTES)
            try {
                while (true) {
                    client.receive(DatagramPacket(buf, buf.size))
                    received++
                }
            } catch (e: SocketTimeoutException) {
                // Expected once the burst window drains.
            }

            // The per-IP response bucket is capacity 1 / 1 per second. The burst normally yields
            // exactly one response, but a slow CI box can let the bucket refill mid-drain, so the
            // assertion bounds the count rather than pinning it: what matters is that 5 valid
            // requests do not produce 5 replies.
            assertTrue(received in 1..2, "expected 1 (or at most 2 on a slow refill) responses, got $received")
        }
    }

    @Test
    fun `start is idempotent and does not rebind an already-running socket`() {
        val responder = buildResponder()
        assertTrue(responder.start())
        val firstPort = responder.localPort

        assertTrue(responder.start(), "a repeated start on a running responder reports success")

        assertEquals(firstPort, responder.localPort)
        assertTrue(responder.isRunning)
    }

    @Test
    fun `start reports failure when the port is already taken`() {
        // A failed UDP bind must be reported, not swallowed: LanSyncServer keeps the TCP sync
        // endpoint alive and lets the network watcher retry the discovery bind later.
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { squatter ->
            val repo = mockk<EnrolledDeviceRepository>()
            coEvery { repo.getAll() } returns emptyList()
            val store = mockk<EnrolledDeviceSecretStore>()
            coEvery { store.retrieve(any()) } returns null

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            startedScopes += scope
            val responder = LanDiscoveryResponder(
                appScope = object : AppScope {
                    override val coroutineScope: CoroutineScope = scope
                    override fun onDestroy() = Unit
                },
                enrolledDeviceRepository = repo,
                secretStore = store,
                syncPort = syncPort,
                port = squatter.localPort,
                bindAddress = "127.0.0.1",
            )
            startedResponders += responder

            assertFalse(responder.start(), "binding an occupied port must report failure")
            assertFalse(responder.isRunning)
        }
    }

    @Test
    fun `stop is idempotent and cleanly tears down the listener`() {
        val responder = buildResponder()
        responder.start()
        assertTrue(responder.isRunning)

        responder.stop()
        assertFalse(responder.isRunning)
        assertNull(responder.localPort)

        // Repeated stop() must not throw.
        responder.stop()
        assertFalse(responder.isRunning)
    }

    @Test
    fun `no requests are answered after stop`() {
        val responder = buildResponder()
        responder.start()
        val port = assertNotNull(responder.localPort)
        responder.stop()

        DatagramSocket().use { client ->
            client.soTimeout = silenceTimeoutMs
            // The responder's socket is closed: sending to the old port must not throw on the
            // client side (best-effort UDP), and certainly must not produce a response. Asserted
            // as "nothing arrived" rather than "a SocketTimeoutException was raised": an ICMP
            // port-unreachable can surface as a PortUnreachableException on some platforms, which
            // is still an absence of response, not a failure of the assertion.
            runCatching { sendRequest(client, port, secret) }

            assertFalse(receivedAnything(client), "a stopped responder must answer nothing")
        }
    }
}

// ── TokenBucket ─────────────────────────────────────────────────────────────
//
// Kept in this file because the bucket is an implementation detail of the responder above and
// shares its package-private visibility. The injected clock makes every case deterministic:
// no sleeping, no wall-clock flakiness.

class TokenBucketTest {

    /** Manually advanced nanosecond clock. */
    private class FakeClock(private var nanos: Long = 0L) : () -> Long {
        override fun invoke(): Long = nanos
        fun advanceMillis(ms: Long) {
            nanos += ms * 1_000_000L
        }
    }

    @Test
    fun `a bucket starts full and drains to its capacity`() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacity = 5.0, refillPerSecond = 2.0, nanoTime = clock)

        repeat(5) { assertTrue(bucket.tryConsume(), "token ${it + 1} of the initial capacity") }
        assertFalse(bucket.tryConsume(), "the 6th consume in the same instant must be refused")
    }

    @Test
    fun `tokens refill at the configured rate and never exceed capacity`() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacity = 5.0, refillPerSecond = 2.0, nanoTime = clock)
        repeat(5) { bucket.tryConsume() }

        // 2 tokens/s → 499 ms is not quite one token.
        clock.advanceMillis(499)
        assertFalse(bucket.tryConsume())

        clock.advanceMillis(1)
        assertTrue(bucket.tryConsume(), "500 ms at 2 tokens/s must yield exactly one token")
        assertFalse(bucket.tryConsume())

        // A long idle period must not build up more than the capacity.
        clock.advanceMillis(60_000)
        repeat(5) { assertTrue(bucket.tryConsume(), "capacity must be restored after a long idle") }
        assertFalse(bucket.tryConsume(), "an idle bucket must cap at its capacity, not accumulate")
    }

    @Test
    fun `the global verification budget shape allows a 20-packet burst then throttles`() {
        // Mirrors VERIFY_GLOBAL_CAPACITY / VERIFY_GLOBAL_RATE_PER_SECOND.
        val clock = FakeClock()
        val bucket = TokenBucket(capacity = 20.0, refillPerSecond = 20.0, nanoTime = clock)

        repeat(20) { assertTrue(bucket.tryConsume()) }
        assertFalse(bucket.tryConsume(), "a flood beyond the burst allowance must be refused")

        clock.advanceMillis(1_000)
        repeat(20) { assertTrue(bucket.tryConsume(), "one second refills the full 20/s budget") }
        assertFalse(bucket.tryConsume())
    }

    @Test
    fun `the per-IP verification budget shape allows 5 then 2 per second`() {
        // Mirrors VERIFY_PER_IP_CAPACITY / VERIFY_PER_IP_RATE_PER_SECOND.
        val clock = FakeClock()
        val bucket = TokenBucket(capacity = 5.0, refillPerSecond = 2.0, nanoTime = clock)

        repeat(5) { assertTrue(bucket.tryConsume()) }
        assertFalse(bucket.tryConsume())

        clock.advanceMillis(1_000)
        assertTrue(bucket.tryConsume())
        assertTrue(bucket.tryConsume())
        assertFalse(bucket.tryConsume(), "only 2 tokens are earned per second for a single source IP")
    }
}

// ── ServedNonceCache ────────────────────────────────────────────────────────

class ServedNonceCacheTest {

    @Test
    fun `a nonce is reported as served only after being marked`() {
        val cache = ServedNonceCache(maxEntries = 4)

        assertFalse(cache.hasServed("nonce-a"))
        cache.markServed("nonce-a")
        assertTrue(cache.hasServed("nonce-a"))
        assertFalse(cache.hasServed("nonce-b"))
    }

    @Test
    fun `the cache never grows past its cap and evicts the least recently used entry`() {
        val cache = ServedNonceCache(maxEntries = 3)

        cache.markServed("a")
        cache.markServed("b")
        cache.markServed("c")
        // Touch "a" so "b" becomes the least recently used.
        assertTrue(cache.hasServed("a"))

        cache.markServed("d")

        assertEquals(3, cache.size(), "a bounded cache must not grow: memory is the whole point")
        assertFalse(cache.hasServed("b"), "the least recently used entry must be the one evicted")
        assertTrue(cache.hasServed("a"))
        assertTrue(cache.hasServed("c"))
        assertTrue(cache.hasServed("d"))
    }

    @Test
    fun `clear drops every entry`() {
        val cache = ServedNonceCache(maxEntries = 4)
        cache.markServed("a")
        cache.markServed("b")

        cache.clear()

        assertEquals(0, cache.size())
        assertFalse(cache.hasServed("a"))
    }
}
