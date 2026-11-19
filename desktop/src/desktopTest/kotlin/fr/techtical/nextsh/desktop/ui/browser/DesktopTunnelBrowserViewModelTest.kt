// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.desktop.core.ssh.SshClientFactory
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DesktopTunnelBrowserViewModel].
 *
 * [DesktopTunnelManager] is constructed with a fake [SshClientFactory] that
 * always fails: we never start real tunnels, we only seed [tunnelStates] via
 * direct [MutableStateFlow] assignment in the manager itself.
 *
 * [TunnelRepository] and [StopTunnelUseCase] are MockK mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopTunnelBrowserViewModelTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val testDispatcher = UnconfinedTestDispatcher()

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private fun makeScope() =
        CoroutineScope(testDispatcher + SupervisorJob())

    private fun makeTunnelConfig(
        id: String = "t1",
        localPort: Int = 8080,
        keepAlive: Boolean = false,
        browserPath: String = "/",
    ) = TunnelConfig(
        id = id,
        label = "Test Tunnel",
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = localPort,
        remoteHost = "localhost",
        remotePort = 80,
        keepAliveAfterBrowserClose = keepAlive,
        browserPath = browserPath,
    )

    private fun makeHost() = Host(
        id = "host-1",
        label = "Test Host",
        hostname = "example.com",
        port = 22,
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private class FakeTunnelRepository(
        private val configs: Map<String, TunnelConfig> = emptyMap(),
    ) : TunnelRepository {
        override fun observeAll(): Flow<List<TunnelConfig>> =
            MutableStateFlow(configs.values.toList()).asStateFlow()
        override fun observeByHost(hostId: String): Flow<List<TunnelConfig>> =
            MutableStateFlow(emptyList<TunnelConfig>()).asStateFlow()
        override suspend fun getById(id: String): TunnelConfig? = configs[id]
        override suspend fun getAutoStartTunnels(): List<TunnelConfig> = emptyList()
        override suspend fun save(config: TunnelConfig) = Unit
        override suspend fun update(config: TunnelConfig) = Unit
        override suspend fun delete(id: String) = Unit
        override fun observeFavorites(): Flow<List<TunnelConfig>> =
            MutableStateFlow(emptyList<TunnelConfig>()).asStateFlow()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private fun makeManager(scope: CoroutineScope): DesktopTunnelManager {
        val factory = SshClientFactory { _, _ -> SshResult.Error(fr.techtical.nextsh.shared.domain.model.SshErrorCode.UNKNOWN, "test") }
        return DesktopTunnelManager(scope = scope, clientFactory = factory)
    }

    private fun makeViewModel(
        tunnelRepository: TunnelRepository,
        tunnelManager: DesktopTunnelManager,
        stopTunnelUseCase: StopTunnelUseCase,
        appScope: AppScope,
        clock: () -> Long = System::currentTimeMillis,
    ) = DesktopTunnelBrowserViewModel(
        tunnelRepository = tunnelRepository,
        tunnelManager = tunnelManager,
        stopTunnel = stopTunnelUseCase,
        sessionHolder = DesktopBrowserSessionHolder(),
        appScope = appScope,
        clock = clock,
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `isLocalhostUrl returns true for loopback addresses and false otherwise`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )

        assertTrue(vm.isLocalhostUrl("http://127.0.0.1:8080"))
        assertTrue(vm.isLocalhostUrl("http://localhost/foo"))
        assertTrue(vm.isLocalhostUrl("https://[::1]"))
        assertFalse(vm.isLocalhostUrl("https://example.com"))
        assertFalse(vm.isLocalhostUrl("not-a-url"))
    }

    @Test
    fun `loadTunnel populates initialUrl from tunnel localPort`() = runTest(testDispatcher) {
        val config = makeTunnelConfig(id = "t1", localPort = 8080)
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(mapOf("t1" to config)),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(backgroundScope),
        )

        vm.loadTunnel("t1")

        // The default path is the root, so the URL ends with a slash.
        assertEquals("http://127.0.0.1:8080/", vm.uiState.value.initialUrl)
        assertEquals("http://127.0.0.1:8080/", vm.uiState.value.currentUrl)
        assertEquals(config, vm.uiState.value.tunnelConfig)
    }

    @Test
    fun `loadTunnel appends the tunnel start path to the loopback URL`() = runTest(testDispatcher) {
        val config = makeTunnelConfig(id = "t1", localPort = 3000, browserPath = "/d/abc?orgId=1#top")
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(mapOf("t1" to config)),
            tunnelManager = makeManager(makeScope()),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(backgroundScope),
        )

        vm.loadTunnel("t1")

        assertEquals("http://127.0.0.1:3000/d/abc?orgId=1#top", vm.uiState.value.initialUrl)
        assertEquals("http://127.0.0.1:3000/d/abc?orgId=1#top", vm.uiState.value.currentUrl)
    }

    @Test
    fun `loadTunnel opens the root when the stored start path is invalid`() = runTest(testDispatcher) {
        // A sync peer or an old backup can carry anything: an invalid path never reaches the URL.
        for (path in listOf("/a b", "/a\\b", "/a%zz", "/a#b#c")) {
            val vm = loadedViewModel(path)

            assertEquals("http://127.0.0.1:8080/", vm.uiState.value.initialUrl, "length ${path.length}")
        }
    }

    @Test
    fun `loadTunnel keeps a valid but odd start path after the loopback authority`() = runTest(testDispatcher) {
        // These pass validation: they are appended as a path, so the authority stays 127.0.0.1:8080.
        val expected = mapOf(
            "//evil.example/x" to "http://127.0.0.1:8080//evil.example/x",
            "@evil.example" to "http://127.0.0.1:8080/@evil.example",
            "" to "http://127.0.0.1:8080/",
        )
        for ((path, url) in expected) {
            val vm = loadedViewModel(path)

            assertEquals(url, vm.uiState.value.initialUrl, "length ${path.length}")
            assertTrue(vm.isLocalhostUrl(url), "length ${path.length}")
        }
    }

    /** A view model that already loaded tunnel t1 (port 8080) stored with [browserPath]. */
    private fun TestScope.loadedViewModel(browserPath: String): DesktopTunnelBrowserViewModel {
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(
                mapOf("t1" to makeTunnelConfig(id = "t1", localPort = 8080, browserPath = browserPath)),
            ),
            tunnelManager = makeManager(makeScope()),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(backgroundScope),
        )
        vm.loadTunnel("t1")
        return vm
    }

    @Test
    fun `the tunnel URL with a start path is internal and never triggers the external warning`() =
        runTest(testDispatcher) {
            val paths = listOf("/", "/admin", "/admin/", "/?token=abc", "/a?x=1&y=2#frag", "//double/slash", "/@user")
            for (path in paths) {
                val vm = loadedViewModel(path)
                val url = vm.uiState.value.initialUrl

                assertTrue(vm.isLocalhostUrl(url), "length ${path.length}")
                assertFalse(vm.shouldCancelNavigation(url), "length ${path.length}")
                assertTrue(vm.onNavigationRequested(url), "length ${path.length}")
                assertFalse(vm.uiState.value.showExternalNavWarning, "length ${path.length}")
                assertNull(vm.uiState.value.pendingExternalUrl, "length ${path.length}")
            }
        }

    @Test
    fun `onNavigationRequested blocks external URLs and sets warning flag`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )
        val externalUrl = "https://example.com/page"

        val allowed = vm.onNavigationRequested(externalUrl)

        assertFalse(allowed)
        assertTrue(vm.uiState.value.showExternalNavWarning)
        assertEquals(externalUrl, vm.uiState.value.pendingExternalUrl)
    }

    @Test
    fun `onNavigationRequested allows localhost URLs without warning`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )

        val allowed = vm.onNavigationRequested("http://127.0.0.1:8080/path")

        assertTrue(allowed)
        assertFalse(vm.uiState.value.showExternalNavWarning)
        assertNull(vm.uiState.value.pendingExternalUrl)
    }

    @Test
    fun `onBrowserClosed calls stopTunnel when keepAliveAfterBrowserClose is false`() =
        runTest(testDispatcher) {
            val config = makeTunnelConfig(id = "t1", keepAlive = false)
            val stopUseCase: StopTunnelUseCase = mockk(relaxed = true)
            coEvery { stopUseCase.invoke(any()) } returns SshResult.Success(Unit)

            val scope = makeScope()
            val vm = makeViewModel(
                tunnelRepository = FakeTunnelRepository(mapOf("t1" to config)),
                tunnelManager = makeManager(scope),
                stopTunnelUseCase = stopUseCase,
                appScope = TestAppScope(backgroundScope),
            )

            vm.loadTunnel("t1")
            vm.onBrowserClosed()

            coVerify(exactly = 1) { stopUseCase.invoke("t1") }
        }

    @Test
    fun `onBrowserClosed does NOT call stopTunnel when keepAliveAfterBrowserClose is true`() =
        runTest(testDispatcher) {
            val config = makeTunnelConfig(id = "t1", keepAlive = true)
            val stopUseCase: StopTunnelUseCase = mockk(relaxed = true)

            val scope = makeScope()
            val vm = makeViewModel(
                tunnelRepository = FakeTunnelRepository(mapOf("t1" to config)),
                tunnelManager = makeManager(scope),
                stopTunnelUseCase = stopUseCase,
                appScope = TestAppScope(backgroundScope),
            )

            vm.loadTunnel("t1")
            vm.onBrowserClosed()

            coVerify(exactly = 0) { stopUseCase.invoke(any()) }
        }

    @Test
    fun `loadTunnel t2 after t1 switches uiState to t2 config`() =
        runTest(testDispatcher) {
            val t1 = makeTunnelConfig(id = "t1", localPort = 8080)
            val t2 = makeTunnelConfig(id = "t2", localPort = 9090)
            val scope = makeScope()
            val vm = makeViewModel(
                tunnelRepository = FakeTunnelRepository(mapOf("t1" to t1, "t2" to t2)),
                tunnelManager = makeManager(scope),
                stopTunnelUseCase = mockk(relaxed = true),
                appScope = TestAppScope(backgroundScope),
            )

            vm.loadTunnel("t1")
            assertEquals("http://127.0.0.1:8080/", vm.uiState.value.initialUrl)

            vm.loadTunnel("t2")
            assertEquals("http://127.0.0.1:9090/", vm.uiState.value.initialUrl)
            assertEquals(t2, vm.uiState.value.tunnelConfig)
        }

    // ── shouldCancelNavigation (Phase 5 CEF handler logic) ────────────────────

    @Test
    fun `shouldCancelNavigation returns false for localhost URLs`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )

        assertFalse(vm.shouldCancelNavigation("http://127.0.0.1:8080/path"))
        assertFalse(vm.shouldCancelNavigation("http://localhost/"))
        assertFalse(vm.shouldCancelNavigation("https://[::1]/admin"))
        assertFalse(vm.uiState.value.showExternalNavWarning)
    }

    @Test
    fun `shouldCancelNavigation returns true and triggers warning for external URLs`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )
        val externalUrl = "https://cdn.example.com/resource"

        assertTrue(vm.shouldCancelNavigation(externalUrl))
        assertTrue(vm.uiState.value.showExternalNavWarning)
        assertEquals(externalUrl, vm.uiState.value.pendingExternalUrl)
    }

    @Test
    fun `shouldCancelNavigation returns true for non-HTTP schemes`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )

        // Malformed / unknown schemes must be blocked: isLocalhostUrl returns false
        assertTrue(vm.shouldCancelNavigation("javascript:alert(1)"))
        assertTrue(vm.shouldCancelNavigation("file:///etc/passwd"))
    }

    // ── HTTP authentication (Basic challenge from a tunnelled service) ─────────

    private class RecordingSender : CdpSender {
        class Command(val method: String, val params: JsonObject) {
            val requestId: String get() = params.getValue("requestId").jsonPrimitive.content
            val response: String?
                get() = (params["authChallengeResponse"] as? JsonObject)?.get("response")?.jsonPrimitive?.content
            val password: String?
                get() = (params["authChallengeResponse"] as? JsonObject)?.get("password")?.jsonPrimitive?.content
        }

        val sent = CopyOnWriteArrayList<Command>()

        override fun send(method: String, params: JsonObject) {
            sent += Command(method, params)
        }

        fun cancels() = sent.filter { it.response == "CancelAuth" }
        fun provides() = sent.filter { it.response == "ProvideCredentials" }
    }

    // Deterministic wall clock for the cancel cooldown: tests move it explicitly.
    private var now = 1_000_000L

    private fun makeAuthVm(
        configs: Map<String, TunnelConfig>,
        appScope: CoroutineScope,
    ) = makeViewModel(
        tunnelRepository = FakeTunnelRepository(configs),
        tunnelManager = makeManager(makeScope()),
        stopTunnelUseCase = mockk(relaxed = true),
        appScope = TestAppScope(appScope),
        clock = { now },
    )

    /** A view model whose tunnel t1 (port 8081) is loaded, with its HTTP authentication attached. */
    private class Attached(
        val vm: DesktopTunnelBrowserViewModel,
        val sender: RecordingSender,
        val auth: BrowserAuthCoordinator,
    ) {
        fun challenge(id: String, origin: String = "http://127.0.0.1:8081", scheme: String = "basic", realm: String = "Private area") =
            auth.onCdpEvent(
                "Fetch.authRequired",
                buildJsonObject {
                    put("requestId", id)
                    putJsonObject("authChallenge") {
                        put("source", "Server")
                        put("origin", origin)
                        put("scheme", scheme)
                        put("realm", realm)
                    }
                }.toString(),
            )

        fun promptId(): Long = assertNotNull(vm.uiState.value.authPrompt).id
    }

    private fun TestScope.attached(
        configs: Map<String, TunnelConfig> = mapOf("t1" to makeTunnelConfig(id = "t1", localPort = 8081, keepAlive = true)),
    ): Attached {
        val vm = makeAuthVm(configs, backgroundScope)
        vm.loadTunnel("t1")
        val sender = RecordingSender()
        val auth = vm.attachAuth(8081, sender)
        return Attached(vm, sender, auth)
    }

    @Test
    fun `a challenge from the tunnel publishes a prompt with the tunnel origin`() = runTest(testDispatcher) {
        val a = attached()

        a.challenge("job-1")

        val prompt = assertNotNull(a.vm.uiState.value.authPrompt)
        assertEquals("127.0.0.1:8081", prompt.origin)
        assertEquals("Private area", prompt.realm)
        assertFalse(prompt.rejected)
        assertTrue(a.sender.sent.isEmpty(), "the request waits for the user")
    }

    @Test
    fun `only the loaded tunnel's origin is prompted for`() = runTest(testDispatcher) {
        val a = attached()

        a.challenge("job-1", origin = "http://127.0.0.1:8082")
        a.challenge("job-2", origin = "http://localhost:8081")

        assertNull(a.vm.uiState.value.authPrompt)
        assertEquals(2, a.sender.cancels().size)
        assertTrue(a.sender.provides().isEmpty())
    }

    @Test
    fun `submitAuth forwards the credentials once and clears the prompt`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")
        val id = a.promptId()

        a.vm.submitAuth(id, "alice".toCharArray(), "s3cret".toCharArray())
        a.vm.submitAuth(id, "mallory".toCharArray(), "other".toCharArray())

        assertNull(a.vm.uiState.value.authPrompt)
        assertEquals(listOf("job-1"), a.sender.provides().map { it.requestId })
        assertEquals("s3cret", a.sender.provides().single().password)
    }

    @Test
    fun `submitAuth with a stale id is ignored and the arrays are wiped`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")
        val id = a.promptId()
        val user = "mallory".toCharArray()
        val pass = "other".toCharArray()

        a.vm.submitAuth(id + 1, user, pass)

        assertTrue(user.all { it == '\u0000' } && pass.all { it == '\u0000' })
        assertTrue(a.sender.sent.isEmpty())
        assertEquals(id, a.vm.uiState.value.authPrompt?.id)
    }

    @Test
    fun `submitAuth and cancelAuth without a session do nothing and wipe the arrays`() {
        val vm = makeAuthVm(emptyMap(), makeScope())
        val user = "alice".toCharArray()
        val pass = "pw".toCharArray()

        vm.submitAuth(1L, user, pass)
        vm.cancelAuth(1L)

        assertTrue(user.all { it == '\u0000' } && pass.all { it == '\u0000' })
        assertNull(vm.uiState.value.authPrompt)
    }

    @Test
    fun `cancelAuth cancels the request, clears the prompt and holds off a repeat`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")

        a.vm.cancelAuth(a.promptId())

        assertNull(a.vm.uiState.value.authPrompt)
        assertEquals(listOf("job-1"), a.sender.cancels().map { it.requestId })

        now += AUTH_CANCEL_COOLDOWN_MS - 1
        a.challenge("job-2")
        assertNull(a.vm.uiState.value.authPrompt)
        assertEquals(2, a.sender.cancels().size)

        now += 1
        a.challenge("job-3")
        assertNotNull(a.vm.uiState.value.authPrompt)
    }

    @Test
    fun `loadTunnel to another tunnel cancels a pending challenge and ends the session`() = runTest(testDispatcher) {
        val t1 = makeTunnelConfig(id = "t1", localPort = 8081)
        val t2 = makeTunnelConfig(id = "t2", localPort = 9090)
        val a = attached(mapOf("t1" to t1, "t2" to t2))
        a.challenge("job-1")

        a.vm.loadTunnel("t2")

        assertNull(a.vm.uiState.value.authPrompt)
        assertEquals(listOf("job-1"), a.sender.cancels().map { it.requestId })
        assertEquals("http://127.0.0.1:9090/", a.vm.uiState.value.initialUrl)

        // The previous browser may still deliver events for a moment: never prompted for.
        a.sender.sent.clear()
        a.challenge("job-2")
        assertNull(a.vm.uiState.value.authPrompt)
        assertEquals(listOf("job-2"), a.sender.cancels().map { it.requestId })
    }

    @Test
    fun `a tunnel switch wipes the credentials typed for the previous tunnel`() = runTest(testDispatcher) {
        val t1 = makeTunnelConfig(id = "t1", localPort = 8081)
        val t2 = makeTunnelConfig(id = "t2", localPort = 9090)
        val a = attached(mapOf("t1" to t1, "t2" to t2))
        a.challenge("job-1")
        val user = "alice".toCharArray()
        val pass = "s3cret".toCharArray()
        a.vm.submitAuth(a.promptId(), user, pass)
        assertFalse(user.all { it == '\u0000' })

        a.vm.loadTunnel("t2")

        assertTrue(user.all { it == '\u0000' }, "username wiped")
        assertTrue(pass.all { it == '\u0000' }, "password wiped")
    }

    @Test
    fun `loadTunnel on the already active tunnel keeps the pending challenge`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")

        a.vm.loadTunnel("t1")

        assertNotNull(a.vm.uiState.value.authPrompt)
        assertTrue(a.sender.cancels().isEmpty())
    }

    @Test
    fun `onBrowserClosed cancels a pending challenge and clears prompt and notice`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")
        a.challenge("job-2", scheme = "digest")
        assertNotNull(a.vm.uiState.value.authPrompt)
        assertNotNull(a.vm.uiState.value.authNotice)
        a.sender.sent.clear()

        a.vm.onBrowserClosed()

        assertEquals(listOf("job-1"), a.sender.cancels().map { it.requestId })
        assertEquals(BrowserUiState(), a.vm.uiState.value)
    }

    @Test
    fun `onBrowserClosed wipes the credentials`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")
        val user = "alice".toCharArray()
        val pass = "s3cret".toCharArray()
        a.vm.submitAuth(a.promptId(), user, pass)

        a.vm.onBrowserClosed()

        assertTrue(user.all { it == '\u0000' } && pass.all { it == '\u0000' })
    }

    @Test
    fun `a challenge arriving after onBrowserClosed is cancelled without a ghost prompt`() =
        runTest(testDispatcher) {
            val a = attached()
            a.vm.onBrowserClosed()

            a.challenge("late")

            assertNull(a.vm.uiState.value.authPrompt)
            assertEquals(listOf("late"), a.sender.cancels().map { it.requestId })
        }

    @Test
    fun `onBrowserClosed without a loaded config is harmless`() =
        runTest(testDispatcher) {
            // The repository does not know t1: loadTunnel sets the id but never a config.
            val vm = makeAuthVm(emptyMap(), backgroundScope)
            vm.loadTunnel("t1")

            vm.onBrowserClosed()
            vm.loadTunnel("t1")

            assertEquals(BrowserUiState(), vm.uiState.value)
        }

    @Test
    fun `after a close the same tunnel gets a fresh session that prompts again`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")
        a.vm.cancelAuth(a.promptId())
        a.vm.onBrowserClosed()

        a.vm.loadTunnel("t1")
        val sender = RecordingSender()
        val fresh = a.vm.attachAuth(8081, sender)
        // Same instant: the cooldown of the closed session must not carry over.
        Attached(a.vm, sender, fresh).challenge("job-2")

        assertNotNull(a.vm.uiState.value.authPrompt)
    }

    @Test
    fun `detachAuth wipes the credentials that were held`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")
        val user = "alice".toCharArray()
        val pass = "s3cret".toCharArray()
        a.vm.submitAuth(a.promptId(), user, pass)
        assertFalse(user.all { it == '\u0000' })

        a.vm.detachAuth()

        assertTrue(user.all { it == '\u0000' }, "username wiped")
        assertTrue(pass.all { it == '\u0000' }, "password wiped")
        // The session is over: a held credential is never sent again.
        a.sender.sent.clear()
        a.challenge("job-2")
        assertTrue(a.sender.provides().isEmpty())
    }

    @Test
    fun `detachAuth cancels waiting requests and clears the prompt`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")
        a.challenge("job-2")

        a.vm.detachAuth()
        a.vm.detachAuth()

        assertNull(a.vm.uiState.value.authPrompt)
        assertEquals(listOf("job-1", "job-2"), a.sender.cancels().map { it.requestId })
    }

    @Test
    fun `attachAuth ends the previous session and starts with a clean state`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")
        a.challenge("job-2", scheme = "digest")
        a.sender.sent.clear()

        val newSender = RecordingSender()
        val newAuth = a.vm.attachAuth(8081, newSender)

        assertEquals(listOf("job-1"), a.sender.cancels().map { it.requestId })
        assertNull(a.vm.uiState.value.authPrompt)
        assertNull(a.vm.uiState.value.authNotice)

        // The replaced session is silent; the new one drives the prompt.
        a.challenge("old-job")
        assertNull(a.vm.uiState.value.authPrompt)
        Attached(a.vm, newSender, newAuth).challenge("new-job")
        assertNotNull(a.vm.uiState.value.authPrompt)
        assertTrue(newSender.sent.isEmpty())
    }

    @Test
    fun `an unsupported authentication publishes a notice that dismissAuthNotice clears`() =
        runTest(testDispatcher) {
            val a = attached()

            a.challenge("job-1", scheme = "negotiate")
            a.challenge("job-2", scheme = "digest")

            assertEquals(AuthNotice.UNSUPPORTED_CHALLENGE, a.vm.uiState.value.authNotice)
            assertNull(a.vm.uiState.value.authPrompt)
            assertEquals(2, a.sender.cancels().size)

            a.vm.dismissAuthNotice()

            assertNull(a.vm.uiState.value.authNotice)
        }

    @Test
    fun `onUserNavigation cancels the waiting request without starting a cooldown`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")

        a.vm.onUserNavigation()

        assertNull(a.vm.uiState.value.authPrompt)
        assertEquals(listOf("job-1"), a.sender.cancels().map { it.requestId })
        a.challenge("job-2")
        assertNotNull(a.vm.uiState.value.authPrompt)
    }

    @Test
    fun `onUserNavigation without a session does nothing`() {
        val vm = makeAuthVm(emptyMap(), makeScope())

        vm.onUserNavigation()

        assertEquals(BrowserUiState(), vm.uiState.value)
    }

    @Test
    fun `the coordinator is bound to the port it was attached with, not to the loaded tunnel`() = runTest(testDispatcher) {
        val a = attached() // t1 is loaded, port 8081
        // A browser created for another tunnel's port: only that origin may be prompted for.
        val sender = RecordingSender()
        val other = a.vm.attachAuth(9090, sender)
        val b = Attached(a.vm, sender, other)

        b.challenge("job-1", origin = "http://127.0.0.1:8081")
        b.challenge("job-2", origin = "http://127.0.0.1:9090")

        assertEquals("127.0.0.1:9090", a.vm.uiState.value.authPrompt?.origin)
        assertEquals(listOf("job-1"), sender.cancels().map { it.requestId })
    }

    @Test
    fun `prompt ids are never reused by a later browser`() = runTest(testDispatcher) {
        val a = attached()
        a.challenge("job-1")
        val first = a.promptId()
        val sender = RecordingSender()
        val next = Attached(a.vm, sender, a.vm.attachAuth(8081, sender))

        next.challenge("job-1")

        assertTrue(next.promptId() > first, "the panel must not see the same id for a different challenge")
    }

    @Test
    fun `another loopback address publishes the other address notice`() = runTest(testDispatcher) {
        val a = attached()

        a.challenge("job-1", origin = "https://127.0.0.1:8081")

        assertEquals(AuthNotice.OTHER_ADDRESS, a.vm.uiState.value.authNotice)
        assertNull(a.vm.uiState.value.authPrompt)
    }

    @Test
    fun `onAuthInterceptionUnavailable publishes a notice that a new attach and a close clear`() = runTest(testDispatcher) {
        val a = attached()

        a.vm.onAuthInterceptionUnavailable()
        assertEquals(AuthNotice.INTERCEPTION_UNAVAILABLE, a.vm.uiState.value.authNotice)

        a.vm.attachAuth(8081, RecordingSender())
        assertNull(a.vm.uiState.value.authNotice)

        a.vm.onAuthInterceptionUnavailable()
        a.vm.onBrowserClosed()
        assertNull(a.vm.uiState.value.authNotice)
    }

    // ── resolvePopupTarget: popups never open a second browser ──

    @Test
    fun `a loopback popup target opens in the same browser`() {
        val vm = makeAuthVm(emptyMap(), makeScope())

        assertEquals("http://127.0.0.1:8081/page", vm.resolvePopupTarget("http://127.0.0.1:8081/page"))
        assertEquals("https://localhost:8443/x", vm.resolvePopupTarget("https://localhost:8443/x"))
        assertEquals("http://[::1]:3000/", vm.resolvePopupTarget("http://[::1]:3000/"))
        assertFalse(vm.uiState.value.showExternalNavWarning)
    }

    @Test
    fun `an external popup target is not opened and raises the external warning`() {
        val vm = makeAuthVm(emptyMap(), makeScope())

        assertNull(vm.resolvePopupTarget("https://example.com/login"))

        assertTrue(vm.uiState.value.showExternalNavWarning)
        assertEquals("https://example.com/login", vm.uiState.value.pendingExternalUrl)
    }

    @Test
    fun `a popup with a script or opaque target is dropped silently`() {
        val vm = makeAuthVm(emptyMap(), makeScope())

        for (target in listOf("javascript:alert(1)", "about:blank", "data:text/html,x", "file:///etc/passwd", "", "   ", null, "not a url")) {
            assertNull(vm.resolvePopupTarget(target), "target $target")
        }

        assertFalse(vm.uiState.value.showExternalNavWarning)
    }

    @Test
    fun `a popup target pointing at another host through userinfo is external`() {
        val vm = makeAuthVm(emptyMap(), makeScope())

        assertNull(vm.resolvePopupTarget("http://127.0.0.1@evil.example/"))
        assertNull(vm.resolvePopupTarget("http://127.0.0.1.evil.example/"))

        assertTrue(vm.uiState.value.showExternalNavWarning)
    }

    @Test
    fun `a challenge racing with detachAuth never leaves a prompt behind`() = runTest(testDispatcher) {
        repeat(150) { n ->
            val a = attached()
            val worker = kotlin.concurrent.thread(isDaemon = true) {
                repeat(40) { i -> a.challenge("job-$n-$i") }
            }

            a.vm.detachAuth()
            worker.join(5000)

            assertFalse(worker.isAlive)
            assertNull(a.vm.uiState.value.authPrompt, "round $n")
            assertNull(a.vm.uiState.value.authNotice, "round $n")
        }
    }
}