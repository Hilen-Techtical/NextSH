// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import fr.techtical.nextsh.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The user name of the host form follows the rule of a restore (a control character is cleaned there, so the
 * editor refuses it up front with a reason) and keeps a typed host restorable.
 */
class UsernameProblemTest {

    @Test
    fun `an ordinary user name has no problem`() {
        assertNull(usernameProblem("root"))
        assertNull(usernameProblem("jean.dupont@example.org"))
        assertNull(usernameProblem("  admin  "))
    }

    @Test
    fun `a control character inside the name is refused`() {
        for (c in listOf('\t', '\u0007', '\u007F', '\u0085')) {
            assertEquals(UsernameProblem.CONTROL_CHARACTER, usernameProblem("ro${c}ot"), "code ${c.code}")
        }
    }

    @Test
    fun `whitespace around the name is not a problem since it is removed on save`() {
        assertNull(usernameProblem("\troot\n"))
    }

    @Test
    fun `a name over 4096 characters is refused and 4096 is fine`() {
        assertNull(usernameProblem("a".repeat(4096)))
        assertEquals(UsernameProblem.TOO_LONG, usernameProblem("a".repeat(4097)))
    }

    @Test
    fun `each problem has its own sentence`() {
        assertEquals(R.string.field_problem_control, usernameProblemRes(UsernameProblem.CONTROL_CHARACTER))
        assertEquals(R.string.field_problem_too_long_username, usernameProblemRes(UsernameProblem.TOO_LONG))
    }

    @Test
    fun `a form with a control character in the user name is not valid and the same form without it is`() {
        val form = HostFormState(label = "Server", hostname = "server.example.org", username = "root")

        assertTrue(form.isValid)
        assertFalse(form.copy(username = "ro\u0007ot").isValid)
        assertFalse(form.copy(username = "a".repeat(4097)).isValid)
    }
}
