// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [DesktopCustomTerminalThemeRepository].
 *
 * Covers:
 * - ANSI-16 clamp (sanitized): short list padded to 16, long list truncated to 16
 * - Full round-trip: save then read back returns identical theme
 * - decodeAnsi companion: CSV parsing produces exactly-16 list
 */
class DesktopCustomTerminalThemeRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var repository: DesktopCustomTerminalThemeRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        repository = DesktopCustomTerminalThemeRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ── sanitized() via save+read ─────────────────────────────────────────────

    @Test
    fun `save with ansi size 3 pads to 16 on read`() = runTest {
        val shortAnsi = listOf(0xFF000000.toInt(), 0xFFAAAAAA.toInt(), 0xFFFFFFFF.toInt())
        val theme = makeTheme(id = "t-short", ansi = shortAnsi)
        repository.save(theme)

        val loaded = repository.getById("t-short")!!
        assertEquals(16, loaded.ansi.size, "ANSI list must be padded to 16 entries")
        // The 3 original entries must be preserved at their positions
        assertEquals(shortAnsi[0], loaded.ansi[0], "ansi[0] must be preserved")
        assertEquals(shortAnsi[1], loaded.ansi[1], "ansi[1] must be preserved")
        assertEquals(shortAnsi[2], loaded.ansi[2], "ansi[2] must be preserved")
        // Padded entries must be opaque black
        val opaqueBlack = 0xFF000000.toInt()
        for (i in 3..15) {
            assertEquals(opaqueBlack, loaded.ansi[i], "ansi[$i] must be padded with opaque black")
        }
    }

    @Test
    fun `save with ansi size 20 truncates to 16 on read`() = runTest {
        val longAnsi = List(20) { i -> 0xFF000000.toInt() or (i * 0x0A0A0A) }
        val theme = makeTheme(id = "t-long", ansi = longAnsi)
        repository.save(theme)

        val loaded = repository.getById("t-long")!!
        assertEquals(16, loaded.ansi.size, "ANSI list must be truncated to 16 entries")
        // Entries 0..15 from the original must be preserved
        for (i in 0..15) {
            assertEquals(longAnsi[i], loaded.ansi[i], "ansi[$i] must be preserved after truncation")
        }
    }

    @Test
    fun `save with exactly 16 ansi entries round-trips cleanly`() = runTest {
        val ansi16 = List(16) { i -> 0xFF000000.toInt() or (i * 0x111111) }
        val theme = makeTheme(id = "t-exact", ansi = ansi16)
        repository.save(theme)

        val loaded = repository.getById("t-exact")!!
        assertEquals(16, loaded.ansi.size)
        assertEquals(ansi16, loaded.ansi)
        assertEquals(theme.name, loaded.name)
        assertEquals(theme.background, loaded.background)
        assertEquals(theme.foreground, loaded.foreground)
        assertEquals(theme.cursor, loaded.cursor)
        assertEquals(theme.selectionBg, loaded.selectionBg)
        assertEquals(theme.createdAt, loaded.createdAt)
    }

    // ── decodeAnsi companion (CSV codec) ─────────────────────────────────────

    @Test
    fun `decodeAnsi pads short CSV to 16`() {
        val csv = "1,2,3"
        val result = DesktopCustomTerminalThemeRepository.decodeAnsi(csv)
        assertEquals(16, result.size)
        assertEquals(1, result[0])
        assertEquals(2, result[1])
        assertEquals(3, result[2])
        val opaqueBlack = 0xFF000000.toInt()
        for (i in 3..15) assertEquals(opaqueBlack, result[i], "Pad at index $i must be opaque black")
    }

    @Test
    fun `decodeAnsi truncates long CSV to 16`() {
        val csv = (0..19).joinToString(",")
        val result = DesktopCustomTerminalThemeRepository.decodeAnsi(csv)
        assertEquals(16, result.size)
        for (i in 0..15) assertEquals(i, result[i])
    }

    @Test
    fun `encodeAnsi then decodeAnsi round-trips a 16-entry list`() {
        val ansi16 = List(16) { i -> 0xFF000000.toInt() or (i * 0x080808) }
        val csv = DesktopCustomTerminalThemeRepository.encodeAnsi(ansi16)
        val result = DesktopCustomTerminalThemeRepository.decodeAnsi(csv)
        assertEquals(ansi16, result)
    }

    // ── observeAll ───────────────────────────────────────────────────────────

    @Test
    fun `observeAll emits empty list when no themes exist`() = runTest {
        val themes = repository.observeAll().first()
        assertEquals(0, themes.size)
    }

    @Test
    fun `observeAll emits saved themes`() = runTest {
        repository.save(makeTheme("t1", name = "Dark"))
        repository.save(makeTheme("t2", name = "Light"))
        val themes = repository.observeAll().first()
        assertEquals(2, themes.size)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeTheme(
        id: String,
        name: String = "Test Theme",
        ansi: List<Int> = List(16) { 0xFF000000.toInt() },
    ): CustomTerminalTheme = CustomTerminalTheme(
        id = id,
        name = name,
        background = 0xFF0F1117.toInt(),
        foreground = 0xFFD4D4D4.toInt(),
        cursor = 0xFF00BFFF.toInt(),
        selectionBg = 0x4D0066CC.toInt(),
        ansi = ansi,
        createdAt = 1_700_000_000_000L,
    )
}
