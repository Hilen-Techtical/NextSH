// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

import java.io.File

/**
 * One checkout of the repository, seen from the tests: its [root] (the folder of `settings.gradle.kts`)
 * and the `app` module under it. Every lookup FAILS (AssertionError) when the file or the folder is not
 * there: a guard that reads a source must never pass because the source went missing, so a renamed or
 * moved file is reported by the guard that expected it.
 *
 * The root is given to the constructor so that the lookup can be tried on a throwaway tree; the tests
 * of the app use [ProjectFiles], which finds the root of the checkout they run in.
 */
internal class Checkout(val root: File) {

    /** The `app` module of the checkout. */
    val app = File(root, "app")

    /** The file at [pathFromModule] (for instance `src/main/res/values/strings.xml`) under `app`. */
    fun appFile(pathFromModule: String): File = existing(app, pathFromModule, "file") { it.isFile }

    /** The folder at [pathFromModule] under `app`. */
    fun appDir(pathFromModule: String): File = existing(app, pathFromModule, "folder") { it.isDirectory }

    /** The folder at [pathFromRoot] under the root of the checkout (for instance `terminal/terminal-emulator`). */
    fun repoDir(pathFromRoot: String): File = existing(root, pathFromRoot, "folder") { it.isDirectory }

    private fun existing(base: File, path: String, kind: String, accepts: (File) -> Boolean): File {
        val found = File(base, path)
        if (!accepts(found)) {
            throw AssertionError(
                "$path not found in ${base.path} (working directory ${System.getProperty("user.dir")}): " +
                    "the $kind was renamed or moved, update the guard",
            )
        }
        return found
    }
}

/**
 * Where the source-level guards and the tests that read a resource find the files of the app.
 *
 * Gradle runs the unit tests from the `app` folder, an IDE may run them from the repository root, and
 * a worktree of the repository sits INSIDE the folder of the main checkout (`.claude/worktrees/<name>`).
 * So the lookup never walks up the folders asking each one for the file: a file missing from the
 * worktree would be found in the main checkout, on another branch, and the guard would read the wrong
 * source. It looks for the root of the checkout once (the nearest folder, going up from the working
 * directory, that holds `settings.gradle.kts`), stops there, and reads everything from it.
 */
internal object ProjectFiles {

    private val ROOT_MARKERS = listOf("settings.gradle.kts", "settings.gradle")

    /** The nearest folder, from [start] upwards ([start] included), that holds a settings file of Gradle. Stops there. */
    internal fun findRepoRoot(start: File): File? = findUpwards(start, ROOT_MARKERS)

    /** The nearest folder, from [start] upwards ([start] included), that holds a file or a folder named one of [names], or null. */
    internal fun findUpwards(start: File, names: List<String>): File? {
        var dir: File? = start.absoluteFile
        while (dir != null) {
            val current: File = dir
            if (names.any { File(current, it).exists() }) return current
            dir = current.parentFile
        }
        return null
    }

    /** The checkout the tests run in. Looked up on first use; a working directory outside a checkout FAILS. */
    val checkout: Checkout by lazy {
        val workingDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        Checkout(
            findRepoRoot(workingDir) ?: throw AssertionError(
                "no settings.gradle.kts above the working directory $workingDir: the guard cannot find the repository",
            ),
        )
    }

    /** The file at [pathFromModule] under `app`, for instance `proguard-rules.pro` or `src/main/res/values/strings.xml`. */
    fun appFile(pathFromModule: String): File = checkout.appFile(pathFromModule)

    /** The folder at [pathFromModule] under `app`, for instance `src/test/kotlin`. */
    fun appDir(pathFromModule: String): File = checkout.appDir(pathFromModule)

    /** The file at [pathUnderNextSh] under `app/src/main/kotlin/fr/techtical/nextsh/`. */
    fun mainSource(pathUnderNextSh: String): File = appFile("$MAIN_SOURCE_ROOT/$pathUnderNextSh")

    /** The folder [dirUnderNextSh] of `app/src/main/kotlin/fr/techtical/nextsh/` (the folder itself by default). */
    fun mainSourceDir(dirUnderNextSh: String = ""): File =
        appDir(if (dirUnderNextSh.isEmpty()) MAIN_SOURCE_ROOT else "$MAIN_SOURCE_ROOT/$dirUnderNextSh")

    /** The folder at [pathFromRoot] under the root of the checkout, for instance `terminal/terminal-emulator`. */
    fun repoDir(pathFromRoot: String): File = checkout.repoDir(pathFromRoot)

    private const val MAIN_SOURCE_ROOT = "src/main/kotlin/fr/techtical/nextsh"
}
