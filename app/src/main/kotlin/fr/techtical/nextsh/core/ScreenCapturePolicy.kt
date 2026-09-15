// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core

import android.view.Window
import android.view.WindowManager
import fr.techtical.nextsh.BuildConfig
import timber.log.Timber

/**
 * Protection du contenu d'écran : captures, enregistrement, miroir.
 *
 * `FLAG_SECURE` est posé sur la fenêtre de toute l'application par
 * `MainActivity`, et redemandé par les écrans sensibles. Il bloque aussi tout
 * enregistrement d'écran, scrcpy compris : impossible de filmer l'application
 * pour une démonstration.
 *
 * La seule dérogation se décide à la compilation. Une build faite avec
 * `-PscreenCapture=true` porte `BuildConfig.ALLOW_SCREEN_CAPTURE = true`, un
 * suffixe de version `-capture` visible dans les réglages, et une ligne de
 * journal au démarrage. Aucun réglage à l'exécution ne lève la protection :
 * un utilisateur ne doit pas pouvoir la désactiver, et une build de
 * démonstration ne doit pas pouvoir passer pour une build publique.
 */
object ScreenCapturePolicy {

    /** Vrai uniquement dans une build de démonstration (`-PscreenCapture=true`). */
    val captureAllowed: Boolean = BuildConfig.ALLOW_SCREEN_CAPTURE

    /**
     * Pose `FLAG_SECURE` sur [window], sauf dans une build de démonstration.
     * Seul point d'entrée : aucun écran ne pose le drapeau directement.
     */
    fun protect(window: Window) {
        if (captureAllowed) {
            Timber.w("Screen capture allowed: demo build (-PscreenCapture=true), FLAG_SECURE not set")
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
