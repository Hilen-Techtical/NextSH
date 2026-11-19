// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The sequence of the "Wipe the vault" action on the JVM: the order of the closing and of the erase, what a
 * failing step does not prevent, the second attempt, the retry, the end ("Close") and the release of the
 * gate. What the steps do to the real app is `AndroidVaultWipeOpsTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultWipeRunnerTest {

    /** A closing and an erase that note what they were asked, and fail on demand. */
    private class FakeOps : VaultWipeStopOps, VaultWipeEraseOps {
        val calls = mutableListOf<String>()

        /** A call fails with this the next [n] times (a negative count: always). */
        val failures = mutableMapOf<String, Pair<Throwable, Int>>()

        /** A closing step that ends without finishing its job (a timeout). */
        val notDone = mutableSetOf<String>()

        var vaultGone = true

        fun failAlways(step: String, error: Throwable = IOException("fixed")) {
            failures[step] = error to -1
        }

        fun failOnce(step: String, error: Throwable = IOException("fixed")) {
            failures[step] = error to 1
        }

        fun heal(step: String) {
            failures.remove(step)
        }

        private fun record(step: String) {
            calls += step
            val (error, left) = failures[step] ?: return
            if (left == 0) return
            if (left > 0) failures[step] = error to left - 1
            throw error
        }

        override suspend fun stopSync(): Boolean { record("stopSync"); return "stopSync" !in notDone }
        override suspend fun endPrompts() = record("endPrompts")
        override suspend fun stopTunnels() = record("stopTunnels")
        override suspend fun cancelTransfers(): Boolean { record("cancelTransfers"); return "cancelTransfers" !in notDone }
        override suspend fun closeTabs() = record("closeTabs")
        override suspend fun closeSessions(): Boolean { record("closeSessions"); return "closeSessions" !in notDone }
        override suspend fun clearTransferList() = record("clearTransferList")

        override suspend fun eraseVault() = record("eraseVault")
        override suspend fun clearTables() = record("clearTables")
        override suspend fun clearKnownHosts() = record("clearKnownHosts")
        override suspend fun disableSync() = record("disableSync")
        override suspend fun deleteCredentialMeta() = record("deleteCredentialMeta")
        override suspend fun clearWebData() = record("clearWebData")
        override suspend fun cancelNotifications() = record("cancelNotifications")
        override suspend fun resetOnboarding() = record("resetOnboarding")
        override suspend fun clearMarker() = record("clearMarker")
        /** When set, vaultGone suspends until it is completed (a read of the vault that takes a while). */
        var vaultGoneGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun vaultGone(): Boolean { calls += "vaultGone"; vaultGoneGate?.await(); return vaultGone }
    }

    private val closing = listOf(
        "stopSync", "endPrompts", "stopTunnels", "cancelTransfers", "closeTabs", "closeSessions", "clearTransferList",
    )
    private val erasing = listOf(
        "eraseVault", "clearTables", "clearKnownHosts", "disableSync", "deleteCredentialMeta", "clearWebData",
        "cancelNotifications", "resetOnboarding", "clearMarker",
    )

    private class Fixture(val scope: TestScope) {
        val ops = FakeOps()
        val gate = VaultWipeGate()
        val runner = VaultWipeRunner(
            scope = scope,
            gate = gate,
            begin = { ops.calls += "begin" },
            stop = { stopVaultDependentWork(ops) },
            erase = { steps -> eraseVaultData(ops, steps) },
            ops = ops,
        )
    }

    private fun TestScope.fixture() = Fixture(this)

    // ── The order ─────────────────────────────────────────────────────────────

    @Test
    fun `the closing comes in its order, then the erase, then the app is told it is done`() = runTest {
        val f = fixture()

        f.runner.start()
        advanceUntilIdle()

        assertEquals(listOf("begin") + closing + erasing + "vaultGone", f.ops.calls)
        assertEquals(VaultWipeState.DONE, f.gate.state.value)
    }

    @Test
    fun `the tunnels are stopped before the tables are erased, the sessions before the vault`() = runTest {
        val f = fixture()

        f.runner.start()
        advanceUntilIdle()

        assertTrue(f.ops.calls.indexOf("stopTunnels") < f.ops.calls.indexOf("clearTables"))
        assertTrue(f.ops.calls.indexOf("cancelTransfers") < f.ops.calls.indexOf("closeSessions"))
        assertTrue(f.ops.calls.indexOf("closeSessions") < f.ops.calls.indexOf("eraseVault"))
        assertTrue(f.ops.calls.indexOf("stopSync") < f.ops.calls.indexOf("endPrompts"))
    }

    @Test
    fun `the gate is closed from the request, and only the end opens it`() = runTest {
        val f = fixture()

        assertFalse(f.gate.isClosed())
        f.runner.start()
        assertTrue(f.gate.isClosed(), "closed at once, before the first step runs")
        advanceUntilIdle()
        assertTrue(f.gate.isClosed(), "closed after the end too: the activity has not restarted the app yet")
        assertTrue(f.gate.acknowledgeDone())
        assertFalse(f.gate.isClosed())
        assertFalse(f.gate.acknowledgeDone(), "the restart is taken by one caller only")
    }

    // ── A step that fails never prevents the next ones ──────────────────────

    @Test
    fun `a closing step that throws prevents neither the next ones nor the erase`() = runTest {
        for (step in closing) {
            val f = fixture()
            f.ops.failAlways(step)

            f.runner.start()
            advanceUntilIdle()

            assertEquals(listOf("begin") + closing + erasing + "vaultGone", f.ops.calls, "after $step failed")
            assertEquals(VaultWipeState.DONE, f.gate.state.value, "the erase went through after $step failed")
        }
    }

    @Test
    fun `a closing step that does not finish in time prevents neither the next ones nor the erase`() = runTest {
        for (step in listOf("stopSync", "cancelTransfers", "closeSessions")) {
            val f = fixture()
            f.ops.notDone += step

            f.runner.start()
            advanceUntilIdle()

            assertEquals(listOf("begin") + closing + erasing + "vaultGone", f.ops.calls, "after $step timed out")
            assertEquals(VaultWipeState.DONE, f.gate.state.value)
        }
    }

    @Test
    fun `the closing and the erase are in separate tries, a closing that fails does not skip the erase`() = runTest {
        val f = fixture()
        f.ops.failAlways("stopTunnels", IllegalStateException("fixed"))

        f.runner.start()
        advanceUntilIdle()

        assertTrue("eraseVault" in f.ops.calls)
        assertTrue("clearTables" in f.ops.calls)
    }

    @Test
    fun `an erase step that fails once is played again, and the other steps still run`() = runTest {
        for (step in erasing.filter { it != "resetOnboarding" && it != "clearMarker" }) {
            val f = fixture()
            f.ops.failOnce(step)

            f.runner.start()
            advanceUntilIdle()

            // Every erase step ran in the first pass, whatever failed, and the failed one ran again.
            for (other in erasing) assertTrue(other in f.ops.calls, "$other ran after $step failed")
            assertEquals(2, f.ops.calls.count { it == step }, "$step is played again")
            assertEquals(VaultWipeState.DONE, f.gate.state.value, "$step worked the second time")
        }
    }

    @Test
    fun `a begin that fails does not prevent the wipe`() = runTest {
        val ops = FakeOps()
        val gate = VaultWipeGate()
        val runner = VaultWipeRunner(
            scope = this,
            gate = gate,
            begin = { throw IOException("marker") },
            stop = { stopVaultDependentWork(ops) },
            erase = { steps -> eraseVaultData(ops, steps) },
            ops = ops,
        )

        runner.start()
        advanceUntilIdle()

        assertEquals(VaultWipeState.DONE, gate.state.value)
    }

    // ── CancellationException is never swallowed ─────────────────────────────

    @Test
    fun `a cancellation in a closing step is not swallowed, the gate is released and the erase does not run`() = runTest {
        val f = fixture()
        f.ops.failAlways("closeSessions", CancellationException("cancelled"))

        f.runner.start()
        advanceUntilIdle()

        assertEquals(VaultWipeState.IDLE, f.gate.state.value, "not left closed, nor RUNNING, for the rest of the process")
        assertFalse("eraseVault" in f.ops.calls)
        assertFalse("clearTransferList" in f.ops.calls, "the steps after it did not run either")
    }

    @Test
    fun `a cancellation in an erase step is not swallowed and the gate is released`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearTables", CancellationException("cancelled"))

        f.runner.start()
        advanceUntilIdle()

        assertEquals(VaultWipeState.IDLE, f.gate.state.value)
        assertFalse("clearKnownHosts" in f.ops.calls)
    }

    // ── The second attempt, FAILED, the retry ────────────────────────────────

    @Test
    fun `the erase is played twice, only the steps that failed again, then the wipe is FAILED`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearKnownHosts")

        f.runner.start()
        advanceUntilIdle()

        assertEquals(VaultWipeState.FAILED, f.gate.state.value)
        assertEquals(2, f.ops.calls.count { it == "clearKnownHosts" })
        assertEquals(1, f.ops.calls.count { it == "clearTables" }, "what went through is not played again")
        assertEquals(1, f.ops.calls.count { it == "eraseVault" })
        assertTrue(f.gate.isClosed(), "FAILED stays closed")
        assertFalse("clearMarker" in f.ops.calls, "the marker is the memory of the work left: kept")
    }

    @Test
    fun `the creation of the vault is not opened over a vault that could not be erased`() = runTest {
        val f = fixture()
        f.ops.failAlways("eraseVault")

        f.runner.start()
        advanceUntilIdle()

        assertEquals(VaultWipeState.FAILED, f.gate.state.value)
        assertFalse("resetOnboarding" in f.ops.calls, "the onboarding flag is reset only once the vault is erased")
        assertFalse("clearMarker" in f.ops.calls)
    }

    @Test
    fun `a vault that is found not gone after every step is a failure to erase it`() = runTest {
        val f = fixture()
        f.ops.vaultGone = false

        f.runner.start()
        advanceUntilIdle()

        assertEquals(VaultWipeState.FAILED, f.gate.state.value)
    }

    @Test
    fun `retry plays the closing again, then what is left, and ends in DONE`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearKnownHosts")
        f.runner.start()
        advanceUntilIdle()
        assertEquals(VaultWipeState.FAILED, f.gate.state.value)
        f.ops.calls.clear()
        f.ops.heal("clearKnownHosts")

        f.runner.retry()
        assertTrue(f.gate.isClosed())
        advanceUntilIdle()

        // The onboarding flag went through in the first attempt: only what was left is played.
        assertEquals(closing + listOf("clearKnownHosts", "clearMarker", "vaultGone"), f.ops.calls)
        assertEquals(VaultWipeState.DONE, f.gate.state.value)
    }

    @Test
    fun `retry is ignored unless the wipe is FAILED`() = runTest {
        val f = fixture()

        f.runner.retry()
        advanceUntilIdle()

        assertTrue(f.ops.calls.isEmpty())
        assertEquals(VaultWipeState.IDLE, f.gate.state.value)
    }

    @Test
    fun `a second start while the wipe runs, or after it is done, is ignored`() = runTest {
        val f = fixture()

        f.runner.start()
        f.runner.start()
        advanceUntilIdle()
        f.runner.start()
        advanceUntilIdle()

        assertEquals(1, f.ops.calls.count { it == "stopSync" })
        assertEquals(1, f.ops.calls.count { it == "eraseVault" })
    }

    @Test
    fun `a start after a failure is a new attempt from the start`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearTables")
        f.runner.start()
        advanceUntilIdle()
        f.ops.calls.clear()
        f.ops.heal("clearTables")

        f.runner.start()
        advanceUntilIdle()

        assertEquals(listOf("begin") + closing + erasing + "vaultGone", f.ops.calls)
        assertEquals(VaultWipeState.DONE, f.gate.state.value)
    }

    @Test
    fun `resume is the same wipe from the start`() = runTest {
        val f = fixture()

        f.runner.resume()
        advanceUntilIdle()

        assertEquals(listOf("begin") + closing + erasing + "vaultGone", f.ops.calls)
        assertEquals(VaultWipeState.DONE, f.gate.state.value)
    }

    // ── "Close" on the failure dialog ────────────────────────────────────────

    @Test
    fun `closing the failure with the vault still there opens the app again and forgets the wipe`() = runTest {
        val f = fixture()
        f.ops.failAlways("eraseVault")
        f.ops.vaultGone = false
        f.runner.start()
        advanceUntilIdle()
        f.ops.calls.clear()

        f.runner.dismissFailure()
        assertTrue(f.gate.isClosed(), "still closed until the end of the abandon")
        advanceUntilIdle()

        assertEquals(VaultWipeState.IDLE, f.gate.state.value, "the app stays where it is")
        assertTrue("clearMarker" in f.ops.calls, "a dying process must not wipe later a vault that is still wanted")
        assertFalse("resetOnboarding" in f.ops.calls)
    }

    @Test
    fun `closing the failure with the vault gone restarts the app on the creation of the vault`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearTables")
        f.runner.start()
        advanceUntilIdle()
        f.ops.calls.clear()

        f.runner.dismissFailure()
        advanceUntilIdle()

        assertEquals(VaultWipeState.DONE, f.gate.state.value)
        assertTrue("resetOnboarding" in f.ops.calls)
        assertTrue("clearMarker" in f.ops.calls, "the marker goes whatever the result, or a later start would wipe the new vault")
    }

    @Test
    fun `closing the failure that cannot read the vault is taken as a vault still there`() = runTest {
        val ops = object : VaultWipeEraseOps by FakeOps() {
            override suspend fun vaultGone(): Boolean = throw IOException("cannot read")
        }
        val gate = VaultWipeGate()
        gate.set(VaultWipeState.FAILED)
        val runner = VaultWipeRunner(this, gate, begin = {}, stop = { true }, erase = { it }, ops = ops)

        runner.dismissFailure()
        advanceUntilIdle()

        assertEquals(VaultWipeState.IDLE, gate.state.value)
    }

    @Test
    fun `the failure is closed once, and only from FAILED`() = runTest {
        val f = fixture()
        f.runner.dismissFailure()
        advanceUntilIdle()
        assertTrue(f.ops.calls.isEmpty(), "nothing to close when nothing failed")

        f.ops.failAlways("clearTables")
        f.runner.start()
        advanceUntilIdle()
        f.ops.calls.clear()
        f.runner.dismissFailure()
        f.runner.dismissFailure()
        advanceUntilIdle()

        assertEquals(1, f.ops.calls.count { it == "clearMarker" })
    }

    @Test
    fun `a marker that cannot be cleared keeps the failure, so the next start never wipes a new vault`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearTables")
        f.runner.start()
        advanceUntilIdle()
        f.ops.failAlways("clearMarker")

        f.runner.dismissFailure()
        advanceUntilIdle()

        assertEquals(VaultWipeState.FAILED, f.gate.state.value, "neither DONE nor IDLE while the marker is on the disk")
        assertFalse("resetOnboarding" in f.ops.calls.takeLast(2), "nothing is abandoned")

        // Closing it again once the marker can be cleared works: the dismissing flag was released.
        f.ops.heal("clearMarker")
        f.runner.dismissFailure()
        advanceUntilIdle()
        assertEquals(VaultWipeState.DONE, f.gate.state.value)
    }

    @Test
    fun `retry and start are ignored while the failure is being closed`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearTables")
        f.runner.start()
        advanceUntilIdle()
        f.ops.calls.clear()

        f.runner.dismissFailure()
        f.runner.retry()
        f.runner.start()
        advanceUntilIdle()

        assertTrue("stopSync" !in f.ops.calls && "eraseVault" !in f.ops.calls, "no wipe started: ${f.ops.calls}")
        assertEquals(VaultWipeState.DONE, f.gate.state.value)
    }

    @Test
    fun `a retry tapped while the vault is being read is ignored, and the close goes on`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearTables")
        f.runner.start()
        advanceUntilIdle()
        f.ops.calls.clear()
        val reading = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.ops.vaultGoneGate = reading

        f.runner.dismissFailure()
        advanceUntilIdle() // suspended in vaultGone
        assertTrue("vaultGone" in f.ops.calls)
        f.runner.retry()
        advanceUntilIdle()
        assertEquals(VaultWipeState.FAILED, f.gate.state.value, "still closing: the retry did not start")
        assertFalse("stopSync" in f.ops.calls)
        reading.complete(Unit)
        advanceUntilIdle()

        assertEquals(VaultWipeState.DONE, f.gate.state.value)
        assertFalse("stopSync" in f.ops.calls, "no wipe was replayed")
    }

    @Test
    fun `a state that is no longer FAILED when the vault has been read abandons nothing`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearTables")
        f.runner.start()
        advanceUntilIdle()
        f.ops.calls.clear()
        val reading = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.ops.vaultGoneGate = reading
        f.runner.dismissFailure()
        advanceUntilIdle() // suspended in vaultGone
        // Whatever moved the state meanwhile: the abandon must not clear the marker of a wipe that runs.
        f.gate.set(VaultWipeState.RUNNING)

        reading.complete(Unit)
        advanceUntilIdle()

        assertEquals(VaultWipeState.RUNNING, f.gate.state.value, "compare and set: the running wipe is not overwritten")
        assertFalse("clearMarker" in f.ops.calls)
        assertFalse("resetOnboarding" in f.ops.calls)
    }

    @Test
    fun `a marker that cannot be cleared tells the dialog why Close did nothing, and the next try clears it`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearTables")
        f.runner.start()
        advanceUntilIdle()
        assertFalse(f.gate.closeBlocked.value)
        f.ops.failAlways("clearMarker")

        f.runner.dismissFailure()
        advanceUntilIdle()
        assertTrue(f.gate.closeBlocked.value, "the dialog says why")

        f.ops.heal("clearMarker")
        f.runner.dismissFailure()
        advanceUntilIdle()
        assertFalse(f.gate.closeBlocked.value)
        assertEquals(VaultWipeState.DONE, f.gate.state.value)
    }

    @Test
    fun `a retry or a new start forgets the blocked close`() = runTest {
        val f = fixture()
        f.ops.failAlways("clearTables")
        f.runner.start()
        advanceUntilIdle()
        f.ops.failAlways("clearMarker")
        f.runner.dismissFailure()
        advanceUntilIdle()
        assertTrue(f.gate.closeBlocked.value)

        f.runner.retry()

        assertFalse(f.gate.closeBlocked.value)
    }


    // ── The conditional steps ────────────────────────────────────────────────

    @Test
    fun `the marker is cleared last and only when everything else is erased`() = runTest {
        val ops = FakeOps()
        ops.failOnce("cancelNotifications")

        val failed = eraseVaultData(ops)

        assertEquals(setOf(EraseStep.NOTIFICATIONS, EraseStep.MARKER), failed)
        assertFalse("clearMarker" in ops.calls)
        // The onboarding flag does not wait for the notifications: the vault is erased.
        assertTrue("resetOnboarding" in ops.calls)
    }

    @Test
    fun `the onboarding flag waits for the vault`() = runTest {
        val ops = FakeOps()
        ops.failAlways("eraseVault")

        val failed = eraseVaultData(ops)

        assertEquals(setOf(EraseStep.VAULT, EraseStep.ONBOARDING, EraseStep.MARKER), failed)
        assertFalse("resetOnboarding" in ops.calls)
    }

    @Test
    fun `only the steps asked for are erased`() = runTest {
        val ops = FakeOps()

        val failed = eraseVaultData(ops, setOf(EraseStep.TABLES, EraseStep.MARKER))

        assertTrue(failed.isEmpty())
        assertEquals(listOf("clearTables", "clearMarker"), ops.calls)
    }
}
