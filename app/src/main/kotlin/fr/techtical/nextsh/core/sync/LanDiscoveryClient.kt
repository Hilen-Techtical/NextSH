// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import fr.techtical.nextsh.shared.core.sync.DiscoveryProtocol
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LanDiscoveryClient"

/** Overall wall-clock budget for one discovery cycle: the socket is closed once this elapses. */
private const val TOTAL_TIMEOUT_MS = 2_000L

/** Probe re-emission schedule, relative to the start of the cycle (mirrors a light retransmit pattern for lossy UDP). */
private val SEND_OFFSETS_MS = longArrayOf(0L, 300L, 900L)

/** Poll granularity for the blocking receive loop: bounds how late we notice the deadline has passed. */
private const val RECEIVE_POLL_MS = 200

/** Hard cap on distinct candidate IPs returned, mirroring [fr.techtical.nextsh.desktop.sync.LanDiscoveryResponder]'s rate limiting intent. */
internal const val MAX_DISCOVERY_CANDIDATES = 5

/**
 * Android-side LAN discovery client (C4).
 *
 * Broadcasts a signed [DiscoveryProtocol] probe on the local network and collects the
 * source IPs of any Desktop peers that answer, so [LanSyncClient] can re-locate a Desktop
 * whose IP changed since the QR pairing scan. See [DiscoveryProtocol] for the wire format
 * and `fr.techtical.nextsh.desktop.sync.LanDiscoveryResponder` for the Desktop responder.
 *
 * Security posture:
 * - The probe is signed with the device's own ECDH-derived shared secret (the same secret
 *   used for HTTPS sync HMACs). If the secret cannot be retrieved for any reason, device
 *   not enrolled, or the vault is locked (`VaultAuthExpiredException`, which comes in two
 *   distinct classes on Android depending on which `VaultManager` interface is in scope),
 *   [discover] returns an empty list without ever touching the network. An unsigned probe
 *   is never sent.
 * - A response is only accepted as a candidate after [DiscoveryProtocol.verifyResponseTag]
 *   passes (correct echoed nonce + valid HMAC tag). The candidate address is always the
 *   UDP packet's actual source IP: never a value taken from the response payload, which
 *   carries no address field to begin with.
 * - This is one-shot and caller-driven only: nothing here runs periodically or in the
 *   background.
 *
 * No new Android permission is required: broadcast uses a plain ephemeral [DatagramSocket]
 * with `broadcast = true`; target addresses come from [NetworkInterface.getNetworkInterfaces].
 * No `WifiManager`, no multicast lock.
 */
@Singleton
class LanDiscoveryClient @Inject constructor(
    private val secretStore: EnrolledDeviceSecretStore,
) {

    /**
     * Probes the LAN for [device] and returns up to [MAX_DISCOVERY_CANDIDATES] distinct
     * candidate IPs that answered with a validly signed response, in the order their
     * responses arrived. Returns an empty list on any failure (no secret, vault locked,
     * no network interfaces, socket error, nobody answered within the timeout budget).
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun discover(device: EnrolledDevice): List<String> {
        val secret = try {
            secretStore.retrieve(device.deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Covers both the app-side and shared-side VaultAuthExpiredException classes
            // (two distinct types with the same name, see class doc) plus any other vault
            // I/O failure: none of them are recoverable here, all mean "cannot sign a probe".
            Timber.d(TAG, "discover: secret unavailable for ${device.deviceId} (vault locked?), skipping probe")
            null
        } ?: return emptyList()

        return try {
            withContext(Dispatchers.IO) { probe(secret) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "discover: probe failed for ${device.deviceId}")
            emptyList()
        } finally {
            secret.fill(0)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun probe(secret: ByteArray): List<String> {
        val targets = broadcastTargets()
        if (targets.isEmpty()) return emptyList()

        val nonce = DiscoveryProtocol.randomNonce()
        val nonceB64 = Base64.encode(nonce)
        val requestBytes = DiscoveryProtocol
            .buildRequestMessage(secret, nonce, System.currentTimeMillis())
            .toByteArray(Charsets.UTF_8)

        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = RECEIVE_POLL_MS
        }
        return try {
            val candidates = mutableListOf<String>()
            val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
            coroutineScope {
                val sendJob = launch { sendSchedule(socket, requestBytes, targets, deadline) }
                receiveResponses(socket, nonceB64, secret, deadline, candidates)
                sendJob.cancel()
            }
            candidates
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun sendSchedule(
        socket: DatagramSocket,
        requestBytes: ByteArray,
        targets: List<InetAddress>,
        deadline: Long,
    ) {
        val start = System.currentTimeMillis()
        for (offset in SEND_OFFSETS_MS) {
            val waitMs = (start + offset) - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            if (System.currentTimeMillis() >= deadline) return
            for (target in targets) {
                runCatching {
                    socket.send(DatagramPacket(requestBytes, requestBytes.size, target, DiscoveryProtocol.DISCOVERY_PORT))
                }
            }
        }
    }

    /** Blocking receive loop: runs on [Dispatchers.IO] via the caller's [withContext]. */
    private fun receiveResponses(
        socket: DatagramSocket,
        expectedNonceB64: String,
        secret: ByteArray,
        deadline: Long,
        out: MutableList<String>,
    ) {
        val buffer = ByteArray(DiscoveryProtocol.MAX_PACKET_BYTES)
        while (System.currentTimeMillis() < deadline && out.size < MAX_DISCOVERY_CANDIDATES) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                return
            }
            val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val sourceIp = packet.address.hostAddress ?: continue
            val candidate = extractCandidate(data, sourceIp, expectedNonceB64, secret)
            if (candidate != null) addCandidate(out, candidate)
        }
    }

    /** Broadcast targets: the limited-broadcast address plus every non-loopback IPv4 interface's subnet broadcast. */
    private fun broadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        runCatching { targets += InetAddress.getByName("255.255.255.255") }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return targets.toList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ifaceAddress in iface.interfaceAddresses) {
                    ifaceAddress.broadcast?.let { targets += it }
                }
            }
        } catch (e: Exception) {
            Timber.d(TAG, "broadcastTargets, interface enumeration failed: ${e.message}")
        }
        return targets.toList()
    }

    companion object {

        /**
         * Pure, socket-free: parses and authenticates a single raw response datagram.
         * Returns [sourceIp] (never anything derived from the payload) when the response
         * parses, echoes [expectedNonceB64] and its HMAC tag validates against [secret];
         * returns null on any parse/auth failure. Exposed for unit tests.
         */
        internal fun extractCandidate(
            data: ByteArray,
            sourceIp: String,
            expectedNonceB64: String,
            secret: ByteArray,
            now: Long = System.currentTimeMillis(),
        ): String? {
            val response = DiscoveryProtocol.parseResponseMessage(data) ?: return null
            if (!DiscoveryProtocol.verifyResponseTag(response, expectedNonceB64, secret, now)) return null
            return sourceIp
        }

        /** Pure de-dup + cap helper (mutates [current] in place). Exposed for unit tests. */
        internal fun addCandidate(current: MutableList<String>, ip: String, max: Int = MAX_DISCOVERY_CANDIDATES) {
            if (current.size >= max) return
            if (ip in current) return
            current.add(ip)
        }
    }
}
