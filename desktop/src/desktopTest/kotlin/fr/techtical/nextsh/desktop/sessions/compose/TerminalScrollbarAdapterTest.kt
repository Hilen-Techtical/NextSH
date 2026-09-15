// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Tests unitaires pour [TerminalScrollbarAdapter].
 *
 * L'interface [androidx.compose.foundation.v2.ScrollbarAdapter] est purement
 * fonctionnelle (pas de dépendance au runtime Compose), ce qui permet de
 * valider la logique d'inversion haut/bas sans Composable.
 */
class TerminalScrollbarAdapterTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeAdapter(
        history: Int,
        rows: Int,
        initOffset: Int = 0,
        captured: Array<Int> = arrayOf(initOffset),
    ): TerminalScrollbarAdapter = TerminalScrollbarAdapter(
        historyCount = { history },
        rows = { rows },
        getTermOffset = { captured[0] },
        setTermOffset = { captured[0] = it },
    )

    // ── Propriétés statiques ─────────────────────────────────────────────────

    /** termOffset 0 → pouce en bas : adapterOffset == historyCount. */
    @Test
    fun termOffset0_thumbAtBottom() {
        val adapter = makeAdapter(history = 100, rows = 25, initOffset = 0)
        assertEquals(100.0, adapter.scrollOffset)
    }

    /** termOffset == historyCount → pouce en haut : adapterOffset == 0. */
    @Test
    fun termOffsetMax_thumbAtTop() {
        val adapter = makeAdapter(history = 100, rows = 25, initOffset = 100)
        assertEquals(0.0, adapter.scrollOffset)
    }

    /** termOffset au milieu → adapterOffset au milieu. */
    @Test
    fun termOffsetHalf_thumbAtMiddle() {
        val adapter = makeAdapter(history = 100, rows = 25, initOffset = 50)
        assertEquals(50.0, adapter.scrollOffset)
    }

    /** contentSize = historyCount + rows. */
    @Test
    fun contentSize_isHistoryPlusRows() {
        val adapter = makeAdapter(history = 200, rows = 40)
        assertEquals(240.0, adapter.contentSize)
    }

    /** viewportSize == rows. */
    @Test
    fun viewportSize_isRows() {
        val adapter = makeAdapter(history = 100, rows = 30)
        assertEquals(30.0, adapter.viewportSize)
    }

    /**
     * Invariant fondamental : pouce en bas ↔ adapterOffset + viewportSize == contentSize.
     * (Le maxScrollOffset de l'API v2 vaut contentSize - viewportSize = historyCount.)
     */
    @Test
    fun thumbAtBottom_offsetPlusViewportEqualsContent() {
        val adapter = makeAdapter(history = 150, rows = 24, initOffset = 0)
        assertEquals(adapter.contentSize, adapter.scrollOffset + adapter.viewportSize, 0.001)
    }

    // ── scrollTo ─────────────────────────────────────────────────────────────

    /** scrollTo(0) → pouce en haut → termOffset = historyCount. */
    @Test
    fun scrollTo0_movesTermOffsetToMax() = runTest {
        val captured = arrayOf(50)
        val adapter = makeAdapter(history = 100, rows = 25, captured = captured)
        adapter.scrollTo(0.0)
        assertEquals(100, captured[0])
    }

    /** scrollTo(historyCount) → pouce en bas → termOffset = 0. */
    @Test
    fun scrollToMax_movesTermOffsetToZero() = runTest {
        val captured = arrayOf(50)
        val adapter = makeAdapter(history = 100, rows = 25, captured = captured)
        adapter.scrollTo(100.0)
        assertEquals(0, captured[0])
    }

    /** Aller-retour : scrollTo(x) → scrollOffset == x (aux arrondis près). */
    @Test
    fun scrollTo_roundTrip() = runTest {
        val captured = arrayOf(0)
        val adapter = makeAdapter(history = 100, rows = 25, captured = captured)
        adapter.scrollTo(40.0)
        // termOffset == 60 → scrollOffset doit valoir 40
        assertEquals(40.0, adapter.scrollOffset, 0.5)
    }

    /** scrollTo d'une valeur négative → clampé à 0 (pouce en bas). */
    @Test
    fun scrollTo_clampsBelowZero() = runTest {
        val captured = arrayOf(50)
        val adapter = makeAdapter(history = 100, rows = 25, captured = captured)
        adapter.scrollTo(-10.0)
        // adapterOffset négatif → termOffset clampe à historyCount
        assertEquals(100, captured[0])
    }

    /** scrollTo au-dessus de historyCount → termOffset clampé à 0. */
    @Test
    fun scrollTo_clampsAboveHistory() = runTest {
        val captured = arrayOf(50)
        val adapter = makeAdapter(history = 100, rows = 25, captured = captured)
        adapter.scrollTo(200.0)
        assertEquals(0, captured[0])
    }

    /** scrollTo une valeur fractionnaire → arrondi correct. */
    @Test
    fun scrollTo_fractionalRoundsSanely() = runTest {
        val captured = arrayOf(0)
        val adapter = makeAdapter(history = 100, rows = 25, captured = captured)
        // 49.6 → roundToInt = 50 → termOffset = 100 - 50 = 50
        adapter.scrollTo(49.6)
        assertEquals(50, captured[0])
        // 49.4 → roundToInt = 49 → termOffset = 100 - 49 = 51
        adapter.scrollTo(49.4)
        assertEquals(51, captured[0])
    }

    // ── Cas limite : historique vide ─────────────────────────────────────────

    /**
     * Quand il n'y a pas d'historique (historyCount == 0), contentSize == viewportSize
     * et scrollTo ne modifie pas l'offset (reste à 0).
     */
    @Test
    fun noHistory_contentEqualsViewport() {
        val adapter = makeAdapter(history = 0, rows = 24)
        assertEquals(adapter.viewportSize, adapter.contentSize)
    }

    @Test
    fun noHistory_scrollToAny_doesNotThrow() = runTest {
        val captured = arrayOf(0)
        val adapter = makeAdapter(history = 0, rows = 24, captured = captured)
        adapter.scrollTo(50.0) // pas d'historique → termOffset reste 0
        assertEquals(0, captured[0])
    }

    @Test
    fun noHistory_scrollOffset_isZero() {
        val adapter = makeAdapter(history = 0, rows = 24, initOffset = 0)
        assertEquals(0.0, adapter.scrollOffset)
    }
}
