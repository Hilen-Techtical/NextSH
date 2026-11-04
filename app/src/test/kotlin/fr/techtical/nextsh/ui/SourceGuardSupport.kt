// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui

import java.io.File

/**
 * Helpers shared by the source-level guards of the UI (see `HostDetailPasswordFieldGuardTest` and
 * `SecretFieldsKeyboardGuardTest`). A Compose UI test would need an instrumented run and there is
 * no `androidTest` source set, so those guards read the Kotlin source instead.
 */
internal object SourceGuardSupport {

    /**
     * Locates a file of the `app` module by walking up from the test's working directory, which
     * Gradle sets to the module directory (`app/`) but which other runners may point at the
     * repository root. A miss FAILS: the files live in the same module as the tests, so it means
     * the guard went stale, not that the runner is unusual.
     */
    fun locate(pathFromModuleRoot: String): File {
        val fromRepoRoot = "app/$pathFromModuleRoot"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, pathFromModuleRoot).takeIf { it.isFile }?.let { return it }
            File(dir, fromRepoRoot).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError(
            "$pathFromModuleRoot not found from working dir ${System.getProperty("user.dir")}: " +
                "the guard cannot check it",
        )
    }

    /** Source of a Kotlin file under `src/main/kotlin/fr/techtical/nextsh/`, comments removed. */
    fun mainSource(pathUnderNextSh: String): String =
        withoutComments(locate("src/main/kotlin/fr/techtical/nextsh/$pathUnderNextSh").readText())

    /**
     * Every Kotlin file under the directory [dirUnderNextSh] of `src/main/kotlin/fr/techtical/nextsh/`
     * (recursively), comments removed, keyed by its path relative to `nextsh/` with `/` separators.
     */
    fun mainSources(dirUnderNextSh: String): Map<String, String> {
        val nextSh = locateDir("src/main/kotlin/fr/techtical/nextsh")
        val root = File(nextSh, dirUnderNextSh)
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        if (files.isEmpty()) throw AssertionError("no Kotlin source under ${root.path}: the guard cannot check it")
        return files.associate { file ->
            file.relativeTo(nextSh).path.replace('\\', '/') to withoutComments(file.readText())
        }
    }

    /** Same walk as [locate], for a directory. */
    private fun locateDir(pathFromModuleRoot: String): File {
        val fromRepoRoot = "app/$pathFromModuleRoot"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, pathFromModuleRoot).takeIf { it.isDirectory }?.let { return it }
            File(dir, fromRepoRoot).takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError(
            "$pathFromModuleRoot not found from working dir ${System.getProperty("user.dir")}: " +
                "the guard cannot check it",
        )
    }

    /**
     * Kotlin source without block comments and line comments, so that a commented-out call does
     * not count. String and character literals are skipped (a `//` inside `"https://..."` is not a
     * comment). Raw triple-quoted strings are not handled, the guarded files do not use them.
     */
    fun withoutComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                }
                c == '"' || c == '\'' -> {
                    out.append(c)
                    i++
                    while (i < source.length && source[i] != c) {
                        if (source[i] == '\\' && i + 1 < source.length) {
                            out.append(source[i])
                            i++
                        }
                        out.append(source[i])
                        i++
                    }
                    if (i < source.length) {
                        out.append(source[i])
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * The text of every call to [name] in [source], from the name to the matching closing
     * parenthesis (parentheses inside string literals are ignored). A qualified call
     * (`androidx.compose.material3.OutlinedTextField(`) counts, a longer name that merely ends with
     * [name] (`PortTextField(` for `TextField`) does not. Declarations (`fun name(`) are not calls
     * and are skipped. [source] must already be free of comments.
     */
    fun calls(source: String, name: String): List<String> {
        val result = mutableListOf<String>()
        Regex("""(?<!\w)${Regex.escape(name)}\(""").findAll(source).forEach { match ->
            if (source.substring(0, match.range.first).trimEnd().endsWith("fun")) return@forEach
            var depth = 0
            var inString = false
            var i = match.range.last
            while (i < source.length) {
                val c = source[i]
                if (inString) {
                    if (c == '\\') i++ else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                result += source.substring(match.range.first, i + 1)
                                return@forEach
                            }
                        }
                    }
                }
                i++
            }
            throw AssertionError("unbalanced parentheses after a call to $name(")
        }
        return result
    }
}
