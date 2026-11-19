// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import fr.techtical.nextsh.shared.domain.model.SshResult
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.PendingAuthResume
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TUNNEL_START_CANCELLED_MESSAGE
import fr.techtical.nextsh.core.ssh.isRunning
import fr.techtical.nextsh.core.ssh.isStartWithdrawn
import fr.techtical.nextsh.domain.model.*
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.domain.usecase.AutoStartTunnelsGate
import fr.techtical.nextsh.domain.usecase.AutoStartTunnelsUseCase
import fr.techtical.nextsh.domain.usecase.LiveTunnelLookup
import fr.techtical.nextsh.domain.usecase.ResumeTunnelsUseCase
import fr.techtical.nextsh.domain.usecase.StartTunnelUseCase
import fr.techtical.nextsh.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.service.TunnelServiceController
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPathError
import fr.techtical.nextsh.ui.browser.BrowserSessionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class TunnelListUiState(
    val tunnels: List<TunnelConfig> = emptyList(),
    val tunnelStates: Map<String, TunnelState> = emptyMap(),
    val hosts: List<Host> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class TunnelFormState(
    val id: String? = null,
    val label: String = "",
    val hostId: String = "",
    val type: TunnelType = TunnelType.LOCAL_FORWARD,
    val localPort: String = "",
    val remoteHost: String = "127.0.0.1",
    val remotePort: String = "",
    val autoStart: Boolean = false,
    val openBrowserOnConnect: Boolean = false,
    val keepAliveAfterBrowserClose: Boolean = true,
    // Text typed in the "start path" field. Empty means the site root (the field shows
    // "/" as a placeholder). May carry a secret in its query string: never log it.
    val browserPath: String = "",
) {
    /**
     * The value persisted for [browserPath]: the normalized text for a LOCAL_FORWARD tunnel,
     * the root for the other types (the field is hidden there, and a leftover value typed
     * before switching type must not be saved).
     */
    val storedBrowserPath: String
        get() = if (type == TunnelType.LOCAL_FORWARD) {
            TunnelBrowserPath.normalize(browserPath)
        } else {
            TunnelBrowserPath.DEFAULT
        }

    /** Why [browserPath] cannot be saved, or null when it can (always null off LOCAL_FORWARD). */
    val browserPathError: TunnelBrowserPathError?
        get() = TunnelBrowserPath.validate(storedBrowserPath)

    /**
     * What is wrong with the local port as typed, for the message under the field.
     *
     * Null while the field is empty: nothing to blame yet, although an empty field still blocks
     * the save ([isValid]). Text that is not a number (pasted) counts as out of range.
     */
    val localPortProblem: TunnelPortProblem?
        get() = if (localPort.isBlank()) {
            null
        } else {
            TunnelPortRules.localPortProblem(type, localPort.toIntOrNull() ?: NOT_A_PORT)
        }

    /** Same as [localPortProblem] for the remote port, which a SOCKS5 proxy does not have. */
    val remotePortProblem: TunnelPortProblem?
        get() = if (remotePort.isBlank()) {
            null
        } else {
            TunnelPortRules.remotePortProblem(type, remotePort.toIntOrNull() ?: NOT_A_PORT)
        }

    /**
     * The local port problem to show under the field now, [hasFocus] telling whether the user is
     * still typing in it.
     *
     * Typing "8080" goes through "8", "80" and "808", which are reserved ports: showing that
     * message and taking it back would make the form jump under the user's fingers. While the
     * field has the focus, only a text that appending characters cannot fix is blamed (see
     * [cannotBecomeValid]). The other problems wait for the field to lose the focus, and show at
     * once on a form opened with the problem already there. This only decides what is displayed:
     * [isValid] does not depend on the focus.
     */
    fun localPortProblemToShow(hasFocus: Boolean): TunnelPortProblem? =
        localPortProblem?.takeIf { !hasFocus || cannotBecomeValid(localPort) }

    /** Same as [localPortProblemToShow] for the remote port. */
    fun remotePortProblemToShow(hasFocus: Boolean): TunnelPortProblem? =
        remotePortProblem?.takeIf { !hasFocus || cannotBecomeValid(remotePort) }

    /**
     * True when the remote host is typed but a restore would refuse it ([BackupValidator.isValidHostName]
     * on the trimmed text: inner space, invisible or control character, over 255 characters), for the
     * message under the field. Never for a SOCKS5 proxy, which has no remote host field.
     */
    val remoteHostInvalid: Boolean
        get() = type != TunnelType.DYNAMIC_SOCKS5 && remoteHost.isNotBlank() &&
            !BackupValidator.isValidHostName(remoteHost.trim())

    /**
     * The value persisted for [remoteHost]: trimmed. For a SOCKS5 proxy, whose field is hidden, a
     * leftover value that a restore would refuse is not saved (an empty host is allowed there).
     */
    val storedRemoteHost: String
        get() = remoteHost.trim().let {
            if (type == TunnelType.DYNAMIC_SOCKS5 && !BackupValidator.isValidHostName(it)) "" else it
        }

    val isValid: Boolean
        get() = label.isNotBlank() && hostId.isNotBlank()
                && localPort.isNotBlank() && localPortProblem == null
                && (type == TunnelType.DYNAMIC_SOCKS5 || (remoteHost.isNotBlank() && !remoteHostInvalid
                        && remotePort.isNotBlank() && remotePortProblem == null))
                && browserPathError == null
}

/** Stands for a port field whose text is not a number: out of range for [TunnelPortRules]. */
private const val NOT_A_PORT = -1

/**
 * True for a port text that no character typed after it can make valid: not a number, below zero
 * or above the last port. "0" and the ports below 1024 are not in that case, they are the start of
 * a longer port.
 */
private fun cannotBecomeValid(text: String): Boolean {
    val port = text.toIntOrNull() ?: return true
    return port < 0 || port > TunnelPortRules.MAX_PORT
}

/**
 * Localized message for a rejected start path, understandable without technical knowledge.
 * Exhaustive on purpose: a new [TunnelBrowserPathError] value must get its own message.
 */
@StringRes
internal fun TunnelBrowserPathError.messageRes(): Int = when (this) {
    TunnelBrowserPathError.TOO_LONG -> R.string.error_tunnel_browser_path_too_long
    TunnelBrowserPathError.MISSING_LEADING_SLASH -> R.string.error_tunnel_browser_path_missing_slash
    TunnelBrowserPathError.INVALID_CHARACTER -> R.string.error_tunnel_browser_path_invalid_character
    TunnelBrowserPathError.INVALID_PERCENT_ENCODING -> R.string.error_tunnel_browser_path_invalid_percent
    TunnelBrowserPathError.MULTIPLE_FRAGMENT_MARKERS -> R.string.error_tunnel_browser_path_multiple_fragments
}

sealed class TunnelEvent {
    data class Error(val message: String) : TunnelEvent()
    data object TunnelSaved : TunnelEvent()

    /** The tunnel being edited was deleted meanwhile: nothing was saved. */
    data object TunnelNoLongerExists : TunnelEvent()

    /** The host chosen for the tunnel was deleted meanwhile: nothing was saved. */
    data object TunnelHostNoLongerExists : TunnelEvent()

    /** A start was refused: the tunnel, or its host, is deleted. Nothing was started. */
    data object TunnelUnavailable : TunnelEvent()
    data class TunnelStarted(val label: String) : TunnelEvent()
    data class TunnelStopped(val label: String) : TunnelEvent()
    data class OpenBrowser(val tunnelId: String) : TunnelEvent()

    /**
     * Le tunnel tourne, mais le système a refusé le service au premier plan :
     * il ne survivra pas au passage en arrière-plan. À signaler, sinon
     * l'utilisateur croit son tunnel protégé alors qu'il ne l'est pas.
     */
    data object ForegroundServiceRefused : TunnelEvent()
}

@HiltViewModel
class TunnelViewModel @Inject constructor(
    private val tunnelRepository: TunnelRepository,
    private val hostRepository: HostRepository,
    private val tunnelManager: SshTunnelManager,
    private val startTunnel: StartTunnelUseCase,
    private val stopTunnel: StopTunnelUseCase,
    private val autoStartTunnelsUseCase: AutoStartTunnelsUseCase,
    private val autoStartGate: AutoStartTunnelsGate,
    private val browserSessionHolder: BrowserSessionHolder,
    private val tunnelService: TunnelServiceController,
    private val liveTunnels: LiveTunnelLookup,
    private val resumeTunnelsUseCase: ResumeTunnelsUseCase,
    private val pendingAuthResume: PendingAuthResume,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TunnelListUiState())
    val uiState: StateFlow<TunnelListUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(TunnelFormState())
    val formState: StateFlow<TunnelFormState> = _formState.asStateFlow()

    private val _events = MutableSharedFlow<TunnelEvent>()
    val events: SharedFlow<TunnelEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            tunnelRepository.observeAll().collect { tunnels ->
                _uiState.update { it.copy(tunnels = tunnels, isLoading = false) }
            }
        }
        viewModelScope.launch {
            hostRepository.observeAll().collect { hosts ->
                _uiState.update { it.copy(hosts = hosts) }
            }
        }
        viewModelScope.launch {
            tunnelManager.tunnelStates.collect { states ->
                _uiState.update { it.copy(tunnelStates = states) }
            }
        }
    }

    /**
     * Signale au service au premier plan qu'un tunnel vient de démarrer.
     *
     * Sans cet appel, les tunnels tournent uniquement dans le process de l'app
     * et meurent dès que le système la met en cache. C'est le chaînon qui
     * manquait : le service était déclaré au manifest mais aucun code ne le
     * démarrait.
     *
     * Appelé depuis une action utilisateur à l'écran, donc le démarrage d'un
     * service au premier plan est autorisé sur Android 12+.
     */
    private suspend fun onTunnelStarted(config: TunnelConfig) {
        if (!tunnelService.ensureRunning(config.label)) {
            _events.emit(TunnelEvent.ForegroundServiceRefused)
        }
        _events.emit(TunnelEvent.TunnelStarted(config.label))
        if (config.openBrowserOnConnect && config.type == TunnelType.LOCAL_FORWARD) {
            _events.emit(TunnelEvent.OpenBrowser(config.id))
        }
    }

    /**
     * Starts the auto-start tunnels, once per app process: the gate is a singleton, this ViewModel
     * is not (see [AutoStartTunnelsGate]).
     */
    fun autoStartTunnels() {
        if (!autoStartGate.tryClaim()) return
        viewModelScope.launch { announce(autoStartTunnelsUseCase()) }
    }

    /**
     * True while tunnels that waited for the user are ready to be restarted: the notification they
     * were reported by was tapped (its one-time token checked) and nobody took them yet. The list
     * screen watches it and calls [resumeAuthTunnels], so that it works whether the user was
     * already on the list or arrives on it from the unlock screen or from any other one.
     */
    val authResumePending: StateFlow<Boolean> = pendingAuthResume.ready
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, pendingAuthResume.ready.value != null)

    /**
     * Restarts the tunnels that stopped reconnecting because their host needed the user while the app
     * was not on screen, now that it is (see [ResumeTunnelsUseCase]). Unlike [autoStartTunnels] it is
     * not once per process: it answers a tap of the user on the notification, every time. The ids are
     * taken from [PendingAuthResume] once, so that two screens never start the same tunnels.
     */
    fun resumeAuthTunnels() {
        val ids = pendingAuthResume.takeReady() ?: return
        viewModelScope.launch { announce(resumeTunnelsUseCase(ids)) }
    }

    /** Tells the screen how a list of tunnels started, as the auto-start and the resume share it. */
    private suspend fun announce(results: List<Pair<String, SshResult<Unit>>>) {
        // The tunnels of a host that was refused all carry the same result (the start is not
        // attempted again on that host, see AutoStartTunnelsUseCase). Each card shows its own
        // error from its state, but the event is one per distinct failure of a host: the user
        // reads it once. Another host, or another message, is another failure.
        val reported = HashSet<Pair<String, SshResult.Error>>()
        results.forEach { (tunnelId, result) ->
            val config = configForAutoStartResult(tunnelId)
            when (result) {
                is SshResult.Success -> if (config != null) onTunnelStarted(config)
                is SshResult.Error -> {
                    // A tunnel that is gone, or that cannot be read, has no host to share the
                    // failure with: its own key.
                    val owner = config?.hostId ?: tunnelId
                    if (reported.add(owner to result)) _events.emit(TunnelEvent.Error(result.message))
                }
            }
        }
    }

    /**
     * The row of [tunnelId] for the events of the auto-start, or null if it is gone. A lookup that
     * fails also gives null (class name logged, nothing else): it only serves to announce a start
     * or to tell which host a failure belongs to, and must not take the app down. A cancellation
     * goes on.
     */
    private suspend fun configForAutoStartResult(tunnelId: String): TunnelConfig? =
        _uiState.value.tunnels.firstOrNull { it.id == tunnelId }
            ?: try {
                tunnelRepository.getById(tunnelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Auto-start: tunnel lookup failed (${e.javaClass.simpleName})")
                null
            }

    /**
     * The coroutine of the start of each tunnel this ViewModel is starting, by tunnel id, so that
     * Stop can end the connection (a security key sheet included) instead of leaving it to run on.
     * Read and written from the main thread only in practice, a concurrent map all the same.
     */
    private val startJobs = ConcurrentHashMap<String, Job>()

    /**
     * Starts [config]. A port that cannot be used (a tunnel that came from the sync or an import) is
     * refused first, before any connection: the card goes to error with the reason, held out of the
     * reconnection (see [SshTunnelManager.refuseInvalidPorts]).
     *
     * A start cancelled on the way (screen left, ViewModel cleared) would leave its card in STARTING
     * for ever: the shared use case sets no error on a cancellation, and nothing is left to move
     * the tunnel on. It goes to error, held out of the automatic reconnection: a start the user
     * abandoned must not come back, with a key sheet, at the next network event. The cancellation is
     * rethrown. When the user stopped the tunnel, which also cancels this start (see
     * [stopAndEndStart]), the manager has already removed the card and this writes nothing.
     */
    private suspend fun startOrMarkCancelled(config: TunnelConfig): SshResult<Unit> {
        tunnelManager.refuseInvalidPorts(config)?.let { return it }
        val job = currentCoroutineContext()[Job]
        if (job != null) startJobs[config.id] = job
        try {
            return startTunnel(config)
        } catch (e: CancellationException) {
            tunnelManager.markStartCancelled(config, TUNNEL_START_CANCELLED_MESSAGE, hold = true)
            throw e
        } finally {
            if (job != null) startJobs.remove(config.id, job)
        }
    }

    /**
     * What a start that ended in an error means for the user. Nothing, when the tunnel was stopped
     * while it connected (or started again, which is another start): it is gone from the list, and
     * an error would be about a start nobody wants any more. Otherwise a refused or cancelled
     * authentication is not retried by the network events, and the error is told. The result of a
     * start that faded out also says nothing (see [isStartWithdrawn]).
     */
    private suspend fun reportFailedStart(config: TunnelConfig, error: SshResult.Error) {
        if (tunnelManager.isStartWithdrawn(config, error)) return
        tunnelManager.holdReconnectIfNonRetryable(config.id, error)
        _events.emit(TunnelEvent.Error(error.message))
    }

    /**
     * Stops [tunnelId], then ends the start of it that this ViewModel is running, if any. In that
     * order: the manager invalidates the start first, so that when the coroutine is cancelled and
     * [startOrMarkCancelled] reaches `markStartCancelled`, the card is already gone and no error
     * is put back. Cancelled first, the start would leave a held ERROR that the stop then has to
     * remove. A stop that failed leaves the start alone.
     *
     * Only the starts this ViewModel runs are ended here ([startJobs]). A start run elsewhere (the
     * auto-start, the resume from the notification, the reconnection) is invalidated by the manager,
     * which makes it fade out without a card or a message, but its connection goes on until it
     * returns. That is assumed: the key sheet covers the screen, so Stop on the card while a prompt is
     * up can only come from outside (the "stop all" action of the notification, a sync that deletes
     * the tunnel). Those connections are not interrupted, only made harmless.
     */
    private suspend fun stopAndEndStart(tunnelId: String): SshResult<Unit> {
        val starting = startJobs[tunnelId]
        val result = stopTunnel(tunnelId)
        if (result is SshResult.Success) starting?.cancel()
        return result
    }

    fun autoStartTunnelById(tunnelId: String) {
        viewModelScope.launch {
            // A shortcut can outlive its tunnel, or the host of it: nothing to start then.
            val config = liveTunnels.find(tunnelId)
            if (config == null) {
                _events.emit(TunnelEvent.TunnelUnavailable)
                return@launch
            }
            // The state of the manager, not the mirror of it in the UI state: the mirror is filled
            // by a coroutine and can trail, and the screen launches this start together with the
            // auto-start, which may have just started this very tunnel.
            val isRunning = tunnelManager.getTunnelState(tunnelId)?.status?.isRunning() == true
            if (!isRunning) {
                when (val result = startOrMarkCancelled(config)) {
                    is SshResult.Success -> onTunnelStarted(config)
                    is SshResult.Error -> reportFailedStart(config, result)
                }
            }
        }
    }

    fun toggleTunnel(config: TunnelConfig) {
        viewModelScope.launch {
            // The manager's state, not the UI mirror, see autoStartTunnelById.
            val isRunning = tunnelManager.getTunnelState(config.id)?.status?.isRunning() == true
            if (isRunning) {
                // Arrêter. Pendant un démarrage (la carte est en DÉMARRAGE) l'arrêt l'emporte : le
                // démarrage s'efface, sans erreur ni message de démarrage.
                when (val result = stopAndEndStart(config.id)) {
                    is SshResult.Success -> _events.emit(TunnelEvent.TunnelStopped(config.label))
                    is SshResult.Error -> _events.emit(TunnelEvent.Error(result.message))
                }
            } else {
                // Démarrer, depuis la ligne stockée : le tunnel ou son hôte a pu être supprimé
                // (sync) depuis l'affichage de la carte, et un tunnel supprimé dont l'hôte
                // existe encore démarrerait en fantôme, sans carte pour l'arrêter.
                val stored = liveTunnels.find(config.id)
                if (stored == null) {
                    _events.emit(TunnelEvent.TunnelUnavailable)
                    return@launch
                }
                when (val result = startOrMarkCancelled(stored)) {
                    is SshResult.Success -> onTunnelStarted(stored)
                    is SshResult.Error -> reportFailedStart(stored, result)
                }
            }
        }
    }

    /**
     * The cross of the error banner of a card: puts the card back to stopped, if the tunnel is still
     * in error (see [SshTunnelManager.dismissError], which decides and acts in one step). Says
     * nothing: the user asked for the error to go, there is no "stopped" to announce and nothing to
     * report. A tunnel that was started again meanwhile is left alone, the same way.
     */
    fun dismissTunnelError(tunnelId: String) {
        tunnelManager.dismissError(tunnelId)
    }

    // ── Form management ──────────────────────────────────────────────────────

    fun loadTunnelForEdit(tunnelId: String) {
        saveStarted = false
        viewModelScope.launch {
            val config = tunnelRepository.getById(tunnelId) ?: return@launch
            _formState.value = TunnelFormState(
                id = config.id,
                label = config.label,
                hostId = config.hostId,
                type = config.type,
                localPort = config.localPort.toString(),
                remoteHost = config.remoteHost,
                remotePort = config.remotePort.toString(),
                autoStart = config.autoStart,
                openBrowserOnConnect = config.openBrowserOnConnect,
                keepAliveAfterBrowserClose = config.keepAliveAfterBrowserClose,
                // The root is shown as an empty field, like a new tunnel: the "/" placeholder
                // then reads as the default instead of a typed value.
                browserPath = config.browserPath.takeUnless { it == TunnelBrowserPath.DEFAULT } ?: "",
            )
        }
    }

    /**
     * Full reset, to defaults. The screen calls it (or [loadTunnelForEdit]) when it opens; a
     * successful [saveTunnel] does not, see there.
     */
    fun resetForm() {
        saveStarted = false
        _formState.value = TunnelFormState()
    }

    fun updateForm(update: TunnelFormState.() -> TunnelFormState) {
        _formState.update { it.update() }
    }

    /**
     * True from the tap on Save until the form is reset or loaded again, unless the save was
     * refused or failed. After a successful save the form stays on screen, and valid, while the
     * screen leaves: a second tap in that window would save a new tunnel again (a fresh id each
     * time) and send the screen back once more.
     */
    @Volatile
    private var saveStarted = false

    fun saveTunnel() {
        val form = _formState.value
        if (!form.isValid || saveStarted) return
        saveStarted = true

        viewModelScope.launch {
            var written = false
            try {
                // Editing: read the stored tunnel again. A tunnel deleted while the form was open (a
                // sync, or the deletion of its host) must not be saved back; the user is told and
                // nothing is written. Same behaviour as the Desktop edit screen.
                val existing = form.id?.let { tunnelRepository.getById(it) }
                if (form.id != null && existing == null) {
                    _events.emit(TunnelEvent.TunnelNoLongerExists)
                    return@launch
                }
                // The host chosen in the form must still be there: a tunnel saved under a deleted
                // host would be a live tunnel pointing at nothing (it would travel in sync bundles
                // and backups), and under a host that is gone for good the foreign key fails.
                if (hostRepository.getById(form.hostId) == null) {
                    _events.emit(TunnelEvent.TunnelHostNoLongerExists)
                    return@launch
                }
                val existingFavorite = existing?.isFavorite ?: false

                val config = TunnelConfig(
                    id = form.id ?: UUID.randomUUID().toString(),
                    label = form.label.trim(),
                    hostId = form.hostId,
                    type = form.type,
                    localPort = form.localPort.toInt(),
                    remoteHost = form.storedRemoteHost,
                    // A SOCKS5 proxy has no remote port and the form does not show one: its text can be
                    // empty, which toInt() would turn into a crash. Same convention as the Desktop form.
                    remotePort = if (form.type == TunnelType.DYNAMIC_SOCKS5) 0 else form.remotePort.toInt(),
                    autoStart = form.autoStart,
                    openBrowserOnConnect = form.openBrowserOnConnect,
                    keepAliveAfterBrowserClose = form.keepAliveAfterBrowserClose,
                    isFavorite = existingFavorite,
                    browserPath = form.storedBrowserPath,
                )

                if (form.id != null) {
                    tunnelRepository.update(config)
                } else {
                    tunnelRepository.save(config)
                }
                written = true

                // The error of an edited tunnel was about its old settings: it goes with the save,
                // the card back to stopped. Only if the tunnel is still in error (the manager
                // decides): a running, starting or reconnecting one is not touched. A new tunnel
                // has no state, nothing to clear.
                if (form.id != null) tunnelManager.dismissError(config.id)

                // The form is left as typed on purpose. The screen is leaving on TunnelSaved, and
                // resetting it here made its switches visibly flip to their defaults (auto start
                // off, keep alive on) during the exit animation. The next opening resets or loads
                // the form (TunnelConfigScreen).
                _events.emit(TunnelEvent.TunnelSaved)
            } finally {
                // Refused (tunnel or host deleted meanwhile) or failed: the user can save again.
                if (!written) saveStarted = false
            }
        }
    }

    val browserActiveTunnelId = browserSessionHolder.activeTunnelId

    fun toggleFavorite(tunnel: TunnelConfig) {
        viewModelScope.launch {
            tunnelRepository.setFavorite(tunnel.id, !tunnel.isFavorite)
        }
    }

    fun deleteTunnel(tunnelId: String) {
        viewModelScope.launch {
            // Stop whatever state the manager keeps for it, ERROR included: an error state is a
            // reconnect candidate, so left behind it would be restarted on the next network
            // event, a ghost with no card, and would keep the foreground service and its
            // notification alive. Same rule as HostViewModel.deleteHost. The result of the stop
            // is ignored: a failed teardown must not make the tunnel undeletable.
            if (tunnelManager.tunnelStates.value.containsKey(tunnelId)) {
                stopAndEndStart(tunnelId)
            }
            tunnelRepository.delete(tunnelId)
        }
    }
}
