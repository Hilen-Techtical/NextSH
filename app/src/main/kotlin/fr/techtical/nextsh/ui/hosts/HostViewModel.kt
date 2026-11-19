// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package fr.techtical.nextsh.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.di.IoDispatcher
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.BiometricPromptResponse
import fr.techtical.nextsh.core.ssh.LIVENESS_MAX_SECONDS
import fr.techtical.nextsh.core.ssh.LIVENESS_MIN_SECONDS
import fr.techtical.nextsh.core.ssh.transportKeepAliveSeconds
import fr.techtical.nextsh.core.ssh.SessionHandoff
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.VaultAuthPurpose
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.ui.sessions.TerminalTabStore
import fr.techtical.nextsh.ui.theme.TerminalThemeId
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.shared.core.backup.BackupLimits
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

data class HostListUiState(
    val hosts: List<Host> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val connectingHostId: String? = null,
    val sshKeys: List<SshKey> = emptyList(),
    /**
     * How many terminal tabs are open, all hosts together and whatever their status: the count of
     * the "Resume" banner. The tabs belong to [TerminalTabStore], which outlives the terminal
     * screen, so they are still there when the user comes back to the list.
     */
    val openTerminalCount: Int = 0,
    /**
     * Among [openTerminalCount], the tabs whose shell is not connected (it ended, or never got
     * there): the banner says so, so that "1 session open" is not read for a tab that is dead.
     */
    val disconnectedTerminalCount: Int = 0,
    /** The hosts that have at least one terminal tab, connected or not. */
    val openTerminalHostIds: Set<String> = emptySet(),
    /** Among [openTerminalHostIds], the hosts that have a tab whose shell is connected. */
    val connectedTerminalHostIds: Set<String> = emptySet(),
) {
    /** What the card of [hostId] shows about the terminal tabs of that host. */
    fun terminalTabOf(hostId: String): HostTerminalTab = when (hostId) {
        in connectedTerminalHostIds -> HostTerminalTab.CONNECTED
        in openTerminalHostIds -> HostTerminalTab.DISCONNECTED
        else -> HostTerminalTab.NONE
    }
}

/**
 * Whether a host has a terminal tab, for the indicator on its card. A host with a connected tab and
 * a disconnected one is [CONNECTED]: the connected tab is the one that matters.
 */
enum class HostTerminalTab { NONE, CONNECTED, DISCONNECTED }

/**
 * Whether [text] is an accepted value of the keepalive field: 0 (no keepalive) or a whole number of
 * seconds from [LIVENESS_MIN_SECONDS] to [LIVENESS_MAX_SECONDS]. A value already stored out of
 * these bounds (synced or imported host) is bounded at use ([transportKeepAliveSeconds]) and only
 * refused here, when the user saves the host.
 */
internal fun isKeepAliveValid(text: String): Boolean {
    val seconds = text.trim().toIntOrNull() ?: return false
    return seconds == 0 || seconds in LIVENESS_MIN_SECONDS..LIVENESS_MAX_SECONDS
}

/**
 * Whether [text] is an accepted host name: the rule a restore applies ([BackupValidator.isValidHostName],
 * whitespace around the value removed, as it is saved), so that a host that can be typed can be restored.
 */
internal fun isHostnameValid(text: String): Boolean = BackupValidator.isValidHostName(text.trim())

/** Why a user name is refused in the host form: the rule a restore applies to a host, so that a typed host stays restorable. */
internal enum class UsernameProblem { TOO_LONG, CONTROL_CHARACTER }

/**
 * The first problem of the user name [text] (whitespace around it is removed on save), or null.
 * A control character (C0, DEL, C1) pasted inside the name never lets a connection work: the restore
 * cleans it, so the editor refuses it with a reason instead of saving a name that will be changed.
 */
internal fun usernameProblem(text: String): UsernameProblem? {
    val trimmed = text.trim()
    if (trimmed.length > BackupLimits.MAX_FREE_TEXT_LENGTH) return UsernameProblem.TOO_LONG
    if (trimmed.any { it.isISOControl() }) return UsernameProblem.CONTROL_CHARACTER
    return null
}

/** The sentence under the user name field for [problem]. No `else`: a new problem will not compile without one. */
@androidx.annotation.StringRes
internal fun usernameProblemRes(problem: UsernameProblem): Int = when (problem) {
    UsernameProblem.TOO_LONG -> fr.techtical.nextsh.R.string.field_problem_too_long_username
    UsernameProblem.CONTROL_CHARACTER -> fr.techtical.nextsh.R.string.field_problem_control
}

/** Whether [text] is an accepted port: a whole number from 1 to 65535 (whitespace around it ignored). */
internal fun isPortValid(text: String): Boolean {
    val port = text.trim().toIntOrNull() ?: return false
    return port in 1..65535
}

