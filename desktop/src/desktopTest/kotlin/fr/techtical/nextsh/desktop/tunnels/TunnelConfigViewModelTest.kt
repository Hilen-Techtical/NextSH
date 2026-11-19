// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPathError
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of [TunnelConfigViewModel.save]: an edit re-reads the stored tunnel
 * right before building (so fields the form does not own survive), and a
 * tunnel deleted meanwhile (e.g. by a sync) is not resurrected.
 *
 * The scope uses [UnconfinedTestDispatcher] and the fakes never suspend for
 * real, so every `launch` in the ViewModel completes before the call returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelConfigViewModelTest {

    /** Every scope a test created (some tests build several view models). */
    private val testScopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        testScopes.forEach { it.cancel() }
        testScopes.clear()
    }

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    /** In-memory repository; [remove] mimics a tombstone (`getById` ignores deleted rows). */
    private class FakeTunnelRepository(initial: List<TunnelConfig> = emptyList()) : TunnelRepository {
        private val tunnels = MutableStateFlow(initial)
        val saved = mutableListOf<TunnelConfig>()
        val updated = mutableListOf<TunnelConfig>()

        /** When set, save and update throw it, like a database that refuses the write. */
        var writeFailure: Exception? = null

        fun remove(id: String) {
            tunnels.value = tunnels.value.filterNot { it.id == id }
        }

        fun replace(config: TunnelConfig) {
            tunnels.value = tunnels.value.map { if (it.id == config.id) config else it }
        }

        override fun observeAll(): Flow<List<TunnelConfig>> = tunnels
        override fun observeByHost(hostId: String): Flow<List<TunnelConfig>> = tunnels
        override suspend fun getById(id: String): TunnelConfig? = tunnels.value.find { it.id == id }
        override suspend fun getAutoStartTunnels(): List<TunnelConfig> = emptyList()

        override suspend fun save(config: TunnelConfig) {
            writeFailure?.let { throw it }
            saved += config
            tunnels.value = tunnels.value + config
        }

        override suspend fun update(config: TunnelConfig) {
            writeFailure?.let { throw it }
            updated += config
            replace(config)
        }

        override suspend fun delete(id: String) = remove(id)
        override fun observeFavorites(): Flow<List<TunnelConfig>> = tunnels
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {
            tunnels.value.find { it.id == id }?.let { replace(it.copy(isFavorite = isFavorite)) }
        }

        override suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>) = Unit
        override suspend fun hardDelete(id: String) = remove(id)
    }

    private fun storedTunnel(isFavorite: Boolean = false, browserPath: String = "/") = TunnelConfig(
        id = "t1",
        label = "Web",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "localhost",
        remotePort = 80,
        isFavorite = isFavorite,
        browserPath = browserPath,
    )

    /** A new LOCAL_FORWARD form with every required field filled, so only the path can block the save. */
    private fun filledNewForm(repo: FakeTunnelRepository): TunnelConfigViewModel {
        val vm = newViewModel(repo)
        vm.load(null)
        vm.onLabel("Web")
        vm.onHost("h1")
        vm.onLocalPort("9000")
        vm.onRemotePort("443")
        return vm
    }

    private val tunnelService = mockk<DesktopTunnelService>(relaxed = true)

    /** Whether the host "h1" still exists (live) for the save-time check; a test flips it to delete it. */
    private var hostExists = true

    private val h1 = Host(
        id = "h1",
        label = "Prod",
        hostname = "prod.example",
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private fun newViewModel(repository: FakeTunnelRepository): TunnelConfigViewModel {
        val scope = CoroutineScope(UnconfinedTestDispatcher()).also { testScopes += it }
        val hostRepository = mockk<HostRepository>()
        every { hostRepository.observeAll() } returns flowOf(emptyList<Host>())
        coEvery { hostRepository.getById(any()) } answers { if (hostExists) h1 else null }
        return TunnelConfigViewModel(
            repository = repository,
            hostRepository = hostRepository,
            appScope = TestAppScope(scope),
            tunnelService = tunnelService,
        )
    }

    @Test
    fun `save of an existing tunnel keeps the favorite flag read at save time`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel(isFavorite = false)))
        val vm = newViewModel(repo)
        vm.load("t1")

        // Favorite toggled elsewhere (list screen, sync) while the form is open.
        repo.replace(storedTunnel(isFavorite = true))
        vm.onLabel("Renamed")
        var done = false
        vm.save { done = true }

        assertTrue(done)
        assertTrue(repo.saved.isEmpty())
        val updated = repo.updated.single()
        assertEquals("Renamed", updated.label)
        assertTrue(updated.isFavorite)
        assertFalse(vm.state.value.isSaving)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `save of a tunnel deleted meanwhile writes nothing and reports the error`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel()))
        val vm = newViewModel(repo)
        vm.load("t1")

        // Deleted (e.g. by a sync) while the form is open.
        repo.remove("t1")
        vm.onLabel("Renamed")
        var done = false
        vm.save { done = true }

        assertFalse(done)
        assertTrue(repo.updated.isEmpty())
        assertTrue(repo.saved.isEmpty())
        assertEquals(TunnelFormError.TUNNEL_DELETED, vm.state.value.error)
        assertFalse(vm.state.value.isSaving)
    }

    @Test
    fun `save of a new tunnel calls save and not update`() {
        val repo = FakeTunnelRepository()
        val vm = newViewModel(repo)
        vm.load(null)
        vm.onLabel("  New  ")
        vm.onHost("h1")
        vm.onLocalPort("9000")
        vm.onRemotePort("443")

        var done = false
        vm.save { done = true }

        assertTrue(done)
        assertTrue(repo.updated.isEmpty())
        val saved = repo.saved.single()
        assertEquals("New", saved.label)
        assertEquals(9000, saved.localPort)
        assertFalse(saved.isFavorite)
        assertFalse(vm.state.value.isSaving)
        assertNull(vm.state.value.error)
    }

    // -- Save refused by the data layer -----------------------------------------

    @Test
    fun `a refused write of a new tunnel gives a typed error and does not leave the form saving`() {
        val repo = FakeTunnelRepository()
        repo.writeFailure = IllegalStateException("FOREIGN KEY constraint failed")
        val vm = filledNewForm(repo)

        var done = false
        vm.save { done = true }

        assertFalse(done)
        assertFalse(vm.state.value.isSaving, "the Save button must come back")
        assertEquals(TunnelSaveError.SAVE_FAILED, vm.state.value.saveError)
        assertNull(vm.state.value.error, "the legacy hard-coded message field is not used for this")
        assertTrue(repo.saved.isEmpty())

        // The form is not stuck: the same save goes through once the database accepts it.
        repo.writeFailure = null
        vm.save { done = true }
        assertTrue(done)
        assertEquals(1, repo.saved.size)
        assertNull(vm.state.value.saveError)
    }

    @Test
    fun `a refused write of an existing tunnel gives a typed error and does not leave the form saving`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel()))
        val vm = newViewModel(repo)
        vm.load("t1")
        repo.writeFailure = IllegalStateException("disk I/O error")

        var done = false
        vm.save { done = true }

        assertFalse(done)
        assertFalse(vm.state.value.isSaving)
        assertEquals(TunnelSaveError.SAVE_FAILED, vm.state.value.saveError)
    }

    @Test
    fun `a host deleted meanwhile is reported specifically and nothing is written`() {
        val repo = FakeTunnelRepository()
        val vm = filledNewForm(repo)
        hostExists = false

        var done = false
        vm.save { done = true }

        assertFalse(done)
        assertFalse(vm.state.value.isSaving)
        assertEquals(TunnelSaveError.HOST_DELETED, vm.state.value.saveError)
        assertTrue(repo.saved.isEmpty())
        assertTrue(repo.updated.isEmpty())
    }

    @Test
    fun `the host check also guards the edit of an existing tunnel`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel()))
        val vm = newViewModel(repo)
        vm.load("t1")
        hostExists = false

        vm.save { }

        assertEquals(TunnelSaveError.HOST_DELETED, vm.state.value.saveError)
        assertTrue(repo.updated.isEmpty())
    }

    @Test
    fun `editing the form clears the save error`() {
        val repo = FakeTunnelRepository()
        val vm = filledNewForm(repo)
        hostExists = false
        vm.save { }
        assertEquals(TunnelSaveError.HOST_DELETED, vm.state.value.saveError)

        vm.onHost("h2")

        assertNull(vm.state.value.saveError)
    }

    @Test
    fun `every edit of the form clears the save error`() {
        val edits: List<Pair<String, (TunnelConfigViewModel) -> Unit>> = listOf(
            "label" to { vm -> vm.onLabel("Other") },
            "host" to { vm -> vm.onHost("h2") },
            "type" to { vm -> vm.onType(TunnelType.REMOTE_FORWARD) },
            "local port" to { vm -> vm.onLocalPort("9001") },
            "remote host" to { vm -> vm.onRemoteHost("db.internal") },
            "remote port" to { vm -> vm.onRemotePort("5432") },
            "auto start" to { vm -> vm.onAutoStart(true) },
            "open browser on connect" to { vm -> vm.onOpenBrowserOnConnect(true) },
            "keep alive after browser close" to { vm -> vm.onKeepAliveAfterBrowserClose(false) },
            "browser path" to { vm -> vm.onBrowserPath("/admin") },
        )
        for ((name, edit) in edits) {
            hostExists = false
            val vm = filledNewForm(FakeTunnelRepository())
            vm.save { }
            assertEquals(TunnelSaveError.HOST_DELETED, vm.state.value.saveError, "precondition for: $name")

            edit(vm)

            assertNull(vm.state.value.saveError, "editing the $name must clear the save error")
        }
    }

    @Test
    fun `a cancellation is not reported as a save failure`() {
        val repo = FakeTunnelRepository()
        repo.writeFailure = kotlinx.coroutines.CancellationException("scope cancelled")
        val vm = filledNewForm(repo)

        vm.save { }

        assertNull(vm.state.value.saveError, "cancellation must propagate, not become an error banner")
    }

    @Test
    fun `deleting a tunnel stops it first if it is running, then deletes it`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel()))
        val vm = newViewModel(repo)
        vm.load("t1")
        var stillStoredWhenStopped: Boolean? = null
        coEvery { tunnelService.stopTunnelIfRunning("t1") } coAnswers {
            stillStoredWhenStopped = repo.getById("t1") != null
        }

        var done = false
        vm.delete { done = true }

        assertTrue(done)
        coVerify(exactly = 1) { tunnelService.stopTunnelIfRunning("t1") }
        assertEquals(true, stillStoredWhenStopped, "the tunnel must be stopped before it is deleted")
        assertNull(runBlocking { repo.getById("t1") })
    }

    @Test
    fun `deleting a tunnel goes on with the delete when stopping it fails`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel()))
        val vm = newViewModel(repo)
        vm.load("t1")
        coEvery { tunnelService.stopTunnelIfRunning("t1") } throws IllegalStateException("socket error")

        var done = false
        vm.delete { done = true }

        assertTrue(done)
        assertNull(runBlocking { repo.getById("t1") })
    }

    // -- Browser start path -----------------------------------------------------

    @Test
    fun `a new form starts with an empty start path and no error`() {
        val vm = newViewModel(FakeTunnelRepository())
        vm.load(null)

        assertEquals("", vm.state.value.browserPath)
        assertNull(vm.state.value.browserPathError)
    }

    @Test
    fun `load shows the stored start path as is`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel(browserPath = "/admin?tab=2#top")))
        val vm = newViewModel(repo)

        vm.load("t1")

        assertEquals("/admin?tab=2#top", vm.state.value.browserPath)
    }

    @Test
    fun `load reports an invalid stored start path on open`() {
        // A path written by a sync peer is not guaranteed to pass this device's validation.
        val repo = FakeTunnelRepository(listOf(storedTunnel(browserPath = "/bad path")))
        val vm = newViewModel(repo)

        vm.load("t1")

        assertEquals("/bad path", vm.state.value.browserPath)
        assertEquals(TunnelBrowserPathError.INVALID_CHARACTER, vm.state.value.browserPathError)
    }

    @Test
    fun `load of a valid or unslashed stored start path reports no error`() {
        for (path in listOf("/", "/admin?tab=2", "admin", "")) {
            val vm = newViewModel(FakeTunnelRepository(listOf(storedTunnel(browserPath = path))))

            vm.load("t1")

            assertNull(vm.state.value.browserPathError, "length ${path.length}")
        }
    }

    @Test
    fun `load does not check the stored start path of a non LOCAL_FORWARD tunnel`() {
        val socks = storedTunnel(browserPath = "/bad path").copy(type = TunnelType.DYNAMIC_SOCKS5)
        val vm = newViewModel(FakeTunnelRepository(listOf(socks)))

        vm.load("t1")

        assertNull(vm.state.value.browserPathError)
    }

    @Test
    fun `save after loading an invalid stored start path is blocked until the field is fixed`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel(browserPath = "/bad path")))
        val vm = newViewModel(repo)
        vm.load("t1")

        var done = false
        vm.save { done = true }

        assertFalse(done)
        assertTrue(repo.updated.isEmpty())
        assertEquals(TunnelBrowserPathError.INVALID_CHARACTER, vm.state.value.browserPathError)

        vm.onBrowserPath("/good%20path")
        vm.save { done = true }

        assertTrue(done)
        assertEquals("/good%20path", repo.updated.single().browserPath)
        assertNull(vm.state.value.browserPathError)
    }

    @Test
    fun `save normalizes the start path before storing it`() {
        val cases = mapOf(
            "" to "/",
            "   " to "/",
            "admin" to "/admin",
            "  /admin  " to "/admin",
            "?tab=2" to "/?tab=2",
            "/d/abc?orgId=1#top" to "/d/abc?orgId=1#top",
        )
        for ((typed, expected) in cases) {
            val repo = FakeTunnelRepository()
            val vm = filledNewForm(repo)
            vm.onBrowserPath(typed)

            var done = false
            vm.save { done = true }

            assertTrue(done, "typed \"$typed\" must be accepted")
            assertEquals(expected, repo.saved.single().browserPath, "typed \"$typed\"")
            assertNull(vm.state.value.browserPathError)
        }
    }

    @Test
    fun `save of an existing tunnel keeps its start path when the field is untouched`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel(browserPath = "/admin?tab=2")))
        val vm = newViewModel(repo)
        vm.load("t1")
        vm.onLabel("Renamed")

        vm.save { }

        assertEquals("/admin?tab=2", repo.updated.single().browserPath)
    }

    @Test
    fun `save of an existing tunnel replaces the start path`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel(browserPath = "/old")))
        val vm = newViewModel(repo)
        vm.load("t1")
        vm.onBrowserPath("new")

        vm.save { }

        assertEquals("/new", repo.updated.single().browserPath)
    }

    @Test
    fun `each invalid start path blocks the save and reports its reason`() {
        val cases = listOf(
            "/" + "a".repeat(TunnelBrowserPath.MAX_LENGTH) to TunnelBrowserPathError.TOO_LONG,
            "/a b" to TunnelBrowserPathError.INVALID_CHARACTER,
            "/café" to TunnelBrowserPathError.INVALID_CHARACTER,
            "/a\\b" to TunnelBrowserPathError.INVALID_CHARACTER,
            "/a%zz" to TunnelBrowserPathError.INVALID_PERCENT_ENCODING,
            "/a%2" to TunnelBrowserPathError.INVALID_PERCENT_ENCODING,
            "/a#b#c" to TunnelBrowserPathError.MULTIPLE_FRAGMENT_MARKERS,
        )
        for ((typed, expected) in cases) {
            val repo = FakeTunnelRepository()
            val vm = filledNewForm(repo)
            vm.onBrowserPath(typed)

            var done = false
            vm.save { done = true }

            assertFalse(done, "length ${typed.length} must not be saved")
            assertTrue(repo.saved.isEmpty() && repo.updated.isEmpty(), "nothing is written for $expected")
            assertEquals(expected, vm.state.value.browserPathError)
            assertNull(vm.state.value.error, "the path error does not use the text banner")
            assertFalse(vm.state.value.isSaving)
            // The field keeps what was typed so the user can fix it.
            assertEquals(typed, vm.state.value.browserPath)
        }
    }

    @Test
    fun `a start path of exactly the maximum length is accepted`() {
        val repo = FakeTunnelRepository()
        val vm = filledNewForm(repo)
        val path = "/" + "a".repeat(TunnelBrowserPath.MAX_LENGTH - 1)
        vm.onBrowserPath(path)

        var done = false
        vm.save { done = true }

        assertTrue(done)
        assertEquals(path, repo.saved.single().browserPath)
    }

    @Test
    fun `an invalid start path on an existing tunnel leaves the stored one untouched`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel(browserPath = "/keep")))
        val vm = newViewModel(repo)
        vm.load("t1")
        vm.onBrowserPath("/bad path")

        vm.save { }

        assertTrue(repo.updated.isEmpty())
        assertEquals("/keep", repo.getByIdBlocking("t1").browserPath)
        assertEquals(TunnelBrowserPathError.INVALID_CHARACTER, vm.state.value.browserPathError)
    }

    @Test
    fun `editing the start path clears its error`() {
        val vm = filledNewForm(FakeTunnelRepository())
        vm.onBrowserPath("/a b")
        vm.save { }
        assertEquals(TunnelBrowserPathError.INVALID_CHARACTER, vm.state.value.browserPathError)

        vm.onBrowserPath("/ab")

        assertNull(vm.state.value.browserPathError)
    }

    @Test
    fun `switching the type clears the start path error`() {
        val vm = filledNewForm(FakeTunnelRepository())
        vm.onBrowserPath("/a b")
        vm.save { }

        vm.onType(TunnelType.DYNAMIC_SOCKS5)

        assertNull(vm.state.value.browserPathError)
    }

    @Test
    fun `an invalid start path does not block a non LOCAL_FORWARD tunnel, which stores the root`() {
        for (type in listOf(TunnelType.REMOTE_FORWARD, TunnelType.DYNAMIC_SOCKS5)) {
            val repo = FakeTunnelRepository()
            val vm = filledNewForm(repo)
            // Typed while LOCAL_FORWARD, then the type changes: the hidden field must not matter.
            vm.onBrowserPath("/a b")
            vm.onType(type)

            var done = false
            vm.save { done = true }

            assertTrue(done, "$type")
            assertEquals("/", repo.saved.single().browserPath, "$type")
            assertNull(vm.state.value.browserPathError)
        }
    }

    @Test
    fun `a start path error and a text error are reported together`() {
        val vm = newViewModel(FakeTunnelRepository())
        vm.load(null)
        vm.onBrowserPath("/a b")

        vm.save { }

        assertEquals(TunnelFormError.LABEL_REQUIRED, vm.state.value.error)
        assertEquals(TunnelBrowserPathError.INVALID_CHARACTER, vm.state.value.browserPathError)
    }

    @Test
    fun `a text error alone leaves the start path error empty`() {
        val vm = newViewModel(FakeTunnelRepository())
        vm.load(null)
        vm.onBrowserPath("/ok")

        vm.save { }

        assertEquals(TunnelFormError.LABEL_REQUIRED, vm.state.value.error)
        assertNull(vm.state.value.browserPathError)
    }

    // -- Remote host restorable from a backup (BackupValidator.isValidHostName) ----

    @Test
    fun `a remote host with an inner space is refused and nothing is written`() {
        val repo = FakeTunnelRepository()
        val vm = filledNewForm(repo)
        vm.onRemoteHost("db internal")
        var done = false

        vm.save { done = true }

        assertTrue(vm.state.value.remoteHostInvalid)
        assertFalse(done)
        assertTrue(repo.saved.isEmpty())
        assertFalse(vm.state.value.isSaving)
    }

    @Test
    fun `a remote host with an invisible character or over 255 characters is refused`() {
        val repo = FakeTunnelRepository()
        val vm = filledNewForm(repo)

        vm.onRemoteHost("db" + Char(0x200B) + ".internal")
        assertTrue(vm.state.value.remoteHostInvalid)

        vm.onRemoteHost("a".repeat(256))
        assertTrue(vm.state.value.remoteHostInvalid)
        vm.save { }
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `a remote host surrounded by spaces is accepted and saved trimmed`() {
        val repo = FakeTunnelRepository()
        val vm = filledNewForm(repo)
        vm.onRemoteHost("  db.internal  ")
        var done = false

        vm.save { done = true }

        assertFalse(vm.state.value.remoteHostInvalid)
        assertTrue(done)
        assertEquals("db.internal", repo.saved.single().remoteHost)
    }

    @Test
    fun `a SOCKS5 tunnel ignores the remote host and the remote port`() {
        val repo = FakeTunnelRepository()
        val vm = newViewModel(repo)
        vm.load(null)
        vm.onLabel("Proxy")
        vm.onHost("h1")
        vm.onType(TunnelType.DYNAMIC_SOCKS5)
        vm.onLocalPort("1080")
        vm.onRemoteHost("not a host")
        var done = false

        vm.save { done = true }

        assertFalse(vm.state.value.remoteHostInvalid)
        assertTrue(done)
        val saved = repo.saved.single()
        assertEquals("", saved.remoteHost)
        assertEquals(0, saved.remotePort)
    }

    @Test
    fun `ports outside 1 to 65535 are refused`() {
        val repo = FakeTunnelRepository()
        val vm = filledNewForm(repo)

        vm.onLocalPort("0")
        vm.save { }
        assertTrue(vm.state.value.error != null)

        vm.onLocalPort("9000")
        vm.onRemotePort("65536")
        vm.save { }
        assertTrue(vm.state.value.error != null)
        assertTrue(repo.saved.isEmpty())
    }

    private fun FakeTunnelRepository.getByIdBlocking(id: String): TunnelConfig =
        runBlocking { checkNotNull(getById(id)) }
}
