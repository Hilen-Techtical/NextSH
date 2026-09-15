// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.ssh.KnownHostEntry
import fr.techtical.nextsh.core.ssh.KnownHostsVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class KnownHostsViewModel @Inject constructor(
    private val knownHostsVerifier: KnownHostsVerifier,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<KnownHostEntry>>(emptyList())
    val entries: StateFlow<List<KnownHostEntry>> = _entries.asStateFlow()

    init {
        loadEntries()
    }

    fun loadEntries() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _entries.value = knownHostsVerifier.getAllEntries()
            } catch (e: Exception) {
                Timber.e(e, "Failed to load known hosts entries")
            }
        }
    }

    fun removeEntry(hostPort: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                knownHostsVerifier.removeEntry(hostPort)
                _entries.value = knownHostsVerifier.getAllEntries()
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove known host entry")
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                knownHostsVerifier.clearAll()
                _entries.value = emptyList()
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear known hosts")
            }
        }
    }
}
