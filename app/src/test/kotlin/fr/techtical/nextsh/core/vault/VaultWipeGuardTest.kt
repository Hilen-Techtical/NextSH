// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Smoke alarms on what a unit test cannot run: the wiring of the "Wipe the vault" action in the app. Each
 * one is a lesson of the review of the Desktop fix or an invariant of the vault, and a cheap source check.
 * A source that cannot be found FAILS the test (see [ProjectFiles]).
 */
class VaultWipeGuardTest {

    private fun code(path: String) = blankComments(ProjectFiles.mainSource(path).readText())

    private fun squeeze(text: String) = text.replace(Regex("""\s+"""), " ")

    /** The body of the function that starts at [signature], up to the closing brace at the same level. */
    private fun bodyOf(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(start >= 0, "$signature not found")
        val open = code.indexOf('{', start)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(open, i + 1)
            }
        }
        error("$signature: no closing brace")
    }

    // ── The vault is never decrypted to be erased ───────────────────────────

    @Test
    fun `the vault is never cleared through EncryptedSharedPreferences, which would decrypt every secret`() {
        val vault = code("core/vault/VaultManager.kt")

        // clear() on the wrapper walks getAll(), which decrypts every name and every value to a String.
        assertFalse(Regex("""encryptedPrefs\s*\.\s*edit\(\)\s*\.\s*clear\(\)""").containsMatchIn(vault), "no edit().clear() on encryptedPrefs")
        assertFalse(Regex("""encryptedPrefs\s*\.\s*all\b""").containsMatchIn(bodyOf(vault, "fun wipeAllEntries(")), "no read of all in the wipe")
    }

    @Test
    fun `the erase of the vault works on the raw preferences, with no authentication window and no probe`() {
        val vault = code("core/vault/VaultManager.kt")

        for (signature in listOf("fun wipeVault(", "fun wipeAllEntries(")) {
            val body = bodyOf(vault, signature)
            assertFalse(body.contains("encryptedPrefs"), "$signature does not touch the encrypted view")
            assertFalse(body.contains("withVaultAuth"), "$signature asks for no authentication")
            assertFalse(body.contains("prefsLazy"), "$signature does not open the vault")
            assertFalse(body.contains("probeMasterKey"), "$signature does not probe the master key")
        }
        assertTrue(bodyOf(vault, "fun wipeAllEntries(").contains("prefsOpener.rawPrefs()"))
    }

    @Test
    fun `the erase keeps the master key of the keystore, and the biometric keys go by their exact alias format`() {
        val keystore = code("core/crypto/KeystoreManager.kt")
        val body = bodyOf(keystore, "fun deleteAllBiometricKeys(")

        assertTrue(body.contains("BiometricKeyAlias.biometricAliasesOf("), "only the aliases of the biometric keys")
        assertFalse(body.contains("AUTH_MASTER_KEY_ALIAS") || body.contains("nextsh_vault_auth_key"), "never the master key")
        assertFalse(body.contains("deleteEntry(\""), "no entry deleted by a literal name")
    }

    // ── It belongs to the application ───────────────────────────────────────

    @Test
    fun `the wipe runs in a scope of the application and never in a view model, a screen or an activity`() {
        val wipe = code("core/vault/AndroidVaultWipe.kt")
        val runner = code("core/vault/VaultWipe.kt")

        assertTrue(wipe.contains("CoroutineScope(SupervisorJob() + Dispatchers.Default)"), "its own scope")
        for ((name, source) in listOf("AndroidVaultWipe.kt" to wipe, "VaultWipe.kt" to runner)) {
            assertFalse(source.contains("viewModelScope"), "$name: a view model scope would cut the wipe when the screen goes")
            assertFalse(source.contains("lifecycleScope"), "$name: an activity scope would cut the wipe when the activity is destroyed")
            assertFalse(source.contains("androidx.lifecycle"), "$name: nothing of a screen")
        }
    }

    @Test
    fun `the view model only starts the wipe, and nothing else starts it`() {
        val viewModel = code("ui/settings/SettingsViewModel.kt")

        assertEquals("{ androidVaultWipe.start() }", squeeze(bodyOf(viewModel, "fun wipeVault(")))
        // The one caller of start() of the facade in the whole app.
        val callers = ProjectFiles.mainSourceDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AndroidVaultWipe.kt" }
            .filter { Regex("""androidVaultWipe\s*\.\s*start\(\)""").containsMatchIn(blankComments(it.readText())) }
            .map { it.name }.toList()
        assertEquals(listOf("SettingsViewModel.kt"), callers)
    }

    // ── The closing ─────────────────────────────────────────────────────────

    @Test
    fun `the closing and the erase steps come in the order of the spec`() {
        val source = squeeze(code("core/vault/VaultWipe.kt"))

        fun order(prefix: String, names: List<String>) {
            val positions = names.map { source.indexOf("step($prefix.$it)") }
            assertTrue(positions.all { it >= 0 }, "every step is called: $prefix $names")
            assertEquals(positions.sorted(), positions, "$prefix steps in order")
        }
        order("StopStep", listOf("SYNC", "PROMPTS", "TUNNELS", "TRANSFERS", "TABS", "SESSIONS", "TRANSFER_LIST"))
        order("EraseStep", listOf("VAULT", "TABLES", "KNOWN_HOSTS", "SYNC_SWITCH", "CREDENTIAL_META", "WEB_DATA", "NOTIFICATIONS"))
    }

    @Test
    fun `every step has its own try, a cancellation goes up and a log line carries a fixed text and a class`() {
        val source = code("core/vault/VaultWipe.kt")

        assertTrue(Regex("""catch \(e: CancellationException\) \{\s*throw e""").findAll(source).count() >= 4, "never swallowed")
        // The closing and the erase are two tries of the runner, not one: a closing that throws still lets the erase run.
        val run = bodyOf(source, "private suspend fun run(")
        assertTrue(run.indexOf("stop()") < run.indexOf("repeat(2)"))
        assertTrue(Regex("""try \{\s*stop\(\)\s*\} catch \(e: CancellationException\) \{\s*throw e\s*\} catch \(e: Exception\)""").containsMatchIn(run), "the closing has a try of its own")
        // The gate is released in a finally, whatever the way out.
        assertTrue(run.contains("finally"), "the release is in a finally")
        assertTrue(Regex("""finally \{[^}]*VaultWipeState\.RUNNING""").containsMatchIn(squeeze(run)))
        for (line in source.lines().filter { it.contains("Timber.") }) {
            val interpolations = Regex("""\$\{([^}]*)}|\$(\w+)""").findAll(line).map { it.groupValues[1] + it.groupValues[2] }.toList()
            assertTrue(interpolations.all { it == "errorClass(e)" }, "only errorClass(e) in a log line: $line")
        }
    }

    @Test
    fun `the sessions are closed in the scope of the manager and only the wait is bounded`() {
        val body = squeeze(bodyOf(code("core/ssh/SshSessionManager.kt"), "suspend fun closeAllAndWait("))

        assertTrue(body.contains("closeScope.async"), "the closes run in another scope")
        assertFalse(body.contains("coroutineScope"), "a coroutineScope would wait for blocking children before it could report a timeout")
        assertTrue(body.contains("withTimeoutOrNull(timeoutMs) { jobs.awaitAll() true }"), "the bound is on the wait only")
        assertTrue(body.contains("sessionEpoch++"), "a connection on its way is refused afterwards")
    }

    @Test
    fun `the tunnels are stopped for good through stopAllTunnels and their flags of manual stop are left alone`() {
        val ops = code("core/vault/AndroidVaultWipe.kt")
        val body = bodyOf(ops, "override suspend fun stopTunnels(")

        assertTrue(body.contains("tunnelManager.stopAllTunnels()"))
        assertFalse(body.contains("manuallyStopped"), "the flags are not cleared in this path")
        assertFalse(body.contains("markStarting"), "nothing is started in this path")
    }

    // ── The gate is looked at where work would start ────────────────────────

    @Test
    fun `every place that opens, starts, syncs or writes asks the gate`() {
        val expectations = mapOf(
            "core/ssh/SshSessionManager.kt" to 1,
            "core/ssh/SshTunnelManager.kt" to 3,
            "core/ssh/TransferTracker.kt" to 1,
            "core/sync/AndroidSyncScheduler.kt" to 2,
            "core/sync/AndroidSyncRepository.kt" to 5,
            "core/ssh/KnownHostsVerifier.kt" to 3,
            "ui/sessions/TerminalTabStore.kt" to 1,
            "core/AppVisibility.kt" to 1,
        )
        for ((path, count) in expectations) {
            val uses = Regex("""wipeGate\.isClosed\(\)""").findAll(code(path)).count()
            assertTrue(uses >= count, "$path asks the gate at least $count time(s), it asks $uses")
        }
    }

    @Test
    fun `a connection that succeeds while the app is closed is not registered`() {
        val body = bodyOf(code("core/ssh/SshSessionManager.kt"), "private fun registerSession(")

        assertTrue(squeeze(body).contains("sessionEpoch != registration.epoch || wipeGate.isClosed()"), "epoch and gate, under the lock")
        assertTrue(body.indexOf("synchronized(registryLock)") < body.indexOf("wipeGate.isClosed()"))
    }

    @Test
    fun `the prompts that are up or queued answer no, the check is made again after the publication`() {
        val coordinator = squeeze(bodyOf(code("core/ssh/HostKeyPromptCoordinator.kt"), "private suspend fun showAndAwait("))

        assertTrue(coordinator.contains("_state.value = prompt if (!appVisibility.isVisible()) { absentPrompt = prompt deferred.complete(false) }"))
        assertTrue(code("core/vault/AndroidVaultWipe.kt").contains("promptsEndedByLock.endPrompts()"))
    }

    // ── The activity ────────────────────────────────────────────────────────

    @Test
    fun `the modal is composed after the prompt hosts and before the lock screen, and the restart clears the task`() {
        val activity = code("MainActivity.kt")

        val biometric = activity.indexOf("BiometricPromptHost(coordinator")
        val overlay = activity.indexOf("VaultWipeOverlay(")
        val lock = activity.indexOf("AppLockScreen()")
        assertTrue(biometric in 0 until overlay && overlay < lock, "prompts, then the modal, then the lock screen")
        val restart = squeeze(bodyOf(activity, "private fun restartAfterWipe("))
        assertTrue(restart.contains("Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK"))
        assertTrue(restart.indexOf("acknowledgeDone()") < restart.indexOf("startActivity("), "one caller wins, then it restarts")
        assertTrue(squeeze(activity).contains("repeatOnLifecycle(Lifecycle.State.STARTED) { wipeGate.state.collect"), "only with the activity on screen")
    }

    @Test
    fun `the application resumes a cut wipe before anything starts a tunnel, a sync or a session`() {
        val app = code("App.kt")
        val onCreate = bodyOf(app, "override fun onCreate(")
        val resume = onCreate.indexOf("androidVaultWipe.resumeIfPending()")

        assertTrue(resume > 0)
        for (later in listOf("shortcutUpdater.startObserving()", "tunnelReconciler.startObserving()", "startSyncSchedulerIfEnabled()", "repairOrphanTunnels()")) {
            assertTrue(resume < onCreate.indexOf(later), "$later comes after the resume")
        }
    }

    // ── The service ─────────────────────────────────────────────────────────

    @Test
    fun `the transfer service cancels everything on the action of the wipe, in the tracker first`() {
        val service = code("service/SftpTransferService.kt")
        val branch = squeeze(service.substring(service.indexOf("ACTION_CANCEL_ALL_TRANSFERS ->")))

        assertTrue(branch.indexOf("transferTracker.cancelAll()") in 0 until branch.indexOf("runningTransfers.values.forEach { it.cancel() }"))
        assertTrue(branch.contains("holds.releaseAll()"))
        assertTrue(squeeze(service).contains("job.invokeOnCompletion { transferTracker.copyEnded(request.id) }"), "the wait is on the end of the copy itself")
    }

    @Test
    fun `a transfer cancelled while its session was reserved does not start its copy`() {
        val service = squeeze(code("service/SftpTransferService.kt"))
        val check = service.indexOf("if (transferTracker.isCancelled(request.id))")

        assertTrue(check > 0)
        assertTrue(service.indexOf("reservations.obtain(request.sessionId)") < check, "after the wait for the session")
        assertTrue(check < service.indexOf("transferTracker.copyStarted(request.id)"), "before the copy")
        // And again once the job is registered, where a cancellation of everything can find it.
        val registered = service.indexOf("runningTransfers[request.id] = job")
        val recheck = service.indexOf("if (transferTracker.isCancelled(request.id)) job.cancel()")
        assertTrue(registered in 0 until recheck && recheck < service.indexOf("job.await()"), "checked after the registration, before the wait")
    }

    @Test
    fun `the vault is erased with no WebView check by class name alone`() {
        val eraser = code("ui/browser/WebDataEraser.kt")

        assertTrue(eraser.contains("WebView.getCurrentWebViewPackage()"), "tested first")
        assertTrue(eraser.contains("catch (e: Exception)"), "the missing package exception is an Exception, not a RuntimeException")
    }
}
