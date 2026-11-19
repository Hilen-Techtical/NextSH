// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.Transport
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthMethod

/**
 * A real [SshSessionManager] whose connections succeed against fake SSHJ clients, for the tests of
 * who owns a session and when it closes (no SSH server in a unit test). Every connection builds a
 * client of its own, so that two sessions of a test never share a link.
 *
 * Clients are closed where `release` is called ([SshSessionManager.closeScope] is unconfined), so
 * a test sees the effect of a release as soon as it returns.
 */
internal class SessionHarness(val wipeGate: VaultWipeGate = VaultWipeGate()) {

    /** One fake link: the client, and the listener the manager installed on its transport. */
    class Link(val client: SSHClient, private val listener: () -> DisconnectListener) {
        /** How many times the client was closed. */
        var closeCount = 0

        val closed: Boolean
            get() = closeCount > 0

        /** The network cuts: SSHJ tells the transport listener the manager installed. */
        fun cut() = listener().notifyDisconnect(DisconnectReason.CONNECTION_LOST, "link down")
    }

    /** Answers every text with an empty string, unless a test says what a resource reads. */
    val context = mockk<Context>(relaxed = true)
    private val settings = mockk<SettingsDataStore>().also {
        every { it.settingsFlow } returns flowOf(SettingsDataStore.Settings())
    }

    /** The loader of the key files: it hands a fake key provider to the key and certificate paths. */
    val keyManager = mockk<SshKeyManager>().also {
        val provider = mockk<KeyProvider>()
        every { it.loadKeyProviderFromString(any(), any()) } returns provider
        every { it.loadKeyProviderWithCertificate(any(), any(), any()) } returns provider
    }

    val manager = SshSessionManager(
        knownHostsVerifier = mockk(),
        sshKeyManager = keyManager,
        settingsDataStore = settings,
        wipeGate = wipeGate,
        context = context,
    ).also { it.closeScope = CoroutineScope(Dispatchers.Unconfined) }

    /**
     * Runs on the thread of the connection each time the manager starts to watch the transport of
     * a client it has just registered: the moment right after a session entered the registry.
     */
    var afterRegistration: () -> Unit = {}

    /** Runs inside the blocking authentication call of every client, which then succeeds. */
    var duringAuthentication: () -> Unit = {}

    /** What every client answers to `isConnected`: false makes the link dead for all of them. */
    @Volatile
    var linksAlive: Boolean = true

    private val links = mutableListOf<Link>()

    init {
        manager.clientFactory = { newLink().client }
    }

    val host = Host(
        id = "host-1",
        label = "Host 1",
        hostname = "host1.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private fun newLink(): Link {
        val listener = slot<DisconnectListener>()
        val transport = mockk<Transport>(relaxed = true)
        every { transport.disconnectListener = capture(listener) } answers { afterRegistration() }
        val client = mockk<SSHClient>(relaxed = true)
        every { client.transport } returns transport
        every { client.isConnected } answers { linksAlive }
        every { client.isAuthenticated } returns true
        every { client.authPassword(any<String>(), any<String>()) } answers { duringAuthentication() }
        every { client.authPublickey(any<String>(), *anyVararg<KeyProvider>()) } answers { duringAuthentication() }
        every { client.auth(any<String>(), *anyVararg<AuthMethod>()) } answers { duringAuthentication() }
        val link = Link(client) { listener.captured }
        every { client.disconnect() } answers { link.closeCount++ }
        return link.also { links += it }
    }

    /** The link a session runs on. */
    fun linkOf(sessionId: String): Link =
        links.first { it.client === manager.getClient(sessionId) }

    /** Opens a session, as a caller of `connectWithPassword` does: it comes with one reference. */
    suspend fun connect(forHost: Host = host): SshSession =
        (manager.connectWithPassword(forHost, "secret".toCharArray()) as SshResult.Success).data

    /** The number of links closed so far. */
    fun closedLinks(): Int = links.count { it.closed }

    /** True while [sessionId] is in the registry of the manager. */
    fun isRegistered(sessionId: String): Boolean = manager.getClient(sessionId) != null
}
