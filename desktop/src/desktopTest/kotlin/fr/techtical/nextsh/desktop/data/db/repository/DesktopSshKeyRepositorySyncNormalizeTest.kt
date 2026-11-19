// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An SSH key row received from a sync peer goes through the same storage rule as a restore
 * (`SshKeyRules.normalizeForStorage`): the public line is cleaned, an entry that cannot be stored is dropped.
 */
class DesktopSshKeyRepositorySyncNormalizeTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var repository: DesktopSshKeyRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        repository = DesktopSshKeyRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun entry(key: SshKey) = SyncEntry(
        id = key.id,
        payload = key,
        clock = seedClock(),
        updatedAt = SEED_UPDATED_AT_MS,
    )

    private fun key(id: String, line: String, label: String = "Peer key") = SshKey(
        id = id,
        label = label,
        keyType = SshKeyType.ED25519,
        publicKey = line,
    )

    @Test
    fun `a public line on several lines is stored cleaned on one line`() = runTest {
        repository.upsertSyncEntry(entry(key("k1", "ssh-ed25519 AAAA\tcomment\nssh-ed25519 BBBB")))

        val stored = assertNotNull(repository.getById("k1")).publicKey
        assertTrue(stored.startsWith("ssh-ed25519 AAAA"))
        assertTrue(stored.none { it.isISOControl() }, "a second key injected on a new line stays inside the comment")
    }

    @Test
    fun `a clean key is stored as received`() = runTest {
        repository.upsertSyncEntry(entry(key("k1", "ssh-ed25519 AAAA laptop")))

        assertEquals("ssh-ed25519 AAAA laptop", repository.getById("k1")?.publicKey)
    }

    @Test
    fun `an update of an existing row is normalized too`() = runTest {
        repository.upsertSyncEntry(entry(key("k1", "ssh-ed25519 AAAA laptop")))

        repository.upsertSyncEntry(entry(key("k1", "ssh-ed25519 BBBB\nlaptop")))

        val stored = assertNotNull(repository.getById("k1")).publicKey
        assertTrue(stored.startsWith("ssh-ed25519 BBBB"))
        assertTrue(stored.none { it.isISOControl() })
    }

    @Test
    fun `an entry with an invalid id is dropped and nothing is written`() = runTest {
        val badId = "k" + String(Character.toChars(1)) + "1"

        repository.upsertSyncEntry(entry(key(badId, "ssh-ed25519 AAAA laptop")))

        assertNull(repository.getById(badId))
        assertTrue(db.sshKeyQueries.selectAllIncludingDeleted().executeAsList().isEmpty())
    }

    @Test
    fun `a public line over 64 KiB drops the entry and keeps the existing row`() = runTest {
        repository.upsertSyncEntry(entry(key("k1", "ssh-ed25519 AAAA laptop")))

        repository.upsertSyncEntry(entry(key("k1", "ssh-ed25519 " + "A".repeat(64 * 1024 + 1))))

        assertEquals("ssh-ed25519 AAAA laptop", repository.getById("k1")?.publicKey)
    }
}
