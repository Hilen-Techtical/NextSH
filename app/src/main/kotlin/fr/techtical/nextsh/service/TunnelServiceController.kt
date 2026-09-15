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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pilote [TunnelForegroundService] depuis la couche UI.
 *
 * Existe pour deux raisons :
 *  - rendre le démarrage idempotent, un seul service pour N tunnels ;
 *  - isoler l'appel système, testable sur la JVM sans framework Android.
 */
interface TunnelServiceController {

    /**
     * Garantit que le service au premier plan tourne.
     *
     * À appeler uniquement depuis un contexte visible par l'utilisateur : sur
     * Android 12+, démarrer un service au premier plan alors que l'app est en
     * arrière-plan lève une exception. Tous les démarrages de tunnel de NextSH
     * partent d'une action utilisateur à l'écran, ce qui est autorisé.
     *
     * @param tunnelLabel libellé affiché tant qu'un seul tunnel tourne.
     * @return false si le système a refusé le démarrage. L'appelant doit alors
     *         prévenir l'utilisateur et ne pas laisser croire que le tunnel
     *         survivra en arrière-plan.
     */
    fun ensureRunning(tunnelLabel: String?): Boolean
}

@Singleton
class AndroidTunnelServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : TunnelServiceController {

    override fun ensureRunning(tunnelLabel: String?): Boolean {
        val intent = Intent(context, TunnelForegroundService::class.java).apply {
            putExtra(TunnelForegroundService.EXTRA_TUNNEL_LABEL, tunnelLabel)
        }
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) n'existe pas
            // sur les API plus anciennes : on attrape large plutôt que de
            // référencer une classe absente à l'exécution sur API 29 et 30.
            Timber.w("Démarrage du service de tunnels refusé : ${e.message}")
            false
        }
    }

}

@Module
@InstallIn(SingletonComponent::class)
abstract class TunnelServiceModule {
    @Binds
    @Singleton
    abstract fun bindTunnelServiceController(
        impl: AndroidTunnelServiceController,
    ): TunnelServiceController
}
