// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [fileListColumnsFor], the pure width-threshold helper
 * behind [FileRow]'s responsive metadata columns (see [SftpBrowserScreen.kt]).
 * `internal` so it's reachable here without any production-code changes,
 * same reasoning as [SftpPreviewRatioTest] for the preview-split math.
 */
class SftpFileListColumnsTest {

    @Test
    fun `wide list shows every column`() {
        val columns = fileListColumnsFor(900f)
        assertEquals(FileListColumns(showSize = true, showPermissions = true, showDate = true), columns)
    }

    @Test
    fun `just above the permissions threshold still shows everything`() {
        val columns = fileListColumnsFor(640f)
        assertEquals(FileListColumns(showSize = true, showPermissions = true, showDate = true), columns)
    }

    @Test
    fun `just below the permissions threshold hides only permissions`() {
        val columns = fileListColumnsFor(639.99f)
        assertEquals(FileListColumns(showSize = true, showPermissions = false, showDate = true), columns)
    }

    @Test
    fun `just below the date threshold hides permissions and date`() {
        val columns = fileListColumnsFor(479.99f)
        assertEquals(FileListColumns(showSize = true, showPermissions = false, showDate = false), columns)
    }

    @Test
    fun `just below the size threshold hides everything but the name`() {
        val columns = fileListColumnsFor(359.99f)
        assertEquals(FileListColumns(showSize = false, showPermissions = false, showDate = false), columns)
    }

    @Test
    fun `very narrow width still resolves without error and hides all metadata`() {
        // The name column keeps weight(1f) regardless, this only asserts the
        // metadata columns never come back at an extreme width.
        val columns = fileListColumnsFor(80f)
        assertTrue(!columns.showSize && !columns.showPermissions && !columns.showDate)
    }

    @Test
    fun `thresholds are monotonic so permissions never shows without date or size`() {
        val widths = listOf(-50f, 0f, 120f, 300f, 360f, 400f, 480f, 500f, 640f, 700f, 2000f)
        widths.forEach { width ->
            val columns = fileListColumnsFor(width)
            if (columns.showPermissions) assertTrue(columns.showDate && columns.showSize, "at width=$width")
            if (columns.showDate) assertTrue(columns.showSize, "at width=$width")
        }
    }
}
