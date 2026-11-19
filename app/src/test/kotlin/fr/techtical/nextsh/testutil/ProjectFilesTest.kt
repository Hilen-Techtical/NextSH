// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * [ProjectFiles] stops at the root of its own checkout and fails on a file that is not there. The
 * trees are built in a temporary folder, so that the case that matters (a worktree inside the folder
 * of the main checkout, with a file missing from it) can be reproduced whatever the machine is.
 */
class ProjectFilesTest {

    private fun file(root: File, path: String): File =
        File(root, path).also {
            it.parentFile.mkdirs()
            it.writeText("x")
        }

    @Test
    fun `the root is found from the app folder and from the root itself`(@TempDir tempDir: Path) {
        val root = tempDir.toFile()
        file(root, "settings.gradle.kts")
        File(root, "app/src").mkdirs()

        assertEquals(root, ProjectFiles.findRepoRoot(File(root, "app")))
        assertEquals(root, ProjectFiles.findRepoRoot(File(root, "app/src")))
        assertEquals(root, ProjectFiles.findRepoRoot(root))
    }

    @Test
    fun `a settings file in Groovy marks the root too`(@TempDir tempDir: Path) {
        val root = tempDir.toFile()
        file(root, "settings.gradle")
        File(root, "app").mkdirs()

        assertEquals(root, ProjectFiles.findRepoRoot(File(root, "app")))
    }

    @Test
    fun `the walk up gives the nearest folder that holds the marker, or null when none does`(@TempDir tempDir: Path) {
        val start = File(tempDir.toFile(), "a/b").also { it.mkdirs() }

        // "a" is a folder of the temporary folder, which is therefore the nearest one that holds it.
        assertEquals(tempDir.toFile(), ProjectFiles.findUpwards(start, listOf("a")))
        // A marker no folder holds, so that the answer does not depend on what sits above the temporary folder.
        assertNull(ProjectFiles.findUpwards(start, listOf("no-such-marker-0b6f4a1c")))
    }

    @Test
    fun `a worktree inside the main checkout stops at its own root and does not read the main checkout`(@TempDir tempDir: Path) {
        val outer = tempDir.toFile()
        file(outer, "settings.gradle.kts")
        file(outer, "app/src/main/kotlin/X.kt")
        val worktree = File(outer, ".claude/worktrees/w")
        file(worktree, "settings.gradle.kts")
        File(worktree, "app").mkdirs()

        val found = ProjectFiles.findRepoRoot(File(worktree, "app"))
        assertEquals(worktree, found)

        // X.kt exists in the main checkout above the worktree, not in the worktree: the guard fails.
        val error = assertThrows(AssertionError::class.java) {
            Checkout(worktree).appFile("src/main/kotlin/X.kt")
        }
        assertTrue(error.message.orEmpty().contains("src/main/kotlin/X.kt"), error.message)
        assertTrue(error.message.orEmpty().contains(File(worktree, "app").path), error.message)
        // And the same file is read when it is in the worktree.
        file(worktree, "app/src/main/kotlin/X.kt")
        assertEquals(File(File(worktree, "app"), "src/main/kotlin/X.kt"), Checkout(worktree).appFile("src/main/kotlin/X.kt"))
    }

    @Test
    fun `a file is not a folder and a folder is not a file`(@TempDir tempDir: Path) {
        val root = tempDir.toFile()
        file(root, "app/proguard-rules.pro")
        File(root, "app/src/test/kotlin").mkdirs()
        File(root, "terminal/terminal-emulator").mkdirs()
        val checkout = Checkout(root)

        assertEquals(File(root, "app/proguard-rules.pro"), checkout.appFile("proguard-rules.pro"))
        assertEquals(File(root, "app/src/test/kotlin"), checkout.appDir("src/test/kotlin"))
        assertEquals(File(root, "terminal/terminal-emulator"), checkout.repoDir("terminal/terminal-emulator"))

        assertThrows(AssertionError::class.java) { checkout.appFile("src/test/kotlin") }
        assertThrows(AssertionError::class.java) { checkout.appDir("proguard-rules.pro") }
        assertThrows(AssertionError::class.java) { checkout.repoDir("app/proguard-rules.pro") }
        assertThrows(AssertionError::class.java) { checkout.appFile("missing.pro") }
        assertThrows(AssertionError::class.java) { checkout.appDir("src/missing") }
        assertThrows(AssertionError::class.java) { checkout.repoDir("missing") }
    }

    @Test
    fun `the checkout the tests run in is found, with the sources of the app in it`() {
        assertTrue(ProjectFiles.checkout.app.isDirectory, "no app folder in ${ProjectFiles.checkout.root}")
        assertTrue(ProjectFiles.appFile("src/main/AndroidManifest.xml").isFile)
        assertTrue(ProjectFiles.appDir("src/test/kotlin").isDirectory)
        assertTrue(ProjectFiles.mainSource("App.kt").isFile)
        assertTrue(ProjectFiles.mainSourceDir().isDirectory)
        assertTrue(ProjectFiles.mainSourceDir("ui").isDirectory)
        assertTrue(ProjectFiles.repoDir("terminal").isDirectory)
    }
}
