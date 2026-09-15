// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import fr.techtical.nextsh.desktop.navigation.Screen
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import org.jetbrains.compose.resources.StringResource
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.cmdk_command_generate_key
import fr.techtical.nextsh.desktop.generated.resources.cmdk_command_lock_vault
import fr.techtical.nextsh.desktop.generated.resources.cmdk_command_new_host
import fr.techtical.nextsh.desktop.generated.resources.cmdk_command_new_tunnel
import fr.techtical.nextsh.desktop.generated.resources.cmdk_command_other_shortcuts
import fr.techtical.nextsh.desktop.generated.resources.cmdk_command_settings

// Maximum d'hôtes affichés quand la query est vide.
private const val MAX_HOSTS_EMPTY_QUERY = 6

/**
 * Commandes statiques disponibles dans la palette Cmd+K.
 * L'ordre est fixe : les commandes correspondant à des actions fréquentes
 * apparaissent en premier.
 */
data class CmdKCommand(
    val id: String,
    val labelRes: StringResource,
    val icon: CmdKCommandIcon,
    /**
     * Mots-clés de recherche FR+EN permettant le filtrage sans résolution
     * Composable du [labelRes]. Couvre les termes naturels que l'utilisateur
     * est susceptible de taper pour retrouver cette commande.
     */
    val searchKeywords: List<String> = emptyList(),
)

enum class CmdKCommandIcon {
    PLUS,           // Nouvel hôte / Nouveau tunnel
    KEY_ROUND,      // Générer une clé SSH
    LOCK,           // Verrouiller le vault
    SETTINGS,       // Paramètres
    ARROW_LEFT_RIGHT, // Nouveau tunnel
    KEYBOARD,       // Autres raccourcis clavier
}

/**
 * Origine de la dernière modification de [CmdKViewModel.selectedIndex].
 *
 * La palette fait défiler la liste pour garder l'item sélectionné visible,
 * mais UNIQUEMENT quand la sélection vient du clavier ([KEYBOARD]). Le survol
 * souris sélectionne lui aussi (highlight + Entrée exécute l'item survolé),
 * or coupler ce survol au scroll automatique créait une boucle : survol →
 * sélection → scroll → un nouvel item passe sous le curseur immobile →
 * sélection → scroll… jusqu'au bas de la liste (bug remonté en validation
 * UI : « ça déroule tout seul vers le bas »). Voir le `LaunchedEffect` de
 * `CmdKPanel` dans CmdK.kt.
 */
enum class CmdKSelectionSource { KEYBOARD, POINTER }

val ALL_COMMANDS = listOf(
    CmdKCommand(
        id = "new_tunnel",
        labelRes = Res.string.cmdk_command_new_tunnel,
        icon = CmdKCommandIcon.ARROW_LEFT_RIGHT,
        searchKeywords = listOf("tunnel", "port", "forward", "socks", "nouveau", "new"),
    ),
    CmdKCommand(
        id = "new_host",
        labelRes = Res.string.cmdk_command_new_host,
        icon = CmdKCommandIcon.PLUS,
        searchKeywords = listOf("hôte", "hote", "host", "serveur", "server", "nouveau", "new", "ajouter", "add"),
    ),
    CmdKCommand(
        id = "generate_key",
        labelRes = Res.string.cmdk_command_generate_key,
        icon = CmdKCommandIcon.KEY_ROUND,
        searchKeywords = listOf("clé", "cle", "key", "ssh", "générer", "generer", "generate", "rsa", "ed25519", "ecdsa"),
    ),
    CmdKCommand(
        id = "lock_vault",
        labelRes = Res.string.cmdk_command_lock_vault,
        icon = CmdKCommandIcon.LOCK,
        searchKeywords = listOf("verrou", "lock", "vault", "verrouiller", "coffre"),
    ),
    CmdKCommand(
        id = "settings",
        labelRes = Res.string.cmdk_command_settings,
        icon = CmdKCommandIcon.SETTINGS,
        searchKeywords = listOf("paramètres", "parametres", "settings", "préférences", "preferences", "configuration", "config"),
    ),
    // ── Découvrabilité des raccourcis clavier ──────────────────────────────
    // Remplace les trois commandes de session (diffusion / pane suivante /
    // pane précédente) livrées précédemment : elles devaient d'abord basculer
    // sur l'écran Sessions pour avoir un sens, ce qui téléportait
    // l'utilisateur hors de l'écran courant. Une simple fenêtre d'aide couvre
    // le besoin réel (savoir QUELS raccourcis existent) sans navigation ni
    // effet de bord : voir [ShortcutsWindow].
    CmdKCommand(
        id = "other_shortcuts",
        labelRes = Res.string.cmdk_command_other_shortcuts,
        icon = CmdKCommandIcon.KEYBOARD,
        searchKeywords = listOf(
            "raccourci", "raccourcis", "shortcut", "shortcuts", "clavier",
            "keyboard", "keys", "touches", "aide", "help",
        ),
    ),
)

