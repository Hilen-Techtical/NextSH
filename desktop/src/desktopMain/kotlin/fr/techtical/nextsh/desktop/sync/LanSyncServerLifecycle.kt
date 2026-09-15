// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.desktop.core.diagnostics.StartupTrace
import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "LanSyncServerLifecycle"

/** Maximum number of [SyncServerControl.start] attempts before giving up (UI keeps the retry button). */
private const val MAX_START_ATTEMPTS = 6

/** First backoff after a failed attempt, in milliseconds. Doubles each retry. */
private const val INITIAL_BACKOFF_MS = 1_000L

/** Upper bound on the exponential backoff between attempts. */
private const val MAX_BACKOFF_MS = 15_000L

/** Poll interval while the server is in the transient [State.Starting] state. */
private const val STARTING_POLL_MS = 200L

/** Max time to wait for a [State.Starting] to settle before treating it as stalled. */
private const val STARTING_SETTLE_TIMEOUT_MS = 10_000L

/** Tick interval of the network re-bind watcher (C4): see [startNetworkWatcher]. */
private const val NETWORK_WATCH_INTERVAL_MS = 20_000L

/**
 * Orchestre le démarrage et l'arrêt du [LanSyncServer] en fonction de deux conditions :
 * - Le vault est déverrouillé (nécessaire pour accéder aux secrets des devices enrôlés)
 * - L'utilisateur a activé la sync LAN dans les paramètres
 *
 * Le serveur ne démarre que si les deux conditions sont remplies.
 *
 * Auto-réparation (B3) : [SyncServerControl.start] est une tentative unique qui peut échouer
 * transitoirement (ex. « Aucune interface LAN détectée » au boot avant que le réseau ne soit
 * monté, ou une erreur de bind). Le lifecycle relance donc avec un backoff exponentiel borné
 * tant que la condition (vault déverrouillé + sync activée) reste vraie, afin que l'utilisateur
 * n'ait jamais à faire un off→on manuel. Le travail de retry s'exécute sur [AppScope].
 */
