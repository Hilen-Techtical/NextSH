// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton d'état UI partagé entre [YubiKitFidoManager] (qui produit les
 * événements touch) et l'UI Compose (qui observe et affiche le dialog).
 *
 * Le bridge est intentionnellement simple : [YubiKitFidoManager] appelle
 * [start] avant d'attendre le touch YubiKey, et [stop] dans le `finally`
 * après réception ou timeout. L'UI observe [touchInProgress] et affiche
 * [YubiKeyTouchDialog] tant que la valeur est non-null.
 *
 * Annulation : le bouton "Annuler" du dialog appelle [TouchRequest.onCancel],
 * qui est actuellement un no-op : CTAP2 GetAssertion ne peut pas être annulé
 * en cours de route sans support YubiKit supplémentaire. L'opération se
 * terminera de toute façon par un timeout ou une réponse.
 * TODO Phase 3+ : explorer l'annulation via commandState CTAP2 si YubiKit l'expose.
 */
class Fido2UiState {

    data class TouchRequest(
        val deviceLabel: String,
        /** Actuellement no-op : voir KDoc classe pour les raisons. */
        val onCancel: () -> Unit,
    )

    private val _touchInProgress = MutableStateFlow<TouchRequest?>(null)
    val touchInProgress: StateFlow<TouchRequest?> = _touchInProgress.asStateFlow()

    fun start(deviceLabel: String, onCancel: () -> Unit = {}) {
        _touchInProgress.value = TouchRequest(deviceLabel = deviceLabel, onCancel = onCancel)
    }

    fun stop() {
        _touchInProgress.value = null
    }
}
