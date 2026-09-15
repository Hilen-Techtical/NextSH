// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

import androidx.compose.foundation.v2.ScrollbarAdapter
import kotlin.math.roundToInt

/**
 * Adapte le modèle de défilement « ancré en bas » du terminal Compose au
 * contrat « ancré en haut » attendu par [ScrollbarAdapter] (v2).
 *
 * ## Inversion du sens
 *
 * Le terminal mémorise sa position via [getTermOffset], un nombre de lignes
 * comptées **vers le haut** depuis le bas vivant :
 * - `termOffset == 0`            → vue sur l'écran courant (bas)
 * - `termOffset == historyCount` → vue sur le début de l'historique (haut)
 *
 * La scrollbar v2 raisonne en sens inverse : le pouce en haut correspond à
 * `scrollOffset == 0`. Le mapping est donc :
 *
 * ```
 * adapterOffset = historyCount - termOffset
 * ```
 *
 * Ainsi :
 * - `termOffset == 0`            → `adapterOffset == historyCount` → pouce en bas ✓
 * - `termOffset == historyCount` → `adapterOffset == 0`            → pouce en haut ✓
 *
 * ## Dimensions
 *
 * ```
 * contentSize  = historyCount + rows   (historique + écran courant)
 * viewportSize = rows
 * ```
 *
 * `maxScrollOffset = contentSize - viewportSize = historyCount`, ce qui
 * correspond exactement à la plage valide de [termOffset].
 *
 * ## Testabilité
 *
 * L'interface v2 est purement fonctionnelle (pas de Composable dans les
 * propriétés) : cette classe peut être instanciée et testée sans runtime
 * Compose (voir [TerminalScrollbarAdapterTest]).
 *
 * @param historyCount  nombre de lignes dans le buffer d'historique JediTerm
 * @param rows          nombre de lignes visibles dans le terminal
 * @param getTermOffset lecture de l'offset terminal courant (0 = bas vivant)
 * @param setTermOffset écriture de l'offset terminal (clampé dans [0, historyCount])
 */
class TerminalScrollbarAdapter(
    private val historyCount: () -> Int,
    private val rows: () -> Int,
    private val getTermOffset: () -> Int,
    private val setTermOffset: (Int) -> Unit,
) : ScrollbarAdapter {

    override val scrollOffset: Double
        get() = (historyCount() - getTermOffset()).toDouble().coerceAtLeast(0.0)

    override val contentSize: Double
        get() = (historyCount() + rows()).toDouble()

    override val viewportSize: Double
        get() = rows().toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        val history = historyCount()
        // Inversion : adapterOffset → termOffset
        val newTermOffset = (history - scrollOffset.roundToInt()).coerceIn(0, history)
        setTermOffset(newTermOffset)
    }
}
