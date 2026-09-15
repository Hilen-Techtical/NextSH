// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * L'orientation par defaut d'un split depend de la largeur disponible.
 *
 * Jusqu'a la migration API 36, l'application etait verrouillee en portrait et
 * le split partait toujours cote a cote. Sur un telephone en portrait cela
 * donne deux panneaux d'environ vingt colonnes, inutilisables pour un
 * terminal. Maintenant que l'ecran peut tourner et que les grands ecrans
 * ignorent le verrouillage, le defaut doit suivre la largeur.
 */
class SplitOrientationDefaultTest {

    @Test
    fun `un telephone en portrait empile les panneaux`() {
        assertEquals(SplitOrientation.VERTICAL, defaultSplitOrientation(360))
        assertEquals(SplitOrientation.VERTICAL, defaultSplitOrientation(412))
    }

    @Test
    fun `un telephone en paysage passe cote a cote`() {
        assertEquals(SplitOrientation.HORIZONTAL, defaultSplitOrientation(800))
    }

    @Test
    fun `une tablette passe cote a cote`() {
        assertEquals(SplitOrientation.HORIZONTAL, defaultSplitOrientation(1280))
    }

    @Test
    fun `le seuil est inclusif`() {
        assertEquals(
            SplitOrientation.VERTICAL,
            defaultSplitOrientation(SPLIT_SIDE_BY_SIDE_MIN_WIDTH_DP - 1),
        )
        assertEquals(
            SplitOrientation.HORIZONTAL,
            defaultSplitOrientation(SPLIT_SIDE_BY_SIDE_MIN_WIDTH_DP),
        )
    }
}
