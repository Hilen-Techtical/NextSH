// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No test of `:app` skips itself or is switched off. A guard that skips when it cannot find what it
 * checks (a source renamed, a runner with another working directory) is reported as skipped, not as
 * failed: the build stays green and the guard checks nothing. The guards read their files through
 * [ProjectFiles], which fails on a miss, so a test that skips from its code has no reason left to
 * exist.
 *
 * It reads the sources of the tests and fails on three kinds of words: the calls that skip a test from
 * its body (the assumptions of JUnit 5, and the ones of JUnit 4, which the test classpath also holds
 * through the web server of the HTTP tests: Jupiter reads the exception of a JUnit 4 assumption as a
 * skipped test too), the exceptions that abort a test, and the annotations that disable a test or
 * enable it on a condition. A name written with its package in front of it is not seen. The names are
 * written in pieces below, so that this file does not hold them itself. The one legitimate skip of the
 * project sits in `:shared` (`SkSshSignatureOpenSshInteropTest`, which needs the `ssh-keygen` of the
 * machine), outside what is read here.
 */
class NoSkippedGuardTest {

    /** What a test says to skip itself or to be switched off, in pieces so that this file does not hold one. */
    private val skips = listOf(
        // The assumptions of JUnit 5 and of JUnit 4.
        "Assump" + "tions.", "assume" + "True(", "assume" + "False(", "assuming" + "That(",
        "Assu" + "me.", "assume" + "NotNull(", "assume" + "That(", "assume" + "NoException(",
        // The exceptions that abort a test: the assumptions throw them, a test could throw one itself.
        "TestAborted" + "Exception", "AssumptionViolated" + "Exception",
        // The annotations that switch a test off, or on a condition.
        "@Dis" + "abled", "@Ig" + "nore", "@Enabled" + "If", "@Dis" + "abledIf", "@Enabled" + "On",
        "@Dis" + "abledOn", "@Enabled" + "For", "@Dis" + "abledFor",
    )

    /** The skip words that [source] holds, in the order of [skips]. */
    private fun skipsIn(source: String): List<String> = skips.filter { it in source }

    @Test
    fun `the check finds each way of skipping a test, and a source that holds none is clean`() {
        skips.forEach { skip ->
            // Some words hold another (the annotation that disables on a condition holds the plain one):
            // the word must be among those found, not the only one.
            assertTrue(skip in skipsIn("val x = 1 // before\n    $skip other)\n"), skip)
        }
        assertEquals(emptyList<String>(), skipsIn("fun `a test`() { assertTrue(found != null, \"not found\") }"))
    }

    @Test
    fun `no test of the app skips itself or is switched off`() {
        val root = ProjectFiles.appDir("src/test/kotlin")
        val files = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        // A guard that reads nothing proves nothing: the tests of the app are by the hundreds.
        assertTrue(files.size > 100, "expected the whole tree of the tests, found ${files.size} files under $root")

        val offenders = files.flatMap { file ->
            skipsIn(file.readText()).map { "${file.relativeTo(root).path.replace('\\', '/')}: $it" }
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "a test that skips itself or is switched off looks green while it checks nothing: " +
                "read its file through ProjectFiles, which fails, and fix the test instead of disabling it",
        )
    }
}
