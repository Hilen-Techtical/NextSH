// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.shared.core.sync.DiscoveryProtocol
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "LanDiscoveryResponder"

/** Max outbound discovery responses per second, across all source IPs combined. */
private const val GLOBAL_RATE_PER_SECOND = 5.0

/** Max outbound discovery responses per second, per distinct source IP. */
private const val PER_IP_RATE_PER_SECOND = 1.0

/**
 * Verification budget, all source IPs combined. Sized well above the response budget: this one
 * only has to keep an unauthenticated flood from reaching the repository and the vault, not to
 * shape legitimate answers.
 */
private const val VERIFY_GLOBAL_CAPACITY = 20.0
private const val VERIFY_GLOBAL_RATE_PER_SECOND = 20.0

/** Verification budget per source-IP slot: a small burst allowance, then a slow trickle. */
private const val VERIFY_PER_IP_CAPACITY = 5.0
private const val VERIFY_PER_IP_RATE_PER_SECOND = 2.0

/**
 * Number of fixed per-IP verification slots. A FIXED array (not a map) on purpose: the key is
 * the source address of an unauthenticated datagram, i.e. fully attacker-controlled, and a map
 * keyed on it would grow without bound under a spoofed-source flood. Hash collisions merely make
 * two source IPs share a budget, which is an acceptable trade for a hard memory ceiling.
 */
private const val VERIFY_IP_SLOTS = 256

/** Bounded LRU of nonces already answered: the anti-replay window. */
private const val SERVED_NONCE_CACHE_SIZE = 256

/**
 * UDP responder for LAN discovery (C4). Listens on [DiscoveryProtocol.DISCOVERY_PORT] for
 * [DiscoveryProtocol.DiscoveryRequest] datagrams, and replies unicast with a
 * [DiscoveryProtocol.DiscoveryResponse] carrying the current TCP sync port, but only when the
 * request's HMAC tag validates against one of the enrolled devices' shared secrets (the same
 * secret store used by [validateHmac] for the HTTPS sync API).
 *
 * Security posture:
 * - No stable identifier ever appears on the wire (see [DiscoveryProtocol]).
 * - Any datagram that fails to parse, is oversized, is stale, replays an already-answered nonce,
 *   or doesn't verify against a known secret is dropped in total silence: no response, no log,
 *   so port-scanning this UDP port cannot be used to fingerprint "a NextSH desktop is here" or
 *   leak *why* a probe failed.
 * - Work is gated *before* it is spent, not after: a free timestamp pre-filter, then a
 *   verification token bucket (global + per-source-IP slot), both consumed before the enrolled
 *   device list and the vault secrets are touched. Otherwise a single unauthenticated packet
 *   costs one repository read plus one secret decrypt and HMAC per enrolled device.
 * - A nonce that has already been answered is never answered twice (bounded LRU, checked only
 *   after the tag verifies so the cache can never be filled from unauthenticated input).
 * - Responses are unicast to the UDP packet's actual source address:port, never to an address
 *   taken from the payload, so this cannot be abused as a reflection/amplification relay.
 * - Token-bucket rate limiting also bounds per-source-IP and global outbound response volume.
 *
 * Thread-safety / lifecycle: [start] and [stop] are idempotent and safe to call from any thread;
 * a repeated [start] while already running, or [stop] while already stopped, is a no-op.
 */
