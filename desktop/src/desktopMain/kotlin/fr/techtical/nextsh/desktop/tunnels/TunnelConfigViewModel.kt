// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.scope.ScreenViewModel
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPathError
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import fr.techtical.nextsh.shared.util.randomUuid
import kotlinx.coroutines.CancellationException
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
    /**
     * Raw text of the "start path" field, as typed (normalized only on save). Empty for a new tunnel,
     * so the field shows its "/" placeholder. LOCAL_FORWARD only.
     *
     * It may carry a secret in its query string: never log this state or the field.
     */
    val browserPath: String = "",
    /**
     * Why [browserPath] was rejected on the last save, or null. A typed error rather than a message
     * so that the screen can localize it ([error] is the form validation, typed the same way).
     */
    val browserPathError: TunnelBrowserPathError? = null,
    val isSaving: Boolean = false,
    val error: TunnelFormError? = null,
    /**
     * Why the last save was refused by the data layer, or null. Typed, like [browserPathError], so that the
     * screen localizes it; cleared when the user edits the form.
     */
    val saveError: TunnelSaveError? = null,
) {
    /**
     * True when the typed remote host could not be restored from a backup (inner space, control or invisible
     * character, over 255 chars), judged after trimming. Empty is not flagged here (the required check handles
     * it) and SOCKS5 has no remote host.
     */
    val remoteHostInvalid: Boolean
        get() = type != TunnelType.DYNAMIC_SOCKS5 && remoteHost.isNotBlank() &&
            !BackupValidator.isValidHostName(remoteHost.trim())

    val isExisting: Boolean get() = tunnelId != null
}

/** Why the form was refused before any write. Typed so that the screen localizes it. */
enum class TunnelFormError {
    TUNNEL_NOT_FOUND,
    TUNNEL_DELETED,
    LABEL_REQUIRED,
    HOST_REQUIRED,
    LOCAL_PORT_RANGE,
    REMOTE_HOST_REQUIRED,
    REMOTE_PORT_RANGE,
}

/** A save that passed the form validation but could not be written. */
enum class TunnelSaveError {
    /** The selected host was deleted (by a sync, or from another screen) while the form was open. */
    HOST_DELETED,

    /** Any other refusal of the write, typically the database. */
    SAVE_FAILED,
}

private const val TAG = "TunnelConfigViewModel"

/**
 * ViewModel du formulaire d'un tunnel. Le chargement du formulaire et l'observation des hôtes vivent dans
 * le scope de l'écran ; l'enregistrement et la suppression restent dans le scope de l'app pour aller au
 * bout même si l'écran est quitté juste après.
 */
