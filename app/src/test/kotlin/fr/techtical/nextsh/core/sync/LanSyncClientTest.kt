// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.shared.core.sync.ApplyResult
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.core.sync.SyncBundle
import fr.techtical.nextsh.shared.core.sync.SyncError
import fr.techtical.nextsh.shared.core.sync.SyncRepository
import fr.techtical.nextsh.shared.core.sync.SyncResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/**
 * Unit tests for [LanSyncClient].
 *
 * Integration tests against a live MockWebServer with TLS pinning are
 * architecturally complex due to the need to wire a self-signed cert
 * through OkHttp's SSLSocketFactory in the JVM test environment.
 * Those scenarios are covered by the E2E test suite (Wave 3.5 / instrumented).
 *
 * Here we validate the guard clauses and the network-unavailable fast path,
 * which do not require an HTTP round-trip.
 */
class LanSyncClientTest {

    private val syncRepository = mockk<SyncRepository>()
    private val credentialSyncRepository = mockk<fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository>(relaxed = true)
    private val secretStore = mockk<EnrolledDeviceSecretStore>()
    private val enrolledDeviceRepository = mockk<EnrolledDeviceRepository>(relaxed = true)
    private val networkMonitor = mockk<NetworkMonitor>()
    private val lanDiscoveryClient = mockk<LanDiscoveryClient>(relaxed = true)

    private val client = LanSyncClient(
        syncRepository = syncRepository,
        credentialSyncRepository = credentialSyncRepository,
        secretStore = secretStore,
        enrolledDeviceRepository = enrolledDeviceRepository,
        networkMonitor = networkMonitor,
        lanDiscoveryClient = lanDiscoveryClient,
    )

    private val validDevice = EnrolledDevice(
        deviceId = "desktop-test-1",
        deviceName = "Test Desktop",
        platform = Platform.DESKTOP,
        publicKeyFingerprint = "fp",
        tlsCertFingerprint = "a".repeat(64),
        lastSyncAt = null,
        enrolledAt = 1_700_000_000_000L,
        lastKnownHost = "192.168.1.10",
    )

    // ── Guard clause: network unavailable ────────────────────────────────────

    @Test
    fun `push returns DeviceUnreachable when network is disconnected`() = runTest {
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(false)

        val result = client.push(validDevice)

        assertInstanceOf(SyncResult.DeviceUnreachable::class.java, result)
    }

    @Test
    fun `pull returns DeviceUnreachable when network is disconnected`() = runTest {
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(false)

        val result = client.pull(validDevice)

        assertInstanceOf(SyncResult.DeviceUnreachable::class.java, result)
    }

    // ── Guard clause: incomplete device config ────────────────────────────────

    @Test
    fun `push returns Error when lastKnownHost is null`() = runTest {
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(true)
        coEvery { secretStore.retrieve(any()) } returns ByteArray(32)

        val deviceNoHost = validDevice.copy(lastKnownHost = null)
        val result = client.push(deviceNoHost)

        assertInstanceOf(SyncResult.Error::class.java, result)
        val message = (result as SyncResult.Error).message
        assertEquals(true, message?.contains("lastKnownHost"))
    }

    @Test
    fun `push returns Error when tlsCertFingerprint is null`() = runTest {
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(true)
        coEvery { secretStore.retrieve(any()) } returns ByteArray(32)

        val deviceNoFp = validDevice.copy(tlsCertFingerprint = null)
        val result = client.push(deviceNoFp)

        assertInstanceOf(SyncResult.Error::class.java, result)
        val message = (result as SyncResult.Error).message
        assertEquals(true, message?.contains("tlsCertFingerprint"))
    }

    // ── Guard clause: secret not found ───────────────────────────────────────

    @Test
    fun `push returns AuthFailed when secret is not in vault`() = runTest {
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(true)
        coEvery { secretStore.retrieve(validDevice.deviceId) } returns null

        val result = client.push(validDevice)

        assertInstanceOf(SyncResult.AuthFailed::class.java, result)
    }