class LanDiscoveryResponder(
    private val appScope: AppScope,
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val secretStore: EnrolledDeviceSecretStore,
    /**
     * TCP port advertised in responses: the port the querying device must connect to for sync.
     * Supplied by [LanSyncServer] so the two can never drift apart; it is NOT [port].
     */
    private val syncPort: Int,
    /** Bind port: overridable so tests can bind an ephemeral port (0) instead of the fixed production port. */
    private val port: Int = DiscoveryProtocol.DISCOVERY_PORT,
    /** Bind address: overridable so tests can stay on loopback instead of every interface. */
    private val bindAddress: String = "0.0.0.0",
) {

    private val lock = Any()

    @Volatile private var socket: DatagramSocket? = null

    @Volatile private var receiveJob: Job? = null

    // Response budget (gates what goes out).
    private val globalBucket = TokenBucket(capacity = GLOBAL_RATE_PER_SECOND, refillPerSecond = GLOBAL_RATE_PER_SECOND)

    /**
     * Per-IP response buckets. A map here (unlike the verification slots) is safe: an entry is
     * only ever created for a source that already authenticated with an enrolled device's shared
     * secret, so the key space is not attacker-controlled. Cleared on every start/stop.
     */
    private val perIpBuckets = ConcurrentHashMap<String, TokenBucket>()

    // Verification budget (gates what gets *examined*: see the class doc).
    private val verifyGlobalBucket =
        TokenBucket(capacity = VERIFY_GLOBAL_CAPACITY, refillPerSecond = VERIFY_GLOBAL_RATE_PER_SECOND)
    private val verifyIpBuckets = Array(VERIFY_IP_SLOTS) {
        TokenBucket(capacity = VERIFY_PER_IP_CAPACITY, refillPerSecond = VERIFY_PER_IP_RATE_PER_SECOND)
    }

    private val servedNonces = ServedNonceCache(SERVED_NONCE_CACHE_SIZE)

    /** The socket's actual local port once bound (useful in tests using [port] = 0), or null when stopped. */
    val localPort: Int? get() = socket?.localPort

    /** True while the UDP socket is bound and the receive loop is running. */
    val isRunning: Boolean get() = socket != null

    /**
     * Binds the UDP socket and starts the receive loop.
     *
     * Returns true when the responder is running afterwards (including the idempotent
     * already-running case), false when the bind failed: typically a port already taken or a
     * firewall refusal. The caller decides what to do about it: the TCP sync endpoint stays
     * perfectly usable without discovery, so a failed bind must not fail the whole server, but it
     * must not be silently permanent either (see [LanSyncServer.ensureDiscoveryRunning]).
     */
    fun start(): Boolean {
        synchronized(lock) {
            if (socket != null) return true

            val sock = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    // Qualified receiver: inside apply{}, a bare `port` resolves to
                    // DatagramSocket.getPort() (-1 while unconnected), not our field.
                    bind(
                        InetSocketAddress(
                            this@LanDiscoveryResponder.bindAddress,
                            this@LanDiscoveryResponder.port,
                        ),
                    )
                }
            } catch (e: IOException) {
                Logger.w(TAG, "Impossible de démarrer le répondeur de découverte LAN : ${e.message}")
                return false
            }

            socket = sock
            perIpBuckets.clear()
            servedNonces.clear()
            Logger.d(TAG, "Répondeur de découverte LAN actif sur le port ${sock.localPort}")
            receiveJob = appScope.coroutineScope.launch(Dispatchers.IO) { receiveLoop(sock) }
            return true
        }
    }

    fun stop() {
        synchronized(lock) {
            val sock = socket ?: return
            socket = null
            receiveJob?.cancel()
            receiveJob = null
            // Closing unblocks the in-flight receive() call in the loop coroutine.
            sock.close()
            perIpBuckets.clear()
            servedNonces.clear()
            Logger.d(TAG, "Répondeur de découverte LAN arrêté")
        }
    }

    private suspend fun receiveLoop(sock: DatagramSocket) {
        val buffer = ByteArray(DiscoveryProtocol.MAX_PACKET_BYTES)
        while (currentCoroutineContext().isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (e: IOException) {
                // Expected on stop() (socket closed under us): anything else is a transient
                // socket-level fault, not a per-packet event, so a single operational log is fine.
                if (sock.isClosed) return
                Logger.w(TAG, "Erreur de réception UDP sur le port de découverte : poursuite")
                continue
            }
            // Copy out of the shared buffer before the next receive() overwrites it, and before
            // handing off to a suspending path.
            val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val sourceAddress = packet.address
            val sourcePort = packet.port
            // Total-silence policy: any failure below (parse, no match, rate limit) is swallowed
            // without logging: never log packet content, tags, nonces or secrets.
            runCatching { handlePacket(sock, data, sourceAddress, sourcePort) }
        }
    }

    private suspend fun handlePacket(sock: DatagramSocket, data: ByteArray, sourceAddress: InetAddress, sourcePort: Int) {
        val request = DiscoveryProtocol.parseRequestMessage(data) ?: return

        // 1. Free pre-filter. A timestamp outside the skew window can never pass
        //    verifyRequestTag, so rejecting it here costs one subtraction and saves the whole
        //    repository + vault round-trip below. Same window, so this can never reject
        //    something full verification would have accepted.
        if (!DiscoveryProtocol.isTimestampFresh(request.ts)) return

        val sourceIp = sourceAddress.hostAddress ?: return

        // 2. Verification budget, consumed before any secret-material work.
        if (!verifyGlobalBucket.tryConsume()) return
        if (!verifyBucketFor(sourceIp).tryConsume()) return

        val devices = enrolledDeviceRepository.getAll()
        for (device in devices) {
            val secret = secretStore.retrieve(device.deviceId) ?: continue
            val matched = try {
                DiscoveryProtocol.verifyRequestTag(request, secret)
            } finally {
                secret.fill(0)
            }
            if (!matched) continue

            // 3. Anti-replay: only now that the tag proved the sender holds the shared secret.
            //    Checking earlier would let unauthenticated traffic populate the cache. A nonce
            //    already answered gets total silence, which also collapses the querying client's
            //    own retransmit schedule (same nonce sent at 0/300/900 ms) into a single reply.
            if (servedNonces.hasServed(request.n)) return

            // Found the enrolled device this request authenticates as. The response budget gates
            // the actual outbound datagram (per the spec: "max N responses/s").
            if (!globalBucket.tryConsume()) return
            val ipBucket = perIpBuckets.getOrPut(sourceIp) {
                TokenBucket(capacity = PER_IP_RATE_PER_SECOND, refillPerSecond = PER_IP_RATE_PER_SECOND)
            }
            if (!ipBucket.tryConsume()) return

            servedNonces.markServed(request.n)

            // Re-fetch a fresh copy to sign the response: the one above was already wiped.
            val signingSecret = secretStore.retrieve(device.deviceId) ?: return
            try {
                val responseJson = DiscoveryProtocol.buildResponseMessage(
                    secret = signingSecret,
                    echoedNonceB64 = request.n,
                    timestamp = System.currentTimeMillis(),
                    port = syncPort,
                )
                sendResponse(sock, responseJson, sourceAddress, sourcePort)
            } finally {
                signingSecret.fill(0)
            }
            return
        }
        // No enrolled device's secret matched: total silence, no response, no log.
    }

    /** Fixed-slot lookup: attacker-controlled key, bounded memory. See [VERIFY_IP_SLOTS]. */
    private fun verifyBucketFor(sourceIp: String): TokenBucket =
        verifyIpBuckets[sourceIp.hashCode() and (VERIFY_IP_SLOTS - 1)]

    private fun sendResponse(sock: DatagramSocket, json: String, address: InetAddress, port: Int) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        sock.send(DatagramPacket(bytes, bytes.size, address, port))
    }
}

