// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.browser

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers the pure parts of the browser profile (path resolution, Chromium switches)
 * and the process-wide instance lock. None of this loads the Chromium natives.
 *
 * [BrowserProfileLock] keeps static state for the life of the process, so every test
 * resets it through [BrowserProfileLock.releaseForTest] before and after running.
 */
class BrowserProfileTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        BrowserProfileLock.releaseForTest()
        tempDir = Files.createTempDirectory("nextsh-browser-profile-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        BrowserProfileLock.releaseForTest()
        tempDir.deleteRecursively()
    }

    // ── resolveBrowserProfileDir ──────────────────────────────────────────────

    @Test
    fun `resolve uses NEXTSH_HOME when set`() {
        assertEquals(
            File("/data/nextsh", "browser"),
            resolveBrowserProfileDir(nextshHome = "/data/nextsh", userHome = "/home/me"),
        )
    }

    @Test
    fun `resolve uses NEXTSH_HOME even when user home is missing`() {
        assertEquals(
            File("/data/nextsh", "browser"),
            resolveBrowserProfileDir(nextshHome = "/data/nextsh", userHome = null),
        )
    }

    @Test
    fun `resolve falls back to user home dot nextsh when NEXTSH_HOME is blank`() {
        val expected = File("/home/me/.nextsh", "browser")
        assertEquals(expected, resolveBrowserProfileDir(nextshHome = "   ", userHome = "/home/me"))
        assertEquals(expected, resolveBrowserProfileDir(nextshHome = "", userHome = "/home/me"))
        assertEquals(expected, resolveBrowserProfileDir(nextshHome = null, userHome = "/home/me"))
    }

    @Test
    fun `resolve returns null when neither NEXTSH_HOME nor user home is usable`() {
        assertNull(resolveBrowserProfileDir(nextshHome = null, userHome = null))
        assertNull(resolveBrowserProfileDir(nextshHome = "", userHome = ""))
        assertNull(resolveBrowserProfileDir(nextshHome = "  ", userHome = "  "))
        assertNull(resolveBrowserProfileDir(nextshHome = null, userHome = " "))
    }

    // ── browserProfileSwitches ────────────────────────────────────────────────

    @Test
    fun `switches contain exactly user-data-dir with the canonical profile path`() {
        val dir = File(tempDir, "browser")
        assertEquals(
            listOf("user-data-dir" to dir.canonicalPath),
            browserProfileSwitches(dir),
        )
    }

    @Test
    fun `switches resolve dot dot segments to the same path the lock uses`() {
        val spelled = File(tempDir, "other/../browser")
        val plain = File(tempDir, "browser")

        assertEquals(
            listOf("user-data-dir" to plain.canonicalPath),
            browserProfileSwitches(spelled),
        )
    }

    @Test
    fun `switches make a relative profile path absolute`() {
        val switches = browserProfileSwitches(File("relative/browser"))
        assertEquals(1, switches.size)
        assertTrue(File(switches.single().second).isAbsolute)
    }

    // ── BrowserProfileLock ────────────────────────────────────────────────────

    @Test
    fun `first acquire returns Acquired and creates the lock file`() {
        val dir = File(tempDir, "browser")

        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(dir))

        assertTrue(File(dir, BrowserProfileLock.LOCK_FILE_NAME).isFile, "lock file must exist")
    }

    @Test
    fun `acquire creates a missing nested profile directory`() {
        val dir = File(tempDir, "a/b/browser")

        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(dir))

        assertTrue(dir.isDirectory)
    }

    @Test
    fun `second acquire on the same directory in the same process is idempotent`() {
        val dir = File(tempDir, "browser")

        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(dir))
        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(dir))
        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(dir))
    }

    @Test
    fun `acquire is idempotent across different spellings of the same directory`() {
        val dir = File(tempDir, "browser")
        val sameDirOtherSpelling = File(File(tempDir, "other"), "../browser")

        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(dir))
        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(sameDirOtherSpelling))
    }

    @Test
    fun `acquire for a different directory while one is held is an Error`() {
        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(File(tempDir, "first")))

        val result = BrowserProfileLock.tryAcquire(File(tempDir, "second"))

        assertEquals(LockResult.Error("already holding another profile lock"), result)
        assertTrue(
            !File(tempDir, "second/${BrowserProfileLock.LOCK_FILE_NAME}").exists(),
            "a refused call must not create anything in the other directory",
        )
    }

    @Test
    fun `a different directory can be acquired once the previous lock is released`() {
        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(File(tempDir, "first")))

        BrowserProfileLock.releaseForTest()

        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(File(tempDir, "second")))
    }

    @Test
    fun `acquire reports Busy when this JVM already holds the lock through another channel`() {
        val dir = File(tempDir, "browser")
        assertTrue(dir.mkdirs())
        val lockFile = File(dir, BrowserProfileLock.LOCK_FILE_NAME)

        // Same-JVM overlap: a second lock on the same file raises
        // OverlappingFileLockException, which must map to Busy. (The real cross-process
        // path, where tryLock() returns null, is covered by the child JVM test below.)
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { other ->
            val foreignLock = assertNotNull(other.tryLock(), "the test must own the lock first")
            try {
                assertEquals(LockResult.Busy, BrowserProfileLock.tryAcquire(dir))
            } finally {
                foreignLock.release()
            }
        }

        // Once the other holder is gone the same call succeeds: the failed attempt
        // did not leak a channel or a half-held lock.
        assertEquals(LockResult.Acquired, BrowserProfileLock.tryAcquire(dir))
    }

    @Test
    fun `acquire reports Busy when another process holds the lock`() {
        val dir = File(tempDir, "browser")
        assertTrue(dir.mkdirs())
        val lockFile = File(dir, BrowserProfileLock.LOCK_FILE_NAME)

        // A child JVM stands in for a second NextSH process: it is the production path
        // where tryLock() returns null instead of raising OverlappingFileLockException.
        val holderSource = File(tempDir, "Holder.java")
        holderSource.writeText(
            """
            import java.nio.channels.FileChannel;
            import java.nio.channels.FileLock;
            import java.nio.file.Paths;
            import java.nio.file.StandardOpenOption;

            public class Holder {
                public static void main(String[] args) throws Exception {
                    try (FileChannel channel = FileChannel.open(
                            Paths.get(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        FileLock lock = channel.tryLock();
                        System.out.println(lock != null ? "locked" : "not-locked");
                        System.out.flush();
                        System.in.read();
                    }
                }
            }
            """.trimIndent(),
        )
        val javaExe = ProcessHandle.current().info().command().orElse(null)
            ?: fail("cannot locate the java executable of the test JVM")

        val child = ProcessBuilder(javaExe, holderSource.absolutePath, lockFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        try {
            val output = StringBuffer()
            val reported = CompletableFuture<Boolean>()
            Thread {
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            output.append(line).append('\n')
                            if (line == "locked") {
                                reported.complete(true)
                            }
                        }
                    }
                } finally {
                    reported.complete(false)
                }
            }.apply { isDaemon = true }.start()

            val locked = try {
                reported.get(15, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                fail("the child JVM did not report the lock within 15 s. Output so far: $output")
            }
            if (!locked) fail("the child JVM did not take the lock. Output: $output")

            assertEquals(LockResult.Busy, BrowserProfileLock.tryAcquire(dir))
        } finally {
            child.destroyForcibly()
            child.waitFor()
        }

        // The OS drops the lock with its owner: the same call now succeeds. A short
        // retry absorbs the delay between process exit and handle teardown on Windows.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var result = BrowserProfileLock.tryAcquire(dir)
        while (result != LockResult.Acquired && System.nanoTime() < deadline) {
            Thread.sleep(50)
            result = BrowserProfileLock.tryAcquire(dir)
        }
        assertEquals(LockResult.Acquired, result)
    }

    @Test
    fun `acquire is an Error without any path when the profile path is a regular file`() {
        val notADir = File(tempDir, "browser")
        assertTrue(notADir.createNewFile())

        val result = BrowserProfileLock.tryAcquire(notADir)

        assertIs<LockResult.Error>(result)
        assertTrue(
            !result.reason.contains(tempDir.name) && !result.reason.contains(File.separator),
            "an Error reason must never carry a path: ${result.reason}",
        )
    }
}