    @Test
    fun `pull returns AuthFailed when secret is not in vault`() = runTest {
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(true)
        coEvery { secretStore.retrieve(validDevice.deviceId) } returns null

        val result = client.pull(validDevice)

        assertInstanceOf(SyncResult.AuthFailed::class.java, result)
    }

    // ── fullSync short-circuits on pull failure ───────────────────────────────

    @Test
    fun `fullSync returns pull failure without attempting push`() = runTest {
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(false)

        val result = client.fullSync(validDevice)

        // Off a LAN transport → fullSync bails before any push/pull attempt.
        assertInstanceOf(SyncResult.DeviceUnreachable::class.java, result)
    }

    @Test
    fun `fullSync off a LAN transport never broadcasts discovery probes`() = runTest {
        // The 4G case for the manual "search on network" button: push/pull each carry their own
        // transport gate, but the discovery resolution path sits outside them. Without the gate
        // at the top of fullSync, a device with no lastKnownHost would send UDP broadcasts over
        // cellular.
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(false)
        val deviceNoHost = validDevice.copy(lastKnownHost = null)

        val result = client.fullSync(deviceNoHost)

        assertInstanceOf(SyncResult.DeviceUnreachable::class.java, result)
        coVerify(exactly = 0) { lanDiscoveryClient.discover(any()) }
        coVerify(exactly = 0) { enrolledDeviceRepository.updateLastKnownHost(any(), any()) }
    }

    // ── Host-unreachable classification (drives the re-discovery decision) ────
    //
    // fullSync only re-resolves an address when the failure says "this address led nowhere".
    // These tests pin the exact set of I/O failures that qualify: the classification used to
    // be matched on a French message prefix, which left UnknownHostException and
    // NoRouteToHostException out and made the wording load-bearing.

    @Test
    fun `a dead hostname (UnknownHostException) classifies as HOST_UNREACHABLE and re-triggers discovery`() {
        // Self-locking case: a device enrolled by host name whose DNS/mDNS record dies. The old
        // classification reported a plain "network error", so discovery was never attempted and
        // the stale entry could never heal.
        val result = client.classifyIoError("pull", "desktop-1", UnknownHostException("my-desktop.local"))

        assertInstanceOf(SyncResult.Error::class.java, result)
        assertEquals(SyncError.HOST_UNREACHABLE, (result as SyncResult.Error).reason)
        assertTrue(client.isHostUnreachable(result), "must route fullSync into the discovery branch")
    }

    @Test
    fun `NoRouteToHostException classifies as HOST_UNREACHABLE and re-triggers discovery`() {
        // What retries 2-3 hit once the ARP entry for a departed peer goes NUD_FAILED.
        val result = client.classifyIoError("pull", "desktop-1", NoRouteToHostException("EHOSTUNREACH"))

        assertEquals(SyncError.HOST_UNREACHABLE, (result as SyncResult.Error).reason)
        assertTrue(client.isHostUnreachable(result))
    }

    @Test
    fun `ConnectException classifies as HOST_UNREACHABLE and re-triggers discovery`() {
        val result = client.classifyIoError("pull", "desktop-1", ConnectException("Connection refused"))

        assertEquals(SyncError.HOST_UNREACHABLE, (result as SyncResult.Error).reason)
        assertTrue(client.isHostUnreachable(result))
    }

    @Test
    fun `a read timeout is not treated as unreachable and does not re-trigger discovery`() {
        // A read/write timeout means a live server accepted the connection: the address is
        // correct, so broadcasting discovery probes would be pure noise.
        val timeoutResult = client.classifyIoError("pull", "desktop-1", SocketTimeoutException("Read timed out"))
        assertFalse(client.isHostUnreachable(timeoutResult))

        // And the shape push/pull actually produce for a timeout (they catch it before
        // classifyIoError is reached) must not re-trigger discovery either.
        assertFalse(client.isHostUnreachable(SyncResult.Error(SyncError.NETWORK_TIMEOUT, "Request timed out")))
    }

    // ── TLS: a refused pin is an authentication failure, any other TLS failure is a network one ──

