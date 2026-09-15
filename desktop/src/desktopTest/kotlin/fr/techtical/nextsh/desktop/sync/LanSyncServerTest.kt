// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.desktop.core.sync.DesktopSyncScheduler
import fr.techtical.nextsh.shared.core.sync.ApplyResult
import fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.HmacSigner
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.core.sync.SyncBundle
import fr.techtical.nextsh.shared.core.sync.SyncBundleCodec
import fr.techtical.nextsh.shared.core.sync.SyncPayload
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncRepository
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.util.AppScope
import io.ktor.client.request.headers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests d'integration in-process pour les routes sync de LanSyncServer.
 *
 * Le body HMAC est signe avec un secret statique de test (32 bytes).
 * Le ktor-server-test-host monte un serveur en memoire, sans TLS.
 */
class LanSyncServerTest {

    private val testSecret = ByteArray(32) { it.toByte() }
    private val testDeviceId = "device-test-uuid-1234"
    private val testLocalDeviceId = "desktop-device-uuid-5678"

    private val testDevice = EnrolledDevice(
        deviceId = testDeviceId,
        deviceName = "Test Android",
        platform = Platform.ANDROID,
        publicKeyFingerprint = "fp-test",
        tlsCertFingerprint = null,
        lastSyncAt = null,
        enrolledAt = 1_000_000L,
    )

