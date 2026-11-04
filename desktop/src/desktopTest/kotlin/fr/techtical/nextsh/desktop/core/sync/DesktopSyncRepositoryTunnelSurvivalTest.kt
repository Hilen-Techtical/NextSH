// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.data.db.repository.DesktopCustomTerminalThemeRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopPendingConflictRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSnippetRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSshKeyRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopTunnelRepository
import fr.techtical.nextsh.desktop.data.db.repository.enableForeignKeys
import fr.techtical.nextsh.desktop.data.db.repository.insertHostRow
import fr.techtical.nextsh.desktop.data.db.repository.insertTunnelRow
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.SyncBundle
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Through the real [DesktopSyncRepository] with foreign keys ON so the cascade is observable: hosts are
 * merged before tunnels, so a host write that wiped its tunnels made the tunnel merge take the remote
 * version blindly and lose every local tunnel change.
 */
class DesktopSyncRepositoryTunnelSurvivalTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var hostRepository: DesktopHostRepository
    private lateinit var tunnelRepository: DesktopTunnelRepository
    private lateinit var syncRepository: DesktopSyncRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        hostRepository = DesktopHostRepository(db)
        tunnelRepository = DesktopTunnelRepository(db)
        syncRepository = DesktopSyncRepository(
            hostRepo = hostRepository,
            tunnelRepo = tunnelRepository,
            sshKeyRepo = DesktopSshKeyRepository(db),
            snippetRepo = DesktopSnippetRepository(db),
            customThemeRepo = DesktopCustomTerminalThemeRepository(db),
            pendingConflictRepository = DesktopPendingConflictRepository(db),
        )
        // Both devices agree on the host and the tunnel (clock {other-device: 5}), then this device
        // changes the tunnel before the next sync.
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a tunnel favorited locally survives a bundle with its host and the older remote tunnel`() = runTest {
        val remoteHost = hostRepository.getAllSyncEntries().single()
        val remoteOlderTunnel = tunnelRepository.getAllSyncEntries().single()
        tunnelRepository.setFavorite("t1", true)
        val localTunnel = tunnelRepository.getAllSyncEntries().single()
        assertTrue(localTunnel.clock.dominates(remoteOlderTunnel.clock))

        val result = syncRepository.applyRemoteBundle(bundle(hosts = listOf(remoteHost), tunnels = listOf(remoteOlderTunnel)))

        assertTrue(result.conflicts.isEmpty())
        val after = tunnelRepository.getAllSyncEntries().single()
        assertEquals(true, after.payload?.isFavorite)
        assertEquals(localTunnel.clock, after.clock)
        assertEquals(localTunnel.updatedAt, after.updatedAt)
    }

    @Test
    fun `a tunnel favorited locally survives when the remote host is newer too`() = runTest {
        val remoteOlderTunnel = tunnelRepository.getAllSyncEntries().single()
        tunnelRepository.setFavorite("t1", true)
        val localTunnel = tunnelRepository.getAllSyncEntries().single()
        val newerRemoteHost = hostEntry(label = "Renamed remotely", counter = 6L)

        syncRepository.applyRemoteBundle(bundle(hosts = listOf(newerRemoteHost), tunnels = listOf(remoteOlderTunnel)))

        assertEquals("Renamed remotely", hostRepository.getById("h1")?.label)
        assertEquals(localTunnel, tunnelRepository.getAllSyncEntries().single())
    }

    @Test
    fun `a tunnel that exists only locally survives the sync of its host`() = runTest {
        db.insertTunnelRow("local-only", hostId = "h1", isFavorite = true)
        val localOnlyBefore = tunnelRepository.getAllSyncEntries().single { it.id == "local-only" }
        val newerRemoteHost = hostEntry(label = "Renamed remotely", counter = 6L)

        syncRepository.applyRemoteBundle(bundle(hosts = listOf(newerRemoteHost), tunnels = emptyList()))

        assertEquals("Renamed remotely", hostRepository.getById("h1")?.label)
        assertEquals(localOnlyBefore, tunnelRepository.getAllSyncEntries().single { it.id == "local-only" })
        assertEquals(2, db.tunnelQueries.selectAllIncludingDeleted().executeAsList().size)
    }

    @Test
    fun `a tunnel deleted locally stays deleted when the older remote tunnel arrives with its host`() = runTest {
        val remoteHost = hostRepository.getAllSyncEntries().single()
        val remoteOlderTunnel = tunnelRepository.getAllSyncEntries().single()
        tunnelRepository.delete("t1")

        syncRepository.applyRemoteBundle(bundle(hosts = listOf(remoteHost), tunnels = listOf(remoteOlderTunnel)))

        val after = tunnelRepository.getAllSyncEntries().single()
        assertTrue(after.deleted)
        assertEquals(null, tunnelRepository.getById("t1"))
    }

    private fun bundle(hosts: List<SyncEntry<Host>>, tunnels: List<SyncEntry<TunnelConfig>>) = SyncBundle(
        hosts = hosts,
        tunnels = tunnels,
        sshKeys = emptyList(),
        snippets = emptyList(),
    )

    private fun hostEntry(label: String, counter: Long): SyncEntry<Host> = SyncEntry(
        id = "h1",
        payload = Host(
            id = "h1",
            label = label,
            hostname = "h1.local",
            username = "user",
            authType = AuthType.PASSWORD,
            credentialId = "cred-h1",
        ),
        clock = VectorClock(mapOf("other-device" to counter)),
        updatedAt = 5_000L,
    )
}