    @Test
    fun `a refused TLS pin wrapped in a handshake failure is AuthFailed and does not re-trigger discovery`() {
        // OkHttp raises the CertificateException of PinnedTrustManager inside an SSLHandshakeException.
        // The peer answered with another certificate: the settings must invite to enroll the device
        // again, which "sync failed" would not.
        val handshake = SSLHandshakeException("handshake failed").apply {
            initCause(CertificateException("TLS fingerprint mismatch: pinning rejected"))
        }

        val result = client.classifyIoError("pull", "desktop-1", handshake)

        assertEquals(SyncResult.AuthFailed, result)
        assertFalse(client.isHostUnreachable(result), "a pin mismatch is a security signal, not a routing problem")
    }

    @Test
    fun `a refused TLS pin is found deeper in the cause chain`() {
        val deep = IOException("outer", IOException("middle", CertificateException("pinning rejected")))

        assertEquals(SyncResult.AuthFailed, client.classifyIoError("push", "desktop-1", deep))
    }

    @Test
    fun `a TLS failure without a certificate stays a generic error`() {
        // A handshake cut short may be a plain network drop: it must not tell the user to enroll again.
        val result = client.classifyIoError("pull", "desktop-1", SSLHandshakeException("Connection reset during handshake"))

        assertInstanceOf(SyncResult.Error::class.java, result)
        assertEquals(SyncError.UNKNOWN, (result as SyncResult.Error).reason)
        assertFalse(client.isHostUnreachable(result))
    }

    @Test
    fun `the developer messages of a classified failure carry no address and no exception message`() {
        val secret = "10.20.30.40:47731 /home/user/secret"
        val results = listOf(
            client.classifyIoError("pull", "desktop-1", ConnectException("Failed to connect to /$secret")),
            client.classifyIoError("pull", "desktop-1", IOException("boom $secret")),
        )

        results.forEach { result ->
            val message = (result as SyncResult.Error).message.orEmpty()
            assertFalse(message.contains("10.20.30.40") || message.contains("secret") || message.contains("47731"), message)
        }
    }

    @Test
    fun `isHostUnreachable ignores non-Error results`() {
        assertFalse(client.isHostUnreachable(SyncResult.DeviceUnreachable))
        assertFalse(client.isHostUnreachable(SyncResult.AuthFailed))
    }

    // ── executeWithRetry: first unreachable verdict wins ─────────────────────

    @Test
    fun `executeWithRetry rethrows the first unreachable exception rather than the last`() = runTest {
        // Attempt 1 proves the host is gone; the later attempts degrade into plain timeouts.
        // Rethrowing the last one would downgrade the verdict to NETWORK_TIMEOUT and suppress
        // the re-discovery that should follow.
        val scripted = listOf(
            NoRouteToHostException("EHOSTUNREACH"),
            SocketTimeoutException("Read timed out"),
            SocketTimeoutException("Read timed out"),
        )
        var attempt = 0

        var caught: Throwable? = null
        try {
            client.executeWithRetry<Unit>(maxAttempts = 3) { throw scripted[attempt++] }
        } catch (e: Throwable) {
            caught = e
        }

        assertEquals(3, attempt, "all attempts must still be spent")
        assertInstanceOf(NoRouteToHostException::class.java, caught)
    }

    @Test
    fun `executeWithRetry rethrows the last exception when none of them was unreachable`() = runTest {
        var attempt = 0
        val last = SocketTimeoutException("attempt 3")
        val scripted = listOf(SocketTimeoutException("attempt 1"), SocketTimeoutException("attempt 2"), last)

        var caught: Throwable? = null
        try {
            client.executeWithRetry<Unit>(maxAttempts = 3) { throw scripted[attempt++] }
        } catch (e: Throwable) {
            caught = e
        }

        assertEquals(last, caught)
    }

    @Test
    fun `executeWithRetry returns the first successful attempt without retrying`() = runTest {
        var attempt = 0

        val value = client.executeWithRetry(maxAttempts = 3) {
            attempt++
            if (attempt == 1) throw IOException("transient") else "ok"
        }

        assertEquals("ok", value)
        assertEquals(2, attempt)
    }

    // ── fullSync: C4 discovery resolution chain ────────────────────────────