/**
 * Item générique dans la liste de résultats de la palette.
 */
sealed class CmdKItem {
    data class HostItem(val host: Host) : CmdKItem()
    data class CommandItem(val command: CmdKCommand) : CmdKItem()
}

/**
 * ViewModel de la palette Cmd+K. Logique de filtrage + exécution des actions.
 *
 * Conçu pour être testé indépendamment : ne dépend que des interfaces du
 * domaine partagé (:shared). Les dépendances externes (navigation, lock) sont
 * injectées en lambdas pour éviter tout couplage avec les singletons Desktop.
 *
 * @param navigate lambda de navigation : accepte un [Screen] et le passe
 *   au [DesktopNavigator]. Découple le VM du navigateur concret pour les tests.
 * @param onLockVault action appelée pour verrouiller le vault (typiquement
 *   [VaultPinManager.lock]), passée en lambda pour éviter une dépendance
 *   directe sur l'implémentation Desktop depuis ce composant.
 * @param onConnectHost action appelée pour ouvrir une session SSH vers un hôte
 *   (typiquement [DesktopSessionManager.openSessionById]), découple le VM du
 *   session manager concret. Reçoit l'id de l'hôte sélectionné.
 * @param onShowShortcuts ouvre la fenêtre « Autres raccourcis clavier »
 *   ([ShortcutsWindow]). Purement UI, aucune navigation : la palette se ferme
 *   et la fenêtre d'aide s'ouvre par-dessus l'écran courant. No-op par défaut
 *   pour ne pas imposer un câblage aux tests qui n'exercent que le filtrage.
 */
