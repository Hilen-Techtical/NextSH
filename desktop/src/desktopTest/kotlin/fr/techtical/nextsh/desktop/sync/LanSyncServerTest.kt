// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.desktop.assertNothingScrubbed
import fr.techtical.nextsh.desktop.captureLogs
import fr.techtical.nextsh.desktop.core.sync.DesktopSyncScheduler
import fr.techtical.nextsh.desktop.taggedBy
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
import fr.techtical.nextsh.shared.util.LogId
import io.ktor.client.request.get
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
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        // A fresh copy per call: the routes wipe the secret they receive, so a shared array
        // would be zeroed by the first request and break every later one in the same test.
        coEvery { secretStore.retrieve(testDeviceId) } answers { testSecret.copyOf() }

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

    @Test
    fun `rejections before authentication log a reason and never the claimed device id`() = testApplication {
        val claimedUnknownId = "unknown-claimed-device"
        val enrolledDeviceRepository = mockk<EnrolledDeviceRepository>()
        coEvery { enrolledDeviceRepository.getByDeviceId(testDeviceId) } returns testDevice
        coEvery { enrolledDeviceRepository.getByDeviceId(claimedUnknownId) } returns null
        val deps = buildDeps(enrolledDeviceRepository, mockk<SyncRepository>())

        application {
            install(ContentNegotiation) { json() }
            routing { syncRoutes(deps) }
        }

        val now = System.currentTimeMillis().toString()
        val captured = captureLogs {
            client.get("/sync/status")
            client.get("/sync/status") {
                headers {
                    append("X-NextSH-Device-Id", testDeviceId)
                    append("X-NextSH-Timestamp", "not-a-number")
                    append("X-NextSH-Signature", "sig")
                }
            }
            client.get("/sync/status") {
                headers {
                    append("X-NextSH-Device-Id", claimedUnknownId)
                    append("X-NextSH-Timestamp", now)
                    append("X-NextSH-Signature", "sig")
                }
            }
            client.get("/sync/status") {
                headers {
                    append("X-NextSH-Device-Id", testDeviceId)
                    append("X-NextSH-Timestamp", now)
                    append("X-NextSH-Signature", "invalidsignature0000000000000000000000000000000000000000000000000")
                }
            }
        }

        // The sink is global: judge the server's own lines for content, and every line for the leak.
        val lines = captured.taggedBy("LanSyncServer")
        listOf("MISSING_HEADERS", "INVALID_TIMESTAMP", "UNKNOWN_DEVICE", "BAD_SIGNATURE").forEach { reason ->
            assertTrue(lines.any { it.contains("reason=$reason") }, "expected a rejection line with reason=$reason, got $captured")
        }
        assertTrue(
            captured.none { it.contains(claimedUnknownId) || it.contains(testDeviceId) },
            "a pre-authentication rejection must not print the claimed device id: $captured",
        )
        assertNothingScrubbed(lines)
    }

    @Test
    fun `authenticated requests log the device id only as a LogId fingerprint`() = testApplication {
        val enrolledDeviceRepository = mockk<EnrolledDeviceRepository>()
        coEvery { enrolledDeviceRepository.getByDeviceId(testDeviceId) } returns testDevice
        val deps = buildDeps(enrolledDeviceRepository, mockk<SyncRepository>())

        application {
            install(ContentNegotiation) { json() }
            routing { syncRoutes(deps) }
        }

        val captured = captureLogs {
            val response = client.get("/sync/status") {
                headers { signedHeaders("GET", "/sync/status").forEach { (k, v) -> append(k, v) } }
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        // The route logs before it replies, so the line is there as soon as the client has its answer.
        val lines = captured.taggedBy("LanSyncServer")
        val statusLine = lines.singleOrNull { it.contains("sync status (GET)") }
        assertNotNull(statusLine, "expected the status debug line, got $captured")
        assertTrue(statusLine.contains("device=${LogId.of(testDeviceId)}"), statusLine)
        assertTrue(captured.none { it.contains(testDeviceId) }, "raw device id leaked: $captured")
        assertNothingScrubbed(lines)
    }

    // ── Pre-body checks on POST /sync/push ───────────────────────────────────

    /** Body whose DECLARED length is [declared] but whose real bytes are tiny: never streamed. */
    private fun lyingBody(declared: Long?, bytes: ByteArray = "{}".toByteArray()): OutgoingContent =
        object : OutgoingContent.ByteArrayContent() {
            override val contentType: ContentType = ContentType.Application.Json
            override val contentLength: Long? = declared
            override fun bytes(): ByteArray = bytes
        }

    private fun io.ktor.server.testing.ApplicationTestBuilder.mountRoutes(
        enrolled: EnrolledDeviceRepository,
        sync: SyncRepository,
    ): LanSyncServerDeps {
        val deps = buildDeps(enrolled, sync)
        application {
            install(ContentNegotiation) { json() }
            routing { syncRoutes(deps) }
        }
        return deps
    }

    @Test
    fun `push with a declared body above the cap is refused with 401 and applies nothing`() = testApplication {
        val syncRepository = mockk<SyncRepository>()
        val enrolled = mockk<EnrolledDeviceRepository>()
        coEvery { enrolled.getByDeviceId(testDeviceId) } returns testDevice
        mountRoutes(enrolled, syncRepository)

        val response = client.post("/sync/push") {
            headers { signedHeaders("POST", "/sync/push").forEach { (k, v) -> append(k, v) } }
            setBody(lyingBody(SYNC_PUSH_MAX_BODY_BYTES + 1))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        coVerify(exactly = 0) { syncRepository.applyRemoteBundle(any()) }
    }

    @Test
    fun `push without Content-Length is refused with 401`() = testApplication {
        val syncRepository = mockk<SyncRepository>()
        val enrolled = mockk<EnrolledDeviceRepository>()
        coEvery { enrolled.getByDeviceId(testDeviceId) } returns testDevice
        mountRoutes(enrolled, syncRepository)

        val body = "{}".toByteArray()
        val response = client.post("/sync/push") {
            headers { signedHeaders("POST", "/sync/push", body).forEach { (k, v) -> append(k, v) } }
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeFully(body)
                }
            })
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        coVerify(exactly = 0) { syncRepository.applyRemoteBundle(any()) }
    }

    @Test
    fun `push from an unknown device is refused before the body is read`() = testApplication {
        val syncRepository = mockk<SyncRepository>()
        val enrolled = mockk<EnrolledDeviceRepository>()
        coEvery { enrolled.getByDeviceId(any()) } returns null
        val deps = mountRoutes(enrolled, syncRepository)

        // Oversize AND unknown: both refuse, and the secret store is never consulted.
        val response = client.post("/sync/push") {
            headers { signedHeaders("POST", "/sync/push").forEach { (k, v) -> append(k, v) } }
            setBody(lyingBody(SYNC_PUSH_MAX_BODY_BYTES))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        coVerify(exactly = 0) { deps.secretStore.retrieve(any()) }
        coVerify(exactly = 0) { syncRepository.applyRemoteBundle(any()) }
    }

    @Test
    fun `push outside the timestamp window or without signed headers is refused`() = testApplication {
        val syncRepository = mockk<SyncRepository>()
        val enrolled = mockk<EnrolledDeviceRepository>()
        coEvery { enrolled.getByDeviceId(testDeviceId) } returns testDevice
        mountRoutes(enrolled, syncRepository)

        val body = "{}".toByteArray()
        val stale = System.currentTimeMillis() - 10 * 60_000L
        val staleResponse = client.post("/sync/push") {
            headers {
                append("X-NextSH-Device-Id", testDeviceId)
                append("X-NextSH-Timestamp", stale.toString())
                append("X-NextSH-Signature", HmacSigner.sign("POST", "/sync/push", stale, body, testSecret))
            }
            setBody(lyingBody(body.size.toLong(), body))
        }
        assertEquals(HttpStatusCode.Unauthorized, staleResponse.status)

        val unsigned = client.post("/sync/push") { setBody(lyingBody(body.size.toLong(), body)) }
        assertEquals(HttpStatusCode.Unauthorized, unsigned.status)
        coVerify(exactly = 0) { syncRepository.applyRemoteBundle(any()) }
    }

    @Test
    fun `push whose body is shorter than its declared length is refused`() = testApplication {
        val syncRepository = mockk<SyncRepository>()
        val enrolled = mockk<EnrolledDeviceRepository>()
        coEvery { enrolled.getByDeviceId(testDeviceId) } returns testDevice
        mountRoutes(enrolled, syncRepository)

        val body = "{}".toByteArray()
        val response = client.post("/sync/push") {
            headers { signedHeaders("POST", "/sync/push", body).forEach { (k, v) -> append(k, v) } }
            setBody(lyingBody(body.size.toLong() + 5, body))
        }

        assertNotEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 0) { syncRepository.applyRemoteBundle(any()) }
    }

    @Test
    fun `push is refused with 503 without reading the body when no permit is free`() = testApplication {
        val syncRepository = mockk<SyncRepository>()
        val enrolled = mockk<EnrolledDeviceRepository>()
        coEvery { enrolled.getByDeviceId(testDeviceId) } returns testDevice
        coEvery { enrolled.updateLastSyncAt(testDeviceId, any()) } returns Unit
        coEvery { syncRepository.applyRemoteBundle(any()) } returns ApplyResult(cleanApplied = 1, conflicts = emptyList())
        val deps = buildDeps(enrolled, syncRepository)
        val gate = Semaphore(1)
        application {
            install(ContentNegotiation) { json() }
            routing { syncRoutes(deps, gate) }
        }

        val payload = SyncBundleCodec.encrypt(SyncBundle.EMPTY, testDeviceId, testSecret)
        val body = Json.encodeToString(SyncPayload.serializer(), payload).toByteArray(Charsets.UTF_8)

        assertTrue(gate.tryAcquire())
        val refused = client.post("/sync/push") {
            headers { signedHeaders("POST", "/sync/push", body).forEach { (k, v) -> append(k, v) } }
            setBody(lyingBody(body.size.toLong(), body))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
        assertEquals("", refused.bodyAsText())
        coVerify(exactly = 0) { syncRepository.applyRemoteBundle(any()) }

        // The permit is returned after each request: once free again, the same push goes through,
        // and a second one too (no permit leak on the success path).
        gate.release()
        repeat(2) {
            val ok = client.post("/sync/push") {
                headers { signedHeaders("POST", "/sync/push", body).forEach { (k, v) -> append(k, v) } }
                setBody(lyingBody(body.size.toLong(), body))
            }
            assertEquals(HttpStatusCode.OK, ok.status)
        }
    }

    @Test
    fun `status and pull still refuse unsigned requests and serve an enrolled device`() = testApplication {
        val syncRepository = mockk<SyncRepository>()
        val enrolled = mockk<EnrolledDeviceRepository>()
        coEvery { enrolled.getByDeviceId(testDeviceId) } returns testDevice
        coEvery { syncRepository.getAllLocalAsBundle() } returns SyncBundle.EMPTY
        mountRoutes(enrolled, syncRepository)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/sync/status").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/sync/pull").status)

        val status = client.get("/sync/status") {
            headers { signedHeaders("GET", "/sync/status").forEach { (k, v) -> append(k, v) } }
        }
        assertEquals(HttpStatusCode.OK, status.status)
        val pull = client.get("/sync/pull") {
            headers { signedHeaders("GET", "/sync/pull").forEach { (k, v) -> append(k, v) } }
        }
        assertEquals(HttpStatusCode.OK, pull.status)
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
