// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression coverage for the "Timeout de connexion" placebo fix.
 *
 * `SSHClient.connectTimeout` must come from [VaultSshClientFactory]'s
 * `connectTimeoutMsProvider` (wired by `DesktopContainer` to the Settings
 * slider): NOT from `host.keepAliveSeconds`, an unrelated per-host field
 * that was previously misused for this purpose (same bug fixed in
 * [DesktopSshSessionManager]).
 *
 * We read the timeout off the REAL (unmocked) `SSHClient` handed back via
 * the `onCreated` callback: [VaultSshClientFactory.open] invokes it right
 * after building the client and setting `connectTimeout`, but BEFORE
 * `connect()` runs. Pointing at an unbound local port makes the subsequent
 * `connect()` fail instantly with "connection refused" (no actual wait),
 * so the test stays fast regardless of the configured timeout.
 */
class VaultSshClientFactoryConnectTimeoutTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val vaultManager = mockk<VaultManager>(relaxed = true)
    private val hostKeyVerifier = mockk<DesktopKnownHostsVerifier>(relaxed = true)

    private fun hostWithKeepAlive(keepAliveSeconds: Int, port: Int) = Host(
        id = "host-1", label = "h", hostname = "127.0.0.1", port = port,
        username = "u", authType = AuthType.PASSWORD, credentialId = "c",
        keepAliveSeconds = keepAliveSeconds,
    )

    @Test
    fun `connectTimeout comes from the provider, not from host keepAliveSeconds`() = runTest {
        val providedTimeoutMs = 4_000
        val factory = VaultSshClientFactory(
            vaultManager = vaultManager,
            hostKeyVerifier = hostKeyVerifier,
            connectTimeoutMsProvider = { providedTimeoutMs },
        )
        // keepAliveSeconds is deliberately far from providedTimeoutMs: if the
        // old bug regressed, connectTimeout would read 55_000 instead of 4_000.
        val host = hostWithKeepAlive(keepAliveSeconds = 55, port = freePort())

        var captured: Int? = null
        factory.open(host) { client -> captured = client.connectTimeout }

        assertEquals(providedTimeoutMs, captured, "connectTimeout must equal the provider's value")
        assertNotEquals(55 * 1000, captured, "connectTimeout must NOT be derived from keepAliveSeconds")
    }

    @Test
    fun `default provider falls back to 10s when the caller doesn't wire one`() = runTest {
        val factory = VaultSshClientFactory(vaultManager = vaultManager, hostKeyVerifier = hostKeyVerifier)
        val host = hostWithKeepAlive(keepAliveSeconds = 55, port = freePort())

        var captured: Int? = null
        factory.open(host) { client -> captured = client.connectTimeout }

        assertEquals(10_000, captured)
    }
}
