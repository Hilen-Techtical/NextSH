// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import fr.techtical.nextsh.ui.SourceGuardSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Durée d'affichage de [NextShMessage] : 3 s pour une information, 7 s pour une erreur, allongées
 * par le réglage d'accessibilité « Délai de réaction ». Le composant lui-même n'est pas testable
 * sans test instrumenté : sa logique de durée est une fonction pure, et un garde de source vérifie
 * le câblage de l'annonce TalkBack.
 */
class NextShMessageDurationTest {

    @Test
    fun `without an accessibility setting an information lasts 3 seconds and an error 7 seconds`() {
        assertEquals(3_000L, messageDurationMillis(isError = false, recommend = null))
        assertEquals(7_000L, messageDurationMillis(isError = true, recommend = null))
    }

    @Test
    fun `a longer recommended timeout replaces the base duration`() {
        assertEquals(12_000L, messageDurationMillis(isError = false, recommend = { 12_000L }))
        assertEquals(30_000L, messageDurationMillis(isError = true, recommend = { 30_000L }))
    }

    @Test
    fun `a shorter recommended timeout never shortens the message`() {
        assertEquals(3_000L, messageDurationMillis(isError = false, recommend = { 1_000L }))
        assertEquals(7_000L, messageDurationMillis(isError = true, recommend = { 4_000L }))
    }

    @Test
    fun `the recommendation is asked from the base duration of the kind of message`() {
        val asked = mutableListOf<Long>()
        messageDurationMillis(isError = false, recommend = { base -> asked += base; base })
        messageDurationMillis(isError = true, recommend = { base -> asked += base; base })

        assertEquals(listOf(3_000L, 7_000L), asked)
    }

    @Test
    fun `the long flag gives an information the 7 seconds of an error`() {
        assertEquals(7_000L, messageDurationMillis(isError = false, recommend = null, long = true))
        assertEquals(7_000L, messageDurationMillis(isError = true, recommend = null, long = true))
        assertEquals(3_000L, messageDurationMillis(isError = false, recommend = null, long = false))
    }

    @Test
    fun `the long flag still follows the reaction delay setting`() {
        assertEquals(20_000L, messageDurationMillis(isError = false, recommend = { 20_000L }, long = true))
        assertEquals(7_000L, messageDurationMillis(isError = false, recommend = { 3_000L }, long = true))
    }

    @Test
    fun `the component has a long flag and a touch target of 48 dp for its cross`() {
        val code = SourceGuardSupport.mainSource("ui/components/NextShMessage.kt")

        assertTrue(code.contains("long: Boolean = false"), "the long flag, off by default")
        assertTrue(code.contains("messageDurationMillis(isError, recommend, long)"), "the flag reaches the delay")
        assertTrue(code.contains(".minimumInteractiveComponentSize()"), "touch target of the cross")
    }

    @Test
    fun `the component is announced by TalkBack and follows the reaction delay setting`() {
        val code = SourceGuardSupport.mainSource("ui/components/NextShMessage.kt")

        assertTrue(code.contains("LiveRegionMode.Polite"), "polite live region for an information")
        assertTrue(code.contains("LiveRegionMode.Assertive"), "assertive live region for an error")
        assertTrue(code.contains("calculateRecommendedTimeoutMillis"), "reaction delay setting")
        assertTrue(code.contains("messageDurationMillis("), "the delay goes through the pure function")
    }
}
