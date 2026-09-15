// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the pure preview-split ratio helpers in
 * [SftpBrowserScreen.kt] (`clampPreviewRatio`, `nextPreviewRatio`). Both are
 * `internal` so they are reachable here without any production-code changes.
 *
 * The rest of the split (SftpSplitRow, PreviewDivider) is Compose UI wired
 * directly to pointer-input gesture callbacks and isn't practically
 * unit-testable outside a UI harness: these two functions are the pure
 * core extracted specifically so the drag-delta math and clamping have
 * coverage independent of Compose.
 */
class SftpPreviewRatioTest {

    /**
     * Absolute tolerance for the drag math, which divides by the measured
     * width and therefore can't land on an exact binary fraction. Passed to
     * `kotlin.test.assertEquals(expected, actual, absoluteTolerance)`, NOT to
     * a hand-rolled `assert(...)`, which the JVM strips unless the runner is
     * started with `-ea` (Gradle's default is off, so such a helper would sit
     * here silently inert).
     */
    private val tolerance = 0.0001f

    // ── clampPreviewRatio ────────────────────────────────────────────────────

    @Test
    fun `clamp keeps a value already inside the allowed range unchanged`() {
        assertEquals(0.35f, clampPreviewRatio(0.35f))
        assertEquals(0.2f, clampPreviewRatio(0.2f))
        assertEquals(0.8f, clampPreviewRatio(0.8f))
    }

    @Test
    fun `clamp floors below the minimum`() {
        assertEquals(0.2f, clampPreviewRatio(0.05f))
        assertEquals(0.2f, clampPreviewRatio(-1f))
    }

    @Test
    fun `clamp ceils above the maximum`() {
        assertEquals(0.8f, clampPreviewRatio(0.95f))
        assertEquals(0.8f, clampPreviewRatio(2f))
    }

    // ── nextPreviewRatio ─────────────────────────────────────────────────────

    @Test
    fun `dragging right shrinks the panel ratio`() {
        // Panel sits on the right of the divider: dragging the divider to
        // the right (positive delta) grows the file list and shrinks the
        // panel, so the ratio decreases.
        val result = nextPreviewRatio(current = 0.35f, dragDeltaPx = 100f, totalWidthPx = 1000f)
        assertEquals(0.25f, result, tolerance)
    }

    @Test
    fun `dragging left grows the panel ratio`() {
        val result = nextPreviewRatio(current = 0.35f, dragDeltaPx = -100f, totalWidthPx = 1000f)
        assertEquals(0.45f, result, tolerance)
    }

    @Test
    fun `result is clamped to the allowed range even for a large drag`() {
        assertEquals(0.8f, nextPreviewRatio(current = 0.35f, dragDeltaPx = -10000f, totalWidthPx = 1000f))
        assertEquals(0.2f, nextPreviewRatio(current = 0.35f, dragDeltaPx = 10000f, totalWidthPx = 1000f))
    }

    @Test
    fun `zero or negative total width is a no-op guard against unmeasured layout`() {
        assertEquals(0.35f, nextPreviewRatio(current = 0.35f, dragDeltaPx = 100f, totalWidthPx = 0f))
        assertEquals(0.35f, nextPreviewRatio(current = 0.35f, dragDeltaPx = 100f, totalWidthPx = -50f))
    }
}
