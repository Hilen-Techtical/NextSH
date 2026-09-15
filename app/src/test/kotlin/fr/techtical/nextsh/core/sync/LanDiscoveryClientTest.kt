// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import fr.techtical.nextsh.shared.core.sync.DiscoveryProtocol
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.Platform
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Unit tests for [LanDiscoveryClient].
 *
 * The real send/receive loop needs a bound UDP socket and a peer to answer it, which is
 * architecturally awkward in a unit test (mirrors the same limitation documented in
 * [LanSyncClientTest]). Instead:
 * - [LanDiscoveryClient.extractCandidate] and [LanDiscoveryClient.addCandidate] (the pure,
 *   socket-free response-processing helpers) are tested directly.
 * - [LanDiscoveryClient.discover]'s vault-locked / secret-not-found guard clauses are
 *   tested end-to-end since they return before any socket is touched.
 */
@OptIn(ExperimentalEncodingApi::class)
class LanDiscoveryClientTest {

    private val testDevice = EnrolledDevice(
        deviceId = "desktop-test-1",
        deviceName = "Test Desktop",
        platform = Platform.DESKTOP,
        publicKeyFingerprint = "fp",
        tlsCertFingerprint = "a".repeat(64),
        lastSyncAt = null,
        enrolledAt = 1_700_000_000_000L,
        lastKnownHost = null,
    )

    /** TCP sync port carried by discovery responses in these tests. */
    private val syncPort = 47731

    // ── extractCandidate: pure response processing ─────────────────────────

    @Test
    fun `extractCandidate returns the source IP for a validly signed response`() {
        val secret = ByteArray(32) { 1 }
        val nonceB64 = Base64.encode(DiscoveryProtocol.randomNonce())
        val json = DiscoveryProtocol.buildResponseMessage(secret, nonceB64, System.currentTimeMillis(), syncPort)

        val candidate = LanDiscoveryClient.extractCandidate(
            data = json.toByteArray(Charsets.UTF_8),
            sourceIp = "192.168.1.42",
            expectedNonceB64 = nonceB64,
            secret = secret,
        )

        assertEquals("192.168.1.42", candidate)
    }

    @Test
    fun `extractCandidate ignores a response whose timestamp has aged out of the window`() {
        // A response captured on the LAN and replayed later must not resurrect a stale address.
        val secret = ByteArray(32) { 1 }
        val nonceB64 = Base64.encode(DiscoveryProtocol.randomNonce())
        val signedAt = 1_700_000_000_000L
        val json = DiscoveryProtocol.buildResponseMessage(secret, nonceB64, signedAt, syncPort)

        val candidate = LanDiscoveryClient.extractCandidate(
            data = json.toByteArray(Charsets.UTF_8),
            sourceIp = "192.168.1.42",
            expectedNonceB64 = nonceB64,
            secret = secret,
            now = signedAt + 10L * 60_000L, // 10 min later, outside the ±5 min window
        )

        assertNull(candidate)

        // Still inside the window it is accepted: proves the rejection above is the freshness
        // check and not some unrelated breakage in the fixture.
        assertEquals(
            "192.168.1.42",
            LanDiscoveryClient.extractCandidate(
                data = json.toByteArray(Charsets.UTF_8),
                sourceIp = "192.168.1.42",
                expectedNonceB64 = nonceB64,
                secret = secret,
                now = signedAt + 60_000L,
            ),
        )
    }

    @Test
    fun `extractCandidate ignores a response signed with the wrong secret`() {
        val realSecret = ByteArray(32) { 1 }
        val wrongSecret = ByteArray(32) { 2 }
        val nonceB64 = Base64.encode(DiscoveryProtocol.randomNonce())
        val json = DiscoveryProtocol.buildResponseMessage(wrongSecret, nonceB64, System.currentTimeMillis(), syncPort)

        val candidate = LanDiscoveryClient.extractCandidate(
            data = json.toByteArray(Charsets.UTF_8),
            sourceIp = "192.168.1.42",
            expectedNonceB64 = nonceB64,
            secret = realSecret,
        )

        assertNull(candidate)
    }

    @Test
    fun `extractCandidate ignores a response echoing an unexpected nonce`() {
        val secret = ByteArray(32) { 1 }
        val echoedNonceB64 = Base64.encode(DiscoveryProtocol.randomNonce())
        val json = DiscoveryProtocol.buildResponseMessage(secret, echoedNonceB64, System.currentTimeMillis(), syncPort)
        val expectedNonceB64 = Base64.encode(DiscoveryProtocol.randomNonce()) // different nonce than what was echoed

        val candidate = LanDiscoveryClient.extractCandidate(
            data = json.toByteArray(Charsets.UTF_8),
            sourceIp = "192.168.1.42",
            expectedNonceB64 = expectedNonceB64,
            secret = secret,
        )

        assertNull(candidate)
    }

    @Test
    fun `extractCandidate ignores malformed bytes`() {
        val candidate = LanDiscoveryClient.extractCandidate(
            data = "not a discovery response".toByteArray(Charsets.UTF_8),
            sourceIp = "192.168.1.42",
            expectedNonceB64 = "irrelevant",
            secret = ByteArray(32),
        )

        assertNull(candidate)
    }

    // ── addCandidate: dedup + cap ───────────────────────────────────────────

    @Test
    fun `addCandidate deduplicates by IP`() {
        val list = mutableListOf<String>()

        LanDiscoveryClient.addCandidate(list, "192.168.1.1")
        LanDiscoveryClient.addCandidate(list, "192.168.1.1")

        assertEquals(listOf("192.168.1.1"), list)
    }

    @Test
    fun `addCandidate caps at the configured maximum, keeping arrival order`() {
        val list = mutableListOf<String>()

        for (i in 1..7) LanDiscoveryClient.addCandidate(list, "192.168.1.$i", max = 5)

        assertEquals(5, list.size)
        assertEquals(listOf("192.168.1.1", "192.168.1.2", "192.168.1.3", "192.168.1.4", "192.168.1.5"), list)
    }

    // ── discover: guard clauses (no socket touched) ─────────────────────────

    @Test
    fun `discover returns an empty list without probing when the vault is locked`() = runTest {
        val secretStore = mockk<EnrolledDeviceSecretStore>()
        coEvery { secretStore.retrieve(testDevice.deviceId) } throws
            fr.techtical.nextsh.core.vault.VaultAuthExpiredException()
        val client = LanDiscoveryClient(secretStore)

        val result = client.discover(testDevice)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `discover returns an empty list without probing when no secret is stored`() = runTest {
        val secretStore = mockk<EnrolledDeviceSecretStore>()
        coEvery { secretStore.retrieve(testDevice.deviceId) } returns null
        val client = LanDiscoveryClient(secretStore)

        val result = client.discover(testDevice)

        assertTrue(result.isEmpty())
        coVerify(exactly = 1) { secretStore.retrieve(testDevice.deviceId) }
    }
}
