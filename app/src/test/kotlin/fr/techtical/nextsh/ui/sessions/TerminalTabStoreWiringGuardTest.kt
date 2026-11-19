// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Smoke alarm on the one call that keeps the host of an open tab up to date: the store observes the
 * hosts only once `App.onCreate` tells it to ([TerminalTabStore.startObservingHosts]), like the other
 * observers of repositories. Forget the call and nothing fails, the tabs just keep the host of the
 * day they were opened. `TerminalTabStoreTest` covers what the observation does.
 *
 * Same compromise and same rule as `ShortcutWiringGuardTest`: a cheap source check. A source that
 * cannot be found FAILS the test (see [ProjectFiles]).
 */
class TerminalTabStoreWiringGuardTest {

    @Test
    fun `the application starts the observation of the hosts of the tabs`() {
        val app = blankComments(ProjectFiles.mainSource("App.kt").readText())

        assertTrue(app.contains("@Inject lateinit var terminalTabStore: TerminalTabStore"), "the store is injected in the application")
        val onCreate = app.substringAfter("override fun onCreate()").substringBefore("private fun startSyncSchedulerIfEnabled")
        assertTrue(onCreate.contains("terminalTabStore.startObservingHosts()"), "and told to observe the hosts at startup")
    }
}
