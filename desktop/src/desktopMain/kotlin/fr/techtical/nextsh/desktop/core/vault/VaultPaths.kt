// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object VaultPaths {

    /**
     * Test-only override of the vault base directory.
     *
     * Production resolves the base dir from `NEXTSH_HOME` / `user.home` (see
     * [resolveBaseDir]). The JVM does not allow per-test mutation of the process
     * environment, so this property gives tests a way to isolate `~/.nextsh`
     * into a per-test temp dir without touching real user data. It MUST stay
     * `null` in production; set it via [withBaseDir] from test code only.
     */
    @Volatile
    private var baseDirOverride: File? = null

    private val defaultBaseDir: File by lazy { resolveBaseDir() }

    private fun resolveBaseDir(): File {
        // Unified location across platforms: ~/.nextsh/. Skipping %APPDATA% avoids
        // Windows UAC/OneDrive/AV redirection quirks where the JVM sees a directory
        // that isn't actually physically there from the user's file explorer.
        val override = System.getenv("NEXTSH_HOME")
        val dir = if (override != null && override.isNotBlank()) {
            File(override)
        } else {
            File(System.getProperty("user.home"), ".nextsh")
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Impossible de créer le répertoire du vault : ${dir.absolutePath}")
        }
        if (!dir.isDirectory) {
            throw IllegalStateException("${dir.absolutePath} existe mais n'est pas un dossier")
        }
        println("[NextSH] Vault dir: ${dir.absolutePath}")
        return dir
    }

    private val baseDir: File get() = baseDirOverride ?: defaultBaseDir

    val metaFile: File get() = File(baseDir, "vault.meta")
    val dataFile: File get() = File(baseDir, "vault.dat")

    /**
     * Test helper: run [block] with the vault base dir pointed at [dir].
     * Restores the previous override afterwards. Not for production use.
     */
    internal fun <T> withBaseDir(dir: File, block: () -> T): T {
        val previous = baseDirOverride
        baseDirOverride = dir
        return try {
            block()
        } finally {
            baseDirOverride = previous
        }
    }

    /**
     * Atomically and durably replace [target]'s contents with [bytes].
     *
     * `vault.meta` holds the ONLY on-disk wrap of the DEK and `vault.dat` holds
     * every credential, so a torn write (truncate-in-place + crash) would
     * permanently destroy access. To avoid that we:
     *  1. write to a sibling temp file in the SAME directory (`<name>.tmp`),
     *  2. `flush()` then `FileChannel.force(true)` to fsync the bytes to the
     *     storage device before they are referenced by [target],
     *  3. `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` so a reader ever sees
     *     either the old intact file or the fully-written new one: never a torn
     *     one (falls back to a plain `REPLACE_EXISTING` move where the platform
     *     does not support atomic moves),
     *  4. best-effort fsync the parent directory so the rename itself is durable.
     *
     * This makes operations that rewrite meta (migration, [changePin],
     * recovery setup) effectively idempotent on crash: on retry either the
     * intact previous file is present, or the fully-written new one is.
     */
    internal fun atomicWrite(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile
            ?: throw IllegalStateException("Cible sans répertoire parent : ${target.absolutePath}")
        val tmp = File(dir, "${target.name}.tmp")
        try {
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                raf.write(bytes)
                raf.fd.sync() // fsync file contents to the storage device
            }
            val tmpPath = tmp.toPath()
            val targetPath = target.toPath()
            try {
                Files.move(
                    tmpPath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Some filesystems (e.g. certain network/Windows cases) reject
                // ATOMIC_MOVE; fall back to a plain replacing move.
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
            // Best-effort: fsync the directory so the rename is durable. Several
            // platforms/JDKs disallow opening a directory channel for write or
            // forcing it: never fail the write on that.
            try {
                java.nio.channels.FileChannel.open(
                    dir.toPath(),
                    java.nio.file.StandardOpenOption.READ,
                ).use { it.force(true) }
            } catch (_: Exception) {
                // Directory fsync not supported here: acceptable.
            }
        } catch (e: Exception) {
            // Clean up the temp file on any failure; never leave a stray .tmp.
            try {
                Files.deleteIfExists(tmp.toPath())
            } catch (_: Exception) {
                // best effort
            }
            throw e
        }
    }
}
