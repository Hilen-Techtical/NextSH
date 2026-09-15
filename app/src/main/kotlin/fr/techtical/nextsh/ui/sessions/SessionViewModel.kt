// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.core.auth.HardwareKeyAuthenticator
import fr.techtical.nextsh.core.auth.HwKeyResult
import fr.techtical.nextsh.core.ssh.Fido2ChallengeRequest
import fr.techtical.nextsh.core.ssh.Fido2ChallengeResponse
import fr.techtical.nextsh.core.ssh.HostKeyVerifyResult
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.SshTerminalSession
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

// ── Modèles d'état ─────────────────────────────────────────────────────────────

data class SessionTab(
    val sessionId: String,
    val host: Host,
    val terminalSession: SshTerminalSession,
    val status: SessionStatus = SessionStatus.CONNECTING,
)

data class SessionUiState(
    val tabs: List<SessionTab> = emptyList(),
    val activeTabIndex: Int = 0,
    val isConnecting: Boolean = false,
    val error: String? = null,
    val splitState: SplitState? = null,
)

/**
 * État du dialogue de vérification de clé hôte.
 *
 * @param hostname          Hôte concerné (ex: "example.com:22")
 * @param algorithm         Algorithme de la clé (ex: "ssh-ed25519")
 * @param fingerprint       Empreinte SHA-256 reçue (ex: "SHA256:…")
 * @param isMismatch        true = avertissement MITM, false = première connexion
 * @param storedFingerprint Empreinte stockée (uniquement si [isMismatch] == true)
 */
data class HostKeyDialogState(
    val hostname: String,
    val algorithm: String,
    val fingerprint: String,
    val isMismatch: Boolean,
    val storedFingerprint: String? = null,
)

/**
 * État du BottomSheet de challenge FIDO2.
 * Affiché lorsqu'une clé de sécurité doit être touchée pendant la connexion.
 */
data class Fido2ChallengeUiState(
    val rpId: String,
    val signingData: ByteArray,
    val keyType: fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType = fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType.SK_ECDSA_256,
    val allowedCredentialIds: List<ByteArray> = emptyList(),
)

// ── ViewModel ──────────────────────────────────────────────────────────────────

