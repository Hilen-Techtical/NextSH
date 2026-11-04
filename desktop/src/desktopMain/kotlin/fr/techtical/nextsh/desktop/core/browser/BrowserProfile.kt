// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.browser

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

// The in-app browser (KCEF/JCEF, Chromium 144) needs a Chromium profile directory
// that NextSH owns, for two reasons.
//
// 1. JCEF's CefSettings in the pinned version has `cache_path` but no
//    `root_cache_path`, and CEF logs "Please customize CefSettings.root_cache_path"
//    at every start. Without an explicit directory, Chromium falls back to the
//    shared default `%LOCALAPPDATA%\CEF\User Data`. In a normal launch (Start menu)
//    Chromium then hits an internal CHECK in libcef.dll at the first page load,
//    whatever the site, and the whole app dies. Passing a NextSH-owned directory
//    through the Chromium switch `--user-data-dir` avoids the crash.
//
// 2. Chromium guards a user-data-dir with a process singleton. A second process
//    using the same directory does not crash, but its browser silently never
//    loads. BrowserProfileLock turns that silent failure into an explicit message.
//
// The directory only holds Chromium's own bookkeeping files. Nothing browsed is
// meant to be persisted there: the browser is created on an in-memory request
// context (see DesktopBrowserOverlay), so cookies, history and cache of tunnelled
// services never reach the disk.

/**
 * Resolves the Chromium profile directory of the in-app browser: `<base>/browser`,
 * where `<base>` is [nextshHome] (the `NEXTSH_HOME` override) when not blank,
 * otherwise `<userHome>/.nextsh`.
 *
 * Same blank-guarded resolution as the rest of the app (vault, credential meta).
 * Returns `null` when neither input is usable, so the caller never ends up with a
 * profile relative to the working directory.
 */
internal fun resolveBrowserProfileDir(nextshHome: String?, userHome: String?): File? {
    val base = nextshHome?.takeIf { it.isNotBlank() }
        ?: userHome?.takeIf { it.isNotBlank() }?.let { "$it/.nextsh" }
        ?: return null
    return File(base, "browser")
}

/**
 * Chromium command-line switches that pin the profile to [profileDir].
 *
 * The path is the canonical one, the same identity [BrowserProfileLock] uses, so the
 * directory locked by NextSH is exactly the one handed to Chromium (symlinks, `..`
 * and 8.3 short names resolved). It falls back to the absolute path if the
 * canonicalization fails.
 *
 * Kept as plain name/value pairs (without the leading dashes, as expected by
 * `CefCommandLine.appendSwitchWithValue`) so the mapping is unit-testable without
 * loading the Chromium natives.
 */
internal fun browserProfileSwitches(profileDir: File): List<Pair<String, String>> {
    val path = try {
        profileDir.canonicalPath
    } catch (_: Exception) {
        profileDir.absolutePath
    }
    return listOf("user-data-dir" to path)
}

/** Outcome of [BrowserProfileLock.tryAcquire]. */
internal sealed interface LockResult {
    /** This process now owns the profile (or already did). */
    data object Acquired : LockResult

    /** Another NextSH process (or another holder in this JVM) owns the profile. */
    data object Busy : LockResult

    /**
     * The lock could not be taken for a technical reason. [reason] is a diagnostic
     * made of fixed text and an exception class name: it never carries a path or an
     * exception message (those would leak the Windows user name into logs).
     */
    data class Error(val reason: String) : LockResult
}

/**
 * Process-wide instance lock on the browser profile directory.
 *
 * Only one NextSH process may drive Chromium on a given profile (see the notes at
 * the top of this file). The lock is an OS-level [FileLock] on
 * `<profileDir>/nextsh-instance.lock`. Once taken, the channel and the lock are
 * kept in static fields for the whole life of the process and never released
 * explicitly: the OS releases them when the process exits, crash included, so a
 * stale lock can not survive its owner.
 *
 * The object holds a single lock. A call for the directory it already holds is
 * idempotent ([LockResult.Acquired]); a call for a different directory is refused
 * with a [LockResult.Error], because the lock is meant for the single profile
 * directory of the process.
 */
internal object BrowserProfileLock {

    const val LOCK_FILE_NAME = "nextsh-instance.lock"

    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var heldDir: File? = null

    /**
     * Tries to take the instance lock for [profileDir], creating the directory when
     * needed. Never throws: failures are reported through [LockResult.Error].
     */
    @Synchronized
    fun tryAcquire(profileDir: File): LockResult = try {
        acquire(profileDir)
    } catch (_: OverlappingFileLockException) {
        // The lock is already held by this JVM through another channel.
        LockResult.Busy
    } catch (e: IOException) {
        LockResult.Error("lock io failure: ${e.javaClass.simpleName}")
    } catch (e: Exception) {
        // SecurityException, UnsupportedOperationException...: best effort, the
        // caller decides whether a missing lock is fatal.
        LockResult.Error("lock failure: ${e.javaClass.simpleName}")
    }

    private fun acquire(profileDir: File): LockResult {
        val canonicalDir = profileDir.canonicalFile

        // Design constraint: keep a single channel per lock file. On POSIX, closing a
        // second channel on the same file drops the locks held through the first one
        // in this JVM, so an already held lock short-circuits here and never reopens.
        val held = heldDir
        if (held != null) {
            if (lock?.isValid == true) {
                return if (held == canonicalDir) {
                    LockResult.Acquired
                } else {
                    LockResult.Error("already holding another profile lock")
                }
            }
            // Defensive: the held lock was invalidated behind our back (channel
            // closed). Forget it and take a fresh one below.
            releaseInternal()
        }

        if (!canonicalDir.isDirectory && !canonicalDir.mkdirs() && !canonicalDir.isDirectory) {
            return LockResult.Error("cannot create profile directory")
        }

        var newChannel: FileChannel? = null
        var acquired = false
        try {
            newChannel = FileChannel.open(
                File(canonicalDir, LOCK_FILE_NAME).toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            // null: another process holds the lock. OverlappingFileLockException:
            // this JVM already holds it through another channel (propagates to Busy).
            val newLock = newChannel.tryLock() ?: return LockResult.Busy
            channel = newChannel
            lock = newLock
            heldDir = canonicalDir
            acquired = true
            return LockResult.Acquired
        } finally {
            if (!acquired) {
                try {
                    newChannel?.close()
                } catch (_: IOException) {
                    // nothing left to do
                }
            }
        }
    }

    /**
     * Test-only: releases the lock and resets the static state so tests stay
     * independent of each other. Production code never releases the lock (the OS
     * does it at process exit). Not for production use.
     */
    @Synchronized
    fun releaseForTest() {
        releaseInternal()
    }

    private fun releaseInternal() {
        try {
            lock?.release()
        } catch (_: IOException) {
            // The channel may already be closed: closing it below drops the lock anyway.
        }
        try {
            channel?.close()
        } catch (_: IOException) {
            // nothing left to do
        }
        lock = null
        channel = null
        heldDir = null
    }
}