class LanSyncServerLifecycle(
    private val server: SyncServerControl,
    private val settingsStore: DesktopSettingsStore,
    private val appScope: AppScope,
) {

    @Volatile private var vaultUnlocked = false

    /**
     * Job de retry courant. On annule-et-remplace systématiquement avant d'en lancer un nouveau
     * pour éviter deux boucles de retry concurrentes (pas de tempête de démarrages).
     */
    @Volatile private var retryJob: Job? = null

    /**
     * Job du watcher réseau (C4). Distinct de [retryJob] : ce n'est pas une tentative bornée de
     * démarrage, mais une boucle vivant pour la durée du processus qui détecte les changements
     * d'IP / la perte d'interface LAN pendant que le serveur tourne déjà.
     */
    @Volatile private var networkWatchJob: Job? = null

    /** True quand les deux conditions de démarrage sont réunies. */
    private fun gateOpen(): Boolean = vaultUnlocked && settingsStore.syncEnabled

    /** Appelé après déverrouillage PIN réussi. Démarre le serveur si la sync LAN est activée. */
    fun onVaultUnlocked() {
        val t0 = System.nanoTime()
        vaultUnlocked = true
        Logger.d(TAG, "Vault déverrouillé : vérification sync LAN")
        if (settingsStore.syncEnabled) {
            Logger.d(TAG, "Sync LAN activée : démarrage du serveur")
            startWithRetry()
            StartupTrace.async("LanSyncServer.start (syncEnabled=true)", (System.nanoTime() - t0) / 1_000_000)
        } else {
            Logger.d(TAG, "Sync LAN désactivée : serveur non démarré")
            StartupTrace.async("LanSyncServerLifecycle.onVaultUnlocked (syncEnabled=false)", (System.nanoTime() - t0) / 1_000_000)
        }
    }

    /** Appelé lors d'un re-lock du vault. Arrête le serveur immédiatement. */
    fun onVaultLocked() {
        vaultUnlocked = false
        Logger.d(TAG, "Vault verrouillé : arrêt du serveur de sync")
        cancelRetry()
        server.stop()
    }

    /**
     * Appelé depuis le toggle UI.
     * - Si [enabled] = true et vault déverrouillé → démarrage (avec retry).
     * - Si [enabled] = false → arrêt.
     */
    fun applySettingsChange(enabled: Boolean) {
        settingsStore.updateSyncEnabled(enabled)
        if (enabled) {
            if (vaultUnlocked) {
                Logger.d(TAG, "Sync LAN activée par l'utilisateur : démarrage du serveur")
                startWithRetry()
            } else {
                Logger.d(TAG, "Sync LAN activée par l'utilisateur mais vault verrouillé : en attente")
            }
        } else {
            Logger.d(TAG, "Sync LAN désactivée par l'utilisateur : arrêt du serveur")
            cancelRetry()
            server.stop()
        }
    }

    /**
     * Relance le serveur s'il devrait tourner mais ne tourne pas (auto-réparation).
     * Idempotent : ne fait rien si la condition est fausse ou si le serveur est déjà
     * Listening/Starting. Appelé notamment à l'ouverture de l'écran Paramètres et par
     * le bouton « Relancer le serveur ».
     */
    fun ensureRunning() {
        if (!gateOpen()) {
            Logger.d(TAG, "ensureRunning ignoré : vault verrouillé ou sync désactivée")
            return
        }
        val current = server.state.value
        if (current is LanSyncServer.State.Listening || current is LanSyncServer.State.Starting) {
            Logger.d(TAG, "ensureRunning ignoré : serveur déjà actif ($current)")
            return
        }
        Logger.d(TAG, "ensureRunning : relance du serveur (état=$current)")
        startWithRetry()
    }

    /** Annule la boucle de retry en cours (le cas échéant). */
    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Démarre le watcher réseau (C4) : re-bind transparent quand l'IP LAN change pendant que le
     * serveur tourne, et reprise automatique quand l'interface LAN disparaît puis revient
     * (câble débranché/rebranché, changement de réseau WiFi…).
     *
     * Boucle vivant pour la durée du processus (contrairement à [retryJob], qui est borné et
     * se termine). Idempotent : un appel répété n'empile pas plusieurs boucles.
     *
     * Volontairement **non auto-démarré** par [onVaultUnlocked]/[applySettingsChange] : c'est une
     * boucle infinie tant que l'objet vit, donc l'appeler depuis un point déjà exercé par les
     * tests existants (qui pilotent le temps virtuel via `advanceUntilIdle()`) empêcherait ces
     * tests de jamais atteindre l'état idle. Le composition root doit appeler cette méthode une
     * seule fois au démarrage de l'application (hors périmètre de ce fichier).
     */
    fun startNetworkWatcher() {
        if (networkWatchJob?.isActive == true) return
        networkWatchJob = appScope.coroutineScope.launch {
            while (isActive) {
                delay(NETWORK_WATCH_INTERVAL_MS)
                // The watcher is the only self-healing path once the bounded retry budget is
                // spent, so it must outlive any single failing tick. A throw from the network
                // probe or from a server call would otherwise end the loop for good, silently,
                // and only under exactly the degraded conditions the watcher exists for.
                runCatching { checkNetworkChange() }
                    .onFailure { Logger.w(TAG, "Tick du watcher réseau en échec, poursuite : ${it.message}") }
            }
        }
    }

    /** Arrête le watcher réseau. Idempotent. Exposé surtout pour un arrêt propre en test. */
    fun stopNetworkWatcher() {
        networkWatchJob?.cancel()
        networkWatchJob = null
    }

    /**
     * Un tick du watcher réseau. No-op silencieux (aucun appel à [LanAddressDetector], aucun log)
     * tant que la porte (vault déverrouillé + sync activée) n'est pas ouverte : le watcher ne doit
     * scruter le réseau que quand le serveur est censé tourner.
     */
    private fun checkNetworkChange() {
        if (!gateOpen()) return

        val current = server.state.value
        val detected = LanAddressDetector.detect()?.address?.hostAddress

        when {
            current is LanSyncServer.State.Listening && detected != null && detected != current.addr -> {
                Logger.d(TAG, "Changement d'IP détecté (${current.addr} → $detected) : re-bind du serveur de sync")
                server.stop()
                ensureRunning()
            }
            current is LanSyncServer.State.Listening && detected == null -> {
                Logger.w(TAG, "Interface LAN disparue : arrêt du serveur de sync (reprise automatique au retour du réseau)")
                server.stop()
            }
            current !is LanSyncServer.State.Listening && current !is LanSyncServer.State.Starting && detected != null -> {
                // Le serveur est down (Stopped/Error) alors que la porte est ouverte et qu'une
                // interface LAN est de nouveau disponible : reprise automatique.
                //
                // Sauf si une boucle de retry est déjà en cours : startWithRetry() annule-et-
                // remplace, donc réarmer ici toutes les 20 s remettrait le backoff à zéro
                // indéfiniment : une tempête de tentatives de bind (et de prompts pare-feu)
                // au lieu des MAX_START_ATTEMPTS bornées.
                if (retryJob?.isActive == true) {
                    Logger.d(TAG, "Reprise différée : une boucle de démarrage est déjà en cours")
                } else {
                    Logger.d(TAG, "Interface LAN disponible : tentative de reprise du serveur de sync")
                    ensureRunning()
                }
            }
            else -> {
                // Serveur en écoute sur la bonne adresse : rien à re-binder côté TCP, mais le
                // répondeur UDP de découverte a pu échouer son bind au démarrage (port pris,
                // refus pare-feu). Idempotent : no-op quand il tourne déjà.
                if (current is LanSyncServer.State.Listening) server.ensureDiscoveryRunning()
            }
        }
    }

    /**
     * Lance (ou relance) la boucle de démarrage avec backoff exponentiel borné.
     * Annule-et-remplace tout job de retry précédent pour garantir une seule boucle active.
     *
     * À chaque itération :
     * 1. Re-vérifie la condition (vault déverrouillé + sync activée) : sort sinon (pas de start
     *    quand désactivé/verrouillé).
     * 2. Si le serveur est déjà Listening, c'est terminé.
     * 3. Appelle [SyncServerControl.start] (no-op si déjà Listening/Starting grâce à son garde interne),
     *    attend que l'état se stabilise (Starting → Listening/Error).
     * 4. Listening → terminé. Sinon attend le backoff puis recommence, jusqu'à [MAX_START_ATTEMPTS].
     */
    private fun startWithRetry() {
        cancelRetry()
        retryJob = appScope.coroutineScope.launch {
            var backoff = INITIAL_BACKOFF_MS
            var attempt = 0
            while (isActive && attempt < MAX_START_ATTEMPTS) {
                if (!gateOpen()) {
                    Logger.d(TAG, "Retry interrompu : condition non remplie (vault/sync)")
                    return@launch
                }
                if (server.state.value is LanSyncServer.State.Listening) {
                    Logger.d(TAG, "Serveur déjà actif : retry terminé")
                    return@launch
                }

                attempt++
                Logger.d(TAG, "Tentative de démarrage du serveur de sync ($attempt/$MAX_START_ATTEMPTS)")
                // Le prédicat est ré-évalué au point exact du bind (avant ET après) : ultime
                // garde-fou contre une race où le vault se verrouille / la sync se désactive entre
                // le gateOpen() ci-dessus et le bind réel, qui laisserait sinon l'endpoint TLS actif.
                server.start(shouldRun = { gateOpen() })

                // Laisse l'état transitoire Starting se stabiliser en Listening ou Error.
                val settled = awaitSettled()
                if (settled is LanSyncServer.State.Listening) {
                    Logger.d(TAG, "Serveur de sync démarré (tentative $attempt)")
                    return@launch
                }

                if (attempt >= MAX_START_ATTEMPTS) {
                    Logger.w(
                        TAG,
                        "Échec du démarrage du serveur de sync après $attempt tentatives : état laissé en $settled",
                    )
                    return@launch
                }

                Logger.d(TAG, "Démarrage échoué (état=$settled) : nouvelle tentative dans ${backoff}ms")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /**
     * Attend que l'état quitte [State.Starting] (vers Listening ou Error), ou jusqu'au timeout.
     * Retourne l'état observé une fois stabilisé. Respecte l'annulation de la coroutine.
     */
    private suspend fun awaitSettled(): LanSyncServer.State {
        var waited = 0L
        var current = server.state.value
        while (current is LanSyncServer.State.Starting && waited < STARTING_SETTLE_TIMEOUT_MS) {
            delay(STARTING_POLL_MS)
            waited += STARTING_POLL_MS
            current = server.state.value
        }
        return current
    }
}