@HiltViewModel
class SessionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sshSessionManager: SshSessionManager,
    private val hostRepository: HostRepository,
    private val snippetRepository: SnippetRepository,
    private val customThemeRepository: fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository,
    private val connectSession: ConnectSessionUseCase,
    private val settingsDataStore: SettingsDataStore,
    private val hardwareKeyAuthenticator: HardwareKeyAuthenticator,
) : ViewModel() {

    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private var clipboardClearJob: Job? = null

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    val terminalFontSize: StateFlow<Int> = settingsDataStore.terminalFontSizeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 24)

    private val clipboardClearTimeout: StateFlow<Int> =
        settingsDataStore.settingsFlow
            .map { it.clipboardClearTimeout }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 60)

    /** Liste des hôtes exposée pour le host picker in-terminal. */
    val hosts: StateFlow<List<Host>> = hostRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Thèmes terminal personnalisés, utilisés pour résoudre les palettes par hôte. */
    val customThemes: StateFlow<List<fr.techtical.nextsh.domain.model.CustomTerminalTheme>> =
        customThemeRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Note : `hostKeyDialog` (state + accept/reject) déplacés vers
    // `HostKeyPromptCoordinator` (Singleton) : le dialogue est rendu au
    // niveau MainActivity pour fonctionner depuis n'importe quel écran.

    /** État du BottomSheet de challenge FIDO2. */
    private val _fido2ChallengeState = MutableStateFlow<Fido2ChallengeUiState?>(null)
    val fido2ChallengeState: StateFlow<Fido2ChallengeUiState?> = _fido2ChallengeState.asStateFlow()

    @Volatile
    private var fido2Response: CompletableDeferred<Fido2ChallengeResponse?>? = null

    /**
     * Event émis quand la fenêtre d'auth vault a expiré pendant une connexion.
     * L'UI doit observer ce flow, afficher un BiometricPrompt, puis appeler
     * [connectToHost] à nouveau avec le même hostId.
     */
    private val _authRequiredEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val authRequiredEvent: SharedFlow<String> = _authRequiredEvent.asSharedFlow()

    /** Expose HardwareKeyAuthenticator pour le BottomSheet FIDO2. */
    val hardwareKeyAuth: HardwareKeyAuthenticator get() = hardwareKeyAuthenticator

    /** Scope dédié au nettoyage : survit à l'annulation de viewModelScope */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Note Phase 3.2 : le callback `hostKeyVerificationCallback` est
        // désormais wiré au niveau App (`App.onCreate`) via le singleton
        // `HostKeyPromptCoordinator`, pour qu'il soit toujours disponible
        // même quand on tape Connect depuis HostListScreen sans avoir
        // navigué dans Sessions au préalable. On ne l'installe plus ici.

        // Installer le callback FIDO2 pour les connexions sk-*.
        sshSessionManager.fido2ChallengeCallback = { request ->
            val deferred = CompletableDeferred<Fido2ChallengeResponse?>()
            fido2Response = deferred

            _fido2ChallengeState.value = Fido2ChallengeUiState(
                rpId = request.rpId,
                signingData = request.signingData,
                keyType = request.keyType,
                allowedCredentialIds = request.allowedCredentialIds,
            )

            // Attendre la réponse avec timeout de 60 secondes.
            // Le BottomSheet UI déclenchera hardwareKeyAuthenticator et résoudra le deferred.
            withTimeoutOrNull(60_000L) { deferred.await() }
                ?: run {
                    _fido2ChallengeState.value?.signingData?.fill(0)
                    _fido2ChallengeState.value = null
                    null
                }
        }
    }

    // ── API publique ──────────────────────────────────────────────────────────────

    /**
     * Connecte à un hôte et crée un nouvel onglet terminal.
     * Enchaîne : connexion SSH → ouverture shell → création SshTerminalSession.
     *
     * Pour les hôtes BIOMETRIC_KEY, l'appelant doit d'abord déclencher
     * BiometricPrompt avec CryptoObject et passer le Signature authentifié.
     */
    fun connectToHost(hostId: String, authenticatedSignature: java.security.Signature? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, error = null) }

            val host = hostRepository.getById(hostId)
            if (host == null) {
                _uiState.update {
                    it.copy(isConnecting = false, error = "Hôte introuvable : $hostId")
                }
                return@launch
            }

            // 1. Connexion SSH
            val sshResult = connectSession(host, authenticatedSignature)
            if (sshResult is SshResult.Error) {
                if (sshResult.code == SshErrorCode.AUTH_EXPIRED) {
                    _uiState.update { it.copy(isConnecting = false) }
                    _authRequiredEvent.tryEmit(hostId)
                    return@launch
                }
                _uiState.update {
                    it.copy(isConnecting = false, error = sshResult.message)
                }
                return@launch
            }
            val sshSession = (sshResult as SshResult.Success).data

            // 2. Ouverture du shell
            val shellResult = sshSessionManager.openShell(sshSession.id)
            if (shellResult is SshResult.Error) {
                _uiState.update {
                    it.copy(isConnecting = false, error = shellResult.message)
                }
                return@launch
            }
            val shell = (shellResult as SshResult.Success).data

            // 3. Création de la SshTerminalSession (bridge terminal)
            val terminalSession = SshTerminalSession(
                sshShell       = shell,
                sessionLabel   = host.label,
                client         = buildSessionClient(sshSession.id),
                transcriptRows = 2000,
            )

            val tab = SessionTab(
                sessionId       = sshSession.id,
                host            = host,
                terminalSession = terminalSession,
                status          = SessionStatus.CONNECTED,
            )

            _uiState.update { state ->
                val newTabs = state.tabs + tab
                state.copy(
                    tabs           = newTabs,
                    activeTabIndex = newTabs.lastIndex,
                    isConnecting   = false,
                )
            }
        }
    }

    /** Sélectionne l'onglet actif. */
    fun selectTab(index: Int) {
        _uiState.update { state ->
            if (index in state.tabs.indices) state.copy(activeTabIndex = index)
            else state
        }
    }

    /** Ferme un onglet et déconnecte la session associée. */
    fun closeTab(index: Int) {
        // Capture tab reference for async cleanup before state mutation
        val tab = _uiState.value.tabs.getOrNull(index) ?: return

        cleanupScope.launch {
            try {
                tab.terminalSession.disconnect()
                sshSessionManager.disconnect(tab.sessionId)
            } catch (e: Exception) {
                Timber.w("Erreur fermeture onglet ${tab.host.label}: ${e.message}")
            }
        }

        // All state computation inside update {} to avoid TOCTOU race
        _uiState.update { state ->
            if (state.tabs.getOrNull(index) == null) return@update state

            val newTabs = state.tabs.toMutableList().also { it.removeAt(index) }
            val newActiveIndex = when {
                newTabs.isEmpty() -> 0
                state.activeTabIndex >= newTabs.size -> newTabs.lastIndex
                else -> state.activeTabIndex
            }

            // Exit split if the closed tab was in a pane, and adjust tab indices
            val newSplitState = state.splitState?.let { split ->
                val leftContent = split.leftOrTopPane
                val rightContent = split.rightOrBottomPane
                val closedInLeft = leftContent is PaneContent.Terminal && leftContent.tabIndex == index
                val closedInRight = rightContent is PaneContent.Terminal && rightContent.tabIndex == index
                if (closedInLeft || closedInRight) {
                    null // Exit split mode
                } else {
                    // Adjust tab indices for remaining terminal panes
                    val adjustIndex = { content: PaneContent ->
                        when (content) {
                            is PaneContent.Terminal -> {
                                val adjusted = if (content.tabIndex > index) content.tabIndex - 1 else content.tabIndex
                                PaneContent.Terminal(adjusted)
                            }
                            is PaneContent.Sftp -> content
                        }
                    }
                    split.copy(
                        leftOrTopPane = adjustIndex(split.leftOrTopPane),
                        rightOrBottomPane = adjustIndex(split.rightOrBottomPane),
                    )
                }
            }

            state.copy(tabs = newTabs, activeTabIndex = newActiveIndex, splitState = newSplitState)
        }
    }

    /** Efface le message d'erreur affiché. */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Retourne un StateFlow des snippets globaux + spécifiques à l'hôte.
     * Si hostId est null, retourne uniquement les snippets globaux.
     */
    fun snippetsForHost(hostId: String?): StateFlow<List<Snippet>> {
        val flow = if (hostId != null) {
            snippetRepository.observeForHost(hostId)
        } else {
            snippetRepository.observeAll()
        }
        return flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /**
     * Envoie une commande snippet + saut de ligne à la session focalisée.
     * En mode split, envoie au pane ayant le focus (ignoré si pane SFTP).
     */
    fun executeSnippet(command: String) {
        val session = focusedSession ?: return
        session.write(command + "\n")
    }

    /**
     * Reconnecte un onglet déconnecté ou en erreur.
     * Ferme l'onglet actuel et relance une connexion vers le même hôte.
     */
    fun reconnectTab(index: Int) {
        val tab = _uiState.value.tabs.getOrNull(index) ?: return
        if (tab.status != SessionStatus.DISCONNECTED && tab.status != SessionStatus.ERROR) return
        closeTab(index)
        connectToHost(tab.host.id)
    }

    // acceptHostKey / rejectHostKey : déplacés vers HostKeyPromptCoordinator
    // (rendu global au niveau MainActivity).

    /**
     * Appelé par le BottomSheet FIDO2 lorsque la clé matérielle a signé.
     */
    fun completeFido2Challenge(signature: SkSshSignature) {
        fido2Response?.complete(Fido2ChallengeResponse.Signed(signature))
        fido2Response = null
        _fido2ChallengeState.value?.signingData?.fill(0)
        _fido2ChallengeState.value = null
    }

    /**
     * Appelé par le BottomSheet FIDO2 en cas d'annulation ou d'erreur.
     */
    fun cancelFido2Challenge() {
        fido2Response?.complete(Fido2ChallengeResponse.Cancelled)
        fido2Response = null
        _fido2ChallengeState.value?.signingData?.fill(0)
        _fido2ChallengeState.value = null
    }

    // ── Split-screen ─────────────────────────────────────────────────────────────

    /**
     * Returns the terminal session of the currently focused pane.
     * In single-pane mode, returns the active tab's session.
     * Returns null if the focused pane is SFTP (SFTP does not receive terminal keys).
     */
    val focusedSession: SshTerminalSession?
        get() {
            val state = _uiState.value
            val split = state.splitState ?: return state.tabs.getOrNull(state.activeTabIndex)?.terminalSession
            val content = when (split.focusedPane) {
                PaneSlot.LEFT_OR_TOP -> split.leftOrTopPane
                PaneSlot.RIGHT_OR_BOTTOM -> split.rightOrBottomPane
            }
            return when (content) {
                is PaneContent.Terminal -> state.tabs.getOrNull(content.tabIndex)?.terminalSession
                is PaneContent.Sftp -> null
            }
        }

    /**
     * Enters split-screen mode with two terminal tabs.
     * The current active tab goes to the left/top pane,
     * [secondTabIndex] goes to the right/bottom pane.
     */
    fun enterSplit(secondTabIndex: Int, orientation: SplitOrientation = SplitOrientation.HORIZONTAL) {
        _uiState.update { state ->
            val activeIdx = state.activeTabIndex
            if (activeIdx == secondTabIndex) return@update state
            if (state.tabs.getOrNull(activeIdx) == null || state.tabs.getOrNull(secondTabIndex) == null) return@update state
            state.copy(
                splitState = SplitState(
                    orientation = orientation,
                    leftOrTopPane = PaneContent.Terminal(activeIdx),
                    rightOrBottomPane = PaneContent.Terminal(secondTabIndex),
                )
            )
        }
    }

    /**
     * Enters split-screen mode with the current terminal tab + an SFTP browser pane.
     */
    fun enterSplitWithSftp(sessionId: String, hostLabel: String, orientation: SplitOrientation = SplitOrientation.HORIZONTAL) {
        _uiState.update { state ->
            val activeIdx = state.activeTabIndex
            if (state.tabs.getOrNull(activeIdx) == null) return@update state
            state.copy(
                splitState = SplitState(
                    orientation = orientation,
                    leftOrTopPane = PaneContent.Terminal(activeIdx),
                    rightOrBottomPane = PaneContent.Sftp(sessionId, hostLabel),
                )
            )
        }
    }

    /**
     * Exits split-screen mode. The focused pane's tab becomes the active tab.
     */
    fun exitSplit() {
        _uiState.update { state ->
            val split = state.splitState ?: return@update state
            val focusedContent = when (split.focusedPane) {
                PaneSlot.LEFT_OR_TOP -> split.leftOrTopPane
                PaneSlot.RIGHT_OR_BOTTOM -> split.rightOrBottomPane
            }
            val maxIndex = (state.tabs.size - 1).coerceAtLeast(0)
            val newActiveIndex = when (focusedContent) {
                is PaneContent.Terminal -> focusedContent.tabIndex.coerceIn(0, maxIndex)
                is PaneContent.Sftp -> state.activeTabIndex.coerceIn(0, maxIndex)
            }
            state.copy(splitState = null, activeTabIndex = newActiveIndex)
        }
    }

    /** Toggles split orientation between horizontal and vertical. */
    fun toggleSplitOrientation() {
        _uiState.update { state ->
            val split = state.splitState ?: return@update state
            val newOrientation = when (split.orientation) {
                SplitOrientation.HORIZONTAL -> SplitOrientation.VERTICAL
                SplitOrientation.VERTICAL -> SplitOrientation.HORIZONTAL
            }
            state.copy(splitState = split.copy(orientation = newOrientation))
        }
    }

    /** Updates the split ratio, clamped to 0.2..0.8. */
    fun updateSplitRatio(ratio: Float) {
        _uiState.update { state ->
            val split = state.splitState ?: return@update state
            state.copy(splitState = split.copy(splitRatio = ratio.coerceIn(0.2f, 0.8f)))
        }
    }

    /** Sets which pane has input focus. */
    fun setFocusedPane(slot: PaneSlot) {
        _uiState.update { state ->
            val split = state.splitState ?: return@update state
            if (split.focusedPane == slot) return@update state
            state.copy(splitState = split.copy(focusedPane = slot))
        }
    }

    /** Swaps the content of the two panes. Focus follows the content. */
    fun swapPanes() {
        _uiState.update { state ->
            val split = state.splitState ?: return@update state
            val newFocus = when (split.focusedPane) {
                PaneSlot.LEFT_OR_TOP -> PaneSlot.RIGHT_OR_BOTTOM
                PaneSlot.RIGHT_OR_BOTTOM -> PaneSlot.LEFT_OR_TOP
            }
            state.copy(
                splitState = split.copy(
                    leftOrTopPane = split.rightOrBottomPane,
                    rightOrBottomPane = split.leftOrTopPane,
                    focusedPane = newFocus,
                )
            )
        }
    }

    // ── Cycle de vie ──────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        // Clear split state
        _uiState.update { it.copy(splitState = null) }
        // hostKeyResponse retiré : géré par HostKeyPromptCoordinator (Singleton).
        fido2Response?.complete(Fido2ChallengeResponse.Cancelled)
        fido2Response = null
        _fido2ChallengeState.value?.signingData?.fill(0)
        _fido2ChallengeState.value = null
        clipboardClearJob?.cancel()

        val tabs = _uiState.value.tabs
        cleanupScope.launch {
            tabs.forEach { tab ->
                try {
                    tab.terminalSession.disconnect()
                    sshSessionManager.disconnect(tab.sessionId)
                } catch (e: Exception) {
                    Timber.w("Erreur nettoyage session ${tab.host.label}: ${e.message}")
                }
            }
        }
    }

    // ── Privé ─────────────────────────────────────────────────────────────────────

    private fun scheduleClipboardClear() {
        clipboardClearJob?.cancel()
        val timeoutSeconds = clipboardClearTimeout.value
        if (timeoutSeconds <= 0) return
        clipboardClearJob = viewModelScope.launch {
            delay(timeoutSeconds * 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboardManager.clearPrimaryClip()
            }
        }
    }

    private fun buildSessionClient(sessionId: String): TerminalSessionClient {
        return object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                if (changedSession is SshTerminalSession) {
                    changedSession.onScreenUpdate?.invoke()
                }
            }

            override fun onTitleChanged(changedSession: TerminalSession) {}

            override fun onSessionFinished(finishedSession: TerminalSession) {
                Timber.i("Session SSH terminée : $sessionId")
                _uiState.update { state ->
                    val idx = state.tabs.indexOfFirst { it.sessionId == sessionId }
                    if (idx == -1) return@update state
                    val updatedTabs = state.tabs.toMutableList()
                    updatedTabs[idx] = updatedTabs[idx].copy(status = SessionStatus.DISCONNECTED)
                    state.copy(tabs = updatedTabs)
                }
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
                if (text.isNullOrEmpty()) return
                val clip = ClipData.newPlainText("NextSH", text)
                clipboardManager.setPrimaryClip(clip)
                scheduleClipboardClear()
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val clip = clipboardManager.primaryClip ?: return
                if (clip.itemCount == 0) return
                val text = clip.getItemAt(0).coerceToText(context).toString()
                if (text.isNotEmpty()) {
                    session?.write(text)
                }
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
            override fun getTerminalCursorStyle(): Int? = null

            override fun logError(tag: String, message: String) { Timber.tag(tag).e(message) }
            override fun logWarn(tag: String, message: String) { Timber.tag(tag).w(message) }
            override fun logInfo(tag: String, message: String) { Timber.tag(tag).i(message) }
            override fun logDebug(tag: String, message: String) { Timber.tag(tag).d(message) }
            override fun logVerbose(tag: String, message: String) { Timber.tag(tag).v(message) }
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
                Timber.tag(tag).e(e, message)
            }
            override fun logStackTrace(tag: String, e: Exception) { Timber.tag(tag).e(e) }
        }
    }
}