data class HostFormState(
    val id: String? = null,
    val credentialId: String? = null,
    /**
     * Vrai quand l'hôte a été chargé avec une authentification par clé : [credentialId]
     * est alors l'id d'une clé SSH et ne doit jamais nommer un mot de passe.
     */
    val credentialIdIsKeyId: Boolean = false,
    val label: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val group: String = "",
    val keepAliveSeconds: String = "30",
    val autoReconnect: Boolean = true,
    val selectedKeyId: String? = null,
    /**
     * Vrai quand l'hôte chargé référence une clé SSH qui n'existe plus : la sélection est vidée
     * et le formulaire invalide tant qu'aucune clé n'est choisie (voir [HostViewModel.selectKey]).
     */
    val selectedKeyMissing: Boolean = false,
    val fido2Mode: Fido2Mode = Fido2Mode.HARDWARE_KEY,
    /**
     * Whether the screen lets the user choose the FIDO2 mode. Only a host already saved in PASSKEY
     * mode gets the choice, to keep it or to leave it: that mode only signs in with an ordinary SSH
     * key (no security key, no passkey is checked), so a new host is a security key host without
     * being asked. Set when the host is loaded, never by the user.
     */
    val showFido2ModeChoice: Boolean = false,
    /**
     * Raw [Host.terminalTheme] value: either a [TerminalThemeId] enum NAME for a
     * preset, or a custom theme UUID. Persisted verbatim: see [resolveThemePalette].
     */
    val terminalTheme: String = TerminalThemeId.TECHTICAL_DARK.name,
) {
    val isValid: Boolean
        get() = label.isNotBlank() && isHostnameValid(hostname) && username.isNotBlank()
                && usernameProblem(username) == null
                && isPortValid(port)
                && isKeepAliveValid(keepAliveSeconds)
                && (authType !in KEY_AUTH_TYPES || selectedKeyId != null)

    /**
     * Vrai tant que l'erreur « clé absente » doit rester affichée : la clé de l'hôte a disparu et
     * aucune autre n'est choisie. Source unique pour les quatre sélecteurs de clé.
     */
    val showMissingKeyError: Boolean
        get() = selectedKeyMissing && selectedKeyId == null

    /**
     * This form in [mode]. The key list of a mode is not that of the other, so a selected key that
     * the new mode does not offer is dropped: left selected out of sight it would still count for
     * [isValid] and be saved. A key the new mode offers too stays selected.
     */
    fun withFido2Mode(mode: Fido2Mode, keys: List<SshKey>): HostFormState {
        if (mode == fido2Mode) return this
        return copy(fido2Mode = mode, selectedKeyId = selectedKeyId.keptAmong(fido2KeysFor(mode, keys)))
    }

    /**
     * This form with [type] as its authentication. A selected key that the list of the new type
     * does not offer is dropped, for the same reason as in [withFido2Mode]: an ordinary key picked
     * for SSH_KEY when going to FIDO2 or BIOMETRIC_KEY, a security key (sk-*) picked for FIDO2 when
     * going to SSH_KEY or CERTIFICATE, a biometric key picked for BIOMETRIC_KEY when going to
     * SSH_KEY or CERTIFICATE. PASSWORD has no list of keys, and the selection stays.
     */
    fun withAuthType(type: AuthType, keys: List<SshKey>): HostFormState {
        if (type == authType) return this
        val offered = when (type) {
            AuthType.FIDO2 -> fido2KeysFor(fido2Mode, keys)
            AuthType.SSH_KEY, AuthType.CERTIFICATE -> privateKeysFor(keys)
            AuthType.BIOMETRIC_KEY -> biometricKeysFor(keys)
            AuthType.PASSWORD -> return copy(authType = type)
        }
        return copy(authType = type, selectedKeyId = selectedKeyId.keptAmong(offered))
    }
}

private fun String?.keptAmong(offered: List<SshKey>): String? = takeIf { id -> offered.any { it.id == id } }

private val SECURITY_KEY_TYPES = setOf(SshKeyType.SK_ED25519, SshKeyType.SK_ECDSA_256)

/** The authentication types whose host keeps the id of a Vault key in [Host.credentialId]. */
private val KEY_AUTH_TYPES = setOf(AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2, AuthType.BIOMETRIC_KEY)

/**
 * The keys a FIDO2 host in [mode] can use: the security keys (sk-*) enrolled in the Vault for
 * HARDWARE_KEY, the ordinary keys for PASSKEY (a security key has no private key in the Vault, so
 * that mode could not sign in with it, and a biometric key is not a Vault key either).
 */
internal fun fido2KeysFor(mode: Fido2Mode, keys: List<SshKey>): List<SshKey> = when (mode) {
    Fido2Mode.HARDWARE_KEY -> keys.filter { it.keyType in SECURITY_KEY_TYPES }
    Fido2Mode.PASSKEY -> privateKeysFor(keys)
}

/**
 * The keys an SSH_KEY or CERTIFICATE host can use: they sign with a private key of the Vault, and
 * neither a security key (sk-*) nor a biometric key has one: the private half of the first never
 * leaves the hardware, that of the second never leaves the Keystore. Offering one would end in a
 * failure at connection time.
 */
internal fun privateKeysFor(keys: List<SshKey>): List<SshKey> =
    keys.filter { !it.isBiometric && it.keyType !in SECURITY_KEY_TYPES }

/**
 * The keys a BIOMETRIC_KEY host can use: the ones generated in the Android Keystore, which sign
 * only after a fingerprint check. Every other key of the Vault is a different kind of host.
 */
internal fun biometricKeysFor(keys: List<SshKey>): List<SshKey> = keys.filter { it.isBiometric }

sealed class HostEvent {
    /**
     * The host list connected [hostId]: [sessionId] is the live SSH session, which the terminal takes
     * over (see `SessionViewModel.attachToSession`) instead of connecting again.
     */
    data class Connected(val sessionId: String, val hostId: String) : HostEvent()

    /**
     * [hostId] already has a terminal tab: the screen opens the terminal on it, which selects the tab
     * (a tab whose shell ended shows "Reconnect" there), instead of connecting a second time.
     */
    data class OpenTerminal(val hostId: String) : HostEvent()
    data class SftpReady(val sessionId: String, val hostLabel: String) : HostEvent()
    data class Error(val message: String) : HostEvent()
    data object HostSaved : HostEvent()
    data object HostDeleted : HostEvent()

    /** The host being edited was deleted meanwhile: nothing was saved. */
    data object HostNoLongerExists : HostEvent()

    /** Deleting the host failed unexpectedly: it is still there. The screen shows a localized message. */
    data object DeleteFailed : HostEvent()
}

