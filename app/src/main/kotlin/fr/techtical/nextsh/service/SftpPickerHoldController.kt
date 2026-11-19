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
import fr.techtical.nextsh.shared.util.errorClass
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pilote le maintien au premier plan de [SftpTransferService] pendant que l'utilisateur choisit un
 * fichier dans le sélecteur du système (voir [PickerHolds] pour le pourquoi).
 *
 * Même rôle que [TerminalServiceController] : isoler l'appel système, que l'explorateur peut ainsi
 * piloter sans framework Android dans ses tests, et absorber le refus du système.
 */
interface SftpPickerHoldController {

    /**
     * Garde l'app au premier plan, avec la session SSH [sessionId], jusqu'à [end] ou au délai de
     * garde du service. À appeler juste avant d'ouvrir le sélecteur, donc depuis l'écran : sur
     * Android 12+, démarrer un service au premier plan depuis l'arrière-plan lève une exception.
     *
     * @return l'identifiant du maintien, à rendre à [end], ou null si le système a refusé le
     *         démarrage du service (app qui n'est plus au premier plan, par exemple). Le sélecteur
     *         s'ouvre quand même : sans maintien, on retrouve le comportement d'avant.
     *         Un identifiant n'est pas la preuve d'un maintien : le refus du quota `dataSync` de 6 h
     *         épuisé n'arrive qu'après, dans le service (son `startForeground` lève), et l'appelant ne
     *         le voit pas. Le service ne prend alors aucun maintien, et [end] d'un maintien qu'il ne
     *         connaît pas ne fait rien : le sélecteur s'ouvre là aussi sans maintien.
     */
    fun begin(sessionId: String): String?

    /**
     * Rend le maintien [holdId], au retour du sélecteur, résultat ou annulation. Sans effet pour un
     * maintien déjà rendu ou expiré. Le service s'arrête alors s'il n'a plus rien à servir.
     */
    fun end(holdId: String)
}

@Singleton
class AndroidSftpPickerHoldController @Inject constructor(
    @ApplicationContext private val context: Context,
) : SftpPickerHoldController {

    override fun begin(sessionId: String): String? {
        val holdId = UUID.randomUUID().toString()
        val intent = Intent(context, SftpTransferService::class.java).apply {
            action = SftpTransferService.ACTION_HOLD_FOR_PICKER
            putExtra(SftpTransferService.EXTRA_HOLD_ID, holdId)
            putExtra(SftpTransferService.EXTRA_SESSION_ID, sessionId)
        }
        return try {
            ContextCompat.startForegroundService(context, intent)
            holdId
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) n'existe pas sur les API plus
            // anciennes : on attrape large. Texte fixe, le message de l'exception n'est pas journalisé.
            Timber.w("SftpPickerHoldController: hold refused (${errorClass(e)})")
            null
        }
    }

    override fun end(holdId: String) {
        val intent = Intent(context, SftpTransferService::class.java).apply {
            action = SftpTransferService.ACTION_RELEASE_PICKER_HOLD
            putExtra(SftpTransferService.EXTRA_HOLD_ID, holdId)
        }
        try {
            // startService et non startForegroundService : le service tourne déjà, et cette commande
            // ne passe jamais au premier plan (comme l'annulation d'un transfert).
            context.startService(intent)
        } catch (e: Exception) {
            // Refusé (app en arrière-plan) : le délai de garde du service rendra le maintien.
            Timber.w("SftpPickerHoldController: release not delivered (${errorClass(e)})")
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SftpPickerHoldModule {
    @Binds
    @Singleton
    abstract fun bindSftpPickerHoldController(
        impl: AndroidSftpPickerHoldController,
    ): SftpPickerHoldController
}