class CmdKViewModel(
    private val hostRepository: HostRepository,
    private val navigate: (Screen) -> Unit,
    private val onLockVault: () -> Unit,
    private val onConnectHost: (hostId: String) -> Unit,
    scope: CoroutineScope,
    private val onShowShortcuts: () -> Unit = {},
) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Index de l'item actuellement sélectionné au clavier dans la liste filtrée.
     * -1 signifie "aucun item sélectionné" (état initial).
     */
    private val _selectedIndex = MutableStateFlow(-1)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    /**
     * Origine de la dernière écriture de [selectedIndex] : voir
     * [CmdKSelectionSource]. La vue ne fait défiler la liste que quand cette
     * valeur est [CmdKSelectionSource.KEYBOARD] ; sans ce garde-fou, le survol
     * souris déclenchait un auto-scroll qui faisait glisser un nouvel item
     * sous le curseur immobile, donc un nouveau survol, donc un nouveau
     * scroll : la liste dévalait toute seule jusqu'en bas.
     */
    private val _selectionSource = MutableStateFlow(CmdKSelectionSource.KEYBOARD)
    val selectionSource: StateFlow<CmdKSelectionSource> = _selectionSource.asStateFlow()

    /**
     * Liste filtrée combinant hôtes et commandes. Exposé en [StateFlow] pour
     * que le composable recompose automatiquement à chaque changement.
     */
    val filteredItems: StateFlow<List<CmdKItem>> =
        combine(_query, hostRepository.observeAll()) { q, hosts ->
            buildFilteredList(q.trim(), hosts)
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = buildFilteredList("", emptyList()),
        )

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        _selectedIndex.value = -1
        // Nouvelle recherche = liste reconstruite : on repart d'une origine
        // clavier pour que la première flèche fasse défiler normalement.
        _selectionSource.value = CmdKSelectionSource.KEYBOARD
    }

    /**
     * Force la sélection à un index donné. Utilisé par le hover souris :
     * survoler un item le marque comme "selected" pour l'highlight et le
     * hint Enter, et l'appui sur Entrée exécute l'item survolé.
     *
     * Marque la sélection comme [CmdKSelectionSource.POINTER] : la vue
     * n'auto-scrollera PAS dessus (sinon la liste s'emballe, cf.
     * [CmdKSelectionSource]).
     */
    fun setSelectedIndex(index: Int) {
        val size = filteredItems.value.size
        if (index in 0 until size && _selectedIndex.value != index) {
            _selectionSource.value = CmdKSelectionSource.POINTER
            _selectedIndex.value = index
        }
    }

    fun onMoveUp() {
        val size = filteredItems.value.size
        if (size == 0) return
        val cur = _selectedIndex.value
        _selectionSource.value = CmdKSelectionSource.KEYBOARD
        _selectedIndex.value = if (cur <= 0) size - 1 else cur - 1
    }

    fun onMoveDown() {
        val size = filteredItems.value.size
        if (size == 0) return
        val cur = _selectedIndex.value
        _selectionSource.value = CmdKSelectionSource.KEYBOARD
        _selectedIndex.value = if (cur >= size - 1) 0 else cur + 1
    }

    /**
     * Exécute l'item sélectionné (par index clavier ou clic).
     * Retourne true si une action a été lancée (permet à l'appelant de fermer
     * la palette).
     */
    fun execute(index: Int, onClose: () -> Unit) {
        val items = filteredItems.value
        if (index < 0 || index >= items.size) return
        executeItem(items[index], onClose)
    }

    fun executeSelected(onClose: () -> Unit) {
        val items = filteredItems.value
        val idx = _selectedIndex.value
        if (idx < 0 || idx >= items.size) {
            // Aucune sélection exploitable : soit jamais sélectionné (-1),
            // soit `filteredItems` a rétréci sous l'index sélectionné SANS
            // repasser par [onQueryChange], par exemple hostRepository
            // .observeAll() qui émet une liste d'hôtes plus courte (hôte
            // supprimé ailleurs, sync CRDT) pendant que la query reste
            // inchangée. `selectedIndex` n'est alors jamais reclampé et
            // Entrée ne faisait rien avant ce garde-fou (execute(idx, ...)
            // retournait tôt sur un index hors bornes). On retombe sur le
            // premier item plutôt que de laisser l'appui sur Entrée sans
            // effet.
            if (items.isNotEmpty()) executeItem(items[0], onClose)
        } else {
            executeItem(items[idx], onClose)
        }
    }

    private fun executeItem(item: CmdKItem, onClose: () -> Unit) {
        when (item) {
            is CmdKItem.HostItem -> {
                onClose()
                onConnectHost(item.host.id)
                navigate(Screen.Sessions)
            }
            is CmdKItem.CommandItem -> executeCommand(item.command.id, onClose)
        }
    }

    private fun executeCommand(id: String, onClose: () -> Unit) {
        when (id) {
            "new_tunnel" -> {
                onClose()
                navigate(Screen.TunnelConfig(tunnelId = null))
            }
            "new_host" -> {
                onClose()
                navigate(Screen.HostDetail(hostId = null))
            }
            "generate_key" -> {
                onClose()
                navigate(Screen.Vault)
            }
            "lock_vault" -> {
                onClose()
                onLockVault()
                navigate(Screen.VaultUnlock)
            }
            "settings" -> {
                onClose()
                navigate(Screen.Settings)
            }
            // Aide raccourcis : aucune navigation. On ferme la palette puis on
            // ouvre la fenêtre d'aide au-dessus de l'écran courant, quel que
            // soit cet écran, l'utilisateur y revient en fermant la fenêtre.
            "other_shortcuts" -> {
                onClose()
                onShowShortcuts()
            }
        }
    }

    /**
     * Appelé lors de l'ouverture de la palette : reset query + sélection.
     */
    fun reset() {
        _query.value = ""
        _selectedIndex.value = -1
        _selectionSource.value = CmdKSelectionSource.KEYBOARD
    }

    // ── Pure filtering logic (testable without coroutines) ────────────────────

    companion object {
        /**
         * Construit la liste filtrée à partir d'une query et d'une liste d'hôtes.
         * Séparée en companion pour être testable de façon déterministe.
         */
        fun buildFilteredList(query: String, allHosts: List<Host>): List<CmdKItem> {
            return if (query.isEmpty()) {
                val hostItems = allHosts
                    .take(MAX_HOSTS_EMPTY_QUERY)
                    .map { CmdKItem.HostItem(it) }
                val commandItems = ALL_COMMANDS.map { CmdKItem.CommandItem(it) }
                hostItems + commandItems
            } else {
                val q = query.lowercase()
                val hostItems = allHosts
                    .filter { host ->
                        host.label.lowercase().contains(q) ||
                            host.hostname.lowercase().contains(q) ||
                            host.username.lowercase().contains(q)
                    }
                    .map { CmdKItem.HostItem(it) }
                val commandItems = ALL_COMMANDS
                    .filter { cmd ->
                        cmd.id.lowercase().contains(q) ||
                            cmd.searchKeywords.any { kw -> kw.lowercase().contains(q) }
                    }
                    .map { CmdKItem.CommandItem(it) }
                hostItems + commandItems
            }
        }
    }
}
