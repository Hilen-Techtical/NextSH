// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSshKeyRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopTunnelRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopVaultRestoreStore
import fr.techtical.nextsh.desktop.data.db.repository.SEED_CREATED_AT
import fr.techtical.nextsh.desktop.data.db.repository.SEED_LAST_CONNECTED_AT
import fr.techtical.nextsh.desktop.data.db.repository.SEED_OTHER_COUNTER
import fr.techtical.nextsh.desktop.data.db.repository.SEED_OTHER_DEVICE
import fr.techtical.nextsh.desktop.data.db.repository.SEED_UPDATED_AT_MS
import fr.techtical.nextsh.desktop.data.db.repository.enableForeignKeys
import fr.techtical.nextsh.desktop.data.db.repository.insertHostRow
import fr.techtical.nextsh.desktop.data.db.repository.insertTunnelRow
import fr.techtical.nextsh.desktop.data.db.repository.seedClock
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Restore over an existing database, through the real repositories and [DesktopVaultRestoreStore], with
 * foreign keys ON on an in-memory driver. Restore is a merge where the backup wins: an existing item is
 * updated in place, a new one is inserted, a local item absent from the backup is kept.
 *
 * The same behaviour on a FILE database opened through `DatabaseFactory.openAt` is in
 * [DesktopVaultImporterRestoreOnFileTest].
 */
class DesktopVaultImporterRestoreTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var hostRepository: DesktopHostRepository
    private lateinit var tunnelRepository: DesktopTunnelRepository
    private lateinit var keyRepository: DesktopSshKeyRepository
    private lateinit var vault: FakeVaultManagerMem
    private lateinit var importer: DesktopVaultImporter

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        hostRepository = DesktopHostRepository(db)
        tunnelRepository = DesktopTunnelRepository(db)
        keyRepository = DesktopSshKeyRepository(db)
        vault = FakeVaultManagerMem()
        importer = DesktopVaultImporter(vaultManager = vault, restoreStore = DesktopVaultRestoreStore(db), settings = FakeBackupSettings())
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `restoring over an existing host updates it in place and keeps its local tunnels`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t-local", hostId = "h1")
        db.insertTunnelRow("t-both", hostId = "h1")
        val localTunnelBefore = db.tunnelQueries.selectByIdIncludingDeleted("t-local").executeAsOne()
        val blob = buildBackupBlob(
            hosts = listOf(backupHost("h1", label = "From backup", isFavorite = true)),
            tunnels = listOf(backupTunnel("t-both", hostId = "h1", label = "Tunnel from backup")),
        )

        val summary = importOk(blob)

        assertEquals(1, summary.hosts)
        assertEquals(1, summary.tunnels)
        assertEquals(0, summary.tunnelsSkipped)
        val hostRow = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        assertEquals("From backup", hostRow.label)
        assertEquals(1L, hostRow.isFavorite)
        assertEquals(SEED_CREATED_AT, hostRow.createdAt, "createdAt is local-only")
        assertEquals(SEED_LAST_CONNECTED_AT, hostRow.lastConnectedAt, "lastConnectedAt is local-only")
        val hostClock = VectorClockCodec.decode(hostRow.vectorClock)
        assertTrue(hostClock.dominates(seedClock()), "the restored version must win on the peers")
        assertEquals(SEED_OTHER_COUNTER, hostClock.entries[SEED_OTHER_DEVICE])
        // The local tunnel that the backup does not mention is untouched.
        assertEquals(localTunnelBefore, db.tunnelQueries.selectByIdIncludingDeleted("t-local").executeAsOne())
        // The tunnel that is in the backup is updated in place.
        val bothRow = db.tunnelQueries.selectByIdIncludingDeleted("t-both").executeAsOne()
        assertEquals("Tunnel from backup", bothRow.label)
        assertEquals(SEED_CREATED_AT, bothRow.createdAt)
        assertTrue(VectorClockCodec.decode(bothRow.vectorClock).dominates(seedClock()))
    }

    @Test
    fun `a tombstoned host and its tombstoned tunnel in the backup are revived`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        db.insertTunnelRow("t-gone", hostId = "h1")
        hostRepository.delete("h1")
        val hostTombstone = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        val tunnelTombstone = db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne()
        assertEquals(1L, hostTombstone.deleted)
        assertEquals(1L, tunnelTombstone.deleted)
        val blob = buildBackupBlob(
            hosts = listOf(backupHost("h1", label = "Back from the dead")),
            tunnels = listOf(backupTunnel("t1", hostId = "h1", label = "Back too")),
        )

        importOk(blob)

        val hostRow = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        assertEquals(0L, hostRow.deleted)
        assertNull(hostRow.deletedAt)
        assertTrue(
            VectorClockCodec.decode(hostRow.vectorClock).dominates(VectorClockCodec.decode(hostTombstone.vectorClock)),
        )
        val tunnelRow = db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne()
        assertEquals(0L, tunnelRow.deleted)
        assertNull(tunnelRow.deletedAt)
        assertEquals(SEED_CREATED_AT, tunnelRow.createdAt)
        assertTrue(
            VectorClockCodec.decode(tunnelRow.vectorClock).dominates(VectorClockCodec.decode(tunnelTombstone.vectorClock)),
        )
        // A tunnel the backup does not carry stays a tombstone.
        assertEquals(1L, db.tunnelQueries.selectByIdIncludingDeleted("t-gone").executeAsOne().deleted)
        assertEquals("Back from the dead", hostRepository.getById("h1")?.label)
    }

    @Test
    fun `tunnels whose host is neither in the backup nor live on this device are skipped and counted`() = runTest {
        db.insertHostRow("h-local")
        db.insertHostRow("h-dead")
        hostRepository.delete("h-dead")
        val blob = buildBackupBlob(
            hosts = listOf(backupHost("h-new", label = "New host")),
            tunnels = listOf(
                backupTunnel("t-new", hostId = "h-new", label = "Host in the backup"),
                backupTunnel("t-local-host", hostId = "h-local", label = "Host only on this device"),
                backupTunnel("t-orphan", hostId = "h-nowhere", label = "Host nowhere"),
                backupTunnel("t-dead-host", hostId = "h-dead", label = "Host deleted on this device"),
            ),
        )

        val summary = importOk(blob)

        assertEquals(2, summary.tunnels)
        assertEquals(2, summary.tunnelsSkipped)
        assertEquals(3, summary.rows().sumOf { it.second }, "skipped tunnels are not part of the restored total")
        assertNotNull(tunnelRepository.getById("t-new"))
        assertNotNull(tunnelRepository.getById("t-local-host"))
        assertNull(db.tunnelQueries.selectByIdIncludingDeleted("t-orphan").executeAsOneOrNull())
        assertNull(db.tunnelQueries.selectByIdIncludingDeleted("t-dead-host").executeAsOneOrNull())
    }

    @Test
    fun `restoring an SSH key over an existing one keeps createdAt and revives a tombstone`() = runTest {
        insertKeyRow("k1")
        keyRepository.delete("k1")
        val tombstone = db.sshKeyQueries.selectByIdIncludingDeleted("k1").executeAsOne()
        val blob = buildBackupBlob(keys = listOf(backupKey("k1", label = "Backup label")))

        val summary = importOk(blob)

        assertEquals(1, summary.sshKeys)
        val row = db.sshKeyQueries.selectByIdIncludingDeleted("k1").executeAsOne()
        assertEquals("Backup label", row.label)
        assertEquals(0L, row.deleted)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertTrue(VectorClockCodec.decode(row.vectorClock).dominates(VectorClockCodec.decode(tombstone.vectorClock)))
    }

    @Test
    fun `restoring the same backup twice does not touch the rows the second time`() = runTest {
        val blob = buildBackupBlob(
            hosts = listOf(backupHost("h1", label = "Host")),
            keys = listOf(backupKey("k1", label = "Key")),
            tunnels = listOf(backupTunnel("t1", hostId = "h1", label = "Tunnel")),
        )
        importOk(blob)
        val hostAfterFirst = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        val keyAfterFirst = db.sshKeyQueries.selectByIdIncludingDeleted("k1").executeAsOne()
        val tunnelAfterFirst = db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne()

        val second = importOk(blob)

        assertEquals(1, second.hosts, "the summary still counts what the backup carries")
        assertEquals(hostAfterFirst, db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne())
        assertEquals(keyAfterFirst, db.sshKeyQueries.selectByIdIncludingDeleted("k1").executeAsOne())
        assertEquals(tunnelAfterFirst, db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne())
    }

    @Test
    fun `a live item that already holds the backup content keeps the clock a peer gave it`() = runTest {
        // Same content as the seeded row, but the row carries a peer clock and updatedAt: re-importing must
        // not tick them, or the restore would show up as a fresh edit on this device.
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        val hostBefore = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        val tunnelBefore = db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne()
        val sameAsLocalHost = Host(
            id = "h1",
            label = "Host h1",
            hostname = "h1.local",
            username = "user",
            authType = AuthType.PASSWORD,
            credentialId = "cred-h1",
        )
        val sameAsLocalTunnel = tunnelRepository.getById("t1") ?: error("seeded tunnel missing")
        val blob = buildBackupBlob(hosts = listOf(sameAsLocalHost), tunnels = listOf(sameAsLocalTunnel))

        importOk(blob)

        assertEquals(hostBefore, db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne())
        assertEquals(tunnelBefore, db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne())
        assertEquals(SEED_UPDATED_AT_MS, hostBefore.updatedAt)
    }

    @Test
    fun `a backup item that differs from the live one is written and ticks its clock`() = runTest {
        db.insertHostRow("h1")
        db.insertHostRow("h2")
        val h2Before = db.hostQueries.selectByIdIncludingDeleted("h2").executeAsOne()
        val blob = buildBackupBlob(hosts = listOf(backupHost("h1", label = "Edited in the backup")))

        importOk(blob)

        val h1 = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        assertEquals("Edited in the backup", h1.label)
        assertTrue(VectorClockCodec.decode(h1.vectorClock).dominates(seedClock()))
        assertEquals(h2Before, db.hostQueries.selectByIdIncludingDeleted("h2").executeAsOne(), "not in the backup: kept")
    }

    @Test
    fun `restore is one transaction, a failing row leaves the database and the vault untouched`() = runTest {
        db.insertHostRow("h1")
        val hostBefore = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        // Every new tunnel row is refused: the restore fails after the hosts were already written.
        driver.execute(
            null,
            "CREATE TRIGGER refuse_tunnel BEFORE INSERT ON tunnels BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
            0,
        )
        val blob = buildBackupBlob(
            hosts = listOf(backupHost("h1", label = "Changed by the backup"), backupHost("h-new", label = "New host")),
            tunnels = listOf(backupTunnel("t-new", hostId = "h-new", label = "New tunnel")),
            credentials = mapOf("cred-h-new" to "s3cret"),
        )

        val result = importer.import(blob, RESTORE_TEST_PASSPHRASE.toCharArray())

        assertIs<VaultImportError.IoError>(result.exceptionOrNull())
        assertEquals(hostBefore, db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne())
        assertNull(db.hostQueries.selectByIdIncludingDeleted("h-new").executeAsOneOrNull())
        assertNull(vault.getPassword("cred-h-new"), "credentials are written only after the database part succeeded")
    }

    @Test
    fun `credentials and private keys are still written back after the database part`() = runTest {
        val blob = buildBackupBlob(
            hosts = listOf(backupHost("h1", label = "Host")),
            keys = listOf(backupKey("k1", label = "Key")),
            credentials = mapOf("cred-h1" to "s3cret"),
            privateKeys = mapOf("k1" to "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----\n"),
        )

        val summary = importOk(blob)

        assertEquals(1, summary.passwords)
        assertEquals(1, summary.privateKeys)
        assertContentEquals("s3cret".toCharArray(), vault.getPassword("cred-h1"))
        assertNotNull(vault.getPrivateKey("k1"))
    }

    // -- Helpers ---------------------------------------------------------------

    private suspend fun importOk(blob: ByteArray): BackupImportReport {
        val result = importer.import(blob, RESTORE_TEST_PASSPHRASE.toCharArray())
        return result.getOrNull() ?: error("import failed: ${result.exceptionOrNull()}")
    }

    private fun insertKeyRow(id: String) {
        db.sshKeyQueries.insert(
            id = id,
            label = "Local label",
            keyType = "ED25519",
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA local",
            isBiometric = 0L,
            keystoreAlias = null,
            fido2CredentialId = null,
            fido2RpId = null,
            createdAt = SEED_CREATED_AT,
            vectorClock = VectorClockCodec.encode(seedClock()),
            deleted = 0L,
            deletedAt = null,
            updatedAt = 1_000L,
        )
    }
}
