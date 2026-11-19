// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.data.db.repository.DesktopCustomTerminalThemeRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopOrphanTunnelRepair
import fr.techtical.nextsh.desktop.data.db.repository.DesktopPendingConflictRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSnippetRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSshKeyRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopTunnelRepository
import fr.techtical.nextsh.desktop.data.db.repository.enableForeignKeys
import fr.techtical.nextsh.desktop.data.db.repository.insertHostRow
import fr.techtical.nextsh.desktop.data.db.repository.insertTunnelRow
import fr.techtical.nextsh.desktop.data.db.repository.tombstoneHostRowOnly
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.SyncBundle
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [DesktopSyncRepository.applyRemoteBundle] runs the orphan repair once the hosts and the tunnels of a
 * bundle are applied: a bundle can write a live tunnel under a host that is tombstoned on this device (see
 * DesktopSyncRepositoryUnknownHostTest, which pins that the merge alone applies it). Foreign keys ON.
 */
class DesktopSyncRepositoryOrphanRepairTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var hostRepository: DesktopHostRepository
    private lateinit var tunnelRepository: DesktopTunnelRepository
    private lateinit var repair: DesktopOrphanTunnelRepair

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        hostRepository = DesktopHostRepository(db)
        tunnelRepository = DesktopTunnelRepository(db)
        repair = DesktopOrphanTunnelRepair(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun syncRepository(afterApplied: suspend () -> Unit = { repair.run() }) = DesktopSyncRepository(
        hostRepo = hostRepository,
        tunnelRepo = tunnelRepository,
        sshKeyRepo = DesktopSshKeyRepository(db),
        snippetRepo = DesktopSnippetRepository(db),
        customThemeRepo = DesktopCustomTerminalThemeRepository(db),
        pendingConflictRepository = DesktopPendingConflictRepository(db),
        afterHostsAndTunnelsApplied = afterApplied,
    )

    @Test
    fun `a live tunnel pushed under a locally deleted host is tombstoned right after the apply`() = runTest {
        db.insertHostRow("h-deleted")
        hostRepository.delete("h-deleted")

        syncRepository().applyRemoteBundle(bundle(tunnels = listOf(tunnelEntry("t-late", hostId = "h-deleted"))))

        val row = db.tunnelQueries.selectByIdIncludingDeleted("t-late").executeAsOne()
        assertEquals(1L, row.deleted, "the orphan must not stay live")
        val tombstone = tunnelRepository.getAllSyncEntries().single { it.id == "t-late" }
        assertTrue(tombstone.clock.dominates(clock(1L)), "the tombstone must win over the pushed live version")
    }

    @Test
    fun `a host tombstone pushed with a live tunnel of that host leaves no live tunnel behind`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        // An Android 1.0.x peer deleted the host and did not cascade: its bundle holds the tombstone only.
        val hostTombstone = SyncEntry<Host>(
            id = "h1",
            payload = null,
            clock = VectorClock(mapOf("other-device" to 99L)),
            deleted = true,
            deletedAt = 9_000L,
            updatedAt = 9_000L,
        )

        syncRepository().applyRemoteBundle(bundle(hosts = listOf(hostTombstone)))

        assertEquals(1L, db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne().deleted)
        assertEquals(1L, db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne().deleted)
        assertTrue(db.tunnelQueries.selectLiveOfDeletedHosts().executeAsList().isEmpty())
    }

    @Test
    fun `the step runs after the hosts and the tunnels are written`() = runTest {
        val seen = mutableListOf<String>()
        val callback: suspend () -> Unit = {
            seen += "host=" + (db.hostQueries.selectByIdIncludingDeleted("h-new").executeAsOneOrNull() != null)
            seen += "tunnel=" + (db.tunnelQueries.selectByIdIncludingDeleted("t-new").executeAsOneOrNull() != null)
        }

        syncRepository(callback).applyRemoteBundle(
            bundle(hosts = listOf(hostEntry("h-new")), tunnels = listOf(tunnelEntry("t-new", hostId = "h-new"))),
        )

        assertEquals(listOf("host=true", "tunnel=true"), seen)
    }

    @Test
    fun `the step does not run for a bundle without hosts nor tunnels`() = runTest {
        var calls = 0

        syncRepository { calls++ }.applyRemoteBundle(
            bundle(snippets = listOf(snippetEntry("s1"))),
        )
        syncRepository { calls++ }.applyRemoteBundle(bundle())

        assertEquals(0, calls)
    }

    @Test
    fun `a failing step does not fail the sync and the rest of the bundle still applies`() = runTest {
        val result = syncRepository { throw IllegalStateException("disk I/O error") }.applyRemoteBundle(
            bundle(
                hosts = listOf(hostEntry("h-new")),
                snippets = listOf(snippetEntry("s1")),
            ),
        )

        assertEquals(2, result.cleanApplied)
        assertNotNull(hostRepository.getById("h-new"))
        assertEquals(1, DesktopSnippetRepository(db).getAllSyncEntries().count { it.payload != null })
    }

    @Test
    fun `the default step is a no-op, the merge alone applies the live tunnel`() = runTest {
        db.insertHostRow("h-deleted")
        hostRepository.delete("h-deleted")
        val plain = DesktopSyncRepository(
            hostRepo = hostRepository,
            tunnelRepo = tunnelRepository,
            sshKeyRepo = DesktopSshKeyRepository(db),
            snippetRepo = DesktopSnippetRepository(db),
            customThemeRepo = DesktopCustomTerminalThemeRepository(db),
            pendingConflictRepository = DesktopPendingConflictRepository(db),
        )

        plain.applyRemoteBundle(bundle(tunnels = listOf(tunnelEntry("t-late", hostId = "h-deleted"))))

        assertEquals(0L, db.tunnelQueries.selectByIdIncludingDeleted("t-late").executeAsOne().deleted)
    }

    @Test
    fun `a locally deleted host whose deletion is contested keeps its tunnels live`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        // Local: the host is deleted (clock {local:2}). Remote: the peer edited it (clock {peer:1}). Concurrent
        // clocks, different payloads: a conflict the user has to settle.
        db.tombstoneHostRowOnly("h1", clock = VectorClock(mapOf("local-device" to 2L)))
        val remoteEdit = SyncEntry(
            id = "h1",
            payload = Host(
                id = "h1",
                label = "Edited on the peer",
                hostname = "h1.local",
                username = "user",
                authType = AuthType.PASSWORD,
                credentialId = "cred-h1",
            ),
            clock = VectorClock(mapOf("peer-device" to 1L)),
            updatedAt = 5_000L,
        )

        val result = syncRepository().applyRemoteBundle(bundle(hosts = listOf(remoteEdit)))

        assertEquals(1, result.conflicts.size)
        assertEquals(0L, db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne().deleted)
        assertTrue(db.tunnelQueries.selectLiveOfDeletedHosts().executeAsList().isEmpty(), "protected by the pending conflict")
    }

    // -- Helpers -------------------------------------------------------------------

    private fun bundle(
        hosts: List<SyncEntry<Host>> = emptyList(),
        tunnels: List<SyncEntry<TunnelConfig>> = emptyList(),
        snippets: List<SyncEntry<Snippet>> = emptyList(),
    ) = SyncBundle(hosts = hosts, tunnels = tunnels, sshKeys = emptyList(), snippets = snippets)

    private fun clock(counter: Long) = VectorClock(mapOf("other-device" to counter))

    private fun hostEntry(id: String) = SyncEntry(
        id = id,
        payload = Host(
            id = id,
            label = "Host $id",
            hostname = "$id.local",
            username = "user",
            authType = AuthType.PASSWORD,
            credentialId = "cred-$id",
        ),
        clock = clock(1L),
        updatedAt = 1_000L,
    )

    private fun tunnelEntry(id: String, hostId: String) = SyncEntry(
        id = id,
        payload = TunnelConfig(
            id = id,
            label = "Tunnel $id",
            hostId = hostId,
            type = TunnelType.LOCAL_FORWARD,
            localPort = 8080,
            remoteHost = "localhost",
            remotePort = 80,
        ),
        clock = clock(1L),
        updatedAt = 1_000L,
    )

    private fun snippetEntry(id: String) = SyncEntry(
        id = id,
        payload = Snippet(id = id, label = "Snippet $id", command = "uptime"),
        clock = clock(1L),
        updatedAt = 1_000L,
    )
}