    private fun signedHeaders(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        secret: ByteArray = testSecret,
    ): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val sig = HmacSigner.sign(method, path, timestamp, body, secret)
        return mapOf(
            "X-NextSH-Device-Id" to testDeviceId,
            "X-NextSH-Timestamp" to timestamp.toString(),
            "X-NextSH-Signature" to sig,
        )
    }

    private fun buildDeps(
        enrolledDeviceRepository: EnrolledDeviceRepository,
        syncRepository: SyncRepository,
    ): LanSyncServerDeps {
        // EnrolledDeviceSecretStore est final : on le mocke avec MockK
        val secretStore = mockk<EnrolledDeviceSecretStore>(relaxed = true)
        // Par defaut retourne null, sauf pour le device de test
        coEvery { secretStore.retrieve(any()) } returns null
        coEvery { secretStore.retrieve(testDeviceId) } returns testSecret.copyOf()

        val fakePendingConflictRepository = object : PendingConflictRepository {
            override fun observeCount(): Flow<Int> = MutableStateFlow(0)
            override suspend fun getAll(): List<PendingConflict> = emptyList()
            override fun observeAll(): Flow<List<PendingConflict>> = MutableStateFlow(emptyList())
            override suspend fun save(conflict: PendingConflict) = Unit
            override suspend fun deleteById(id: String) = Unit
            override suspend fun deleteByEntity(entityType: SyncableEntityType, entityId: String) = Unit
            override suspend fun deleteAll() = Unit
        }

        return LanSyncServerDeps(
            enrolledDeviceRepository = enrolledDeviceRepository,
            secretStore = secretStore,
            syncRepository = syncRepository,
            credentialSyncRepository = io.mockk.mockk(relaxed = true),
            localDeviceId = testLocalDeviceId,
            syncScheduler = DesktopSyncScheduler(fakePendingConflictRepository),
        )
    }

    @Test
    fun `push endpoint decrypts bundle and applies via syncRepository`() = testApplication {
        val syncRepository = mockk<SyncRepository>()
        val enrolledDeviceRepository = mockk<EnrolledDeviceRepository>()

        coEvery { enrolledDeviceRepository.getByDeviceId(testDeviceId) } returns testDevice
        coEvery { enrolledDeviceRepository.updateLastSyncAt(testDeviceId, any()) } returns Unit
        coEvery { syncRepository.applyRemoteBundle(any()) } returns ApplyResult(
            cleanApplied = 3,
            conflicts = emptyList(),
        )

        val deps = buildDeps(enrolledDeviceRepository, syncRepository)

        application {
            install(ContentNegotiation) { json() }
            routing { syncRoutes(deps) }
        }

        val bundle = SyncBundle.EMPTY
        val payload = SyncBundleCodec.encrypt(bundle, testDeviceId, testSecret)
        val bodyBytes = Json.encodeToString(SyncPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)

        val headers = signedHeaders("POST", "/sync/push", bodyBytes)

        val response = client.post("/sync/push") {
            contentType(ContentType.Application.Json)
            headers {
                headers.forEach { (k, v) -> append(k, v) }
            }
            setBody(String(bodyBytes))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val responseBody = response.bodyAsText()
        assertNotNull(responseBody)
        assertTrue(responseBody.contains("\"cleanApplied\":3"), "Expected cleanApplied=3 in response, got: $responseBody")
        assertTrue(responseBody.contains("\"conflictsCount\":0"), "Expected conflictsCount=0 in response, got: $responseBody")

        coVerify(exactly = 1) { syncRepository.applyRemoteBundle(any()) }
        coVerify(exactly = 1) { enrolledDeviceRepository.updateLastSyncAt(testDeviceId, any()) }
    }

    @Test
    fun `request without valid HMAC is rejected with 401`() = testApplication {
        val syncRepository = mockk<SyncRepository>()
        val enrolledDeviceRepository = mockk<EnrolledDeviceRepository>()

        coEvery { enrolledDeviceRepository.getByDeviceId(testDeviceId) } returns testDevice

        val deps = buildDeps(enrolledDeviceRepository, syncRepository)

        application {
            install(ContentNegotiation) { json() }
            routing { syncRoutes(deps) }
        }

        val bundle = SyncBundle.EMPTY
        val payload = SyncBundleCodec.encrypt(bundle, testDeviceId, testSecret)
        val bodyBytes = Json.encodeToString(SyncPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)

        val responseNoHeaders = client.post("/sync/push") {
            contentType(ContentType.Application.Json)
            setBody(String(bodyBytes))
        }
        assertEquals(HttpStatusCode.Unauthorized, responseNoHeaders.status)

        val timestamp = System.currentTimeMillis()
        val responseInvalidSig = client.post("/sync/push") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-NextSH-Device-Id", testDeviceId)
                append("X-NextSH-Timestamp", timestamp.toString())
                append("X-NextSH-Signature", "invalidsignature0000000000000000000000000000000000000000000000000")
            }
            setBody(String(bodyBytes))
        }
        assertEquals(HttpStatusCode.Unauthorized, responseInvalidSig.status)
    }

    // ── At-bind gate (HIGH-1) ────────────────────────────────────────────────
    //
    // Exercised against the REAL LanSyncServer. The previous version of this regression test
    // lived in LanSyncServerLifecycleTest and called start() on the test's own FakeSyncServer,
    // so it only asserted that the fake honoured its own scripted `if (!shouldRun())` branch:
    // it could never have caught a production server that ignored the predicate.

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start with the gate closed at bind time leaves the server stopped and binds nothing`() = runTest {
        mockkObject(LanAddressDetector)
        try {
            // Pin the detected address to loopback so the assertion below can prove nothing was
            // bound without ever touching a real network interface.
            every { LanAddressDetector.detect() } returns LanAddressDetector.LanInterface(
                address = InetAddress.getByName("127.0.0.1"),
                displayName = "test-loopback",
            )

            val server = buildRealServer(this)

            server.start(shouldRun = { false })
            advanceUntilIdle()

            assertTrue(
                server.state.value is LanSyncServer.State.Stopped,
                "gate closed at bind → the server must not reach Listening, got ${server.state.value}",
            )
            // Direct proof that no TLS endpoint was exposed: the sync port is still free on the
            // very address the server would have bound.
            ServerSocket().use { probe ->
                probe.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), SYNC_PORT_UNDER_TEST))
            }
        } finally {
            unmockkObject(LanAddressDetector)
        }
    }

    /** Mirrors the private SYNC_PORT constant in LanSyncServer. */
    private val SYNC_PORT_UNDER_TEST = 47731

    /**
     * A real [LanSyncServer] wired to mocks. Only [TlsCertificateManager] needs a meaningful
     * stub: an empty in-memory PKCS12 is enough because the connector config is never read,
     * the gate closes before `srv.start()`.
     */
    private fun buildRealServer(scope: CoroutineScope): LanSyncServer {
        val tlsCertificateManager = mockk<TlsCertificateManager>()
        coEvery { tlsCertificateManager.loadOrGenerate() } returns TlsMaterial(
            certificate = mockk(relaxed = true),
            privateKey = mockk(relaxed = true),
            keystore = KeyStore.getInstance("PKCS12").apply { load(null, CharArray(0)) },
            passphrase = "test-passphrase".toCharArray(),
        )

        val fakePendingConflictRepository = object : PendingConflictRepository {
            override fun observeCount(): Flow<Int> = MutableStateFlow(0)
            override suspend fun getAll(): List<PendingConflict> = emptyList()
            override fun observeAll(): Flow<List<PendingConflict>> = MutableStateFlow(emptyList())
            override suspend fun save(conflict: PendingConflict) = Unit
            override suspend fun deleteById(id: String) = Unit
            override suspend fun deleteByEntity(entityType: SyncableEntityType, entityId: String) = Unit
            override suspend fun deleteAll() = Unit
        }

        return LanSyncServer(
            appScope = object : AppScope {
                override val coroutineScope: CoroutineScope = scope
                override fun onDestroy() = Unit
            },
            tlsCertificateManager = tlsCertificateManager,
            enrolledDeviceRepository = mockk(relaxed = true),
            secretStore = mockk(relaxed = true),
            syncRepository = mockk<SyncRepository>(relaxed = true),
            credentialSyncRepository = mockk<CredentialSyncRepository>(relaxed = true),
            deviceIdentity = DeviceIdentity,
            syncScheduler = DesktopSyncScheduler(fakePendingConflictRepository),
        )
    }
}
