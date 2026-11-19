// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import java.io.File
import kotlin.test.fail

/**
 * Finds `testdata/backup/` at the root of the repository. Gradle runs the tests of `:shared` with the
 * module directory as working directory, so the search walks up from `user.dir` and STOPS at the first
 * directory that holds `settings.gradle.kts` (the repository root, worktree or not): going further would
 * land on the `testdata` of another checkout. A missing folder fails the test (it never skips it: a
 * guard that skips itself guards nothing).
 */
internal object BackupFixtures {

    val directory: File by lazy {
        val start = File(System.getProperty("user.dir")).absoluteFile
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) {
                val candidate = File(dir, "testdata/backup")
                if (candidate.isDirectory) return@lazy candidate
                fail("testdata/backup not found at the repository root ${dir.path}")
            }
            dir = dir.parentFile
        }
        fail("Repository root (settings.gradle.kts) not found above ${start.path}")
    }

    fun read(name: String): String {
        val file = File(directory, name)
        if (!file.isFile) fail("Missing fixture $name")
        return file.readText(Charsets.UTF_8)
    }
}
