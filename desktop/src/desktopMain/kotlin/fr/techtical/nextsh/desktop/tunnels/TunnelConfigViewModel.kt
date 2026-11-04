// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.randomUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TunnelConfigState(
    val tunnelId: String? = null,
    val label: String = "",
    val hostId: String = "",
    val type: TunnelType = TunnelType.LOCAL_FORWARD,
    val localPort: String = "",
    val remoteHost: String = "localhost",
    val remotePort: String = "",
    val autoStart: Boolean = false,
    val openBrowserOnConnect: Boolean = false,
    val keepAliveAfterBrowserClose: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val isExisting: Boolean get() = tunnelId != null
}

class TunnelConfigViewModel(
    private val repository: TunnelRepository = DesktopContainer.tunnelRepository,
    hostRepository: HostRepository = DesktopContainer.hostRepository,
    private val appScope: AppScope = DesktopContainer.appScope,
) {
    private val _state = MutableStateFlow(TunnelConfigState())
    val state: StateFlow<TunnelConfigState> = _state.asStateFlow()

    val hosts: StateFlow<List<Host>> = hostRepository
        .observeAll()
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, emptyList())

    fun load(tunnelId: String?) {
        if (tunnelId == null) {
            _state.value = TunnelConfigState()
            return
        }
        appScope.coroutineScope.launch {
            val t = repository.getById(tunnelId)
            _state.value = if (t != null) {
                TunnelConfigState(
                    tunnelId = t.id,
                    label = t.label,
                    hostId = t.hostId,
                    type = t.type,
                    localPort = t.localPort.toString(),
                    remoteHost = t.remoteHost,
                    remotePort = t.remotePort.toString(),
                    autoStart = t.autoStart,
                    openBrowserOnConnect = t.openBrowserOnConnect,
                    keepAliveAfterBrowserClose = t.keepAliveAfterBrowserClose,
                )
            } else {
                TunnelConfigState(error = "Tunnel introuvable")
            }
        }
    }

    fun onLabel(v: String) { _state.value = _state.value.copy(label = v, error = null) }
    fun onHost(id: String) { _state.value = _state.value.copy(hostId = id, error = null) }
    fun onType(v: TunnelType) { _state.value = _state.value.copy(type = v, error = null) }
    fun onLocalPort(v: String) { if (v.all { it.isDigit() } && v.length <= 5) _state.value = _state.value.copy(localPort = v, error = null) }
    fun onRemoteHost(v: String) { _state.value = _state.value.copy(remoteHost = v, error = null) }
    fun onRemotePort(v: String) { if (v.all { it.isDigit() } && v.length <= 5) _state.value = _state.value.copy(remotePort = v, error = null) }
    fun onAutoStart(v: Boolean) { _state.value = _state.value.copy(autoStart = v) }
    fun onOpenBrowserOnConnect(v: Boolean) { _state.value = _state.value.copy(openBrowserOnConnect = v) }
    fun onKeepAliveAfterBrowserClose(v: Boolean) { _state.value = _state.value.copy(keepAliveAfterBrowserClose = v) }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        val err = validate(s)
        if (err != null) {
            _state.value = s.copy(error = err)
            return
        }
        _state.value = s.copy(isSaving = true, error = null)
        appScope.coroutineScope.launch {
            // Re-read the stored tunnel so fields the form does not own are current.
            // Null means it was deleted meanwhile: write nothing.
            val storedId = s.tunnelId
            val stored = if (storedId != null) {
                repository.getById(storedId) ?: run {
                    _state.value = _state.value.copy(
                        isSaving = false,
                        error = "Ce tunnel a été supprimé entre-temps, rien n'a été enregistré.",
                    )
                    return@launch
                }
            } else {
                null
            }
            val config = buildTunnelConfig(s, stored, randomUuid())
            if (stored != null) repository.update(config) else repository.save(config)
            _state.value = _state.value.copy(isSaving = false)
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = _state.value.tunnelId ?: return
        appScope.coroutineScope.launch {
            repository.delete(id)
            onDone()
        }
    }

    private fun validate(s: TunnelConfigState): String? {
        if (s.label.isBlank()) return "Le label est requis"
        if (s.hostId.isBlank()) return "Sélectionne un hôte"
        val lp = s.localPort.toIntOrNull()
        if (lp == null || lp !in 1..65535) return "Le port local doit être entre 1 et 65535"
        if (s.type != TunnelType.DYNAMIC_SOCKS5) {
            if (s.remoteHost.isBlank()) return "L'hôte distant est requis"
            val rp = s.remotePort.toIntOrNull()
            if (rp == null || rp !in 1..65535) return "Le port distant doit être entre 1 et 65535"
        }
        return null
    }
}

/**
 * Builds the [TunnelConfig] to persist from the Desktop form. On edit ([stored]
 * non-null) only the form-owned fields are overwritten: rebuilding from scratch
 * would reset the others (today `isFavorite`) and the newest CRDT write would
 * push that reset to Android. A new tunnel gets the model defaults and [newId].
 */
internal fun buildTunnelConfig(
    form: TunnelConfigState,
    stored: TunnelConfig?,
    newId: String,
): TunnelConfig {
    val socks = form.type == TunnelType.DYNAMIC_SOCKS5
    val isLocalForward = form.type == TunnelType.LOCAL_FORWARD
    // New tunnel: blank base, the form-owned fields are overwritten below.
    val base = stored ?: TunnelConfig(
        id = newId,
        label = "",
        hostId = "",
        type = form.type,
        localPort = 0,
        remoteHost = "",
        remotePort = 0,
    )
    return base.copy(
        label = form.label.trim(),
        hostId = form.hostId,
        type = form.type,
        localPort = form.localPort.toInt(),
        remoteHost = if (socks) "" else form.remoteHost.trim(),
        remotePort = if (socks) 0 else form.remotePort.toInt(),
        autoStart = form.autoStart,
        openBrowserOnConnect = isLocalForward && form.openBrowserOnConnect,
        keepAliveAfterBrowserClose = isLocalForward && form.keepAliveAfterBrowserClose,
    )
}