/**
 * Bounded LRU of discovery nonces already answered. Access-ordered so a nonce being re-probed
 * stays hot; capacity-capped so it can never grow. No explicit TTL is needed: the ±5 min
 * timestamp window already rejects anything old enough to have aged out of a 256-entry cache
 * under any realistic LAN probe rate.
 */
internal class ServedNonceCache(private val maxEntries: Int) {

    private val entries = object : LinkedHashMap<String, Unit>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > maxEntries
    }

    /** True when [nonceB64] was already answered. Counts as an access, refreshing its LRU position. */
    @Synchronized
    fun hasServed(nonceB64: String): Boolean = entries[nonceB64] != null

    @Synchronized
    fun markServed(nonceB64: String) {
        entries[nonceB64] = Unit
    }

    @Synchronized
    fun clear() = entries.clear()

    /** Current entry count: exposed so the eviction cap can be asserted in tests. */
    @Synchronized
    fun size(): Int = entries.size
}

/**
 * Minimal thread-safe token bucket for rate limiting. [capacity] and [refillPerSecond] share the
 * same unit (tokens per second at steady state); a capacity of 1 with a 1/s refill enforces
 * "at most one per second".
 *
 * [nanoTime] is injectable so tests can drive the refill deterministically instead of sleeping.
 */
internal class TokenBucket(
    private val capacity: Double,
    private val refillPerSecond: Double,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var tokens = capacity
    private var lastRefillNanos = nanoTime()

    @Synchronized
    fun tryConsume(): Boolean {
        val now = nanoTime()
        val elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0
        lastRefillNanos = now
        tokens = (tokens + elapsedSeconds * refillPerSecond).coerceAtMost(capacity)
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }
}
