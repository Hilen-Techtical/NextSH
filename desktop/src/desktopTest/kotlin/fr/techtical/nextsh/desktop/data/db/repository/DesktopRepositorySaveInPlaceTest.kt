// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
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
 * `save` on an id that already exists rewrites the row IN PLACE. It used to be INSERT OR REPLACE, which is a
 * DELETE then an INSERT: with foreign keys ON that wipes the tunnels of a host, and in every case it resets
 * the local-only columns (createdAt, lastConnectedAt) and the vector clock.
 *
 * Foreign keys are ON here, the way the production database behaves.
 */
class DesktopRepositorySaveInPlaceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var hostRepository: DesktopHostRepository
    private lateinit var tunnelRepository: DesktopTunnelRepository
    private lateinit var keyRepository: DesktopSshKeyRepository

    private val deviceId = DeviceIdentity.deviceId()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        hostRepository = DesktopHostRepository(db)
        tunnelRepository = DesktopTunnelRepository(db)
        keyRepository = DesktopSshKeyRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // -- Hosts -----------------------------------------------------------------

    @Test
    fun `host save on an existing id keeps createdAt, lastConnectedAt and its tunnels, and ticks the clock`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        val tunnelBefore = db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne()
        val before = System.currentTimeMillis()

        hostRepository.save(host("h1", label = "From backup", isFavorite = true))

        val row = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        assertEquals("From backup", row.label)
        assertEquals(1L, row.isFavorite)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertEquals(SEED_LAST_CONNECTED_AT, row.lastConnectedAt)
        assertEquals(0L, row.deleted)
        assertNull(row.deletedAt)
        assertTrue(row.updatedAt >= before)
        val clock = VectorClockCodec.decode(row.vectorClock)
        assertTrue(clock.dominates(seedClock()), "the clock must dominate the previous one")
        assertEquals(SEED_OTHER_COUNTER, clock.entries[SEED_OTHER_DEVICE])
        assertNotNull(clock.entries[deviceId])
        // No DELETE happened, so the foreign key cascade did not fire.
        assertEquals(tunnelBefore, db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne())
    }

    @Test
    fun `host save on a tombstoned id revives it in place and the clock dominates the tombstone`() = runTest {
        db.insertHostRow("h1")
        hostRepository.delete("h1")
        val tombstone = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        assertEquals(1L, tombstone.deleted)

        hostRepository.save(host("h1", label = "Back"))

        val row = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        assertEquals(0L, row.deleted)
        assertNull(row.deletedAt)
        assertEquals("Back", row.label)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertEquals(SEED_LAST_CONNECTED_AT, row.lastConnectedAt)
        assertTrue(
            VectorClockCodec.decode(row.vectorClock).dominates(VectorClockCodec.decode(tombstone.vectorClock)),
        )
        assertEquals("Back", hostRepository.getById("h1")?.label)
    }

    @Test
    fun `host save on a new id inserts it with a fresh clock and no lastConnectedAt`() = runTest {
        val before = System.currentTimeMillis()

        hostRepository.save(host("h-new", label = "Fresh"))

        val row = db.hostQueries.selectByIdIncludingDeleted("h-new").executeAsOne()
        assertEquals("Fresh", row.label)
        assertTrue(row.createdAt >= before)
        assertNull(row.lastConnectedAt)
        assertEquals(0L, row.deleted)
        assertEquals(setOf(deviceId), VectorClockCodec.decode(row.vectorClock).entries.keys)
    }

    // -- Tunnels ---------------------------------------------------------------

    @Test
    fun `tunnel save on an existing id keeps createdAt and ticks the clock`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1", browserPath = "/old")
        val before = System.currentTimeMillis()

        tunnelRepository.save(tunnel("t1", hostId = "h1", label = "From backup", browserPath = "/new"))

        val row = db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne()
        assertEquals("From backup", row.label)
        assertEquals("/new", row.browserPath)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertEquals(0L, row.deleted)
        assertTrue(row.updatedAt >= before)
        val clock = VectorClockCodec.decode(row.vectorClock)
        assertTrue(clock.dominates(seedClock()))
        assertEquals(SEED_OTHER_COUNTER, clock.entries[SEED_OTHER_DEVICE])
    }

    @Test
    fun `tunnel save on a tombstoned id revives it`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        tunnelRepository.delete("t1")
        val tombstone = db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne()

        tunnelRepository.save(tunnel("t1", hostId = "h1", label = "Back"))

        val row = db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne()
        assertEquals(0L, row.deleted)
        assertNull(row.deletedAt)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertTrue(VectorClockCodec.decode(row.vectorClock).dominates(VectorClockCodec.decode(tombstone.vectorClock)))
        assertEquals("Back", tunnelRepository.getById("t1")?.label)
    }

    // -- SSH keys --------------------------------------------------------------

    @Test
    fun `key save on an existing id keeps createdAt, revives a tombstone and ticks the clock`() = runTest {
        insertKeyRow("k1")
        keyRepository.delete("k1")
        val tombstone = db.sshKeyQueries.selectByIdIncludingDeleted("k1").executeAsOne()
        assertEquals(1L, tombstone.deleted)

        keyRepository.save(key("k1", label = "From backup"))

        val row = db.sshKeyQueries.selectByIdIncludingDeleted("k1").executeAsOne()
        assertEquals("From backup", row.label)
        assertEquals(0L, row.deleted)
        assertNull(row.deletedAt)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertTrue(VectorClockCodec.decode(row.vectorClock).dominates(VectorClockCodec.decode(tombstone.vectorClock)))
        assertEquals("From backup", keyRepository.getById("k1")?.label)
    }

    @Test
    fun `key save on a new id inserts it`() = runTest {
        keyRepository.save(key("k-new", label = "Fresh"))

        assertEquals("Fresh", keyRepository.getById("k-new")?.label)
    }

    @Test
    fun `key sync upsert updates the row in place and keeps createdAt`() = runTest {
        insertKeyRow("k1")

        keyRepository.upsertSyncEntry(
            SyncEntry(
                id = "k1",
                payload = key("k1", label = "Renamed remotely"),
                clock = VectorClock(mapOf(SEED_OTHER_DEVICE to SEED_OTHER_COUNTER + 1)),
                updatedAt = 5_000L,
            ),
        )

        val row = db.sshKeyQueries.selectByIdIncludingDeleted("k1").executeAsOne()
        assertEquals("Renamed remotely", row.label)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertEquals(5_000L, row.updatedAt)
    }

    @Test
    fun `key sync upsert of a new key inserts it and a tombstone marks it deleted`() = runTest {
        keyRepository.upsertSyncEntry(
            SyncEntry(
                id = "k2",
                payload = key("k2", label = "Remote key"),
                clock = VectorClock(mapOf(SEED_OTHER_DEVICE to 1L)),
                updatedAt = 1_000L,
            ),
        )
        assertEquals("Remote key", keyRepository.getById("k2")?.label)

        keyRepository.upsertSyncEntry(
            SyncEntry(
                id = "k2",
                payload = null,
                clock = VectorClock(mapOf(SEED_OTHER_DEVICE to 2L)),
                deleted = true,
                deletedAt = 2_000L,
                updatedAt = 2_000L,
            ),
        )

        assertNull(keyRepository.getById("k2"))
        assertEquals(1L, db.sshKeyQueries.selectByIdIncludingDeleted("k2").executeAsOne().deleted)
    }

    // -- Helpers ---------------------------------------------------------------

    private fun insertKeyRow(id: String) {
        db.sshKeyQueries.insert(
            id = id,
            label = "Key $id",
            keyType = "ED25519",
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA seeded",
            isBiometric = 0L,
            keystoreAlias = null,
            fido2CredentialId = null,
            fido2RpId = null,
            createdAt = SEED_CREATED_AT,
            vectorClock = VectorClockCodec.encode(seedClock()),
            deleted = 0L,
            deletedAt = null,
            updatedAt = SEED_UPDATED_AT_MS,
        )
    }

    private fun host(id: String, label: String, isFavorite: Boolean = false) = Host(
        id = id,
        label = label,
        hostname = "$id.backup",
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
        isFavorite = isFavorite,
    )

    private fun tunnel(id: String, hostId: String, label: String, browserPath: String = "/") = TunnelConfig(
        id = id,
        label = label,
        hostId = hostId,
        type = TunnelType.LOCAL_FORWARD,
        localPort = 9090,
        remoteHost = "db.internal",
        remotePort = 5432,
        browserPath = browserPath,
    )

    private fun key(id: String, label: String) = SshKey(
        id = id,
        label = label,
        keyType = SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA backup",
    )
}
