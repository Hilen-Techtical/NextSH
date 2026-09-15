// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2.winwebauthn

import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Enroller
import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Signer
import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2UiState
import fr.techtical.nextsh.desktop.core.auth.fido2.YubiKitFido2Enroller
import fr.techtical.nextsh.desktop.core.auth.fido2.YubiKitFido2Signer
import fr.techtical.nextsh.desktop.core.auth.fido2.YubiKitFidoManager
import fr.techtical.nextsh.shared.util.Logger

private const val TAG = "Fido2Dispatcher"

/**
 * Dispatcher FIDO2 : sélectionne automatiquement le bon provider selon l'OS et
 * la disponibilité de Windows WebAuthn API.
 *
 * ## Logique de sélection
 * ```
 * Windows + WebAuthn API version >= 1 ?
 *   → WindowsWebAuthnProvider (natif, dialog Hello)
 * Sinon :
 *   → YubiKitFidoManager (PC/SC + HID)
 * ```
 *
 * ## Motif "Windows WebAuthn skips YubiKeyTouchDialog"
 * Sur Windows avec WebAuthn API actif, [Fido2UiState] n'est PAS connecté au
 * [WindowsWebAuthnProvider] via les callbacks onTouch* : le dialog Hello natif
 * suffit. À la place, l'UI Compose peut observer [isUsingWindowsWebAuthn] pour
 * adapter son affichage (masquer le champ PIN, changer le texte du bouton).
 *
 * ## Fallback YubiKit intact
 * Sur Linux/macOS, [YubiKitFidoManager] est instancié normalement. Le code
 * YubiKit n'est pas modifié : [Fido2ProviderDispatcher] est le seul point
 * d'entrée à changer.
 */
class Fido2ProviderDispatcher(
    private val fido2UiState: Fido2UiState,
) {
    /**
     * true si Windows WebAuthn API est disponible ET activée sur ce système.
     * false sur Linux/macOS ou Windows sans la DLL.
     */
    val isUsingWindowsWebAuthn: Boolean by lazy { resolveProvider() }

    /** Provider enrôlement (Fido2Enroller). */
    val enroller: Fido2Enroller by lazy { createEnroller() }

    /** Provider signature (Fido2Signer). */
    val signer: Fido2Signer by lazy { createSigner() }

    /** Instance [WindowsWebAuthnProvider] si sélectionné, null sinon. */
    private var windowsProvider: WindowsWebAuthnProvider? = null

    /** Instance [YubiKitFidoManager] si sélectionné, null sinon. */
    private var yubiKitManager: YubiKitFidoManager? = null

    private fun resolveProvider(): Boolean {
        if (!WindowsWebAuthnProvider.isPlatformSupported) {
            Logger.d(TAG, "resolveProvider: non-Windows platform → YubiKit")
            return false
        }

        val provider = WindowsWebAuthnProvider(
            onTouchRequired = { label ->
                // Sur Windows WebAuthn, le dialog Hello est affiché par Windows.
                // On émet quand même un état "waiting" minimal dans notre UI pour
                // que l'utilisateur voie un feedback côté Compose.
                // Note : on n'appelle PAS fido2UiState.start() ici car ça afficherait
                // le YubiKeyTouchDialog Compose par-dessus le dialog Hello natif.
                Logger.d(TAG, "WindowsWebAuthn: touch required for $label (native Windows dialog active)")
            },
            onTouchDone = {
                Logger.d(TAG, "WindowsWebAuthn: touch done")
            },
        )

        return if (provider.isAvailable()) {
            val version = provider.apiVersion()
            Logger.d(TAG, "resolveProvider: Windows WebAuthn API version $version → using WindowsWebAuthnProvider")
            windowsProvider = provider
            true
        } else {
            Logger.d(TAG, "resolveProvider: Windows but WebAuthn API not available → YubiKit fallback")
            false
        }
    }

    private fun createEnroller(): Fido2Enroller {
        return if (isUsingWindowsWebAuthn) {
            requireNotNull(windowsProvider) { "windowsProvider must be initialized before enroller" }
        } else {
            YubiKitFido2Enroller(getOrCreateYubiKitManager())
        }
    }

    private fun createSigner(): Fido2Signer {
        return if (isUsingWindowsWebAuthn) {
            requireNotNull(windowsProvider) { "windowsProvider must be initialized before signer" }
        } else {
            YubiKitFido2Signer(getOrCreateYubiKitManager())
        }
    }

    private fun getOrCreateYubiKitManager(): YubiKitFidoManager {
        return yubiKitManager ?: YubiKitFidoManager(
            onTouchRequired = { label -> fido2UiState.start(label) },
            onTouchDone = { fido2UiState.stop() },
        ).also { yubiKitManager = it }
    }

    /**
     * Accès direct au [YubiKitFidoManager] sous-jacent si YubiKit est le provider actif.
     * Retourne null si Windows WebAuthn est actif (pas de YubiKit dans ce cas).
     *
     * Réservé aux usages qui nécessitent spécifiquement [YubiKitFidoManager] (ex.
     * listDevices, diagnostics). Pour la signature SSH, utiliser [signer] à la place.
     */
    fun yubiKitManagerOrNull(): YubiKitFidoManager? {
        return if (isUsingWindowsWebAuthn) null else getOrCreateYubiKitManager()
    }
}
