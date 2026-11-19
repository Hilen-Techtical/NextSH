// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Gravité du message de l'écran des appareils enrôlés : erreur (7 s) ou information (3 s). */
class EnrolledDevicesMessageTest {

    @Test
    fun `a failed revocation and an unreachable device are errors`() {
        assertTrue(enrolledMessageIsError(EnrolledDevicesViewModel.Event.Error("x")))
        assertTrue(enrolledMessageIsError(EnrolledDevicesViewModel.Event.SearchNotFound("Phone")))
    }

    @Test
    fun `a revocation, the start and the success of a search are information`() {
        assertFalse(enrolledMessageIsError(EnrolledDevicesViewModel.Event.Revoked("Phone")))
        assertFalse(enrolledMessageIsError(EnrolledDevicesViewModel.Event.SearchStarted("Phone")))
        assertFalse(enrolledMessageIsError(EnrolledDevicesViewModel.Event.SearchFound("Phone")))
    }
}