/**
 * Étape de l'enregistrement d'un hôte. Source unique de « Enregistrer est désactivé » (tout sauf
 * [IDLE]) et de la demande de ré-authentification du vault, qui est un ÉTAT et non un événement
 * ponctuel : une demande émise pendant que l'écran n'est pas là (recréation de l'activité, écran
 * arrêté) n'est pas perdue, elle attend [AWAITING_REAUTH] que l'écran revienne au premier plan.
 *
 * ```
 * IDLE --saveHost--> SAVING --enregistré ou échec--> IDLE
 *                      |
 *                      +--vault expiré--> AWAITING_REAUTH --invite lancée--> PROMPT_SHOWN
 *                                                                              |
 *                          IDLE <--invite fermée ou en erreur--------------------+
 *                          SAVING (relance unique) <--invite réussie-------------+
 * ```
 */
enum class HostSavePhase {
    /** Rien en cours : Enregistrer est actif. */
    IDLE,

    /** Écriture en cours : première tentative, ou relance qui suit une invite réussie. */
    SAVING,

    /**
     * Le vault a refusé l'écriture (authentification expirée) : rien n'est écrit, le formulaire
     * est intact. L'écran lance l'invite dès qu'il est au premier plan, via
     * [HostViewModel.confirmIdentityAndSave], qui seul fait sortir de cet état.
     */
    AWAITING_REAUTH,

    /** Invite système ouverte : on attend son premier rappel. */
    PROMPT_SHOWN,
}

/**
 * Raison pour laquelle un enregistrement n'a pas abouti, à afficher à l'utilisateur. Le texte est
 * résolu par l'écran : le ViewModel ne connaît pas les ressources, et surtout il ne relaie jamais
 * le message du système (voir [HostViewModel.confirmIdentityAndSave]).
 */
enum class HostSaveError {
    /** L'utilisateur a fermé l'invite (ou le système l'a annulée) : le vault est resté verrouillé. */
    VAULT_LOCKED,

    /**
     * Le vault n'a pas pu être déverrouillé ou écrit : invite en erreur, activité absente, vault
     * encore expiré après l'invite, écriture refusée pour une autre raison.
     */
    VAULT_UNAVAILABLE,

    /** Échec hors vault (base de données, lecture préalable) : l'hôte n'a pas été enregistré. */
    SAVE_FAILED,
}

/** Issue d'une tentative d'enregistrement (voir [HostViewModel.attemptSave]). */
private enum class SaveOutcome {
    /** Hôte écrit, mot de passe compris. */
    SAVED,

    /** Rien à faire : formulaire devenu invalide. Aucun message, comme avant. */
    SKIPPED,

    /**
     * L'hôte modifié a été supprimé entre-temps (une synchro peut le faire) : rien n'est écrit, pas
     * même le mot de passe, et l'écran en est informé par [HostEvent.HostNoLongerExists].
     */
    HOST_GONE,

    /** Le vault a levé [VaultAuthExpiredException] : rien n'a été écrit. */
    VAULT_EXPIRED,

    /** Le vault a refusé l'écriture pour une autre raison : rien n'a été écrit. */
    VAULT_ERROR,
}

