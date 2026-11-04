// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package fr.techtical.nextsh.ui.hosts

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.crypto.BiometricHelper
import fr.techtical.nextsh.core.di.IoDispatcher
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.ui.theme.TerminalThemeId
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
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
)

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
     * Raw [Host.terminalTheme] value: either a [TerminalThemeId] enum NAME for a
     * preset, or a custom theme UUID. Persisted verbatim: see [resolveThemePalette].
     */
    val terminalTheme: String = TerminalThemeId.TECHTICAL_DARK.name,
) {
    val isValid: Boolean
        get() = label.isNotBlank() && hostname.isNotBlank() && username.isNotBlank()
                && port.toIntOrNull() != null
                && (authType !in KEY_AUTH_TYPES || selectedKeyId != null)

    /**
     * Vrai tant que l'erreur « clé absente » doit rester affichée : la clé de l'hôte a disparu et
     * aucune autre n'est choisie. Source unique pour les trois sélecteurs de clé.
     */
    val showMissingKeyError: Boolean
        get() = selectedKeyMissing && selectedKeyId == null
}

/**
 * Types d'authentification pour lesquels le formulaire laisse choisir une clé SSH et dont
 * [Host.credentialId] est l'id de cette clé. BIOMETRIC_KEY porte aussi un id de clé, mais le
 * formulaire ne permet pas de la choisir : il est donc absent de cette liste.
 */
private val KEY_AUTH_TYPES = listOf(AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2)

sealed class HostEvent {
    data class Connected(val sessionId: String) : HostEvent()
    data class SftpReady(val sessionId: String, val hostLabel: String) : HostEvent()
    data class Error(val message: String) : HostEvent()
    data object HostSaved : HostEvent()
    data object HostDeleted : HostEvent()
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

    /** Le vault a levé [VaultAuthExpiredException] : rien n'a été écrit. */
    VAULT_EXPIRED,

    /** Le vault a refusé l'écriture pour une autre raison : rien n'a été écrit. */
    VAULT_ERROR,
}

/**
 * Codes BiometricPrompt qui signifient « l'utilisateur a renoncé », par opposition à une panne.
 * ERROR_CANCELED couvre aussi une annulation par le système, par exemple l'application passée en
 * arrière-plan : « le coffre est resté verrouillé » y est exact.
 */
private val PROMPT_DISMISSED_CODES = setOf(
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
)

