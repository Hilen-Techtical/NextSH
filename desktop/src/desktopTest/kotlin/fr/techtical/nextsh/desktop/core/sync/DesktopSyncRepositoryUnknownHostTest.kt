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
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A live tunnel whose host this device has never heard of used to be stored (foreign keys were not
 * enforced). With foreign keys ON that insert fails, and since [DesktopSyncRepository.applyRemoteBundle]
 * has no per-entry try/catch one such tunnel would abort the whole bundle (HTTP 500 for the peer, nothing
 * after the tunnels applied). The tunnel is now skipped and the rest of the bundle goes through.
 *
 * Runs on an in-memory driver with foreign keys ON, the way the production database behaves.
 */
class DesktopSyncRepositoryUnknownHostTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var hostRepository: DesktopHostRepository
    private lateinit var tunnelRepository: DesktopTunnelRepository
    private lateinit var sshKeyRepository: DesktopSshKeyRepository
    private lateinit var snippetRepository: DesktopSnippetRepository
    private lateinit var themeRepository: DesktopCustomTerminalThemeRepository
    private lateinit var syncRepository: DesktopSyncRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        hostRepository = DesktopHostRepository(db)
        tunnelRepository = DesktopTunnelRepository(db)
        sshKeyRepository = DesktopSshKeyRepository(db)
        snippetRepository = DesktopSnippetRepository(db)
        themeRepository = DesktopCustomTerminalThemeRepository(db)
        syncRepository = DesktopSyncRepository(
            hostRepo = hostRepository,
            tunnelRepo = tunnelRepository,
            sshKeyRepo = sshKeyRepository,
            snippetRepo = snippetRepository,
            customThemeRepo = themeRepository,
            pendingConflictRepository = DesktopPendingConflictRepository(db),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `an unknown host tombstone with a live tunnel does not abort the bundle`() = runTest {
        val bundle = SyncBundle(
            hosts = listOf(
                // The peer deleted this host and this device never had it: the merge is clean but writes nothing.
                SyncEntry(
                    id = "h-gone",
                    payload = null,
                    clock = clock(3L),
                    deleted = true,
                    deletedAt = 4_000L,
                    updatedAt = 4_000L,
                ),
                hostEntry("h-new"),
            ),
            tunnels = listOf(
                // Live tunnel of the unknown tombstoned host: cannot be stored.
                tunnelEntry("t-orphan", hostId = "h-gone"),
                tunnelEntry("t-ok", hostId = "h-new"),
            ),
            sshKeys = listOf(keyEntry("k1")),
            snippets = listOf(snippetEntry("s1")),
            customThemes = listOf(themeEntry("c1")),
        )

        val result = syncRepository.applyRemoteBundle(bundle)

        assertTrue(result.conflicts.isEmpty())
        assertNull(db.tunnelQueries.selectByIdIncludingDeleted("t-orphan").executeAsOneOrNull())
        assertNotNull(tunnelRepository.getById("t-ok"))
        assertNotNull(hostRepository.getById("h-new"))
        // Everything after the tunnels in the bundle still applied.
        assertNotNull(sshKeyRepository.getById("k1"))
        assertEquals(1, snippetRepository.getAllSyncEntries().count { it.payload != null })
        assertEquals(1, themeRepository.getAllSyncEntries().count { it.payload != null })
        // Two hosts (the tombstone counts as a clean merge), one tunnel, one key, one snippet, one theme.
        assertEquals(6, result.cleanApplied)
    }

    @Test
    fun `a live tunnel of a host that is nowhere is skipped and the other tunnels apply`() = runTest {
        db.insertHostRow("h-local")

        val result = syncRepository.applyRemoteBundle(
            bundle(
                tunnels = listOf(
                    tunnelEntry("t-orphan", hostId = "h-nowhere"),
                    tunnelEntry("t-local", hostId = "h-local"),
                ),
            ),
        )

        assertTrue(result.conflicts.isEmpty())
        assertEquals(1, result.cleanApplied)
        assertNull(db.tunnelQueries.selectByIdIncludingDeleted("t-orphan").executeAsOneOrNull())
        assertNotNull(tunnelRepository.getById("t-local"))
    }

    @Test
    fun `a live tunnel of a locally tombstoned host is still applied, the host row exists`() = runTest {
        db.insertHostRow("h-deleted")
        hostRepository.delete("h-deleted")

        syncRepository.applyRemoteBundle(bundle(tunnels = listOf(tunnelEntry("t-late", hostId = "h-deleted"))))

        val row = db.tunnelQueries.selectByIdIncludingDeleted("t-late").executeAsOne()
        assertEquals("h-deleted", row.hostId)
        assertEquals(0L, row.deleted)
    }

    @Test
    fun `a tunnel tombstone is never filtered`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        val tombstone = SyncEntry<TunnelConfig>(
            id = "t1",
            payload = null,
            clock = VectorClock(mapOf("other-device" to 9L)),
            deleted = true,
            deletedAt = 9_000L,
            updatedAt = 9_000L,
        )

        syncRepository.applyRemoteBundle(bundle(tunnels = listOf(tombstone)))

        assertEquals(1L, db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne().deleted)
    }

    // -- Helpers ---------------------------------------------------------------

    private fun bundle(tunnels: List<SyncEntry<TunnelConfig>>) = SyncBundle(
        hosts = emptyList(),
        tunnels = tunnels,
        sshKeys = emptyList(),
        snippets = emptyList(),
    )

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

    private fun keyEntry(id: String) = SyncEntry(
        id = id,
        payload = SshKey(
            id = id,
            label = "Key $id",
            keyType = SshKeyType.ED25519,
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA test",
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

    private fun themeEntry(id: String) = SyncEntry(
        id = id,
        payload = CustomTerminalTheme(
            id = id,
            name = "Theme $id",
            background = 0xFF000000.toInt(),
            foreground = 0xFFFFFFFF.toInt(),
            cursor = 0xFFFFFFFF.toInt(),
            selectionBg = 0xFF333333.toInt(),
            ansi = List(CustomTerminalTheme.ANSI_SIZE) { 0xFF000000.toInt() + it },
        ),
        clock = clock(1L),
        updatedAt = 1_000L,
    )
}