@HiltViewModel
class HostViewModel @Inject constructor(
    private val hostRepository: HostRepository,
    private val connectSession: ConnectSessionUseCase,
    private val sessionHandoff: SessionHandoff,
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val customThemeRepository: fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository,
    private val syncScheduler: SyncScheduler,
    private val tunnelManager: SshTunnelManager,
    private val stopTunnel: StopTunnelUseCase,
    private val biometricPrompts: BiometricPromptCoordinator,
    private val appLock: AppLock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val tabStore: TerminalTabStore,
) : ViewModel() {

    /** Thèmes terminal personnalisés, observés pour le sélecteur de thème par hôte. */
    val customThemes: StateFlow<List<fr.techtical.nextsh.domain.model.CustomTerminalTheme>> =
        customThemeRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Crée ou met à jour un thème personnalisé (la palette ANSI est validée au repo). */
    fun saveCustomTheme(theme: fr.techtical.nextsh.domain.model.CustomTerminalTheme) {
        viewModelScope.launch { customThemeRepository.save(theme) }
    }

    /**
     * Supprime un thème personnalisé. Les hôtes qui le référençaient retombent sur
     * le preset par défaut via [resolveThemePalette] : pas de migration nécessaire.
     */
    fun deleteCustomTheme(id: String) {
        viewModelScope.launch { customThemeRepository.delete(id) }
    }

    private val _uiState = MutableStateFlow(HostListUiState())
    val uiState: StateFlow<HostListUiState> = _uiState.asStateFlow()

    /** Live sync status forwarded from [SyncScheduler.syncState] for the banner. */
    val syncState: StateFlow<SyncState> = syncScheduler.syncState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SyncState(SyncStatus.IDLE, null),
    )

    private val _formState = MutableStateFlow(HostFormState())
    val formState: StateFlow<HostFormState> = _formState.asStateFlow()

    private val _events = MutableSharedFlow<HostEvent>()
    val events: SharedFlow<HostEvent> = _events.asSharedFlow()

    /**
     * Étape de l'enregistrement. Enregistrer est désactivé dans tout état autre que
     * [HostSavePhase.IDLE], et [saveHost] ignore alors tout appui. L'écran lance l'invite système
     * quand l'état passe à [HostSavePhase.AWAITING_REAUTH] et qu'il est au premier plan.
     */
    private val _savePhase = MutableStateFlow(HostSavePhase.IDLE)
    val savePhase: StateFlow<HostSavePhase> = _savePhase.asStateFlow()

    /**
     * Vrai tant que l'app est derrière l'écran de verrou. L'écran n'ouvre l'invite de ré-authentification
     * ([confirmIdentityAndSave]) que déverrouillé : sous le verrou, l'état reste [HostSavePhase.AWAITING_REAUTH]
     * et l'invite part au déverrouillage.
     */
    val appLocked: StateFlow<Boolean> = appLock.locked

    /** Dernier échec d'enregistrement à afficher, remis à null par [dismissSaveError]. */
    private val _saveError = MutableStateFlow<HostSaveError?>(null)
    val saveError: StateFlow<HostSaveError?> = _saveError.asStateFlow()

    /**
     * Jeton de l'invite ouverte, posé au lancement et retiré par son PREMIER rappel (voir
     * [takePrompt]). Un rappel en double, tardif, ou d'une invite plus ancienne ne le retrouve pas
     * et est ignoré : pas de seconde écriture, pas d'erreur après un succès. Thread principal
     * uniquement, comme tout l'état de l'enregistrement.
     */
    private var activePrompt: Any? = null

    /**
     * Formulaire tel qu'il était à l'appui sur Enregistrer : la tentative et l'unique relance qui
     * suit l'invite travaillent dessus, jamais sur le formulaire courant. L'écran rejoue son effet
     * d'initialisation (resetForm ou loadHostForEdit) quand l'activité est recréée pendant
     * l'invite : relire le formulaire courant perdrait le nouvel hôte, ou le mot de passe retapé
     * d'un hôte existant. Retombe à null au retour à IDLE (voir [setPhase]) et à [onCleared].
     * Thread principal uniquement.
     */
    private var pendingForm: HostFormState? = null

    /**
     * Source de vérité du filtre actif. Drive un unique collecteur via
     * `flatMapLatest` (cf. init) : éviter de lancer un nouveau
     * `viewModelScope.launch { collect }` par appel `filterByGroup` qui
     * superposait plusieurs collecteurs concurrents (cause des
     * inconsistances "Tous ne montre pas tous les hôtes").
     */
    private val selectedGroup = MutableStateFlow<String?>(null)

    init {
        // Un SEUL collecteur d'hôtes, switche entre observeAll / observeByGroup
        // via flatMapLatest qui annule automatiquement la source précédente.
        viewModelScope.launch {
            selectedGroup.flatMapLatest { group ->
                if (group != null) hostRepository.observeByGroup(group)
                else hostRepository.observeAll()
            }.collect { hosts ->
                _uiState.update { it.copy(hosts = hosts, isLoading = false) }
            }
        }
        viewModelScope.launch {
            hostRepository.observeGroups().collect { groups ->
                _uiState.update { it.copy(groups = groups) }
            }
        }
        viewModelScope.launch {
            sshKeyRepository.observeAll().collect { keys ->
                _uiState.update { it.copy(sshKeys = keys) }
            }
        }
        // The tabs, not the whole state of the store: dragging the split divider republishes it at
        // every frame, and the list has nothing to redraw for that.
        viewModelScope.launch {
            tabStore.state.map { it.tabs }.distinctUntilChanged().collect { tabs ->
                _uiState.update {
                    it.copy(
                        openTerminalCount = tabs.size,
                        disconnectedTerminalCount = tabs.count { tab -> tab.status != SessionStatus.CONNECTED },
                        openTerminalHostIds = tabs.mapTo(HashSet()) { tab -> tab.host.id },
                        connectedTerminalHostIds = tabs
                            .filter { tab -> tab.status == SessionStatus.CONNECTED }
                            .mapTo(HashSet()) { tab -> tab.host.id },
                    )
                }
            }
        }
    }

    fun filterByGroup(group: String?) {
        selectedGroup.value = group
        _uiState.update { it.copy(selectedGroup = group) }
    }

    /**
     * Connects to [host] once, then tells the screen to open the terminal on that very session: the
     * terminal takes it over instead of connecting again (a second connection meant a second
     * authentication, a second touch with a security key, and an orphan first session). Only when
     * that session is gone by then does the terminal connect itself, once (see
     * `SessionViewModel.attachToSession`).
     *
     * The session comes out of the connection holding one reference, which this ViewModel gives to
     * the [SessionHandoff] before it announces the session: the terminal claims it, and a session
     * nobody claims (the list was left, the event was dropped) is released by the hand-over itself,
     * see there.
     *
     * A host that already has a terminal tab, connected or not, is not connected again: the screen is
     * told to open the terminal on that tab ([HostEvent.OpenTerminal]). A second session on the same
     * host is asked for with the "+" of the terminal.
     */
    fun connectToHost(host: Host) {
        if (tabStore.state.value.tabs.any { it.host.id == host.id }) {
            viewModelScope.launch { _events.emit(HostEvent.OpenTerminal(host.id)) }
            return
        }
        _uiState.update { it.copy(connectingHostId = host.id) }
        viewModelScope.launch {
            when (val result = connectSession(host)) {
                is SshResult.Success -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    sessionHandoff.offer(result.data.id)
                    _events.emit(HostEvent.Connected(sessionId = result.data.id, hostId = host.id))
                }
                is SshResult.Error -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    _events.emit(HostEvent.Error(result.message))
                }
            }
        }
    }

    /** Same hand-over as [connectToHost], to the SFTP browser: it claims the session. */
    fun connectForSftp(host: Host) {
        viewModelScope.launch {
            _uiState.update { it.copy(connectingHostId = host.id) }
            when (val result = connectSession(host)) {
                is SshResult.Success -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    // result.data est la SshSession : utiliser son id comme sessionId
                    sessionHandoff.offer(result.data.id)
                    _events.emit(HostEvent.SftpReady(result.data.id, host.label))
                }
                is SshResult.Error -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    _events.emit(HostEvent.Error(result.message))
                }
            }
        }
    }

    // ── Form management ──────────────────────────────────────────────────────

    fun loadHostForEdit(hostId: String) {
        saveDone = false
        viewModelScope.launch {
            val host = hostRepository.getById(hostId) ?: return@launch
            // A host without a mode is a security key host (the Desktop has no such notion), and
            // the mode of a host that is not FIDO2 is of no use: stale, it must not come back
            // as PASSKEY the day the user switches the host to FIDO2.
            val fido2Mode = when (host.authType) {
                AuthType.FIDO2 -> host.fido2Mode ?: Fido2Mode.HARDWARE_KEY
                else -> Fido2Mode.HARDWARE_KEY
            }
            // For SSH_KEY / CERTIFICATE / FIDO2 / BIOMETRIC_KEY auth, credentialId holds the SSH key ID
            var selectedKeyId = host.credentialId.takeIf { host.authType in KEY_AUTH_TYPES }
            var keyMissing = false
            if (selectedKeyId != null) {
                // Looked up by id for every key type, not in the list of the UI state, which may
                // not be loaded yet.
                val key = sshKeyRepository.getById(selectedKeyId)
                if (key == null) {
                    // The key no longer exists. It must not stay selected invisibly (the list would
                    // show nothing, and save would write the dangling id back): the selection is
                    // cleared and the form asks for another key (see [HostFormState.selectedKeyMissing]).
                    selectedKeyId = null
                    keyMissing = true
                } else if (host.authType != AuthType.FIDO2) {
                    // A host saved with a key that the list of its type does not offer: a security
                    // key (sk-*) or a biometric key on an SSH_KEY or CERTIFICATE host, an ordinary
                    // key on a BIOMETRIC_KEY host. The list does not show that key, so it would stay
                    // selected out of sight (the list looks empty) and still count for isValid, and
                    // the connection would fail. Dropped, the form is invalid until a key is chosen.
                    // Silently: the key exists, so no "key missing" alert.
                    val offered = when (host.authType) {
                        AuthType.BIOMETRIC_KEY -> biometricKeysFor(listOf(key))
                        AuthType.SSH_KEY, AuthType.CERTIFICATE -> privateKeysFor(listOf(key))
                        // Not reached (no key id here, or FIDO2 excluded above); named so that a new
                        // type has to be decided here. Nothing is dropped.
                        AuthType.PASSWORD, AuthType.FIDO2 -> listOf(key)
                    }
                    if (offered.isEmpty()) selectedKeyId = null
                }
            }
            _formState.value = HostFormState(
                id           = host.id,
                credentialId = host.credentialId,
                credentialIdIsKeyId = host.authType in KEY_AUTH_TYPES,
                label        = host.label,
                hostname     = host.hostname,
                port         = host.port.toString(),
                username     = host.username,
                authType     = host.authType,
                group        = host.group ?: "",
                // A stored value out of bounds (old editor, backup, sync) is shown bounded, as it is used;
                // nothing is written until the user saves.
                keepAliveSeconds = (transportKeepAliveSeconds(host.keepAliveSeconds) ?: 0).toString(),
                autoReconnect    = host.autoReconnect,
                selectedKeyId = selectedKeyId,
                selectedKeyMissing = keyMissing,
                fido2Mode = fido2Mode,
                // Only a host that is already in PASSKEY mode is offered the choice of the mode.
                showFido2ModeChoice = fido2Mode == Fido2Mode.PASSKEY,
                terminalTheme = host.terminalTheme,
            )
        }
    }

    /**
     * Full reset, to defaults. The screen calls it (or [loadHostForEdit]) when it opens; a
     * successful [saveHost] does not, see there.
     */
    fun resetForm() {
        saveDone = false
        _formState.value = HostFormState()
    }

    fun updateForm(update: HostFormState.() -> HostFormState) {
        _formState.update { it.update() }
    }

    /** Changes the authentication type; the selected key follows, see [HostFormState.withAuthType]. */
    fun selectAuthType(type: AuthType) {
        _formState.update { it.withAuthType(type, _uiState.value.sshKeys) }
    }

    /** Changes the FIDO2 mode; the selected key follows, see [HostFormState.withFido2Mode]. */
    fun selectFido2Mode(mode: Fido2Mode) {
        _formState.update { it.withFido2Mode(mode, _uiState.value.sshKeys) }
    }

    /** Choisit la clé de l'hôte : lève l'alerte « clé absente » posée au chargement. */
    fun selectKey(keyId: String) {
        _formState.update { it.copy(selectedKeyId = keyId, selectedKeyMissing = false) }
    }

    /**
     * True once a save went through, until the form is reset or loaded again. After a successful
     * save the form stays on screen, and valid, while the screen leaves: a second tap in that window
     * would save a new host again (a fresh id each time). A save that was refused or failed leaves
     * it false, so the user can fix and save again. A save in flight is covered by [savePhase].
     */
    @Volatile
    private var saveDone = false

    /**
     * Enregistre l'hôte du formulaire. Un seul enregistrement à la fois : hors de
     * [HostSavePhase.IDLE], un nouvel appui est ignoré (pas de double écriture), et il l'est aussi
     * une fois l'hôte enregistré, tant que le formulaire n'est pas réinitialisé ou rechargé.
     *
     * Si le vault a perdu son authentification (fenêtre de 300 s), rien n'est écrit, le formulaire
     * reste tel quel et l'état passe à [HostSavePhase.AWAITING_REAUTH] : l'écran lance l'invite
     * système via [confirmIdentityAndSave]. Tout autre échec pose un [saveError].
     */
    fun saveHost() {
        val form = _formState.value
        if (!form.isValid || saveDone) return
        if (!_savePhase.compareAndSet(HostSavePhase.IDLE, HostSavePhase.SAVING)) return
        _saveError.value = null
        pendingForm = form

        viewModelScope.launch { runSave(form, afterReauth = false) }
    }

    /**
     * Demande l'invite système (empreinte ou code de l'appareil) pour une demande
     * [HostSavePhase.AWAITING_REAUTH], par le coordinateur d'invites (même tour que les autres
     * invites, voir [BiometricPromptCoordinator.authenticateVault]), puis relance l'enregistrement
     * UNE seule fois si elle réussit. Une réussite rafraîchit la fenêtre de 300 s de la clé
     * maîtresse, comme au déverrouillage du vault. L'écran n'a plus d'activité à fournir : le
     * titre et le sous-titre de l'invite viennent de [VaultAuthPurpose.CONFIRM_SAVE].
     *
     * La demande n'est consommée qu'ici. L'écran appelle cette méthode au premier plan : le dialogue
     * n'est lancé que si l'activité est reprise, et une demande qui n'a pas pu l'être finit au bout de
     * sa minute. Hors de [HostSavePhase.AWAITING_REAUTH], l'appel ne fait rien, ce qui interdit une
     * seconde invite.
     *
     * - invite fermée par l'utilisateur (ou par le système, l'app ayant été quittée) :
     *   [HostSaveError.VAULT_LOCKED], formulaire intact ;
     * - invite en erreur, sans réponse à temps, ou qui n'a pas pu s'ouvrir :
     *   [HostSaveError.VAULT_UNAVAILABLE]. Le texte du système n'est jamais relayé ;
     * - vault encore expiré après l'invite : même message, sans seconde invite.
     *
     * Seule la première réponse de l'invite compte (voir [takePrompt]).
     */
    fun confirmIdentityAndSave() {
        if (_savePhase.value != HostSavePhase.AWAITING_REAUTH) return
        // Sous l'écran de verrou la demande attend, intacte (ni consommée ni perdue) : l'écran la
        // relance quand [appLocked] repasse à faux.
        if (appLock.isLocked()) return

        val prompt = Any()
        activePrompt = prompt
        _savePhase.value = HostSavePhase.PROMPT_SHOWN
        viewModelScope.launch {
            val response = try {
                biometricPrompts.authenticateVault(VaultAuthPurpose.CONFIRM_SAVE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // L'invite n'a pas pu s'ouvrir, aucune réponse ne viendra.
                Timber.w("Vault re-authentication prompt unavailable: ${e.javaClass.simpleName}")
                failPrompt(prompt)
                return@launch
            }
            // Une seule réponse compte (voir takePrompt) : celle d'une invite plus ancienne est ignorée.
            if (!takePrompt(prompt)) return@launch
            when (response) {
                BiometricPromptResponse.Authorized -> retrySaveAfterReauth()
                // L'utilisateur a fermé l'invite, ou le système l'a fermée (app quittée) : le coffre est resté verrouillé.
                BiometricPromptResponse.Cancelled -> failSave(HostSaveError.VAULT_LOCKED)
                // En erreur, sans réponse à temps (null) : le coffre n'a pas pu être déverrouillé.
                is BiometricPromptResponse.Failed, BiometricPromptResponse.NoScreen, null ->
                    failSave(HostSaveError.VAULT_UNAVAILABLE)
            }
        }
    }

    /** L'invite n'a pas pu s'ouvrir : le même échec qu'une invite en erreur, si elle est encore la courante. */
    private fun failPrompt(prompt: Any) {
        if (takePrompt(prompt)) failSave(HostSaveError.VAULT_UNAVAILABLE)
    }

    /** Ferme le message d'échec affiché par l'écran. */
    fun dismissSaveError() {
        _saveError.value = null
    }

    /**
     * Retire le jeton de l'invite [prompt] : vrai pour son premier rappel, faux pour tout rappel
     * suivant ou venu d'une invite plus ancienne que l'invite courante.
     */
    private fun takePrompt(prompt: Any): Boolean {
        if (activePrompt !== prompt) return false
        activePrompt = null
        return true
    }

    /** Termine un enregistrement sans succès : message posé, bouton réactivé, formulaire intact. */
    private fun failSave(error: HostSaveError) {
        _saveError.value = error
        setPhase(HostSavePhase.IDLE)
    }

    /**
     * Change d'étape. Au retour à IDLE le formulaire mémorisé pour la relance ([pendingForm]) n'a
     * plus de raison d'être : il contient le mot de passe saisi.
     */
    private fun setPhase(phase: HostSavePhase) {
        if (phase == HostSavePhase.IDLE) pendingForm = null
        _savePhase.value = phase
    }

    /** Relance unique après une invite réussie, sur le formulaire mémorisé à l'appui. */
    private fun retrySaveAfterReauth() {
        val form = pendingForm
        if (form == null) {
            failSave(HostSaveError.SAVE_FAILED)
            return
        }
        setPhase(HostSavePhase.SAVING)
        viewModelScope.launch { runSave(form, afterReauth = true) }
    }

    /**
     * Exécute une tentative d'enregistrement de [form] (état [HostSavePhase.SAVING]) et en tire
     * les conséquences. [afterReauth] vaut vrai pour la relance qui suit l'invite : un vault encore
     * expiré y est un échec, jamais une nouvelle demande d'invite (une seule relance par appui), et
     * un formulaire devenu inutilisable ne se perd jamais en silence.
     *
     * Aucune exception ne sort : une panne hors vault (lecture, base de données) pose
     * [HostSaveError.SAVE_FAILED], formulaire intact. L'état repasse toujours à
     * [HostSavePhase.IDLE], sauf pour attendre l'invite.
     */
    private suspend fun runSave(form: HostFormState, afterReauth: Boolean) {
        var next = HostSavePhase.IDLE
        try {
            when (attemptSave(form)) {
                SaveOutcome.SAVED -> {
                    saveDone = true
                    // The password is in the vault now: it does not stay in memory with the form. The
                    // other fields are left as typed on purpose. The screen is leaving on HostSaved,
                    // and resetting them here made its options visibly flip to their defaults during
                    // the exit animation. The next opening resets or loads the form (HostDetailScreen).
                    _formState.update { it.copy(password = "") }
                    _events.emit(HostEvent.HostSaved)
                }
                SaveOutcome.HOST_GONE -> {
                    // The typed password is of no use any more: do not keep it in the form state.
                    _formState.update { it.copy(password = "") }
                    _events.emit(HostEvent.HostNoLongerExists)
                }
                SaveOutcome.SKIPPED -> if (afterReauth) _saveError.value = HostSaveError.SAVE_FAILED
                SaveOutcome.VAULT_ERROR -> _saveError.value = HostSaveError.VAULT_UNAVAILABLE
                SaveOutcome.VAULT_EXPIRED ->
                    if (afterReauth) _saveError.value = HostSaveError.VAULT_UNAVAILABLE
                    else next = HostSavePhase.AWAITING_REAUTH
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nom de la classe seulement : le message peut citer une valeur du formulaire.
            Timber.w("Host save failed: ${e.javaClass.simpleName}")
            _saveError.value = HostSaveError.SAVE_FAILED
        } finally {
            setPhase(next)
        }
    }

    /**
     * Une tentative d'enregistrement de [form]. Le mot de passe est écrit dans le vault AVANT la
     * ligne de l'hôte : si le vault refuse, rien n'est écrit et le formulaire reste intact.
     */
    private suspend fun attemptSave(form: HostFormState): SaveOutcome {
        if (!form.isValid) return SaveOutcome.SKIPPED

        // Editing: read the stored host again. A host deleted while the form was open (a sync
        // can do it) must not be saved back; the user is told and nothing is written, not
        // even the password below. Same behaviour as the Desktop edit screen.
        val existing = form.id?.let { hostRepository.getById(it) }
        if (form.id != null && existing == null) return SaveOutcome.HOST_GONE
        val existingFavorite = existing?.isFavorite ?: false

        // For the key types (SSH_KEY, CERTIFICATE, FIDO2, BIOMETRIC_KEY), credentialId is
        // the SSH key ID from the vault (isValid guarantees a selection, the elvis is only a
        // guard: it never falls back on a stored id, which may name a key that is gone).
        // For PASSWORD auth, preserve the existing credentialId unless it is a key ID; the
        // password of a host being repaired is carried over inside passwordCredentialId,
        // before the host row is written.
        val credentialId = when (form.authType) {
            in KEY_AUTH_TYPES -> form.selectedKeyId ?: return SaveOutcome.SKIPPED
            AuthType.PASSWORD -> passwordCredentialId(form)
            // Not reached (KEY_AUTH_TYPES and PASSWORD cover every type); a type added later
            // keeps the id it has.
            else -> form.credentialId ?: UUID.randomUUID().toString()
        }
        val host = Host(
            id = form.id ?: UUID.randomUUID().toString(),
            label = form.label.trim(),
            hostname = form.hostname.trim(),
            port = form.port.trim().toIntOrNull() ?: 22,
            username = form.username.trim(),
            authType = form.authType,
            credentialId = credentialId,
            group = form.group.trim().ifEmpty { null },
            keepAliveSeconds = form.keepAliveSeconds.trim().toIntOrNull() ?: 30,
            autoReconnect = form.autoReconnect,
            terminalTheme = form.terminalTheme,
            fido2Mode = if (form.authType == AuthType.FIDO2) form.fido2Mode else null,
            isFavorite = existingFavorite,
        )

        // Stocker le mot de passe si auth password
        var passwordWritten = false
        if (form.authType == AuthType.PASSWORD && form.password.isNotEmpty()) {
            val password = form.password.toCharArray()
            try {
                // Le Keystore et EncryptedSharedPreferences bloquent : jamais sur le thread principal.
                withContext(ioDispatcher) { vaultManager.storePassword(credentialId, password) }
                passwordWritten = true
            } catch (e: VaultAuthExpiredException) {
                return SaveOutcome.VAULT_EXPIRED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nom de la classe seulement : ni mot de passe ni identifiant dans les logs.
                Timber.w("Host save aborted, vault write failed: ${e.javaClass.simpleName}")
                return SaveOutcome.VAULT_ERROR
            } finally {
                // Le vrai storePassword efface son argument, sauf quand il échoue avant.
                password.fill('\u0000')
            }
        }

        try {
            if (form.id != null) {
                hostRepository.update(host)
            } else {
                hostRepository.save(host)
            }
        } catch (e: Exception) {
            // Annulation comprise : le slot ne doit pas rester sans hôte. Jamais pour un hôte existant.
            if (form.id == null && passwordWritten) discardOrphanPassword(credentialId)
            throw e
        }
        return SaveOutcome.SAVED
    }

    /**
     * Supprime le mot de passe écrit pour un NOUVEL hôte dont la ligne n'a pas pu l'être : sans cela
     * le slot `pwd_<id>` resterait dans le vault, sans hôte, et continuerait de se synchroniser.
     * L'id vient d'être tiré au hasard pour cet hôte (voir [passwordCredentialId]) : aucun autre
     * hôte ni aucune clé SSH ne le référence, la suppression ne peut rien emporter d'autre.
     *
     * Au mieux : un échec est journalisé (nom de la classe seul) et avalé, il ne doit pas masquer
     * l'erreur d'origine. La suppression tourne même si l'enregistrement est annulé (écran quitté).
     */
    private suspend fun discardOrphanPassword(credentialId: String) {
        try {
            withContext(NonCancellable + ioDispatcher) { vaultManager.deletePassword(credentialId) }
        } catch (e: Exception) {
            Timber.w("Orphan password cleanup skipped: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Id sous lequel un hôte PASSWORD range son mot de passe (slot `pwd_<id>`).
     *
     * Les slots `pwd_`, `key_`, `cert_` et `keypass_` partagent le même id brut : un mot de passe
     * ne doit donc jamais porter l'id d'une clé SSH, sinon la suppression de l'un emporte l'autre
     * (un pair Desktop 1.0.0 applique un tombstone à tous les types sous l'id). Un hôte passé
     * d'une clé au mot de passe gardait pourtant l'id de la clé : on en alloue un nouveau, ce
     * qui répare aussi, à l'enregistrement suivant, les hôtes déjà dans cet état.
     *
     * Pour un hôte déjà PASSWORD resté sur un id de clé dont le mot de passe n'est pas retapé, le
     * mot de passe existant est recopié sous le nouvel id AVANT que l'hôte soit écrit (voir
     * [carryOverPassword]). Si la recopie n'aboutit pas, l'hôte garde son id actuel : rien n'est
     * perdu, il sera réparé à un enregistrement ultérieur. Un hôte chargé avec une clé n'a jamais
     * eu de mot de passe sous cet id : `pwd_<idCle>` serait celui d'un autre hôte, jamais recopié.
     */
    private suspend fun passwordCredentialId(form: HostFormState): String {
        val current = form.credentialId
        if (current != null && !form.credentialIdIsKeyId && sshKeyRepository.getById(current) == null) {
            return current
        }
        val newId = UUID.randomUUID().toString()
        // Rien à recopier : nouvel hôte, hôte chargé avec une clé, ou mot de passe retapé.
        if (current == null || form.credentialIdIsKeyId || form.password.isNotEmpty()) return newId
        return if (carryOverPassword(fromId = current, toId = newId, hostId = form.id)) newId else current
    }

    /**
     * Recopie sous [toId] le mot de passe stocké sous [fromId], pour un hôte déjà PASSWORD dont
     * l'id change sans que l'utilisateur ait retapé son mot de passe (voir [passwordCredentialId]).
     * Renvoie vrai si l'hôte peut passer sur [toId] (recopie faite, ou rien à recopier), faux s'il
     * doit garder [fromId].
     *
     * L'ancien slot est conservé volontairement : [fromId] est l'id d'une clé SSH, et supprimer
     * `pwd_<fromId>` diffuserait un tombstone qu'un pair Desktop 1.0.0 appliquerait à la clé aussi.
     *
     * Pas de recopie quand un autre hôte actif référence encore [fromId] : ce slot partagé contient
     * le dernier mot de passe saisi pour l'un ou l'autre, pas forcément celui de cet hôte. Il garde
     * [fromId] jusqu'à ce que son mot de passe soit retapé (il atterrit alors dans un slot isolé) ou
     * que l'autre hôte soit réparé le premier. Un échec du vault (lecture ou écriture) laisse aussi
     * l'hôte sur [fromId].
     */
    private suspend fun carryOverPassword(fromId: String, toId: String, hostId: String?): Boolean {
        val sharedWithAnotherHost = hostRepository.observeAll().first()
            .any { it.id != hostId && it.credentialId == fromId }
        if (sharedWithAnotherHost) return false
        val existing = try {
            withContext(ioDispatcher) { vaultManager.getPassword(fromId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Password carry-over skipped: ${e.javaClass.simpleName}")
            return false
        } ?: return true
        try {
            withContext(ioDispatcher) { vaultManager.storePassword(toId, existing) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Password carry-over skipped: ${e.javaClass.simpleName}")
            return false
        } finally {
            existing.fill('\u0000')
        }
        return true
    }

    fun toggleFavorite(host: Host) {
        viewModelScope.launch {
            hostRepository.setFavorite(host.id, !host.isFavorite)
        }
    }

    fun deleteHost(hostId: String) {
        viewModelScope.launch {
            try {
                // Read before anything is stopped or deleted: the password of a PASSWORD host is
                // cleaned up below from this row, which no longer exists once the repository has
                // deleted it.
                val host = hostRepository.getById(hostId)
                // The repository tombstones the tunnels of the host. A tunnel that runs is held
                // in memory by SshTunnelManager, independently of the database, and the list
                // only shows live rows: left running it could no longer be stopped from the UI.
                // Same stop as TunnelViewModel.deleteTunnel, for every state the manager keeps
                // (ACTIVE, STARTING, RECONNECTING, and ERROR, which is a reconnect candidate).
                // The result of a stop is ignored like there: a failed teardown must not make
                // the host undeletable.
                tunnelManager.tunnelStates.value.values
                    .filter { it.config.hostId == hostId }
                    .forEach { stopTunnel(it.config.id) }
                hostRepository.delete(hostId)
                // Guarded on its own: the host is deleted at this point, a failure of the cleanup
                // (vault, database) must not be reported as a failed deletion.
                if (host != null) deleteOrphanedPassword(host)
                _events.emit(HostEvent.HostDeleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class name only: nothing from the host goes to the log.
                Timber.w("deleteHost failed: ${e::class.java.simpleName}")
                _events.emit(HostEvent.DeleteFailed)
            }
        }
    }

    /**
     * Supprime le mot de passe d'un hôte PASSWORD qui vient d'être supprimé : sans cela
     * `pwd_<credentialId>` reste dans le vault et continue de se synchroniser.
     *
     * Le slot est conservé quand son id est celui d'une clé SSH (hôte laissé dans cet état par
     * la 1.0.0) : le tombstone `pwd:<id>` serait appliqué à la clé par un pair Desktop 1.0.0.
     * Il l'est aussi tant qu'un autre hôte actif référence le même id. Aucun échec (vault, base)
     * n'empêche la suppression de l'hôte, déjà faite à ce stade : il est journalisé par le nom de
     * sa classe, sans secret.
     */
    private suspend fun deleteOrphanedPassword(host: Host) {
        try {
            if (host.authType != AuthType.PASSWORD) return
            val credentialId = host.credentialId
            if (sshKeyRepository.getById(credentialId) != null) return
            val sharedWithAnotherHost = hostRepository.observeAll().first()
                .any { it.id != host.id && it.credentialId == credentialId }
            if (sharedWithAnotherHost) return
            vaultManager.deletePassword(credentialId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Password cleanup skipped after host deletion: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Le ViewModel peut survivre un instant à son écran, retenu par le rappel d'une invite encore
     * ouverte : il ne doit alors garder ni le mot de passe saisi ni le formulaire mémorisé pour la
     * relance, et un rappel tardif ne retrouve plus son jeton (il est ignoré).
     */
    override fun onCleared() {
        _formState.value = HostFormState()
        pendingForm = null
        activePrompt = null
        super.onCleared()
    }
}
