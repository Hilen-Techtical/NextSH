// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import fr.techtical.nextsh.desktop.navigation.Screen
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests unitaires pour [CmdKViewModel].
 *
 * La logique de filtrage ([CmdKViewModel.Companion.buildFilteredList]) est
 * testée de façon pure (sans coroutines). Les comportements d'état (index,
 * query) et d'exécution sont testés via un faux [HostRepository] et des
 * lambdas capturées.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CmdKViewModelTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeHost(
        id: String = "h1",
        label: String = "Mon Serveur",
        hostname: String = "example.com",
        username: String = "admin",
    ) = Host(
        id = id,
        label = label,
        hostname = hostname,
        port = 22,
        username = username,
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    private class FakeHostRepository(
        hosts: List<Host> = emptyList(),
    ) : HostRepository {
        private val _hosts = MutableStateFlow(hosts)

        /** Test-only: mutate the observed host list without going through [save]/[delete]. */
        fun setHosts(newHosts: List<Host>) {
            _hosts.value = newHosts
        }

        override fun observeAll(): StateFlow<List<Host>> = _hosts.asStateFlow()
        override fun observeByGroup(group: String): StateFlow<List<Host>> =
            MutableStateFlow(emptyList<Host>()).asStateFlow()
        override fun observeGroups(): StateFlow<List<String>> =
            MutableStateFlow(emptyList<String>()).asStateFlow()
        override suspend fun getById(id: String): Host? = _hosts.value.find { it.id == id }
        override suspend fun save(host: Host) = Unit
        override suspend fun update(host: Host) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun updateLastConnected(id: String) = Unit
        override fun observeFavorites(): StateFlow<List<Host>> =
            MutableStateFlow(emptyList<Host>()).asStateFlow()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private fun makeVm(
        hosts: List<Host> = emptyList(),
        navigated: MutableList<Screen> = mutableListOf(),
        locked: () -> Unit = {},
        connected: MutableList<String> = mutableListOf(),
        scope: kotlinx.coroutines.CoroutineScope,
        showShortcuts: () -> Unit = {},
    ) = CmdKViewModel(
        hostRepository = FakeHostRepository(hosts),
        navigate = { screen -> navigated.add(screen) },
        onLockVault = locked,
        onConnectHost = { hostId -> connected.add(hostId) },
        // Passer backgroundScope (depuis runTest) pour éviter UncompletedCoroutinesError :
        // stateIn(WhileSubscribed) garde une coroutine active ; backgroundScope est nettoyé
        // après le test sans bloquer sa complétion (contrairement au testScope direct).
        scope = scope,
        onShowShortcuts = showShortcuts,
    )

    /**
     * Pour les tests utilisant stateIn WhileSubscribed, on doit utiliser backgroundScope :
     * voir ConflictResolutionViewModelTest dans ce projet pour le pattern de référence.
     */

    // ── Tests filtrage pur (CmdKViewModel.Companion.buildFilteredList) ────────

    @Test
    fun `query vide retourne jusqu a 6 hotes et toutes les commandes`() {
        val hosts = (1..10).map { makeHost(id = "h$it", label = "Hote $it") }
        val items = CmdKViewModel.buildFilteredList("", hosts)

        val hostCount = items.filterIsInstance<CmdKItem.HostItem>().size
        val commandCount = items.filterIsInstance<CmdKItem.CommandItem>().size

        assertEquals(6, hostCount, "Max 6 hôtes attendus quand query vide")
        assertEquals(ALL_COMMANDS.size, commandCount, "Toutes les commandes attendues")
    }

    @Test
    fun `query vide avec moins de 6 hotes retourne tous les hotes`() {
        val hosts = listOf(makeHost("h1"), makeHost("h2"), makeHost("h3"))
        val items = CmdKViewModel.buildFilteredList("", hosts)

        val hostCount = items.filterIsInstance<CmdKItem.HostItem>().size
        assertEquals(3, hostCount)
    }

    @Test
    fun `filtrage par label hote case insensitive`() {
        val hosts = listOf(
            makeHost(id = "h1", label = "Production DB"),
            makeHost(id = "h2", label = "Staging Web"),
            makeHost(id = "h3", label = "Dev Local"),
        )
        val items = CmdKViewModel.buildFilteredList("prod", hosts)
        val hostItems = items.filterIsInstance<CmdKItem.HostItem>()

        assertEquals(1, hostItems.size)
        assertEquals("h1", hostItems.first().host.id)
    }

    @Test
    fun `filtrage par hostname`() {
        val hosts = listOf(
            makeHost(id = "h1", hostname = "db.prod.example.com"),
            makeHost(id = "h2", hostname = "web.staging.example.com"),
        )
        val items = CmdKViewModel.buildFilteredList("staging", hosts)
        val hostItems = items.filterIsInstance<CmdKItem.HostItem>()

        assertEquals(1, hostItems.size)
        assertEquals("h2", hostItems.first().host.id)
    }

    @Test
    fun `filtrage par username`() {
        val hosts = listOf(
            makeHost(id = "h1", username = "root"),
            makeHost(id = "h2", username = "deploy"),
        )
        val items = CmdKViewModel.buildFilteredList("deploy", hosts)
        val hostItems = items.filterIsInstance<CmdKItem.HostItem>()

        assertEquals(1, hostItems.size)
        assertEquals("h2", hostItems.first().host.id)
    }

    @Test
    fun `filtrage commandes par label`() {
        val items = CmdKViewModel.buildFilteredList("tunnel", emptyList())
        val commandItems = items.filterIsInstance<CmdKItem.CommandItem>()

        assertEquals(1, commandItems.size)
        assertEquals("new_tunnel", commandItems.first().command.id)
    }

    @Test
    fun `filtrage commande verrou vault`() {
        val items = CmdKViewModel.buildFilteredList("vault", emptyList())
        val commandItems = items.filterIsInstance<CmdKItem.CommandItem>()

        assertTrue(commandItems.any { it.command.id == "lock_vault" })
    }

    @Test
    fun `query sans correspondance retourne liste vide`() {
        val hosts = listOf(makeHost(id = "h1", label = "Prod", hostname = "prod.com", username = "root"))
        val items = CmdKViewModel.buildFilteredList("zzznomatch", hosts)

        assertTrue(items.isEmpty())
    }

    @Test
    fun `filtrage retourne hotes ET commandes correspondants simultanement`() {
        val hosts = listOf(
            makeHost(id = "h1", label = "Production SSH"),
            makeHost(id = "h2", label = "Dev"),
        )
        // "SSH" devrait matcher le label hôte "Production SSH" et la commande
        // "Générer une clé SSH".
        val items = CmdKViewModel.buildFilteredList("ssh", hosts)
        val hostItems = items.filterIsInstance<CmdKItem.HostItem>()
        val commandItems = items.filterIsInstance<CmdKItem.CommandItem>()

        assertEquals(1, hostItems.size, "Un hôte 'Production SSH' attendu")
        assertEquals(1, commandItems.size, "Commande 'Générer une clé SSH' attendue")
        assertEquals("generate_key", commandItems.first().command.id)
    }

    @Test
    fun `filtrage commande autres raccourcis par mot cle`() {
        listOf("raccourci", "shortcut", "clavier", "keyboard").forEach { query ->
            val items = CmdKViewModel.buildFilteredList(query, emptyList())
            val commandItems = items.filterIsInstance<CmdKItem.CommandItem>()
            assertTrue(
                commandItems.any { it.command.id == "other_shortcuts" },
                "La commande 'other_shortcuts' devrait matcher la query '$query'",
            )
        }
    }

    /**
     * Régression : les trois commandes de session (diffusion / pane suivante /
     * pane précédente) forçaient un `navigate(Screen.Sessions)` avant d'agir,
     * ce qui téléportait l'utilisateur hors de l'écran courant. Elles ont été
     * retirées au profit de la fenêtre d'aide `other_shortcuts`, qui ne navigue
     * pas. Aucune commande de la palette ne doit plus imposer ce détour.
     */
    @Test
    fun `les commandes de session forcant un passage sur Sessions ont ete retirees`() {
        val removed = setOf("toggle_broadcast", "focus_next_pane", "focus_previous_pane")
        val remaining = ALL_COMMANDS.map { it.id }.filter { it in removed }

        assertTrue(remaining.isEmpty(), "Commandes de session résiduelles : $remaining")
        assertTrue(
            ALL_COMMANDS.any { it.id == "other_shortcuts" },
            "La commande 'Autres raccourcis clavier' devrait les remplacer",
        )
    }

    // ── Tests navigation clavier ──────────────────────────────────────────────

    @Test
    fun `onMoveDown incremente selectedIndex depuis -1`() = runTest {
        val vm = makeVm(hosts = listOf(makeHost()), scope = backgroundScope)

        assertEquals(-1, vm.selectedIndex.value)
        vm.onMoveDown()
        assertEquals(0, vm.selectedIndex.value)
    }

    @Test
    fun `onMoveDown cycle depuis dernier item revient au premier`() = runTest {
        val hosts = listOf(makeHost("h1"), makeHost("h2"))
        val vm = makeVm(hosts = hosts, scope = backgroundScope)

        // Aller jusqu'au dernier item
        repeat(vm.filteredItems.value.size) { vm.onMoveDown() }
        // Maintenant on est à l'index taille-1, un autre Down doit revenir à 0
        vm.onMoveDown()
        assertEquals(0, vm.selectedIndex.value)
    }

    @Test
    fun `onMoveUp depuis -1 atterrit sur le dernier item`() = runTest {
        val hosts = listOf(makeHost("h1"), makeHost("h2"))
        val vm = makeVm(hosts = hosts, scope = backgroundScope)

        vm.onMoveUp()
        val size = vm.filteredItems.value.size
        assertEquals(size - 1, vm.selectedIndex.value)
    }

    @Test
    fun `reset reinitialise query et selectedIndex`() = runTest {
        val vm = makeVm(hosts = listOf(makeHost()), scope = backgroundScope)

        vm.onQueryChange("test")
        vm.onMoveDown()
        vm.reset()

        assertEquals("", vm.query.value)
        assertEquals(-1, vm.selectedIndex.value)
    }

    // ── Origine de la sélection (anti-emballement du scroll au survol) ───────

    @Test
    fun `les fleches marquent la selection comme clavier`() = runTest {
        val vm = makeVm(hosts = listOf(makeHost("h1"), makeHost("h2")), scope = backgroundScope)

        vm.onMoveDown()
        assertEquals(CmdKSelectionSource.KEYBOARD, vm.selectionSource.value)
        vm.onMoveUp()
        assertEquals(CmdKSelectionSource.KEYBOARD, vm.selectionSource.value)
    }

    /**
     * Le survol souris sélectionne (highlight + Entrée exécute l'item survolé)
     * mais doit être identifié comme POINTER : c'est ce qui empêche la vue de
     * faire défiler la liste sur cette sélection. Sans ce marqueur, le survol
     * déclenchait un auto-scroll qui amenait un nouvel item sous le curseur
     * immobile, donc un nouveau survol, donc un nouveau scroll : la liste
     * dévalait toute seule jusqu'en bas.
     */
    @Test
    fun `le survol marque la selection comme pointeur`() = runTest {
        val vm = makeVm(hosts = listOf(makeHost("h1"), makeHost("h2")), scope = backgroundScope)

        vm.onMoveDown()
        assertEquals(CmdKSelectionSource.KEYBOARD, vm.selectionSource.value)

        // Survol d'un item différent de celui sélectionné au clavier.
        vm.setSelectedIndex(2)
        assertEquals(2, vm.selectedIndex.value)
        assertEquals(CmdKSelectionSource.POINTER, vm.selectionSource.value)
    }

    @Test
    fun `reset repart d une origine clavier`() = runTest {
        val vm = makeVm(hosts = listOf(makeHost("h1"), makeHost("h2")), scope = backgroundScope)

        vm.setSelectedIndex(1)
        assertEquals(CmdKSelectionSource.POINTER, vm.selectionSource.value)

        vm.reset()
        assertEquals(CmdKSelectionSource.KEYBOARD, vm.selectionSource.value)
    }

    @Test
    fun `onQueryChange remet selectedIndex a -1`() = runTest {
        val vm = makeVm(hosts = listOf(makeHost()), scope = backgroundScope)

        vm.onMoveDown()
        assertTrue(vm.selectedIndex.value >= 0)
        vm.onQueryChange("nouveau")
        assertEquals(-1, vm.selectedIndex.value)
    }

    // ── Tests exécution commandes ─────────────────────────────────────────────

    @Test
    fun `commande new_host navigue vers HostDetail null`() = runTest {
        val navigated = mutableListOf<Screen>()
        val vm = makeVm(navigated = navigated, scope = backgroundScope)

        vm.onQueryChange("hôte")
        // Forcer la sélection de "Nouvel hôte"
        val idx = vm.filteredItems.value.indexOfFirst {
            it is CmdKItem.CommandItem && it.command.id == "new_host"
        }
        assertTrue(idx >= 0, "Commande 'new_host' devrait apparaître")
        vm.execute(idx, onClose = {})

        assertEquals(1, navigated.size)
        assertTrue(navigated.first() is Screen.HostDetail)
        assertEquals(null, (navigated.first() as Screen.HostDetail).hostId)
    }

    @Test
    fun `commande new_tunnel navigue vers TunnelConfig null`() = runTest {
        val navigated = mutableListOf<Screen>()
        val vm = makeVm(navigated = navigated, scope = backgroundScope)

        val idx = vm.filteredItems.value.indexOfFirst {
            it is CmdKItem.CommandItem && it.command.id == "new_tunnel"
        }
        assertTrue(idx >= 0)
        vm.execute(idx, onClose = {})

        assertEquals(1, navigated.size)
        assertTrue(navigated.first() is Screen.TunnelConfig)
        assertEquals(null, (navigated.first() as Screen.TunnelConfig).tunnelId)
    }

    @Test
    fun `commande settings navigue vers Settings`() = runTest {
        val navigated = mutableListOf<Screen>()
        val vm = makeVm(navigated = navigated, scope = backgroundScope)

        val idx = vm.filteredItems.value.indexOfFirst {
            it is CmdKItem.CommandItem && it.command.id == "settings"
        }
        assertTrue(idx >= 0)
        vm.execute(idx, onClose = {})

        assertEquals(1, navigated.size)
        assertTrue(navigated.first() is Screen.Settings)
    }

    @Test
    fun `commande lock_vault appelle onLockVault et navigue vers VaultUnlock`() = runTest {
        var locked = false
        var closed = false
        val navigated = mutableListOf<Screen>()
        val vm = makeVm(
            navigated = navigated,
            locked = { locked = true },
            scope = backgroundScope,
        )

        val idx = vm.filteredItems.value.indexOfFirst {
            it is CmdKItem.CommandItem && it.command.id == "lock_vault"
        }
        assertTrue(idx >= 0)
        vm.execute(idx, onClose = { closed = true })

        assertTrue(locked, "onLockVault devrait être appelé")
        assertTrue(closed, "onClose devrait être appelé")
        assertEquals(1, navigated.size)
        assertTrue(navigated.first() is Screen.VaultUnlock)
    }

    /**
     * La commande d'aide ouvre la fenêtre des raccourcis SANS naviguer : elle
     * doit être utilisable depuis n'importe quel écran sans faire basculer
     * l'application ailleurs (c'était le défaut des trois commandes de session
     * qu'elle remplace).
     */
    @Test
    fun `commande other_shortcuts ouvre la fenetre sans naviguer`() = runTest {
        var shown = 0
        var closed = false
        val navigated = mutableListOf<Screen>()
        val vm = makeVm(
            navigated = navigated,
            scope = backgroundScope,
            showShortcuts = { shown++ },
        )

        val idx = vm.filteredItems.value.indexOfFirst {
            it is CmdKItem.CommandItem && it.command.id == "other_shortcuts"
        }
        assertTrue(idx >= 0, "Commande 'other_shortcuts' devrait apparaître")
        vm.execute(idx, onClose = { closed = true })

        assertEquals(1, shown, "onShowShortcuts devrait être appelé une fois")
        assertTrue(closed, "La palette devrait se fermer avant l'ouverture de la fenêtre")
        assertTrue(navigated.isEmpty(), "Aucune navigation ne doit être déclenchée")
    }

    @Test
    fun `execute index hors bornes ne fait rien`() = runTest {
        var closed = false
        val navigated = mutableListOf<Screen>()
        val vm = makeVm(navigated = navigated, scope = backgroundScope)

        vm.execute(9999, onClose = { closed = true })

        assertFalse(closed)
        assertTrue(navigated.isEmpty())
    }

    /**
     * Régression : `filteredItems` peut rétrécir SANS passer par
     * [CmdKViewModel.onQueryChange] (ex : hostRepository.observeAll() émet
     * une liste d'hôtes plus courte pendant que la query reste inchangée).
     * `selectedIndex` n'est alors jamais reclampé et peut pointer au-delà de
     * la nouvelle taille : avant le fix, `executeSelected` (Entrée) ne
     * faisait rien dans ce cas. Il doit désormais retomber sur le premier
     * item plutôt que rester silencieux.
     */
    @Test
    fun `executeSelected retombe sur le premier item quand la liste retrecit hors onQueryChange`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = FakeHostRepository(listOf(makeHost("h1"), makeHost("h2"), makeHost("h3")))
            val navigated = mutableListOf<Screen>()
            val vm = CmdKViewModel(
                hostRepository = repo,
                navigate = { screen -> navigated.add(screen) },
                onLockVault = {},
                onConnectHost = {},
                scope = backgroundScope,
            )

            // Abonné permanent : filteredItems est un stateIn(WhileSubscribed),
            // sans collecteur actif, .value ne se recalcule pas et un `first {}`
            // ponctuel laisse l'upstream s'endormir entre deux attentes.
            backgroundScope.launch { vm.filteredItems.collect {} }
            advanceUntilIdle()

            val initialSize = vm.filteredItems.value.size
            assertTrue(initialSize > 0, "La liste initiale (3 hôtes + commandes) ne doit pas être vide")

            // Sélectionne le DERNIER item au clavier (ex: "Autres raccourcis clavier").
            vm.onMoveUp()
            assertEquals(initialSize - 1, vm.selectedIndex.value)

            // Les hôtes disparaissent SANS passer par onQueryChange : la liste
            // rétrécit sous l'index sélectionné, qui n'est jamais reclampé
            // automatiquement.
            repo.setHosts(emptyList())
            advanceUntilIdle()
            val shrunkSize = vm.filteredItems.value.size
            assertTrue(shrunkSize < initialSize, "La liste doit rétrécir après la disparition des hôtes")
            assertTrue(
                vm.selectedIndex.value >= shrunkSize,
                "L'index sélectionné devrait être hors bornes après le rétrécissement",
            )

            var closed = false
            vm.executeSelected(onClose = { closed = true })

            assertTrue(closed, "executeSelected devrait exécuter le premier item au lieu de ne rien faire")
            assertEquals(1, navigated.size)
        }

    @Test
    fun `executeSelected sans selection execute premier item`() = runTest {
        var closed = false
        val navigated = mutableListOf<Screen>()
        val vm = makeVm(navigated = navigated, scope = backgroundScope)

        // selectedIndex = -1 (aucune sélection)
        assertEquals(-1, vm.selectedIndex.value)
        vm.executeSelected(onClose = { closed = true })

        assertTrue(closed, "onClose devrait être appelé même sans sélection")
        assertTrue(navigated.isNotEmpty(), "Navigation devrait avoir eu lieu")
    }

    // ── Tests connexion hôte (Fix UX #2) ─────────────────────────────────────

    @Test
    fun `selection hote appelle onConnectHost avec le bon hostId et navigue vers Sessions`() = runTest {
        val host = makeHost(id = "srv-prod", label = "Production")
        val navigated = mutableListOf<Screen>()
        val connected = mutableListOf<String>()
        // filteredItems utilise stateIn(WhileSubscribed). On teste le comportement via
        // buildFilteredList (companion, pure) pour la résolution d'index, et on vérifie
        // le contrat complet (onConnectHost + navigate) via executeSelected après avoir
        // changé la query pour isoler l'hôte cible.
        val vm = makeVm(hosts = listOf(host), navigated = navigated, connected = connected, scope = backgroundScope)

        // Utiliser la fonction pure du companion pour construire l'index attendu et
        // vérifier que l'hôte est bien dans la liste filtrée : ceci teste le filtrage
        // sans dépendre du timing stateIn.
        val expectedItems = CmdKViewModel.buildFilteredList("", listOf(host))
        val expectedIdx = expectedItems.indexOfFirst { it is CmdKItem.HostItem && it.host.id == "srv-prod" }
        assertTrue(expectedIdx >= 0, "L'hôte 'srv-prod' devrait apparaître dans la liste filtrée")

        // Pour déclencher executeItem(HostItem), on utilise executeSelected qui exécute
        // le premier item. On filtre sur le label de l'hôte pour que le premier item
        // soit bien le HostItem (pas une commande). filteredItems.first() force la
        // subscription et attend l'émission du stateIn.
        // Filtrer sur le label de l'hôte pour que filteredItems le retourne.
        vm.onQueryChange("Production")
        // Attendre que filteredItems émette un résultat contenant l'hôte.
        val liveItems = vm.filteredItems.first { list ->
            list.any { it is CmdKItem.HostItem && it.host.id == "srv-prod" }
        }
        val liveIdx = liveItems.indexOfFirst { it is CmdKItem.HostItem && it.host.id == "srv-prod" }

        var closed = false
        vm.execute(liveIdx, onClose = { closed = true })

        assertTrue(closed, "onClose devrait être appelé")
        assertEquals(listOf("srv-prod"), connected, "onConnectHost doit être appelé avec l'id exact de l'hôte")
        assertEquals(1, navigated.size)
        assertTrue(navigated.first() is Screen.Sessions, "Navigation vers Sessions attendue")
    }

    // ── Tests lock vault teardown (Fix Sécurité #1) ───────────────────────────

    @Test
    fun `commande lock_vault appelle onLockVault ET onConnectHost n est pas appele`() = runTest {
        var locked = false
        val connected = mutableListOf<String>()
        val navigated = mutableListOf<Screen>()
        val vm = makeVm(
            navigated = navigated,
            locked = { locked = true },
            connected = connected,
            scope = backgroundScope,
        )

        val idx = vm.filteredItems.value.indexOfFirst {
            it is CmdKItem.CommandItem && it.command.id == "lock_vault"
        }
        assertTrue(idx >= 0)
        vm.execute(idx, onClose = {})

        assertTrue(locked, "onLockVault doit être appelé lors du lock vault")
        assertTrue(connected.isEmpty(), "onConnectHost ne doit pas être appelé lors d'un lock vault")
        assertTrue(navigated.first() is Screen.VaultUnlock)
    }
}