@HiltViewModel
class HostViewModel @Inject constructor(
    private val hostRepository: HostRepository,
    private val connectSession: ConnectSessionUseCase,
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val customThemeRepository: fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository,
    private val syncScheduler: SyncScheduler,
    private val biometricHelper: BiometricHelper,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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
    }

    fun filterByGroup(group: String?) {
        selectedGroup.value = group
        _uiState.update { it.copy(selectedGroup = group) }
    }

    fun connectToHost(host: Host) {
        _uiState.update { it.copy(connectingHostId = host.id) }
        viewModelScope.launch {
            when (val result = connectSession(host)) {
                is SshResult.Success -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    _events.emit(HostEvent.Connected(host.id))
                }
                is SshResult.Error -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    _events.emit(HostEvent.Error(result.message))
                }
            }
        }
    }

    fun connectForSftp(host: Host) {
        viewModelScope.launch {
            _uiState.update { it.copy(connectingHostId = host.id) }
            when (val result = connectSession(host)) {
                is SshResult.Success -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    // result.data est la SshSession : utiliser son id comme sessionId
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
        viewModelScope.launch {
            val host = hostRepository.getById(hostId) ?: return@launch
            // For key-based auth, credentialId holds the SSH key ID. A key that no longer exists
            // must not stay selected invisibly (the list would show nothing, and save would write
            // the dangling id back): the selection is cleared and the form asks for another key.
            val keyAuth = host.authType in KEY_AUTH_TYPES
            val keyMissing = keyAuth && sshKeyRepository.getById(host.credentialId) == null
            _formState.value = HostFormState(
                id           = host.id,
                credentialId = host.credentialId,
                credentialIdIsKeyId = host.authType != AuthType.PASSWORD,
                label        = host.label,
                hostname     = host.hostname,
                port         = host.port.toString(),
                username     = host.username,
                authType     = host.authType,
                group        = host.group ?: "",
                keepAliveSeconds = host.keepAliveSeconds.toString(),
                autoReconnect    = host.autoReconnect,
                selectedKeyId = if (keyAuth && !keyMissing) host.credentialId else null,
                selectedKeyMissing = keyMissing,
                fido2Mode = host.fido2Mode ?: Fido2Mode.HARDWARE_KEY,
                terminalTheme = host.terminalTheme,
            )
        }
    }

    fun resetForm() {
        _formState.value = HostFormState()
    }

    fun updateForm(update: HostFormState.() -> HostFormState) {
        _formState.update { it.update() }
    }

    /** Choisit la clé de l'hôte : lève l'alerte « clé absente » posée au chargement. */
    fun selectKey(keyId: String) {
        _formState.update { it.copy(selectedKeyId = keyId, selectedKeyMissing = false) }
    }

    /**
     * Enregistre l'hôte du formulaire. Un seul enregistrement à la fois : hors de
     * [HostSavePhase.IDLE], un nouvel appui est ignoré (pas de double écriture).
     *
     * Si le vault a perdu son authentification (fenêtre de 300 s), rien n'est écrit, le formulaire
     * reste tel quel et l'état passe à [HostSavePhase.AWAITING_REAUTH] : l'écran lance l'invite
     * système via [confirmIdentityAndSave]. Tout autre échec pose un [saveError].
     */
    fun saveHost() {
        val form = _formState.value
        if (!form.isValid) return
        if (!_savePhase.compareAndSet(HostSavePhase.IDLE, HostSavePhase.SAVING)) return
        _saveError.value = null
        pendingForm = form

        viewModelScope.launch { runSave(form, afterReauth = false) }
    }

    /**
     * Lance l'invite système (empreinte ou code de l'appareil) pour une demande
     * [HostSavePhase.AWAITING_REAUTH], puis relance l'enregistrement UNE seule fois si elle
     * réussit. Une réussite rafraîchit la fenêtre de 300 s de la clé maîtresse, comme au
     * déverrouillage du vault.
     *
     * La demande n'est consommée qu'ici, au moment où l'invite part vraiment : l'écran n'appelle
     * donc cette méthode qu'au premier plan (BiometricPrompt ignore sans rappel un lancement fait
     * pendant que l'activité est arrêtée). Hors de [HostSavePhase.AWAITING_REAUTH], l'appel ne
     * fait rien, ce qui interdit une seconde invite.
     *
     * - invite fermée par l'utilisateur : [HostSaveError.VAULT_LOCKED], formulaire intact ;
     * - invite en erreur, ou [activity] absente : [HostSaveError.VAULT_UNAVAILABLE]. Le texte du
     *   système n'est jamais relayé ;
     * - vault encore expiré après l'invite : même message, sans seconde invite.
     *
     * Seul le premier rappel de l'invite compte (voir [takePrompt]).
     */
    fun confirmIdentityAndSave(activity: FragmentActivity?, title: String, subtitle: String) {
        if (_savePhase.value != HostSavePhase.AWAITING_REAUTH) return
        if (activity == null) {
            failSave(HostSaveError.VAULT_UNAVAILABLE)
            return
        }

        val prompt = Any()
        activePrompt = prompt
        _savePhase.value = HostSavePhase.PROMPT_SHOWN
        try {
            biometricHelper.authenticate(
                activity = activity,
                title = title,
                subtitle = subtitle,
                onSuccess = { if (takePrompt(prompt)) retrySaveAfterReauth() },
                onError = { errorCode, _ ->
                    if (takePrompt(prompt)) {
                        failSave(
                            if (errorCode in PROMPT_DISMISSED_CODES) HostSaveError.VAULT_LOCKED
                            else HostSaveError.VAULT_UNAVAILABLE
                        )
                    }
                },
            )
        } catch (e: Exception) {
            // L'invite n'a pas pu s'ouvrir, aucun rappel ne viendra.
            if (takePrompt(prompt)) {
                Timber.w("Vault re-authentication prompt unavailable: ${e.javaClass.simpleName}")
                failSave(HostSaveError.VAULT_UNAVAILABLE)
            }
        }
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
                    _events.emit(HostEvent.HostSaved)
                    resetForm()
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

        val existingFavorite = if (form.id != null) {
            hostRepository.getById(form.id)?.isFavorite ?: false
        } else false

        // For SSH_KEY / CERTIFICATE auth, credentialId is the SSH key ID from the vault
        // (isValid guarantees a selection, the elvis is only a guard).
        // For PASSWORD auth, preserve the existing credentialId unless it is a key ID; the
        // password of a host being repaired is carried over inside passwordCredentialId, before
        // the host row is written.
        val credentialId = when (form.authType) {
            AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2 ->
                form.selectedKeyId ?: return SaveOutcome.SKIPPED
            AuthType.PASSWORD -> passwordCredentialId(form)
            else -> form.credentialId ?: UUID.randomUUID().toString()
        }
        val host = Host(
            id = form.id ?: UUID.randomUUID().toString(),
            label = form.label.trim(),
            hostname = form.hostname.trim(),
            port = form.port.toIntOrNull() ?: 22,
            username = form.username.trim(),
            authType = form.authType,
            credentialId = credentialId,
            group = form.group.trim().ifEmpty { null },
            keepAliveSeconds = form.keepAliveSeconds.toIntOrNull() ?: 30,
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
            val host = hostRepository.getById(hostId)
            hostRepository.delete(hostId)
            if (host != null) deleteOrphanedPassword(host)
            _events.emit(HostEvent.HostDeleted)
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
