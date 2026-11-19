// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.usecase

import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelState
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager
import fr.techtical.nextsh.shared.domain.ssh.TunnelSessionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StartTunnelUseCaseTest {

    private val host = Host(
        id = "host-7c1f",
        label = "Prod",
        hostname = "10.0.0.5",
        username = "admin",
        authType = AuthType.FIDO2,
        credentialId = "cred-1",
    )

    private val config = TunnelConfig(
        id = "tunnel-1",
        label = "Web",
        hostId = host.id,
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "localhost",
        remotePort = 80,
    )

    private val manager = FakeTunnelManager()
    private val hosts = FakeHostRepository(mapOf(host.id to host))

    /** What the provider was asked for, in order, and what it answers. */
    private val askedHosts = mutableListOf<Host>()
    private var providerAnswer: SshResult<String> = SshResult.Success("session-1")

    private val provider = TunnelSessionProvider { requested ->
        manager.events += "session"
        askedHosts += requested
        providerAnswer
    }

    private val useCase = StartTunnelUseCase(manager, hosts, provider)

    // ── Dispatch by tunnel type ──────────────────────────────────────────────

    @Test
    fun `local forward starts on the session of the provider`() = runTest {
        val result = useCase(config.copy(type = TunnelType.LOCAL_FORWARD))

        assertEquals(SshResult.Success(Unit), result)
        assertEquals(listOf("local:session-1"), manager.started)
        assertEquals(listOf(host), askedHosts)
    }

    @Test
    fun `remote forward starts on the session of the provider`() = runTest {
        val result = useCase(config.copy(type = TunnelType.REMOTE_FORWARD))

        assertEquals(SshResult.Success(Unit), result)
        assertEquals(listOf("remote:session-1"), manager.started)
    }

    @Test
    fun `dynamic forward starts on the session of the provider`() = runTest {
        val result = useCase(config.copy(type = TunnelType.DYNAMIC_SOCKS5))

        assertEquals(SshResult.Success(Unit), result)
        assertEquals(listOf("dynamic:session-1"), manager.started)
    }

    @Test
    fun `marks the tunnel STARTING before asking for the session`() = runTest {
        useCase(config)

        assertEquals(listOf("starting", "session", "local"), manager.events)
        // Same instance: a reconnection tells its own STARTING from another one by identity.
        assertTrue(manager.startingConfigs.single() === config)
    }

    @Test
    fun `an error of the forward is returned as is and not marked by the use case`() = runTest {
        manager.startResult = SshResult.Error(SshErrorCode.PORT_IN_USE, "Port occupe")

        val result = useCase(config)

        assertEquals(SshResult.Error(SshErrorCode.PORT_IN_USE, "Port occupe"), result)
        assertTrue(manager.errors.isEmpty())
    }

    // ── Host not found ───────────────────────────────────────────────────────

    @Test
    fun `unknown host gives a fixed message without the host id`() = runTest {
        val orphan = config.copy(hostId = "ghost-host-id")

        val result = useCase(orphan)

        val error = assertIs<SshResult.Error>(result)
        assertEquals(SshErrorCode.UNKNOWN, error.code)
        assertEquals("Hôte introuvable", error.message)
        assertFalse(error.message.contains("ghost-host-id"))
        assertEquals(listOf(orphan to "Hôte introuvable"), manager.errors)
        // Nothing was started and no session was asked for.
        assertTrue(askedHosts.isEmpty())
        assertTrue(manager.started.isEmpty())
        assertTrue(manager.startingConfigs.isEmpty())
    }

    // ── Session failure ──────────────────────────────────────────────────────

    @Test
    fun `a failed session marks the error and returns the same error`() = runTest {
        providerAnswer = SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Cle de securite annulee")

        val result = useCase(config)

        assertEquals(SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Cle de securite annulee"), result)
        assertEquals(listOf(config to "Cle de securite annulee"), manager.errors)
        // The tunnel was STARTING while the session was asked for, then went to error.
        assertEquals(listOf("starting", "session", "error"), manager.events)
        assertTrue(manager.started.isEmpty())
    }

    // ── Cancellation ─────────────────────────────────────────────────────────

    @Test
    fun `a cancellation thrown by the provider goes up and is not marked as an error`() = runTest {
        val cancelling = StartTunnelUseCase(manager, hosts) { throw CancellationException("abandoned") }

        assertFailsWith<CancellationException> { cancelling(config) }

        assertTrue(manager.errors.isEmpty())
        assertTrue(manager.started.isEmpty())
    }

    @Test
    fun `cancelling the caller while the session is pending marks no error`() = runTest {
        val pending = StartTunnelUseCase(manager, hosts) { awaitCancellation() }

        val job = launch { pending(config) }
        runCurrent()
        assertEquals(listOf("starting"), manager.events)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(manager.errors.isEmpty())
        assertTrue(manager.started.isEmpty())
    }

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class FakeTunnelManager : SshTunnelManager {
        val events = mutableListOf<String>()
        val started = mutableListOf<String>()
        val startingConfigs = mutableListOf<TunnelConfig>()
        val errors = mutableListOf<Pair<TunnelConfig, String>>()
        var startResult: SshResult<Unit> = SshResult.Success(Unit)

        override val tunnels: Flow<List<TunnelState>> = emptyFlow()

        override suspend fun startLocalForward(config: TunnelConfig, sessionId: String): SshResult<Unit> =
            record("local", sessionId)

        override suspend fun startRemoteForward(config: TunnelConfig, sessionId: String): SshResult<Unit> =
            record("remote", sessionId)

        override suspend fun startDynamicForward(config: TunnelConfig, sessionId: String): SshResult<Unit> =
            record("dynamic", sessionId)

        override suspend fun stopTunnel(tunnelId: String): SshResult<Unit> = SshResult.Success(Unit)

        override fun getTunnelState(tunnelId: String): TunnelState? = null

        override fun markStarting(config: TunnelConfig) {
            events += "starting"
            startingConfigs += config
        }

        override fun markError(config: TunnelConfig, message: String) {
            events += "error"
            errors += config to message
        }

        private fun record(kind: String, sessionId: String): SshResult<Unit> {
            events += kind
            started += "$kind:$sessionId"
            return startResult
        }
    }

    private class FakeHostRepository(private val hosts: Map<String, Host>) : HostRepository {
        override suspend fun getById(id: String): Host? = hosts[id]

        override fun observeAll(): Flow<List<Host>> = unused()
        override fun observeByGroup(group: String): Flow<List<Host>> = unused()
        override fun observeGroups(): Flow<List<String>> = unused()
        override suspend fun save(host: Host) = unused()
        override suspend fun update(host: Host) = unused()
        override suspend fun delete(id: String) = unused()
        override suspend fun updateLastConnected(id: String) = unused()
        override fun observeFavorites(): Flow<List<Host>> = unused()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = unused()
        override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = unused()
        override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) = unused()
        override suspend fun hardDelete(id: String) = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not used by StartTunnelUseCase")
    }
}
