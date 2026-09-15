// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopKnownHostsStoreTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: DesktopKnownHostsStore

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NextShDatabase.Schema.create(driver)
        store = DesktopKnownHostsStore(NextShDatabase(driver), nowMs = { 1_700_000_000L })
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `getStored returns null for unknown host`() {
        assertNull(store.getStored("nope:22"))
    }

    @Test
    fun `upsert then getStored roundtrips raw key bytes`() {
        val key = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        store.upsert("prod.example.com:22", "ssh-ed25519", key)

        val stored = store.getStored("prod.example.com:22")
        assertNotNull(stored)
        assertEquals("ssh-ed25519", stored!!.first)
        assertContentEquals(key, stored.second)
    }

    @Test
    fun `upsert replaces an existing entry for the same hostPort`() {
        val k1 = byteArrayOf(0x01)
        val k2 = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        store.upsert("host:22", "ssh-rsa", k1)
        store.upsert("host:22", "ssh-ed25519", k2)

        val stored = store.getStored("host:22")
        assertNotNull(stored)
        assertEquals("ssh-ed25519", stored!!.first, "second upsert should win")
        assertContentEquals(k2, stored.second)
    }

    @Test
    fun `deleteByHostPort returns true when row existed and false otherwise`() {
        store.upsert("a:22", "ssh-ed25519", byteArrayOf(0x01))
        assertTrue(store.deleteByHostPort("a:22"))
        assertFalse(store.deleteByHostPort("a:22"), "second delete is no-op")
        assertNull(store.getStored("a:22"))
    }

    @Test
    fun `getAllEntries returns every row with computed fingerprint`() {
        store.upsert("host1:22", "ssh-ed25519", byteArrayOf(0x01, 0x02))
        store.upsert("host2:22", "ssh-rsa", byteArrayOf(0x03, 0x04))

        val entries = store.getAllEntries()
        assertEquals(2, entries.size)
        val byHost = entries.associateBy { it.hostPort }
        assertNotNull(byHost["host1:22"])
        assertTrue(byHost["host1:22"]!!.fingerprint.startsWith("SHA256:"))
        assertEquals(1_700_000_000L, byHost["host1:22"]!!.addedAt)
    }

    @Test
    fun `deleteAll empties the table`() {
        store.upsert("a:22", "ssh-ed25519", byteArrayOf(0x01))
        store.upsert("b:22", "ssh-rsa", byteArrayOf(0x02))
        store.deleteAll()
        assertEquals(0, store.getAllEntries().size)
    }

    @Test
    fun `sha256Fingerprint produces stable SHA256 base64 with prefix`() {
        val fp = DesktopKnownHostsStore.sha256Fingerprint(byteArrayOf(1, 2, 3))
        assertTrue(fp.startsWith("SHA256:"))
        // Deterministic: same input → same output
        assertEquals(fp, DesktopKnownHostsStore.sha256Fingerprint(byteArrayOf(1, 2, 3)))
    }
}
