// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2.winwebauthn

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import fr.techtical.nextsh.shared.util.Logger

private const val TAG = "WinWebAuthn"

/**
 * Interface JNA vers `webauthn.dll` (livré avec Windows 10 1903+ / Windows 11).
 *
 * Les noms de méthodes correspondent EXACTEMENT aux exports C de la DLL.
 * JNA fait le mapping automatique : méthode Kotlin → symbol exporté par nom.
 *
 * ## Versions API WebAuthn Windows
 * - Version 1 : Windows 10 1903 (18362) → ECDSA P-256 (-7) uniquement
 * - Version 2 : Windows 10 2004 (19041) → ajout U2F app ID, cancellation
 * - Version 3 : Windows 10 21H2 (19044) / Windows 11 21H2 → **Ed25519 (-8) supporté**
 * - Version 4 : Windows 11 22H2 (22621) → pAllowCredentialList dans GetAssertionOptions
 *
 * Référence Microsoft : https://learn.microsoft.com/en-us/windows/win32/api/webauthn/
 *
 * ## HWND
 * On passe `Pointer.NULL` comme HWND parent. Windows centre alors le dialog
 * Hello/WebAuthn sur l'écran principal. Une alternative serait de récupérer
 * le HWND de la fenêtre AWT active via `sun.awt.windows.WFramePeer`, mais
 * cela crée une dépendance sur les internals JDK : non stable, non nécessaire.
 *
 * ## Thread safety
 * Les fonctions MakeCredential/GetAssertion sont synchrones bloquantes.
 * Elles DOIVENT être appelées sur `Dispatchers.IO` (jamais sur le thread UI Compose).
 */
interface WebAuthnLibrary : Library {

    /**
     * Retourne le numéro de version de l'API WebAuthn Windows.
     * - Retourne 0 si webauthn.dll n'est pas disponible ou si Windows est trop ancien.
     * - Version >= 3 requise pour Ed25519 (alg -8).
     */
    fun WebAuthNGetApiVersionNumber(): Int

    /**
     * Enrôle un nouvel authenticateur externe (MakeCredential).
     *
     * @param hWnd          HWND de la fenêtre parent (NULL = centré sur l'écran principal)
     * @param pRp           Infos RP (id + name)
     * @param pUser         Infos utilisateur (id bytes + name)
     * @param pPubKeyCredParams Paramètres COSE (algorithme)
     * @param pClientData   Client data (bytes + hash algorithm)
     * @param pOptions      Options MakeCredential (timeout, attachment, UV, annulation…)
     * @param ppAttestation OUT : pointeur vers le struct alloué par Windows
     * @return HRESULT (S_OK = 0, négatif = erreur)
     */
    fun WebAuthNAuthenticatorMakeCredential(
        hWnd: Pointer?,
        pRp: WinWebAuthnRpEntityInfo,
        pUser: WinWebAuthnUserEntityInfo,
        pPubKeyCredParams: WinWebAuthnCoseCredentialParameters,
        pClientData: WinWebAuthnClientData,
        pOptions: WinWebAuthnMakeCredentialOptions,
        ppAttestation: PointerByReference,
    ): Int

    /**
     * Effectue une assertion (GetAssertion / signe un challenge).
     *
     * @param hWnd        HWND de la fenêtre parent
     * @param pwszRpId    RP-ID (ex. "ssh:")
     * @param pClientData Client data (bytes SSH challenge + "SHA-256")
     * @param pOptions    Options GetAssertion (credential allow-list, timeout, annulation…)
     * @param ppAssertion OUT : pointeur vers le struct alloué par Windows
     * @return HRESULT
     */
    fun WebAuthNAuthenticatorGetAssertion(
        hWnd: Pointer?,
        pwszRpId: WString,
        pClientData: WinWebAuthnClientData,
        pOptions: WinWebAuthnGetAssertionOptions,
        ppAssertion: PointerByReference,
    ): Int

    /**
     * Libère la mémoire allouée par [WebAuthNAuthenticatorMakeCredential].
     * DOIT être appelé après usage du résultat MakeCredential.
     */
    fun WebAuthNFreeCredentialAttestation(pAttestation: Pointer)

    /**
     * Libère la mémoire allouée par [WebAuthNAuthenticatorGetAssertion].
     * DOIT être appelé après usage du résultat GetAssertion.
     */
    fun WebAuthNFreeAssertion(pAssertion: Pointer)

    /**
     * Génère un identifiant d'annulation unique pour l'opération courante.
     * Stocker ce GUID et le passer dans les options permet d'annuler via
     * [WebAuthNCancelCurrentOperation].
     *
     * @param pCancellationId OUT : GUID rempli par Windows (16 bytes)
     * @return HRESULT
     */
    fun WebAuthNGetCancellationId(pCancellationId: Pointer): Int

    /**
     * Annule l'opération WebAuthn en cours identifiée par [pCancellationId].
     *
     * @param pCancellationId GUID obtenu via [WebAuthNGetCancellationId]
     * @return HRESULT
     */
    fun WebAuthNCancelCurrentOperation(pCancellationId: Pointer): Int

    /**
     * Retourne le nom lisible d'une HRESULT WebAuthn.
     *
     * Exemples :
     * - S_OK (0) → "Success"
     * - NTE_NOT_SUPPORTED (-2146893783) → "NotSupported"
     * - NTE_USER_CANCELLED (-2146893315) → "UserCancelled" (ou similaire)
     *
     * @param hr HRESULT WebAuthn
     * @return Pointeur vers une string wide constante (non à libérer)
     */
    fun WebAuthNGetErrorName(hr: Int): WString?

    companion object {
        /** DLL name : `Native.load` la cherche dans System32 automatiquement. */
        const val DLL_NAME = "webauthn"

        /**
         * Version API minimale requise pour Ed25519 (COSE alg -8).
         * Windows 10 21H2 (build 19044) ou Windows 11 21H2.
         */
        const val MIN_VERSION_ED25519 = 3

        /**
         * HRESULT S_OK : opération réussie.
         */
        const val S_OK = 0

        /**
         * Instance singleton de la library : lazy, nullé si la DLL n'est pas disponible.
         *
         * Utiliser [loadOrNull] pour obtenir une instance avec gestion d'erreur.
         */
        val INSTANCE: WebAuthnLibrary? by lazy { loadOrNull() }

        /**
         * Tente de charger webauthn.dll via JNA.
         *
         * Retourne null si :
         * - La DLL est absente (Windows < 1903)
         * - JNA n'est pas sur le classpath
         * - L'OS n'est pas Windows
         * - Toute autre exception de chargement natif
         */
        fun loadOrNull(): WebAuthnLibrary? {
            val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
            if (!isWindows) {
                Logger.d(TAG, "loadOrNull: non-Windows OS: WebAuthn API not available")
                return null
            }
            return runCatching {
                Native.load(DLL_NAME, WebAuthnLibrary::class.java) as WebAuthnLibrary
            }.onFailure { e ->
                Logger.w(TAG, "loadOrNull: failed to load $DLL_NAME.dll, ${e.javaClass.simpleName}: ${e.message}")
            }.getOrNull()
        }
    }
}
