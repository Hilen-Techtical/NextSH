// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Tests de [TunnelBrowserViewModel] : défis d'authentification HTTP (Basic ou
 * Digest) d'un service tunnelé, et le cycle de vie qui les entoure.
 *
 * Aucune WebView ici : le ViewModel ne voit qu'un [HttpAuthResponder], remplacé
 * par un faux qui compte les appels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class TunnelBrowserViewModelTest {

    @MockK
    private lateinit var tunnelRepository: TunnelRepository

    @MockK
    private lateinit var tunnelManager: SshTunnelManager

    @MockK
    private lateinit var stopTunnel: StopTunnelUseCase

    @MockK
    private lateinit var sessionHolder: BrowserSessionHolder

    private val testDispatcher = UnconfinedTestDispatcher()

    // Configs que le faux dépôt connaît ; un test y ajoute avant loadTunnel.
    private val configs = mutableMapOf<String, TunnelConfig>()

    // Horloge déterministe pour le délai après annulation : les tests la déplacent.
    private var now = 1_000_000L

    private lateinit var viewModel: TunnelBrowserViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { tunnelManager.tunnelStates } returns MutableStateFlow<Map<String, TunnelState>>(emptyMap())
        coEvery { tunnelRepository.getById(any()) } answers { configs[firstArg()] }
        coEvery { stopTunnel.invoke(any()) } returns SshResult.Success(Unit)

        viewModel = newViewModel()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun newViewModel() = TunnelBrowserViewModel(
        tunnelRepository = tunnelRepository,
        tunnelManager = tunnelManager,
        stopTunnel = stopTunnel,
        sessionHolder = sessionHolder,
        clock = { now },
    )

    private fun makeConfig(
        id: String = "t1",
        localPort: Int = 8081,
        keepAlive: Boolean = true,
    ) = TunnelConfig(
        id = id,
        label = "Test tunnel",
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = 80,
        keepAliveAfterBrowserClose = keepAlive,
    )

    private fun loadTunnel(id: String = "t1", localPort: Int = 8081, keepAlive: Boolean = true) {
        configs[id] = makeConfig(id, localPort, keepAlive)
        viewModel.loadTunnel(id)
    }

    private class FakeResponder(override val isRetry: Boolean = false) : HttpAuthResponder {
        val proceeded = mutableListOf<Pair<String, String>>()
        var cancelCount = 0
        override fun proceed(username: String, password: String) {
            proceeded += username to password
        }
        override fun cancel() {
            cancelCount++
        }
    }

    private fun challenge(
        responder: HttpAuthResponder,
        host: String? = "127.0.0.1",
        realm: String? = "Private area",
        url: String? = "http://127.0.0.1:8081/",
    ) = viewModel.onAuthRequested(host, realm, url, responder)

    private fun prompt(): HttpAuthPrompt =
        viewModel.uiState.value.authPrompt ?: fail("no auth prompt is pending")

    // What the dialog does: answers the prompt it is currently showing.
    private fun submit(username: String, password: String) =
        viewModel.submitAuth(prompt().id, username, password)

    private fun dismiss() = viewModel.cancelAuth(prompt().id)

    // ── Loopback and origin ───────────────────────────────────────────────────

    @Test
    fun `isLoopbackHost accepts only exact loopback names`() {
        assertTrue(isLoopbackHost("localhost"))
        assertTrue(isLoopbackHost("LOCALHOST"))
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("::1"))
        assertTrue(isLoopbackHost("[::1]"))
        assertFalse(isLoopbackHost("127.0.0.1.evil.com"))
        assertFalse(isLoopbackHost("localhost.evil.com"))
        assertFalse(isLoopbackHost("evil127.0.0.1"))
        assertFalse(isLoopbackHost("127.0.0.2"))
        assertFalse(isLoopbackHost("example.com"))
        assertFalse(isLoopbackHost(""))
        assertFalse(isLoopbackHost(null))
    }

    @Test
    fun `formatAuthOrigin brackets IPv6 and omits a missing port`() {
        assertEquals("127.0.0.1:8081", formatAuthOrigin("127.0.0.1", 8081))
        assertEquals("[::1]:8443", formatAuthOrigin("::1", 8443))
        assertEquals("[::1]:8443", formatAuthOrigin("[::1]", 8443))
        assertEquals("localhost", formatAuthOrigin("localhost", 0))
    }

    @Test
    fun `resolveAuthOrigin uses the explicit port of a page URL on the same host`() {
        assertEquals("127.0.0.1:8081", resolveAuthOrigin("127.0.0.1", "http://127.0.0.1:8081/app?x=1"))
        assertEquals("localhost:9090", resolveAuthOrigin("localhost", "https://localhost:9090"))
    }

    @Test
    fun `resolveAuthOrigin shows the bare host when the URL cannot vouch for a port`() {
        // Another host, no explicit port, no URL, unparsable URL, no host in the URL.
        assertEquals("127.0.0.1", resolveAuthOrigin("127.0.0.1", "http://localhost:8081/"))
        assertEquals("127.0.0.1", resolveAuthOrigin("127.0.0.1", "http://127.0.0.1/"))
        assertEquals("127.0.0.1", resolveAuthOrigin("127.0.0.1", null))
        assertEquals("127.0.0.1", resolveAuthOrigin("127.0.0.1", "http://127.0.0.1:8081/a b"))
        assertEquals("127.0.0.1", resolveAuthOrigin("127.0.0.1", "about:blank"))
        assertEquals("127.0.0.1", resolveAuthOrigin("127.0.0.1", ""))
    }

    @Test
    fun `resolveAuthOrigin lowercases the host and compares it case-insensitively`() {
        assertEquals("localhost:9090", resolveAuthOrigin("LOCALHOST", "http://localhost:9090/"))
        assertEquals("localhost:9090", resolveAuthOrigin("localhost", "http://LocalHost:9090/"))
        assertEquals("localhost", resolveAuthOrigin("LocalHost", null))
    }

    @Test
    fun `resolveAuthOrigin handles IPv6 spellings of the host`() {
        assertEquals("[::1]:8443", resolveAuthOrigin("::1", "http://[::1]:8443/"))
        assertEquals("[::1]:8443", resolveAuthOrigin("[::1]", "http://[::1]:8443/"))
        assertEquals("[::1]", resolveAuthOrigin("::1", "http://[::1]/"))
    }

    @Test
    fun `resolveAuthOrigin ignores user info in the page URL`() {
        assertEquals("127.0.0.1:8081", resolveAuthOrigin("127.0.0.1", "http://alice:s3cret@127.0.0.1:8081/"))
    }

    @Test
    fun `loopback challenge publishes a prompt and leaves the responder untouched`() {
        val responder = FakeResponder()

        challenge(responder)

        val prompt = prompt()
        assertEquals("127.0.0.1:8081", prompt.origin)
        assertEquals("Private area", prompt.realm)
        assertFalse(prompt.rejected)
        assertEquals(0, responder.cancelCount)
        assertTrue(responder.proceeded.isEmpty())
    }

    @Test
    fun `the displayed origin takes the port from the current page URL`() {
        challenge(FakeResponder(), host = "localhost", url = "http://localhost:9090/admin")

        assertEquals("localhost:9090", prompt().origin)
    }

    @Test
    fun `a page URL on another host gives the bare host`() {
        challenge(FakeResponder(), host = "127.0.0.1", url = "http://localhost:9090/")

        assertEquals("127.0.0.1", prompt().origin)
    }

    @Test
    fun `an uppercase host is normalized in the displayed origin`() {
        challenge(FakeResponder(), host = "LOCALHOST", url = "http://localhost:9090/")

        assertEquals("localhost:9090", prompt().origin)
    }

    @Test
    fun `the displayed origin no longer depends on the loaded tunnel`() {
        loadTunnel(id = "t9", localPort = 9090)

        challenge(FakeResponder(), host = "127.0.0.1", url = null)

        assertEquals("127.0.0.1", prompt().origin)
    }

    @Test
    fun `an IPv6 host is bracketed in the displayed origin`() {
        challenge(FakeResponder(), host = "::1", url = "http://[::1]:8443/")

        assertEquals("[::1]:8443", prompt().origin)
    }

    @Test
    fun `a host that carries a port is not a loopback host and is cancelled`() {
        // The WebView reports the host without its port; this documents what happens
        // if a platform ever hands one over: the challenge is refused, not guessed.
        val responder = FakeResponder()

        challenge(responder, host = "127.0.0.1:8081")

        assertEquals(1, responder.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `loopback host spellings are all accepted`() {
        for (host in listOf("localhost", "LOCALHOST", "127.0.0.1", "::1", "[::1]")) {
            viewModel = newViewModel()
            val responder = FakeResponder()

            challenge(responder, host = host)

            assertNotNull(viewModel.uiState.value.authPrompt, "host $host should prompt")
            assertEquals(0, responder.cancelCount)
        }
    }

    @Test
    fun `non loopback or unknown host is cancelled without a prompt`() {
        val hosts = listOf("example.com", "192.168.1.10", "10.0.0.1", "localhost.evil.com", "127.0.0.1.evil.com", "", null)
        for (host in hosts) {
            viewModel = newViewModel()
            val responder = FakeResponder()

            challenge(responder, host = host)

            assertEquals(1, responder.cancelCount, "host $host should be cancelled")
            assertNull(viewModel.uiState.value.authPrompt)
        }
    }

    // ── Answers and prompt ids ────────────────────────────────────────────────

    @Test
    fun `submitAuth forwards the credentials once and clears the prompt`() {
        val responder = FakeResponder()
        challenge(responder)
        val id = prompt().id

        viewModel.submitAuth(id, "alice", "s3cret")

        assertEquals(listOf("alice" to "s3cret"), responder.proceeded)
        assertEquals(0, responder.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)

        viewModel.submitAuth(id, "mallory", "other")

        assertEquals(1, responder.proceeded.size)
        assertEquals(0, responder.cancelCount)
    }

    @Test
    fun `submitAuth and cancelAuth without a prompt do nothing`() {
        viewModel.submitAuth(1L, "alice", "s3cret")
        viewModel.cancelAuth(1L)

        assertNull(viewModel.uiState.value.authPrompt)
        // An ignored cancel records nothing: the next challenge is not held back.
        challenge(FakeResponder())
        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `cancelAuth cancels the responder once and clears the prompt`() {
        val responder = FakeResponder()
        challenge(responder)
        val id = prompt().id

        viewModel.cancelAuth(id)
        viewModel.cancelAuth(id)

        assertEquals(1, responder.cancelCount)
        assertTrue(responder.proceeded.isEmpty())
        assertNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `submitAuth with a stale id is ignored and leaves the pending challenge untouched`() {
        val responder = FakeResponder()
        challenge(responder)
        val id = prompt().id

        viewModel.submitAuth(id + 1, "mallory", "other")
        viewModel.submitAuth(id - 1, "mallory", "other")

        assertTrue(responder.proceeded.isEmpty())
        assertEquals(0, responder.cancelCount)
        assertEquals(id, prompt().id)

        viewModel.submitAuth(id, "alice", "pw")

        assertEquals(listOf("alice" to "pw"), responder.proceeded)
    }

    @Test
    fun `cancelAuth with a stale id is ignored and leaves the pending challenge untouched`() {
        val responder = FakeResponder()
        challenge(responder)
        val id = prompt().id

        viewModel.cancelAuth(id + 1)

        assertEquals(0, responder.cancelCount)
        assertEquals(id, prompt().id)

        viewModel.cancelAuth(id)

        assertEquals(1, responder.cancelCount)
    }

    @Test
    fun `an answer to a replaced prompt cannot answer the next challenge`() {
        val first = FakeResponder()
        val second = FakeResponder()
        challenge(first)
        val firstId = prompt().id
        viewModel.submitAuth(firstId, "alice", "wrong")
        challenge(second)
        val secondId = prompt().id

        // The double tap on the first dialog lands after the second one appeared.
        viewModel.submitAuth(firstId, "alice", "wrong")
        viewModel.cancelAuth(firstId)

        assertEquals(1, first.proceeded.size)
        assertTrue(second.proceeded.isEmpty())
        assertEquals(0, second.cancelCount)
        assertEquals(secondId, prompt().id)
    }

    @Test
    fun `second challenge while one is pending is cancelled and the first keeps the dialog`() {
        val first = FakeResponder()
        val second = FakeResponder()
        challenge(first, realm = "First")
        val firstPrompt = prompt()

        challenge(second, realm = "Second")

        assertEquals(1, second.cancelCount)
        assertEquals(0, first.cancelCount)
        assertEquals(firstPrompt, prompt())

        submit("alice", "pw")

        assertEquals(listOf("alice" to "pw"), first.proceeded)
        assertTrue(second.proceeded.isEmpty())
    }

    @Test
    fun `prompt ids increase with each new challenge`() {
        challenge(FakeResponder())
        val firstId = prompt().id
        dismiss()
        now += AUTH_CANCEL_COOLDOWN_MS

        challenge(FakeResponder())

        assertTrue(prompt().id > firstId)
    }

    // ── Rejected flag (reported by the platform) ──────────────────────────────

    @Test
    fun `a retry reported by the platform is flagged as rejected`() {
        challenge(FakeResponder(isRetry = true))

        assertTrue(prompt().rejected)
    }

    @Test
    fun `a first attempt reported by the platform is not flagged as rejected`() {
        challenge(FakeResponder(isRetry = false))

        assertFalse(prompt().rejected)
    }

    @Test
    fun `the rejected flag follows each challenge and is not remembered across prompts`() {
        challenge(FakeResponder(isRetry = false))
        submit("alice", "wrong")
        assertNull(viewModel.uiState.value.authPrompt)

        // The server refused: the platform raises the same request again as a retry.
        challenge(FakeResponder(isRetry = true))
        assertTrue(prompt().rejected)
        submit("alice", "right")

        // A later, unrelated first attempt for the same origin and realm is clean.
        challenge(FakeResponder(isRetry = false))
        assertFalse(prompt().rejected)
    }

    // ── Cancel cooldown ───────────────────────────────────────────────────────

    @Test
    fun `a challenge repeated within the cooldown after a user cancel is auto-cancelled`() {
        challenge(FakeResponder())
        dismiss()
        now += AUTH_CANCEL_COOLDOWN_MS - 1
        val again = FakeResponder()

        challenge(again)

        assertEquals(1, again.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `a challenge after the cooldown prompts again`() {
        challenge(FakeResponder())
        dismiss()
        now += AUTH_CANCEL_COOLDOWN_MS
        val again = FakeResponder()

        challenge(again)

        assertEquals(0, again.cancelCount)
        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `the cooldown only covers the same origin and realm`() {
        challenge(FakeResponder(), host = "127.0.0.1", realm = "Private area")
        dismiss()

        challenge(FakeResponder(), host = "127.0.0.1", realm = "Other realm")
        assertEquals("Other realm", prompt().realm)
        dismiss()

        challenge(FakeResponder(), host = "localhost", realm = "Other realm")
        assertEquals("localhost", prompt().origin)
    }

    @Test
    fun `a clock that moved backwards does not extend the cooldown`() {
        challenge(FakeResponder())
        dismiss()
        now -= 60_000L

        challenge(FakeResponder())

        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `a submit does not start a cooldown`() {
        challenge(FakeResponder())
        submit("alice", "wrong")
        val again = FakeResponder(isRetry = true)

        challenge(again)

        assertEquals(0, again.cancelCount)
        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `the cooldown key uses the lowercase host`() {
        challenge(FakeResponder(), host = "LOCALHOST", url = "http://localhost:9090/")
        dismiss()
        val again = FakeResponder()

        challenge(again, host = "localhost", url = "http://localhost:9090/")

        assertEquals(1, again.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `an explicit user navigation lifts the cooldown`() {
        challenge(FakeResponder())
        dismiss()
        val automatic = FakeResponder()
        challenge(automatic)
        assertEquals(1, automatic.cancelCount)

        viewModel.onUserNavigation()
        val afterRefresh = FakeResponder()
        challenge(afterRefresh)

        assertEquals(0, afterRefresh.cancelCount)
        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `an automatic re-challenge stays throttled until a user navigation`() {
        challenge(FakeResponder())
        dismiss()

        repeat(3) {
            val automatic = FakeResponder()
            challenge(automatic)
            assertEquals(1, automatic.cancelCount)
            assertNull(viewModel.uiState.value.authPrompt)
        }
    }

    @Test
    fun `a user navigation with no cooldown or prompt changes nothing`() {
        viewModel.onUserNavigation()
        assertNull(viewModel.uiState.value.authPrompt)

        val responder = FakeResponder()
        challenge(responder)
        viewModel.onUserNavigation()

        // A pending prompt is not touched by a navigation.
        assertEquals(0, responder.cancelCount)
        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `a tunnel switch is not a user cancel and starts no cooldown`() {
        loadTunnel(id = "t1", localPort = 8080)
        challenge(FakeResponder())

        loadTunnel(id = "t2", localPort = 9090)
        challenge(FakeResponder())

        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `closing the browser wipes the cooldown`() {
        loadTunnel()
        challenge(FakeResponder())
        dismiss()

        viewModel.onBrowserClosed()
        viewModel.loadTunnel("t1")
        challenge(FakeResponder())

        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    // ── Teardown and lifecycle ────────────────────────────────────────────────

    @Test
    fun `onBrowserClosed cancels a pending challenge`() {
        loadTunnel()
        val responder = FakeResponder()
        challenge(responder)

        viewModel.onBrowserClosed()

        assertEquals(1, responder.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `onBrowserClosed cancels a pending challenge even when no tunnel is loaded`() {
        val responder = FakeResponder()
        challenge(responder)

        viewModel.onBrowserClosed()

        assertEquals(1, responder.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `a challenge arriving after onBrowserClosed is cancelled without a ghost prompt`() {
        loadTunnel()
        viewModel.onBrowserClosed()
        val late = FakeResponder()

        challenge(late)

        assertEquals(1, late.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `a challenge arriving after onBrowserClosed with no tunnel loaded is cancelled`() {
        viewModel.onBrowserClosed()
        val late = FakeResponder()

        challenge(late)

        assertEquals(1, late.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `loadTunnel after a close accepts challenges again`() {
        loadTunnel()
        viewModel.onBrowserClosed()

        viewModel.loadTunnel("t1")
        val responder = FakeResponder()
        challenge(responder)

        assertEquals(0, responder.cancelCount)
        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `loadTunnel of the same id after a close without a loaded config accepts challenges again`() {
        // The repository does not know t1: loadTunnel sets the id but never a config.
        viewModel.loadTunnel("t1")
        viewModel.onBrowserClosed()

        viewModel.loadTunnel("t1")
        challenge(FakeResponder())

        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `onBrowserClosed stops the tunnel unless it is kept alive`() {
        loadTunnel(id = "t1", keepAlive = false)

        viewModel.onBrowserClosed()

        coVerify(exactly = 1) { stopTunnel.invoke("t1") }
    }

    @Test
    fun `onBrowserClosed leaves a kept alive tunnel running`() {
        loadTunnel(id = "t1", keepAlive = true)

        viewModel.onBrowserClosed()

        coVerify(exactly = 0) { stopTunnel.invoke(any()) }
    }

    @Test
    fun `onWebViewReleased cancels a pending challenge and refuses later ones`() {
        val pending = FakeResponder()
        challenge(pending)

        viewModel.onWebViewReleased()

        assertEquals(1, pending.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)

        val late = FakeResponder()
        challenge(late)

        assertEquals(1, late.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `onWebViewReleased keeps the loaded tunnel`() {
        loadTunnel(localPort = 8081)

        viewModel.onWebViewReleased()

        assertEquals("http://127.0.0.1:8081", viewModel.uiState.value.initialUrl)
        assertNotNull(viewModel.uiState.value.tunnelConfig)
    }

    @Test
    fun `onWebViewAttached accepts challenges again after a release`() {
        viewModel.onWebViewReleased()

        viewModel.onWebViewAttached()
        val responder = FakeResponder()
        challenge(responder)

        assertEquals(0, responder.cancelCount)
        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `an activity recreation leaves no prompt bound to the dead handler`() {
        // Rotation: the ViewModel survives, the WebView and its handler do not.
        loadTunnel()
        val dead = FakeResponder()
        challenge(dead)

        viewModel.onWebViewReleased()
        // The same tunnel is requested again by the new composition: no reload.
        viewModel.loadTunnel("t1")

        assertEquals(1, dead.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)

        viewModel.onWebViewAttached()
        val fresh = FakeResponder()
        challenge(fresh)

        assertEquals(0, fresh.cancelCount)
        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `loadTunnel of a new tunnel accepts challenges again after a release`() {
        viewModel.onWebViewReleased()

        loadTunnel(id = "t1", localPort = 8080)
        challenge(FakeResponder())

        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `loadTunnel to another tunnel cancels a pending challenge`() {
        loadTunnel(id = "t1", localPort = 8080)
        val responder = FakeResponder()
        challenge(responder)

        loadTunnel(id = "t2", localPort = 9090)

        assertEquals(1, responder.cancelCount)
        assertNull(viewModel.uiState.value.authPrompt)
        assertEquals("http://127.0.0.1:9090", viewModel.uiState.value.initialUrl)
    }

    @Test
    fun `loadTunnel on the already active tunnel keeps the pending challenge`() {
        loadTunnel(id = "t1", localPort = 8080)
        val responder = FakeResponder()
        challenge(responder)

        viewModel.loadTunnel("t1")

        assertEquals(0, responder.cancelCount)
        assertNotNull(viewModel.uiState.value.authPrompt)
    }

    @Test
    fun `clearing the ViewModel cancels a pending challenge`() {
        val responder = FakeResponder()
        challenge(responder)
        val store = ViewModelStore()
        val vm = viewModel
        // The only public way to reach onCleared outside the framework.
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        )[TunnelBrowserViewModel::class.java]

        store.clear()

        assertEquals(1, responder.cancelCount)
        assertNull(vm.uiState.value.authPrompt)
    }

    @Test
    fun `concurrent challenges leave exactly one prompt and cancel every other responder once`() {
        val count = 32
        val responders = List(count) { FakeResponder() }
        val start = CountDownLatch(1)
        val threads = responders.map { responder ->
            thread(isDaemon = true) {
                start.await()
                challenge(responder)
            }
        }
        start.countDown()
        // A bounded join so a deadlock fails the test instead of hanging the build.
        threads.forEach { it.join(5000) }
        assertTrue(threads.none { it.isAlive }, "challenge threads did not finish")

        assertNotNull(viewModel.uiState.value.authPrompt)
        assertEquals(count - 1, responders.count { it.cancelCount == 1 })
        assertEquals(1, responders.count { it.cancelCount == 0 })

        submit("alice", "pw")

        assertEquals(1, responders.sumOf { it.proceeded.size })
        assertEquals(count - 1, responders.sumOf { it.cancelCount })
    }

    // ── Realm in the prompt ───────────────────────────────────────────────────

    @Test
    fun `the realm is sanitized before it reaches the prompt`() {
        val rlo = 0x202E.toChar()
        val newline = 10.toChar()

        challenge(FakeResponder(), realm = "Admin" + rlo + newline + "area")

        assertEquals("Adminarea", prompt().realm)
    }

    @Test
    fun `a blank realm becomes null in the prompt`() {
        challenge(FakeResponder(), realm = "   ")

        assertNull(prompt().realm)
    }

    @Test
    fun `a null realm gives a prompt without realm`() {
        challenge(FakeResponder(), realm = null)

        assertNull(prompt().realm)
    }

    // ── sanitizeAuthRealm ─────────────────────────────────────────────────────

    @Test
    fun `sanitizeAuthRealm keeps a plain realm and trims it`() {
        assertEquals("Private area", sanitizeAuthRealm("Private area"))
        assertEquals("Private area", sanitizeAuthRealm("  Private area  "))
    }

    @Test
    fun `sanitizeAuthRealm keeps ordinary non-ASCII text`() {
        val accented = "Espace priv" + 0xE9.toChar()
        val cjk = "" + 0x7BA1.toChar() + 0x7406.toChar()

        assertEquals(accented, sanitizeAuthRealm(accented))
        assertEquals(cjk, sanitizeAuthRealm(cjk))
    }

    @Test
    fun `sanitizeAuthRealm strips ISO control characters`() {
        val controls = listOf(0, 9, 10, 13, 0x1B, 0x7F, 0x85).map { it.toChar() }
        val raw = "Ad" + controls.joinToString("") + "min"

        assertEquals("Admin", sanitizeAuthRealm(raw))
    }

    @Test
    fun `sanitizeAuthRealm strips bidi override and isolation characters`() {
        val bidi = (0x202A..0x202E).map { it.toChar() } + (0x2066..0x2069).map { it.toChar() }
        val raw = "Ad" + bidi.joinToString("") + "min"

        assertEquals("Admin", sanitizeAuthRealm(raw))
    }

    @Test
    fun `sanitizeAuthRealm strips line and paragraph separators`() {
        val raw = "Ad" + 0x2028.toChar() + "mi" + 0x2029.toChar() + "n"

        assertEquals("Admin", sanitizeAuthRealm(raw))
    }

    @Test
    fun `sanitizeAuthRealm strips zero-width and directional marks`() {
        // ZWSP, ZWNJ, ZWJ, LRM, RLM, ALM, word joiner and invisible operators.
        val invisible = listOf(0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x061C, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064)
            .map { it.toChar() }
        val raw = "Ad" + invisible.joinToString("") + "min"

        assertEquals("Admin", sanitizeAuthRealm(raw))
    }

    @Test
    fun `sanitizeAuthRealm strips the byte order mark and the soft hyphen`() {
        val raw = 0xFEFF.toChar() + "Ad" + 0xAD.toChar() + "min" + 0xFEFF.toChar()

        assertEquals("Admin", sanitizeAuthRealm(raw))
    }

    @Test
    fun `sanitizeAuthRealm strips lone surrogates but keeps a valid pair`() {
        val emoji = StringBuilder().appendCodePoint(0x1F600).toString()
        val loneHigh = 0xD83D.toChar()
        val loneLow = 0xDE00.toChar()
        // A reversed pair is two lone surrogates, not an emoji.
        val reversed = "" + loneLow + loneHigh

        assertEquals("Admin", sanitizeAuthRealm("Ad" + loneHigh + "min"))
        assertEquals("Admin", sanitizeAuthRealm("Ad" + loneLow + "min"))
        assertEquals("Admin", sanitizeAuthRealm("Ad" + reversed + "min"))
        assertEquals("Admin", sanitizeAuthRealm("Admin" + loneHigh))
        assertEquals("Ad${emoji}min", sanitizeAuthRealm("Ad${emoji}min"))
    }

    @Test
    fun `sanitizeAuthRealm strips private use characters`() {
        val bmpPrivate = listOf(0xE000, 0xF8FF).map { it.toChar() }.joinToString("")
        val supplementaryPrivate = StringBuilder().appendCodePoint(0xF0000).appendCodePoint(0x10FFFD).toString()

        assertEquals("Admin", sanitizeAuthRealm("Ad" + bmpPrivate + "min"))
        assertEquals("Admin", sanitizeAuthRealm("Ad" + supplementaryPrivate + "min"))
        assertNull(sanitizeAuthRealm(bmpPrivate + supplementaryPrivate))
    }

    @Test
    fun `sanitizeAuthRealm strips unassigned code points`() {
        // U+0378 and U+0379 sit in the Greek block and have never been assigned;
        // U+FFFF is a noncharacter.
        val unassigned = listOf(0x0378, 0x0379, 0xFFFF).map { it.toChar() }.joinToString("")

        assertEquals("Admin", sanitizeAuthRealm("Ad" + unassigned + "min"))
        assertNull(sanitizeAuthRealm(unassigned))
    }

    @Test
    fun `sanitizeAuthRealm returns null when nothing printable is left`() {
        val junk = (0x202A..0x202E).map { it.toChar() }.joinToString("") +
            10.toChar() + 0.toChar() + 0x200B.toChar() + 0xFEFF.toChar()

        assertNull(sanitizeAuthRealm(null))
        assertNull(sanitizeAuthRealm(""))
        assertNull(sanitizeAuthRealm("   "))
        assertNull(sanitizeAuthRealm(junk))
        assertNull(sanitizeAuthRealm(" $junk "))
    }

    @Test
    fun `sanitizeAuthRealm caps at 80 characters and appends an ellipsis only when truncated`() {
        val ellipsis = 0x2026.toChar().toString()

        assertEquals("a".repeat(80), sanitizeAuthRealm("a".repeat(80)))
        assertEquals("a".repeat(80) + ellipsis, sanitizeAuthRealm("a".repeat(81)))
        assertEquals("a".repeat(80) + ellipsis, sanitizeAuthRealm("a".repeat(500)))
    }

    @Test
    fun `sanitizeAuthRealm bounds a huge input before filtering it`() {
        val ellipsis = 0x2026.toChar().toString()

        assertEquals("a".repeat(80) + ellipsis, sanitizeAuthRealm("a".repeat(1_000_000)))
        // Only the first AUTH_REALM_RAW_LIMIT characters are ever looked at.
        val buried = 0.toChar().toString().repeat(AUTH_REALM_RAW_LIMIT) + "visible"
        assertNull(sanitizeAuthRealm(buried))
    }

    @Test
    fun `sanitizeAuthRealm does not split a surrogate pair when truncating`() {
        val ellipsis = 0x2026.toChar().toString()
        val emoji = "" + 0xD83D.toChar() + 0xDE00.toChar()

        val result = sanitizeAuthRealm("a".repeat(79) + emoji + "bbb")

        assertEquals("a".repeat(79) + ellipsis, result)
    }

    @Test
    fun `sanitizeAuthRealm does not split a surrogate pair at the input bound`() {
        val emoji = "" + 0xD83D.toChar() + 0xDE00.toChar()
        val raw = "a".repeat(AUTH_REALM_RAW_LIMIT - 1) + emoji

        val result = sanitizeAuthRealm(raw)

        // 511 letters survive the bound and the emoji is dropped whole: no lone surrogate.
        assertEquals("a".repeat(80) + 0x2026.toChar(), result)
        assertNotNull(result)
        assertTrue(result!!.none { Character.isSurrogate(it) })
    }
}
