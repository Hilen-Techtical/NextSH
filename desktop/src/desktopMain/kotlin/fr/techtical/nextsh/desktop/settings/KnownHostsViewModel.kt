// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.settings

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.desktop.core.ssh.KnownHostEntry
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KnownHostsViewModel(
    private val store: DesktopKnownHostsStore = DesktopContainer.knownHostsStore,
    private val appScope: AppScope = DesktopContainer.appScope,
) {
    private val _entries = MutableStateFlow<List<KnownHostEntry>>(emptyList())
    val entries: StateFlow<List<KnownHostEntry>> = _entries.asStateFlow()

    fun refresh() {
        appScope.coroutineScope.launch {
            _entries.value = withContext(Dispatchers.IO) { store.getAllEntries() }
        }
    }

    fun removeEntry(hostPort: String) {
        appScope.coroutineScope.launch {
            withContext(Dispatchers.IO) { store.deleteByHostPort(hostPort) }
            _entries.value = withContext(Dispatchers.IO) { store.getAllEntries() }
        }
    }

    fun clearAll() {
        appScope.coroutineScope.launch {
            withContext(Dispatchers.IO) { store.deleteAll() }
            _entries.value = emptyList()
        }
    }
}