class TunnelConfigViewModel(
    private val repository: TunnelRepository = DesktopContainer.tunnelRepository,
    private val hostRepository: HostRepository = DesktopContainer.hostRepository,
    appScope: AppScope = DesktopContainer.appScope,
    private val tunnelService: DesktopTunnelService = DesktopContainer.tunnelService,
) : ScreenViewModel(appScope) {
    private val _state = MutableStateFlow(TunnelConfigState())
    val state: StateFlow<TunnelConfigState> = _state.asStateFlow()

    val hosts: StateFlow<List<Host>> = hostRepository
        .observeAll()
        .stateIn(screenScope, SharingStarted.Eagerly, emptyList())

    fun load(tunnelId: String?) {
        if (tunnelId == null) {
            _state.value = TunnelConfigState()
            return
        }
        screenScope.launch {
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
                    browserPath = t.browserPath,
                ).let { loaded ->
                    // A path that came from a sync peer may be invalid: report it on open, not only at save.
                    loaded.copy(browserPathError = validateBrowserPath(loaded))
                }
            } else {
                TunnelConfigState(error = TunnelFormError.TUNNEL_NOT_FOUND)
            }
        }
    }

    fun onLabel(v: String) { _state.value = _state.value.copy(label = v, error = null, saveError = null) }
    fun onHost(id: String) { _state.value = _state.value.copy(hostId = id, error = null, saveError = null) }
    fun onType(v: TunnelType) { _state.value = _state.value.copy(type = v, error = null, browserPathError = null, saveError = null) }
    fun onLocalPort(v: String) { if (v.all { it.isDigit() } && v.length <= 5) _state.value = _state.value.copy(localPort = v, error = null, saveError = null) }
    fun onRemoteHost(v: String) { _state.value = _state.value.copy(remoteHost = v, error = null, saveError = null) }
    fun onRemotePort(v: String) { if (v.all { it.isDigit() } && v.length <= 5) _state.value = _state.value.copy(remotePort = v, error = null, saveError = null) }
    fun onAutoStart(v: Boolean) { _state.value = _state.value.copy(autoStart = v, saveError = null) }
    fun onOpenBrowserOnConnect(v: Boolean) { _state.value = _state.value.copy(openBrowserOnConnect = v, saveError = null) }
    fun onKeepAliveAfterBrowserClose(v: Boolean) { _state.value = _state.value.copy(keepAliveAfterBrowserClose = v, saveError = null) }

    /** Kept as typed (no trim, no length cap): [save] normalizes and validates, so nothing is silently altered. */
    fun onBrowserPath(v: String) { _state.value = _state.value.copy(browserPath = v, browserPathError = null, saveError = null) }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        val err = validate(s)
        val pathError = validateBrowserPath(s)
        if (err != null || pathError != null) {
            // Both can be reported together: the path error is shown next to its field.
            _state.value = s.copy(error = err, browserPathError = pathError)
            return
        }
        // The reason is already under the remote host field: nothing is written.
        if (s.remoteHostInvalid) return
        _state.value = s.copy(isSaving = true, error = null, browserPathError = null, saveError = null)
        appScope.coroutineScope.launch {
            try {
                // Re-read the stored tunnel so fields the form does not own are current.
                // Null means it was deleted meanwhile: write nothing.
                val storedId = s.tunnelId
                val stored = if (storedId != null) {
                    repository.getById(storedId) ?: run {
                        _state.value = _state.value.copy(
                            isSaving = false,
                            error = TunnelFormError.TUNNEL_DELETED,
                        )
                        return@launch
                    }
                } else {
                    null
                }
                // The host must still be there (live only): a tunnel cannot exist without it, and the
                // database refuses the write. Say so precisely instead of a generic failure.
                if (hostRepository.getById(s.hostId) == null) {
                    _state.value = _state.value.copy(isSaving = false, saveError = TunnelSaveError.HOST_DELETED)
                    return@launch
                }
                val config = buildTunnelConfig(s, stored, randomUuid())
                if (stored != null) repository.update(config) else repository.save(config)
                _state.value = _state.value.copy(isSaving = false)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Without this the exception left isSaving stuck on true and the Save button disabled.
                // Class name only: an exception message can quote SQL and row values.
                Logger.e(TAG, "save: failed (${errorClass(e)})")
                _state.value = _state.value.copy(isSaving = false, saveError = TunnelSaveError.SAVE_FAILED)
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = _state.value.tunnelId ?: return
        appScope.coroutineScope.launch {
            // Deleting only tombstones the row: a running tunnel would keep forwarding with no row in the list.
            try {
                tunnelService.stopTunnelIfRunning(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stopping is best effort: the delete goes on. Class name only in the log.
                Logger.e(TAG, "delete: stopping the tunnel failed (${errorClass(e)})")
            }
            repository.delete(id)
            onDone()
        }
    }

    private fun validate(s: TunnelConfigState): TunnelFormError? {
        if (s.label.isBlank()) return TunnelFormError.LABEL_REQUIRED
        if (s.hostId.isBlank()) return TunnelFormError.HOST_REQUIRED
        val lp = s.localPort.toIntOrNull()
        if (lp == null || lp !in 1..65535) return TunnelFormError.LOCAL_PORT_RANGE
        if (s.type != TunnelType.DYNAMIC_SOCKS5) {
            if (s.remoteHost.isBlank()) return TunnelFormError.REMOTE_HOST_REQUIRED
            val rp = s.remotePort.toIntOrNull()
            if (rp == null || rp !in 1..65535) return TunnelFormError.REMOTE_PORT_RANGE
        }
        return null
    }

    /** The start path only applies to LOCAL_FORWARD (the only type whose browser is offered). */
    private fun validateBrowserPath(s: TunnelConfigState): TunnelBrowserPathError? {
        if (s.type != TunnelType.LOCAL_FORWARD) return null
        return TunnelBrowserPath.validate(TunnelBrowserPath.normalize(s.browserPath))
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
        browserPath = if (isLocalForward) storableBrowserPath(form.browserPath) else TunnelBrowserPath.DEFAULT,
    )
}

/**
 * The canonical form of the field, or the root when it is not valid. [TunnelConfigViewModel.save]
 * has already rejected invalid input, so the fallback is only a guard: an invalid path is never stored.
 */
private fun storableBrowserPath(input: String): String {
    val normalized = TunnelBrowserPath.normalize(input)
    return if (TunnelBrowserPath.validate(normalized) == null) normalized else TunnelBrowserPath.DEFAULT
}
