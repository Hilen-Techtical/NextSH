// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.techtical.nextsh.ui.sessions.SessionTab
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pilote [TerminalSessionService] depuis la couche UI.
 *
 * Existe pour deux raisons :
 *  - rendre le démarrage idempotent, un seul service pour N onglets ;
 *  - isoler l'appel système, testable sur la JVM sans framework Android.
 */
interface TerminalServiceController {

    /**
     * Garantit que le service au premier plan tourne. À appeler une fois l'onglet ajouté au
     * `TerminalTabStore` : le service décide de s'arrêter d'après les onglets connectés.
     *
     * À appeler depuis un contexte visible par l'utilisateur : sur Android 12+, démarrer un service
     * au premier plan alors que l'app est en arrière-plan lève une exception. Un onglet s'ouvre sur
     * une action de l'utilisateur à l'écran, ce qui est autorisé, mais la connexion qui le précède
     * peut durer plus longtemps que le séjour de l'utilisateur dans l'app : le démarrage est alors
     * refusé, et [resumeTerminalService] le redemande au retour à l'écran.
     *
     * @return false si le système a refusé le démarrage. L'appelant doit alors prévenir
     *         l'utilisateur, si l'app est visible, et ne pas laisser croire que la session survivra
     *         en arrière-plan.
     */
    fun ensureRunning(): Boolean
}

/**
 * Redemande le service au retour de l'app à l'écran (`MainActivity.onStart`), s'il y a de quoi
 * servir : au moins un onglet connecté parmi [tabs].
 *
 * L'ouverture d'un onglet ne demande le service qu'une fois, après des suspensions (connexion,
 * touche FIDO2, confirmation de la clé de l'hôte). Si l'utilisateur a quitté l'app entre-temps, le
 * système refuse le démarrage d'un service au premier plan depuis l'arrière-plan, et sans cette
 * relance la session mourrait au passage suivant en arrière-plan. À l'écran, le démarrage est
 * permis. Sans onglet connecté il ne fait rien (le service s'arrêterait de lui-même), et il est
 * sans effet sur un service qui tourne déjà.
 *
 * Un refus ne s'annonce pas à l'utilisateur ici, il n'a rien demandé : un avertissement seulement,
 * sans donnée privée.
 */
fun resumeTerminalService(tabs: List<SessionTab>, controller: TerminalServiceController) {
    if (connectedTabs(tabs).isEmpty()) return
    if (!controller.ensureRunning()) {
        Timber.w("Service des sessions terminal refusé au retour à l'écran")
    }
}

@Singleton
class AndroidTerminalServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : TerminalServiceController {

    override fun ensureRunning(): Boolean {
        val intent = Intent(context, TerminalSessionService::class.java)
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) n'existe pas
            // sur les API plus anciennes : on attrape large plutôt que de
            // référencer une classe absente à l'exécution sur API 29 et 30.
            Timber.w("Démarrage du service de sessions refusé (${e::class.java.simpleName})")
            false
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TerminalServiceModule {
    @Binds
    @Singleton
    abstract fun bindTerminalServiceController(
        impl: AndroidTerminalServiceController,
    ): TerminalServiceController
}
