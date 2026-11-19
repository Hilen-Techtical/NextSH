// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.CustomTerminalThemeDao
import fr.techtical.nextsh.data.db.dao.SnippetDao
import fr.techtical.nextsh.data.db.entity.CustomTerminalThemeEntity
import fr.techtical.nextsh.data.db.entity.SnippetEntity
import fr.techtical.nextsh.shared.core.backup.BackupCustomTheme
import fr.techtical.nextsh.shared.core.backup.BackupSnippet
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * The restore of snippets and themes from a backup: tombstones included, nothing written for an
 * identical live row, otherwise the clock ticked from the stored one (never restarted from "{}",
 * which would look concurrent to every peer and raise a false conflict).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class BackupRestoreStoreTest {

    @MockK
    private lateinit var snippetDao: SnippetDao

    @MockK
    private lateinit var themeDao: CustomTerminalThemeDao

    @MockK
    private lateinit var hostDao: fr.techtical.nextsh.data.db.dao.HostDao

    private lateinit var store: BackupRestoreStore

    @BeforeEach
    fun setUp() {
        store = BackupRestoreStore(snippetDao, themeDao, hostDao)
    }

    private fun backupSnippet(id: String = "s1", command: String = "uptime") =
        BackupSnippet(id = id, label = "Uptime", command = command, category = "System", hostId = "h1", createdAt = 5L)

    private fun backupTheme(id: String = THEME_ID, name: String = "Night") = BackupCustomTheme(
        id = id,
        name = name,
        background = -16777216,
        foreground = -1,
        cursor = -1,
        selectionBg = 0x40FFFFFF,
        ansi = List(16) { -16777216 },
        createdAt = 7L,
    )

    private fun storedSnippet(clock: String, deleted: Boolean = false, command: String = "uptime") = SnippetEntity(
        id = "s1",
        label = "Uptime",
        command = command,
        category = "System",
        hostId = "h1",
        createdAt = 5L,
        vectorClock = clock,
        deleted = deleted,
        deletedAt = if (deleted) 10L else null,
        updatedAt = 10L,
    )

    private fun storedTheme(clock: String, deleted: Boolean = false, name: String = "Night") =
        CustomTerminalThemeEntity.fromDomain(
            fr.techtical.nextsh.domain.model.CustomTerminalTheme(
                id = THEME_ID,
                name = name,
                background = -16777216,
                foreground = -1,
                cursor = -1,
                selectionBg = 0x40FFFFFF,
                ansi = List(16) { -16777216 },
                createdAt = 7L,
            ),
        ).copy(vectorClock = clock, deleted = deleted, deletedAt = if (deleted) 10L else null, updatedAt = 10L)

    /** A stored clock JSON in the real format, where only a peer has written. */
private fun peerClock(counter: Long): String = VectorClockCodec.encode(VectorClock(mapOf("peer" to counter)))

/** The counter of this device in a stored clock JSON. */
    private fun ownCounter(clockJson: String): Long =
        VectorClockCodec.decode(clockJson).entries[DeviceIdentity.deviceId()] ?: 0L

    // ── Snippets ────────────────────────────────────────────────────────────

    @Test
    fun `a new snippet is inserted with a fresh clock`() = runTest {
        coEvery { snippetDao.getByIdIncludingDeleted("s1") } returns null
        val written = slot<SnippetEntity>()
        coEvery { snippetDao.upsert(capture(written)) } returns Unit

        store.restoreSnippets(listOf(backupSnippet()))

        assertEquals("s1", written.captured.id)
        assertEquals("h1", written.captured.hostId)
        assertEquals(5L, written.captured.createdAt)
        assertFalse(written.captured.deleted)
        assertTrue(ownCounter(written.captured.vectorClock) > 0)
    }

    @Test
    fun `a live identical snippet is not touched`() = runTest {
        coEvery { snippetDao.getByIdIncludingDeleted("s1") } returns storedSnippet(clock = peerClock(3))

        store.restoreSnippets(listOf(backupSnippet()))

        coVerify(exactly = 0) { snippetDao.upsert(any()) }
    }

    @Test
    fun `a live snippet with another content is updated and its clock ticked from the stored one`() = runTest {
        coEvery { snippetDao.getByIdIncludingDeleted("s1") } returns
            storedSnippet(clock = peerClock(3), command = "old command")
        val written = slot<SnippetEntity>()
        coEvery { snippetDao.upsert(capture(written)) } returns Unit

        store.restoreSnippets(listOf(backupSnippet()))

        assertEquals("uptime", written.captured.command)
        val clock = VectorClockCodec.decode(written.captured.vectorClock).entries
        assertEquals(3L, clock["peer"], "the counter of the peer must survive: the new clock dominates the old one")
        assertTrue(ownCounter(written.captured.vectorClock) > 0)
        assertFalse(written.captured.deleted)
    }

    @Test
    fun `a tombstoned snippet is revived with a clock ticked from the tombstone, even if the content is identical`() = runTest {
        coEvery { snippetDao.getByIdIncludingDeleted("s1") } returns
            storedSnippet(clock = peerClock(9), deleted = true)
        val written = slot<SnippetEntity>()
        coEvery { snippetDao.upsert(capture(written)) } returns Unit

        store.restoreSnippets(listOf(backupSnippet()))

        assertFalse(written.captured.deleted)
        assertNull(written.captured.deletedAt)
        assertEquals(9L, VectorClockCodec.decode(written.captured.vectorClock).entries["peer"])
        assertTrue(ownCounter(written.captured.vectorClock) > 0)
    }

    @Test
    fun `the restore never goes through the live-only reads`() = runTest {
        coEvery { snippetDao.getByIdIncludingDeleted(any()) } returns null
        coEvery { snippetDao.upsert(any()) } returns Unit

        store.restoreSnippets(listOf(backupSnippet("a"), backupSnippet("b")))

        coVerify(exactly = 2) { snippetDao.getByIdIncludingDeleted(any()) }
        coVerify(exactly = 0) { snippetDao.getById(any()) }
        coVerify(exactly = 0) { snippetDao.insert(any()) }
    }

    // ── Themes ──────────────────────────────────────────────────────────────

    @Test
    fun `a new theme is inserted with a fresh clock`() = runTest {
        coEvery { themeDao.getByIdIncludingDeleted(THEME_ID) } returns null
        val written = slot<CustomTerminalThemeEntity>()
        coEvery { themeDao.upsert(capture(written)) } returns Unit

        store.restoreThemes(listOf(backupTheme()))

        assertEquals(THEME_ID, written.captured.id)
        assertEquals(16, written.captured.toDomain().ansi.size)
        assertTrue(ownCounter(written.captured.vectorClock) > 0)
    }

    @Test
    fun `a live identical theme is not touched`() = runTest {
        coEvery { themeDao.getByIdIncludingDeleted(THEME_ID) } returns storedTheme(clock = peerClock(2))

        store.restoreThemes(listOf(backupTheme()))

        coVerify(exactly = 0) { themeDao.upsert(any()) }
    }

    @Test
    fun `a live theme with another content is updated and its clock ticked from the stored one`() = runTest {
        coEvery { themeDao.getByIdIncludingDeleted(THEME_ID) } returns storedTheme(clock = peerClock(2), name = "Old name")
        val written = slot<CustomTerminalThemeEntity>()
        coEvery { themeDao.upsert(capture(written)) } returns Unit

        store.restoreThemes(listOf(backupTheme()))

        assertEquals("Night", written.captured.name)
        assertEquals(2L, VectorClockCodec.decode(written.captured.vectorClock).entries["peer"])
        assertTrue(ownCounter(written.captured.vectorClock) > 0)
    }

    @Test
    fun `a tombstoned theme is revived with a clock ticked from the tombstone`() = runTest {
        // The live-only getById would answer null here and restart the clock from "{}".
        coEvery { themeDao.getByIdIncludingDeleted(THEME_ID) } returns storedTheme(clock = peerClock(8), deleted = true)
        val written = slot<CustomTerminalThemeEntity>()
        coEvery { themeDao.upsert(capture(written)) } returns Unit

        store.restoreThemes(listOf(backupTheme()))

        assertFalse(written.captured.deleted)
        assertNull(written.captured.deletedAt)
        assertEquals(8L, VectorClockCodec.decode(written.captured.vectorClock).entries["peer"])
        coVerify(exactly = 0) { themeDao.getById(any()) }
    }

    // ── Local hosts (redirection rule) ──────────────────────────────────────

    @Test
    fun `the local hosts are read through the unfiltered query, tombstones included`() = runTest {
        val live = fr.techtical.nextsh.domain.model.Host(
            id = "live", label = "L", hostname = "live.example.org", username = "u",
            authType = fr.techtical.nextsh.domain.model.AuthType.PASSWORD, credentialId = "c",
        )
        val gone = live.copy(id = "gone", hostname = "gone.example.org")
        val entities = listOf(live, gone).map { io.mockk.mockk<fr.techtical.nextsh.data.db.entity.HostEntity>().also { e -> io.mockk.every { e.toDomain() } returns it } }
        coEvery { hostDao.getAllIncludingDeleted() } returns entities

        val hosts = store.localHostsIncludingDeleted()

        assertEquals(listOf("live", "gone"), hosts.map { it.id })
        coVerify(exactly = 0) { hostDao.observeAll() }
    }

    private companion object {
        const val THEME_ID = "7d1f0c2a-5b3e-4a6d-9c8b-1e2f3a4b5c6d"
    }
}
