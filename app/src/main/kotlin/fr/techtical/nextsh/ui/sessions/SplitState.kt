// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

/**
 * Content type displayed in a split pane.
 */
sealed class PaneContent {
    /** A terminal session tab. */
    data class Terminal(val tabIndex: Int) : PaneContent()
    /** An embedded SFTP browser for the given session. */
    data class Sftp(val sessionId: String, val hostLabel: String) : PaneContent()
}

/** Orientation of the split divider. */
enum class SplitOrientation { HORIZONTAL, VERTICAL }

/** Largeur à partir de laquelle deux panneaux côte à côte restent lisibles. */
const val SPLIT_SIDE_BY_SIDE_MIN_WIDTH_DP = 600

/**
 * Orientation par défaut d'un nouveau split, selon la largeur disponible.
 *
 * Côte à côte sur écran large, empilé sinon : sur un téléphone en portrait,
 * deux panneaux côte à côte laissent environ vingt colonnes chacun, ce qui rend
 * un terminal inutilisable. La bascule manuelle reste disponible.
 */
fun defaultSplitOrientation(availableWidthDp: Int): SplitOrientation =
    if (availableWidthDp >= SPLIT_SIDE_BY_SIDE_MIN_WIDTH_DP) {
        SplitOrientation.HORIZONTAL
    } else {
        SplitOrientation.VERTICAL
    }

/** Identifies which pane in a split layout. */
enum class PaneSlot { LEFT_OR_TOP, RIGHT_OR_BOTTOM }

/**
 * Runtime state for split-screen mode.
 * Null in [SessionUiState.splitState] means single-pane mode.
 *
 * @param orientation    Horizontal (side-by-side) or vertical (top-bottom)
 * @param leftOrTopPane  Content of the first pane
 * @param rightOrBottomPane Content of the second pane
 * @param splitRatio     Ratio for first pane size (0.2..0.8), default 0.5
 * @param focusedPane    Which pane currently has input focus
 */
data class SplitState(
    val orientation: SplitOrientation = SplitOrientation.HORIZONTAL,
    val leftOrTopPane: PaneContent,
    val rightOrBottomPane: PaneContent,
    val splitRatio: Float = 0.5f,
    val focusedPane: PaneSlot = PaneSlot.LEFT_OR_TOP,
)