    @Test
    fun `fullSync with no host and no tls fingerprint returns DeviceUnreachable without persisting a host`() = runTest {
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(true)
        // No lastKnownHost at all: attemptSyncCycle is skipped entirely, resolveHost is
        // tried, but bails immediately since a TLS pin is required and there is none.
        val deviceNoHostNoFingerprint = validDevice.copy(lastKnownHost = null, tlsCertFingerprint = null)

        val result = client.fullSync(deviceNoHostNoFingerprint)

        assertInstanceOf(SyncResult.DeviceUnreachable::class.java, result)
        coVerify(exactly = 0) { enrolledDeviceRepository.updateLastKnownHost(any(), any()) }
    }

    @Test
    fun `fullSync with no host tries LAN discovery at most once and does not persist when nothing validates`() = runTest {
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(true)
        // deviceName contains a space so the best-effort hostname guess is skipped:
        // resolution goes straight to (mocked) LAN discovery, avoiding any real socket I/O.
        val deviceNoHost = validDevice.copy(lastKnownHost = null)
        coEvery { lanDiscoveryClient.discover(deviceNoHost) } returns emptyList()

        val result = client.fullSync(deviceNoHost)

        assertInstanceOf(SyncResult.DeviceUnreachable::class.java, result)
        coVerify(exactly = 1) { lanDiscoveryClient.discover(deviceNoHost) }
        coVerify(exactly = 0) { enrolledDeviceRepository.updateLastKnownHost(any(), any()) }
    }

    @Test
    fun `fullSync does not persist a discovered candidate whose authenticated validation fails`() = runTest {
        // End-to-end on the security invariant: discovery answers with an address, but the
        // TLS-pinned + HMAC-signed GET /sync/status against it never validates. 192.0.2.1 is
        // TEST-NET-1 (RFC 5737), guaranteed unroutable, so the per-candidate withTimeout fires
        // (virtual time under runTest) and the candidate is rejected.
        every { networkMonitor.isOnLanTransport } returns MutableStateFlow(true)
        coEvery { secretStore.retrieve(validDevice.deviceId) } returns ByteArray(32)
        val deviceNoHost = validDevice.copy(lastKnownHost = null)
        coEvery { lanDiscoveryClient.discover(deviceNoHost) } returns listOf("192.0.2.1")

        val result = client.fullSync(deviceNoHost)

        assertInstanceOf(SyncResult.DeviceUnreachable::class.java, result)
        coVerify(exactly = 1) { lanDiscoveryClient.discover(deviceNoHost) }
        coVerify(exactly = 0) { enrolledDeviceRepository.updateLastKnownHost(any(), any()) }
    }

    // ── persistResolvedHostIfSuccessful: security invariant ────────────────
    //
    // A re-discovered host must be persisted ONLY after a full authenticated sync
    // succeeds, never from the UDP discovery ACK alone. These tests exercise that
    // choke point directly (see LanSyncClient KDoc), independent of any HTTP/socket I/O.

    @Test
    fun `persistResolvedHostIfSuccessful does not persist when result is not Success`() = runTest {
        client.persistResolvedHostIfSuccessful("device-1", "192.168.1.50", SyncResult.DeviceUnreachable)
        client.persistResolvedHostIfSuccessful("device-1", "192.168.1.50", SyncResult.AuthFailed)
        client.persistResolvedHostIfSuccessful(
            "device-1",
            "192.168.1.50",
            SyncResult.Error(SyncError.HOST_UNREACHABLE, "Host unreachable"),
        )

        coVerify(exactly = 0) { enrolledDeviceRepository.updateLastKnownHost(any(), any()) }
    }

    @Test
    fun `persistResolvedHostIfSuccessful persists only after a Success result`() = runTest {
        val success = SyncResult.Success(
            pushedCount = 1,
            pulledCount = 1,
            conflicts = emptyList(),
            syncedAt = System.currentTimeMillis(),
        )

        client.persistResolvedHostIfSuccessful("device-1", "192.168.1.50", success)

        coVerify(exactly = 1) { enrolledDeviceRepository.updateLastKnownHost("device-1", "192.168.1.50") }
    }
}
