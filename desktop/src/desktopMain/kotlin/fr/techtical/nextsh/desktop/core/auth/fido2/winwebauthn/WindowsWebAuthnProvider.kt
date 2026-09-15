// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2.winwebauthn

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.PointerByReference
import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Enroller
import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Signer
import fr.techtical.nextsh.desktop.core.auth.fido2.YubiKitFidoManager
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Arrays

private const val TAG = "WinWebAuthn"

/**
 * Provider FIDO2 Windows utilisant Windows WebAuthn API native (`webauthn.dll`).
 *
 * ## Pourquoi cette implémentation
 * Sur Windows 10 1903+, le service Windows Hello / WebAuthn revendique en accès
 * exclusif l'interface HID FIDO (usage page 0xF1D0) des YubiKey. Les applications
 * non-élevées ne peuvent pas ouvrir le transport CTAP2 directement.
 * La solution stable est d'utiliser l'API Windows WebAuthn qui délègue l'accès
 * à la YubiKey au service Windows WebAuthn avec son propre dialog natif.
 *
 * ## Algorithme supporté
 * Ed25519 (COSE alg -8) : disponible à partir de WebAuthn API **version 3**
 * (Windows 10 21H2 / Windows 11). Sur version < 3, l'enrôlement est refusé
 * avec un message explicite.
 *
 * ## HWND
 * On passe `Pointer.NULL` : Windows centre le dialog sur l'écran principal.
 * Le callback [onTouchRequired] permet à l'UI de montrer un message d'attente
 * côté Compose pendant que le dialog Hello est visible.
 *
 * ## Gestion mémoire
 * Les structs résultat (`WEBAUTHN_CREDENTIAL_ATTESTATION`, `WEBAUTHN_ASSERTION`)
 * sont alloués par Windows et DOIVENT être libérés via les fonctions Free*
 * correspondantes dans les blocs `finally`.
 *
 * @param onTouchRequired Callback appelé AVANT d'appeler MakeCredential/GetAssertion
 *                        (permet d'afficher "En attente Windows Hello…" dans notre UI).
 * @param onTouchDone     Callback appelé APRÈS retour (succès ou erreur).
 * @param lib             Instance de la library JNA : injectée pour tests.
 */
class WindowsWebAuthnProvider(
    private val onTouchRequired: (deviceLabel: String) -> Unit = {},
    private val onTouchDone: () -> Unit = {},
    private val lib: WebAuthnLibrary? = WebAuthnLibrary.INSTANCE,
) : Fido2Enroller, Fido2Signer {

    // ── Public API : disponibilité ────────────────────────────────────────────

    /**
     * Retourne true si Windows WebAuthn API est disponible et fonctionnelle
     * sur le système courant.
     *
     * Vérifie :
     * 1. La DLL est chargée (Windows 10 1903+)
     * 2. `WebAuthNGetApiVersionNumber()` retourne > 0
     */
    fun isAvailable(): Boolean {
        val localLib = lib ?: return false
        return runCatching {
            localLib.WebAuthNGetApiVersionNumber() > 0
        }.getOrDefault(false)
    }

    /**
     * Retourne la version de l'API WebAuthn Windows (1, 2, 3 ou 4).
     * Retourne 0 si non disponible.
     */
    fun apiVersion(): Int {
        val localLib = lib ?: return 0
        return runCatching {
            localLib.WebAuthNGetApiVersionNumber()
        }.getOrDefault(0)
    }

    // ── Fido2Enroller: detectDevice ─────────────────────────────────────────

    /**
     * Sur Windows WebAuthn API, il n'y a pas de détection de device à l'avance.
     * Windows affiche son propre dialog qui permet à l'utilisateur de choisir
     * son authenticateur. On retourne une détection synthétique pour satisfaire
     * l'interface.
     *
     * PIN : Windows Hello gère le PIN de manière native : on ne demande pas de PIN
     * dans notre UI (pinConfigured = false pour éviter le champ PIN côté Compose).
     */
    override suspend fun detectDevice(): Fido2Enroller.DeviceDetection {
        checkAvailableOrThrow()
        return Fido2Enroller.DeviceDetection(
            deviceLabel = "Windows WebAuthn",
            pinConfigured = false,  // Windows Hello gère le PIN nativement
        )
    }

    // ── Fido2Enroller: makeCredential ───────────────────────────────────────

    /**
     * Enrôle un nouvel authenticateur externe via Windows WebAuthn MakeCredential.
     *
     * Le paramètre `pin` est ignoré sur Windows : Windows Hello gère le PIN nativement.
     *
     * @throws YubiKitFidoManager.FidoError.CtapError si WebAuthn API version < 3
     * @throws YubiKitFidoManager.FidoError sur toute autre erreur
     */
    override suspend fun makeCredential(
        rpId: String,
        rpName: String,
        userName: String,
        userDisplayName: String,
        pin: CharArray?,
        timeoutMs: Long,
    ): Fido2Enroller.EnrollResult {
        val localLib = checkAvailableOrThrow()

        val version = localLib.WebAuthNGetApiVersionNumber()
        // ECDSA P-256 (alg -7) est disponible dès WebAuthn API version 1.
        // Ed25519 (alg -8) requiert version 3+.
        // On propose les deux : Windows utilisera le premier supporté par l'authenticateur.
        // Pas de rejet basé sur la version : Windows retournera une erreur explicite si besoin.

        // PIN ignoré sur Windows : effacement préventif par politesse mémoire
        if (pin != null) Arrays.fill(pin, ' ')

        return withContext(Dispatchers.IO) {
            onTouchRequired("Windows WebAuthn")
            var attestationPtr: Pointer? = null
            try {
                // ── 1. Prépare les structs ────────────────────────────────────

                val rp = WinWebAuthnRpEntityInfo().apply {
                    dwVersion = 1
                    pwszId = WString(rpId)
                    pwszName = WString(rpName)
                }
                rp.write()

                // userId = 16 bytes random (jamais secret : juste un identifiant)
                val userIdBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val userIdMem = Memory(userIdBytes.size.toLong())
                userIdMem.write(0, userIdBytes, 0, userIdBytes.size)

                val user = WinWebAuthnUserEntityInfo().apply {
                    dwVersion = 1
                    cbId = userIdBytes.size
                    pbId = userIdMem
                    pwszName = WString(userName)
                    pwszIcon = null
                    pwszDisplayName = WString(userDisplayName)
                }
                user.write()

                // Tableau de 2 params COSE contigu en mémoire (JNA Structure.toArray) :
                //   [0] Ed25519 alg -8 (préféré : version 3+)
                //   [1] ECDSA P-256 alg -7 (fallback : version 1+)
                // Windows choisit le premier algo supporté par l'authenticateur.
                // Structure.toArray alloue N structs adjacentes : l'ABI C attend exactement cela.
                val coseParam0 = WinWebAuthnCoseCredentialParameter()
                @Suppress("UNCHECKED_CAST")
                val coseParamArray = coseParam0.toArray(2) as Array<WinWebAuthnCoseCredentialParameter>
                with(coseParamArray[0]) {
                    dwVersion = 1
                    pwszCredentialType = WString("public-key")
                    lAlg = WinWebAuthnCoseCredentialParameter.COSE_ALG_EDDSA
                    write()
                }
                with(coseParamArray[1]) {
                    dwVersion = 1
                    pwszCredentialType = WString("public-key")
                    lAlg = WinWebAuthnCoseCredentialParameter.COSE_ALG_ECDSA_P256
                    write()
                }

                val coseParams = WinWebAuthnCoseCredentialParameters().apply {
                    cCredentialParameters = 2
                    pCredentialParameters = coseParamArray[0].pointer
                }
                coseParams.write()

                // clientDataJSON synthétique pour enrôlement (32 bytes random : SSH n'utilise pas l'attestation)
                val challengeBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val clientDataMem = Memory(challengeBytes.size.toLong())
                clientDataMem.write(0, challengeBytes, 0, challengeBytes.size)

                val clientData = WinWebAuthnClientData().apply {
                    dwVersion = 1
                    cbClientDataJSON = challengeBytes.size
                    pbClientDataJSON = clientDataMem
                    pwszHashAlgId = WString("SHA-256")
                }
                clientData.write()

                // Options MakeCredential
                // dwVersion = 1 : SSH n'a besoin d'aucune feature v2+.
                // Utiliser VERSION_1 = 1 comme libfido2 (implémentation de référence) :
                // "opt->dwVersion = WEBAUTHN_AUTHENTICATOR_MAKE_CREDENTIAL_OPTIONS_VERSION_1"
                // Tous les champs v2-v9 sont présents dans la struct JNA et initialisés à 0/null.
                //
                // dwAuthenticatorAttachment = ANY (0) : laisse Windows choisir entre
                //   Windows Hello (platform) et YubiKey externe (cross-platform).
                //   Forcer CROSS_PLATFORM (2) peut causer NTE_INVALID_PARAMETER si
                //   aucun authenticateur externe n'est détecté au moment de l'appel.
                //
                // dwUserVerificationRequirement = ANY (0) : laisse l'authenticateur décider.
                //
                // dwAttestationConveyancePreference = DIRECT (3), miroir exact de libfido2 :
                //   "opt->dwAttestationConveyancePreference =
                //       WEBAUTHN_ATTESTATION_CONVEYANCE_PREFERENCE_DIRECT"
                //   ANY (0) peut causer NTE_INVALID_PARAMETER sur certains authenticateurs.
                val options = WinWebAuthnMakeCredentialOptions().apply {
                    dwVersion = 1  // VERSION_1 : suffit pour SSH SK (libfido2 fait de même)
                    dwTimeoutMilliseconds = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    // CredentialList et Extensions : cCredentials=0 + pCredentials=null déjà initialisés
                    dwAuthenticatorAttachment = WinWebAuthnMakeCredentialOptions.ATTACHMENT_ANY  // 0
                    bRequireResidentKey = 0  // BOOL false : SSH SK n'est pas resident
                    dwUserVerificationRequirement = WinWebAuthnMakeCredentialOptions.UV_ANY  // 0
                    dwAttestationConveyancePreference = WinWebAuthnMakeCredentialOptions.ATTESTATION_DIRECT  // 3
                    dwFlags = 0
                    // Champs v2-v9 restent à 0/null (init JVM) : Windows les ignore avec dwVersion=1
                }
                options.write()

                // ── 2. Appel Windows WebAuthn, log diagnostique AVANT ────────
                // Récupère le HWND de la fenêtre active, exactement comme libfido2 :
                // "if ((w = GetForegroundWindow()) == NULL) { w = GetTopWindow(NULL); }"
                // Nécessaire pour que Windows affiche le dialog WebAuthn de façon modale
                // correctement rattaché à notre fenêtre (NULL peut causer NTE_INVALID_PARAMETER).
                val hWnd = getForegroundHwnd()
                Logger.d(TAG, "makeCredential: hWnd=${hWnd}")

                Logger.d(TAG, buildString {
                    append("makeCredential: PRE-CALL diagnostics\n")
                    append("  hWnd=$hWnd\n")
                    append("  rp.dwVersion=${rp.dwVersion} id='${rp.pwszId}' name='${rp.pwszName}'\n")
                    append("  user.dwVersion=${user.dwVersion} cbId=${user.cbId} name='${user.pwszName}' displayName='${user.pwszDisplayName}'\n")
                    append("  coseParams.cCredentialParameters=${coseParams.cCredentialParameters}\n")
                    append("  coseParams.pCredentialParameters=${coseParams.pCredentialParameters}\n")
                    append("  coseParam[0].dwVersion=${coseParamArray[0].dwVersion} type='${coseParamArray[0].pwszCredentialType}' alg=${coseParamArray[0].lAlg}\n")
                    append("  coseParam[1].dwVersion=${coseParamArray[1].dwVersion} type='${coseParamArray[1].pwszCredentialType}' alg=${coseParamArray[1].lAlg}\n")
                    append("  clientData.dwVersion=${clientData.dwVersion} cbClientDataJSON=${clientData.cbClientDataJSON} hashAlg='${clientData.pwszHashAlgId}'\n")
                    append("  options.dwVersion=${options.dwVersion} timeout=${options.dwTimeoutMilliseconds}\n")
                    append("  options.dwAuthenticatorAttachment=${options.dwAuthenticatorAttachment}\n")
                    append("  options.bRequireResidentKey=${options.bRequireResidentKey}\n")
                    append("  options.dwUserVerificationRequirement=${options.dwUserVerificationRequirement}\n")
                    append("  options.dwAttestationConveyancePreference=${options.dwAttestationConveyancePreference}\n")
                    append("  options.dwFlags=${options.dwFlags}\n")
                    append("  options.pCancellationId=${options.pCancellationId}\n")
                    append("  options.pExcludeCredentialList=${options.pExcludeCredentialList}")
                })

                val ppAttestation = PointerByReference()

                Logger.d(TAG, "makeCredential: calling WebAuthNAuthenticatorMakeCredential (rpId='$rpId', waiting for touch/Windows dialog...)")

                val hr = localLib.WebAuthNAuthenticatorMakeCredential(
                    /* hWnd */ hWnd,
                    rp, user, coseParams, clientData, options,
                    ppAttestation,
                )

                // Log HRESULT + nom lisible APRÈS l'appel
                val errorName = localLib.WebAuthNGetErrorName(hr)?.toString() ?: "unknown"
                Logger.d(TAG, "makeCredential: HRESULT=0x${hr.toUInt().toString(16).padStart(8, '0')} ($errorName)")

                if (hr != WebAuthnLibrary.S_OK) {
                    Logger.w(TAG, "makeCredential: FAILED HRESULT=0x${hr.toUInt().toString(16)} ($errorName)")
                    throw hr.toFidoError(errorName)
                }

                attestationPtr = ppAttestation.value
                Logger.d(TAG, "makeCredential: success: parsing attestation")

                // ── 3. Parse le résultat ──────────────────────────────────────
                val attestation = WinWebAuthnCredentialAttestation.fromPointer(attestationPtr)

                val credentialId = attestation.pbCredentialId
                    ?.getByteArray(0, attestation.cbCredentialId)
                    ?: throw YubiKitFidoManager.FidoError.TransportError(
                        IllegalStateException("WebAuthn MakeCredential: credential ID is null")
                    )

                val authData = attestation.pbAuthenticatorData
                    ?.getByteArray(0, attestation.cbAuthenticatorData)
                    ?: throw YubiKitFidoManager.FidoError.TransportError(
                        IllegalStateException("WebAuthn MakeCredential: authenticatorData is null")
                    )

                Logger.d(TAG, "makeCredential: credentialId.size=${credentialId.size}, authData.size=${authData.size}")

                // Extrait la clé publique (Ed25519 ou ECDSA P-256) depuis l'authenticatorData
                val (keyType, publicKey) = parseCosePubKey(authData)

                Logger.d(TAG, "makeCredential: keyType=$keyType, publicKey.size=${publicKey.size}")

                Fido2Enroller.EnrollResult(
                    credentialId = credentialId,
                    application = rpId,
                    publicKey = publicKey,
                    deviceLabel = "Windows WebAuthn",
                    keyType = keyType,
                )

            } catch (e: YubiKitFidoManager.FidoError) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "makeCredential: unexpected ${e.javaClass.simpleName}: ${e.message}")
                throw YubiKitFidoManager.FidoError.TransportError(e)
            } finally {
                attestationPtr?.let {
                    runCatching { localLib.WebAuthNFreeCredentialAttestation(it) }
                }
                onTouchDone()
            }
        }
    }

    // ── Fido2Signer: signChallenge ───────────────────────────────────────────

    /**
     * Signe un challenge SSH via Windows WebAuthn GetAssertion.
     *
     * ## Astuce SSH clientDataHash
     * WebAuthn API calcule SHA-256 sur `pbClientDataJSON` avec `pwszHashAlgId`.
     * Pour SSH, on passe directement les bytes du challenge dans `pbClientDataJSON`
     * avec `pwszHashAlgId = "SHA-256"`. Windows calcule SHA-256(challengeBytes)
     * et l'utilise comme clientDataHash dans l'assertion : exactement ce que
     * CTAP2 GetAssertion attend pour SSH.
     *
     * @param rpId         RP-ID de la clé SK (ex. "ssh:")
     * @param clientDataHash SHA-256 du challenge SSH (32 bytes)
     * @param credentialId  ID de la credential FIDO2
     * @param requireUv    Ignoré sur Windows : Hello gère l'UV nativement
     * @param timeoutMs    Timeout en ms
     */
    suspend fun signChallenge(
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
        requireUv: Boolean = false,
        timeoutMs: Long = 30_000,
    ): Fido2Signer.Assertion {
        val localLib = checkAvailableOrThrow()
        return withContext(Dispatchers.IO) {
            onTouchRequired("Windows WebAuthn")
            var assertionPtr: Pointer? = null
            try {
                // ── 1. clientData : les bytes du challenge SSH ────────────────
                // Windows hashera ces bytes en SHA-256 → clientDataHash SSH.
                val clientDataMem = Memory(clientDataHash.size.toLong())
                clientDataMem.write(0, clientDataHash, 0, clientDataHash.size)

                val clientData = WinWebAuthnClientData().apply {
                    dwVersion = 1
                    cbClientDataJSON = clientDataHash.size
                    pbClientDataJSON = clientDataMem
                    pwszHashAlgId = WString("SHA-256")
                }
                clientData.write()

                // ── 2. Allow-list : un seul credential ───────────────────────
                val credHolder = buildCredentialList(credentialId)

                // ── 3. Options GetAssertion ───────────────────────────────────
                // dwVersion = 4 : permet d'utiliser pAllowCredentialList (nécessaire pour SSH SK).
                // dwAuthenticatorAttachment = ANY (0) : même logique que MakeCredential.
                // dwUserVerificationRequirement = ANY (0) ou REQUIRED (1) selon requireUv.
                // Champs v5-v9 restent à 0/null.
                val options = WinWebAuthnGetAssertionOptions().apply {
                    dwVersion = 4  // VERSION_4 : pAllowCredentialList disponible
                    dwTimeoutMilliseconds = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    // CredentialList legacy : cCredentials=0 (on utilise pAllowCredentialList)
                    dwAuthenticatorAttachment = WinWebAuthnMakeCredentialOptions.ATTACHMENT_ANY  // 0
                    dwUserVerificationRequirement = if (requireUv) {
                        WinWebAuthnMakeCredentialOptions.UV_REQUIRED  // 1
                    } else {
                        WinWebAuthnMakeCredentialOptions.UV_ANY  // 0 : laisse l'authenticateur décider
                    }
                    dwFlags = 0
                    // pwszU2fAppId=null et pbU2fAppId=null : pas de legacy U2F
                    pCancellationId = null
                    pAllowCredentialList = credHolder.credList.pointer
                    // Champs v5-v9 : 0/null (init JVM)
                }
                options.write()

                // ── 4. Appel Windows WebAuthn, log diagnostique AVANT ────────
                // Récupère le HWND de la fenêtre active : miroir de libfido2
                val hWnd = getForegroundHwnd()

                Logger.d(TAG, buildString {
                    append("signChallenge: PRE-CALL diagnostics\n")
                    append("  hWnd=$hWnd\n")
                    append("  rpId='$rpId'\n")
                    append("  clientData.cbClientDataJSON=${clientData.cbClientDataJSON} hashAlg='${clientData.pwszHashAlgId}'\n")
                    append("  options.dwVersion=${options.dwVersion} timeout=${options.dwTimeoutMilliseconds}\n")
                    append("  options.dwAuthenticatorAttachment=${options.dwAuthenticatorAttachment}\n")
                    append("  options.dwUserVerificationRequirement=${options.dwUserVerificationRequirement}\n")
                    append("  options.pAllowCredentialList=${options.pAllowCredentialList}")
                })

                val ppAssertion = PointerByReference()

                Logger.d(TAG, "signChallenge: calling WebAuthNAuthenticatorGetAssertion (rpId='$rpId')")

                val hr = localLib.WebAuthNAuthenticatorGetAssertion(
                    /* hWnd */ hWnd,
                    WString(rpId),
                    clientData,
                    options,
                    ppAssertion,
                )

                val errorName = localLib.WebAuthNGetErrorName(hr)?.toString() ?: "unknown"
                Logger.d(TAG, "signChallenge: HRESULT=0x${hr.toUInt().toString(16).padStart(8, '0')} ($errorName)")

                if (hr != WebAuthnLibrary.S_OK) {
                    Logger.w(TAG, "signChallenge: FAILED HRESULT=0x${hr.toUInt().toString(16)} ($errorName)")
                    throw hr.toFidoError(errorName)
                }

                assertionPtr = ppAssertion.value
                Logger.d(TAG, "signChallenge: success: parsing assertion")

                // ── 5. Parse le résultat ──────────────────────────────────────
                val assertion = WinWebAuthnAssertion.fromPointer(assertionPtr)

                val authData = assertion.pbAuthenticatorData
                    ?.getByteArray(0, assertion.cbAuthenticatorData)
                    ?: throw YubiKitFidoManager.FidoError.TransportError(
                        IllegalStateException("WebAuthn GetAssertion: authenticatorData is null")
                    )

                val signature = assertion.pbSignature
                    ?.getByteArray(0, assertion.cbSignature)
                    ?: throw YubiKitFidoManager.FidoError.TransportError(
                        IllegalStateException("WebAuthn GetAssertion: signature is null")
                    )

                Logger.d(TAG, "signChallenge: authData.size=${authData.size}, signature.size=${signature.size}")

                // Note : credHolder maintient credEx + credIdMem + ptrArrayMem en vie jusqu'ici.
                // WebAuthNFreeAssertion est appelé dans finally : à ce stade credHolder peut être GCé.
                @Suppress("UNUSED_VARIABLE")
                val keepAlive = credHolder

                Fido2Signer.Assertion(
                    authData = authData,
                    signature = signature,
                )

            } catch (e: YubiKitFidoManager.FidoError) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "signChallenge: unexpected ${e.javaClass.simpleName}: ${e.message}")
                throw YubiKitFidoManager.FidoError.TransportError(e)
            } finally {
                assertionPtr?.let {
                    runCatching { localLib.WebAuthNFreeAssertion(it) }
                }
                onTouchDone()
            }
        }
    }

    // ── Fido2Signer interface (sans requireUv) ────────────────────────────────

    override suspend fun signChallenge(
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
    ): Fido2Signer.Assertion = signChallenge(
        rpId = rpId,
        clientDataHash = clientDataHash,
        credentialId = credentialId,
        requireUv = false,
    )

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Vérifie que la library est disponible.
     * @throws YubiKitFidoManager.FidoError.WindowsHidLocked si non disponible.
     */
    private fun checkAvailableOrThrow(): WebAuthnLibrary {
        return lib ?: throw YubiKitFidoManager.FidoError.TransportError(
            IllegalStateException("Windows WebAuthn API (webauthn.dll) not available on this system")
        )
    }

    /**
     * Construit une [WinWebAuthnCredentialList] avec un seul credential.
     *
     * ## Gestion mémoire : critique
     * Trois zones mémoire natives doivent rester en vie jusqu'après l'appel WebAuthn :
     *
     * 1. `credIdMem` : les bytes bruts du credentialId (pointé par `credEx.pbId`)
     * 2. `ptrArrayMem` : le tableau de 1 pointeur vers `credEx` (pointé par `credList.ppCredentials`)
     * 3. `credEx` (Structure JNA) : le struct WinWebAuthnCredentialEx lui-même
     *
     * Si l'une de ces Memory est GCée avant que Windows ne lise la struct, on lit
     * de la mémoire libérée → crash ou valeurs corrompues silencieuses.
     *
     * On retourne une `CredentialListHolder` qui maintient les trois références en vie.
     *
     * Structure en mémoire :
     * ```
     * credList.ppCredentials → [ptrArrayMem: ptr_to_credEx]
     *                                                  ↓
     *                                     [credEx struct en mémoire JNA]
     *                                          ↓ pbId
     *                                     [credIdMem: credentialId bytes]
     * ```
     *
     * @return [CredentialListHolder] : doit rester en scope jusqu'après l'appel Windows.
     */
    internal data class CredentialListHolder(
        val credList: WinWebAuthnCredentialList,
        val credEx: WinWebAuthnCredentialEx,
        val credIdMem: Memory,
        val ptrArrayMem: Memory,
    )

    private fun buildCredentialList(credentialId: ByteArray): CredentialListHolder {
        // 1. Copie les bytes du credential ID en mémoire native JNA (off-heap, survit au GC
        //    tant que la référence credIdMem est vivante)
        val credIdMem = Memory(credentialId.size.toLong())
        credIdMem.write(0, credentialId, 0, credentialId.size)

        // 2. Structure CredentialEx
        val credEx = WinWebAuthnCredentialEx().apply {
            dwVersion = 1
            cbId = credentialId.size
            pbId = credIdMem
            pwszCredentialType = WString("public-key")
            dwTransports = 0  // 0 = any transport (USB + NFC + BLE + ...)
        }
        credEx.write()

        // 3. Tableau de 1 pointeur vers credEx
        //    ppCredentials est un PWEBAUTHN_CREDENTIAL_EX* (tableau de pointeurs)
        //    → allouer un bloc de 1 pointeur natif (8 bytes sur x86-64 Windows)
        val ptrSize = Native.POINTER_SIZE.toLong()
        val ptrArrayMem = Memory(ptrSize)
        ptrArrayMem.setPointer(0, credEx.pointer)

        // 4. Liste de credentials
        val credList = WinWebAuthnCredentialList().apply {
            cCredentials = 1
            ppCredentials = ptrArrayMem
        }
        credList.write()

        Logger.d(TAG, "buildCredentialList: credIdMem=${credIdMem} ptrArrayMem=${ptrArrayMem} credEx.pointer=${credEx.pointer} credList.pointer=${credList.pointer}")

        return CredentialListHolder(credList, credEx, credIdMem, ptrArrayMem)
    }

    /**
     * Parse la clé publique (Ed25519 ou ECDSA P-256) depuis l'authenticatorData.
     *
     * Structure authenticatorData :
     * - [0..31]  : rpIdHash (SHA-256 du RP-ID)
     * - [32]     : flags (UP=0x01, UV=0x04, AT=0x40, ED=0x80)
     * - [33..36] : signCount (big-endian UINT32)
     * - [37..52] : aaguid (16 bytes, si AT=1)
     * - [53..54] : credentialIdLength (big-endian UINT16, si AT=1)
     * - [55..55+credIdLen-1] : credentialId (si AT=1)
     * - [55+credIdLen..] : CBOR-encoded COSE public key (si AT=1)
     *
     * @return Pair(SkKeyType, pubKeyBytes) où pubKeyBytes est :
     *   - SK_ED25519 : 32 bytes raw Ed25519 (label -2 COSE OKP)
     *   - SK_ECDSA_256 : 65 bytes SEC1 uncompressed = 0x04 || x(32B) || y(32B)
     * @throws YubiKitFidoManager.FidoError.TransportError si le parsing échoue
     */
    internal fun parseCosePubKey(authData: ByteArray): Pair<SkKeyType, ByteArray> {
        if (authData.size < 37) {
            throw YubiKitFidoManager.FidoError.TransportError(
                IllegalStateException("authenticatorData too short (${authData.size} bytes, minimum 37)")
            )
        }

        val flags = authData[32].toInt() and 0xFF
        val hasAttestedCredData = (flags and 0x40) != 0  // AT bit

        if (!hasAttestedCredData) {
            throw YubiKitFidoManager.FidoError.TransportError(
                IllegalStateException("authenticatorData missing AT flag (0x40): no attested credential data")
            )
        }

        // Skip rpIdHash(32) + flags(1) + signCount(4) + aaguid(16) = 53 bytes
        var offset = 53

        // credentialIdLength (2 bytes big-endian)
        if (authData.size < offset + 2) {
            throw YubiKitFidoManager.FidoError.TransportError(
                IllegalStateException("authenticatorData truncated before credentialIdLength")
            )
        }
        val credIdLen = ((authData[offset].toInt() and 0xFF) shl 8) or (authData[offset + 1].toInt() and 0xFF)
        offset += 2 + credIdLen

        if (authData.size <= offset) {
            throw YubiKitFidoManager.FidoError.TransportError(
                IllegalStateException("authenticatorData truncated before COSE key (credIdLen=$credIdLen)")
            )
        }

        // Parse COSE key en CBOR minimal : détecte alg depuis label 3
        val cborBytes = authData.copyOfRange(offset, authData.size)
        return parseCborCoseKey(cborBytes)
    }

    /**
     * Parse un CBOR COSE key et retourne (SkKeyType, pubKeyBytes).
     *
     * Détecte l'algorithme via le label 3 (alg) du COSE map :
     * - alg = -8 (0x27 en CBOR) → Ed25519 : lit label -2 (32 bytes x)
     * - alg = -7 (0x26 en CBOR) → ECDSA P-256 : lit label -2 (x, 32B) + label -3 (y, 32B)
     *
     * CBOR encodings des labels négatifs :
     *   label -1 = 0x20, label -2 = 0x21, label -3 = 0x22
     *   label  3 = 0x03
     *
     * CBOR encodings des alg négatifs :
     *   alg -7 (ECDSA P-256) = 0x26
     *   alg -8 (EdDSA / Ed25519) = 0x27
     */
    internal fun parseCborCoseKey(cbor: ByteArray): Pair<SkKeyType, ByteArray> {
        if (cbor.isEmpty()) throw YubiKitFidoManager.FidoError.TransportError(
            IllegalStateException("CBOR buffer empty")
        )

        // Skip map header
        var i = 0
        val firstByte = cbor[i].toInt() and 0xFF
        when {
            firstByte in 0xA0..0xB7 -> i++  // definite-length map, count <= 23
            firstByte == 0xB8 -> i += 2     // map with 1-byte length
            else -> i++                     // best effort
        }

        // Premier passage : détermine alg via label 3 (0x03)
        var algCbor: Int? = null
        var scanIdx = i
        while (scanIdx < cbor.size - 1) {
            val kb = cbor[scanIdx].toInt() and 0xFF
            if (kb == 0x03) {
                // Label 3 (alg) trouvé : lire la valeur suivante
                val vb = cbor[scanIdx + 1].toInt() and 0xFF
                algCbor = vb
                break
            }
            scanIdx++
        }

        val alg = algCbor ?: -8  // défaut Ed25519 si label 3 absent (ne devrait pas arriver)
        Logger.d(TAG, "parseCborCoseKey: detected alg CBOR=0x${alg.toString(16)}")

        return when (alg) {
            // COSE alg -7 = ECDSA P-256 (0x26 en CBOR = negative int 6)
            WinWebAuthnCoseCredentialParameter.COSE_ALG_ECDSA_P256.let { coseAlgToCbor(it) } -> {
                val x = findCborByteString(cbor, i, label = 0x21)  // label -2
                    ?: throw YubiKitFidoManager.FidoError.TransportError(
                        IllegalStateException("COSE ECDSA P-256: x coordinate (label -2) not found")
                    )
                val y = findCborByteString(cbor, i, label = 0x22)  // label -3
                    ?: throw YubiKitFidoManager.FidoError.TransportError(
                        IllegalStateException("COSE ECDSA P-256: y coordinate (label -3) not found")
                    )
                require(x.size == 32 && y.size == 32) {
                    "ECDSA P-256 coordinates must be 32 bytes each, got x=${x.size} y=${y.size}"
                }
                Logger.d(TAG, "parseCborCoseKey: ECDSA P-256 x+y extracted (65 bytes SEC1 uncompressed)")
                // SEC1 uncompressed = 0x04 || x || y (65 bytes)
                val sec1 = ByteArray(65)
                sec1[0] = 0x04
                System.arraycopy(x, 0, sec1, 1, 32)
                System.arraycopy(y, 0, sec1, 33, 32)
                Pair(SkKeyType.SK_ECDSA_256, sec1)
            }

            // COSE alg -8 = EdDSA / Ed25519 (0x27 en CBOR = negative int 7), et défaut
            else -> {
                val x = findCborByteString(cbor, i, label = 0x21)  // label -2
                    ?: throw YubiKitFidoManager.FidoError.TransportError(
                        IllegalStateException(
                            "COSE key: Ed25519 x coordinate (label -2) not found. " +
                            "cbor[${cbor.size}]=${cbor.take(16).joinToString(",") { "%02x".format(it) }}..."
                        )
                    )
                require(x.size == 32) {
                    "Ed25519 public key must be 32 bytes, got ${x.size}"
                }
                Logger.d(TAG, "parseCborCoseKey: Ed25519 x extracted (32 bytes)")
                Pair(SkKeyType.SK_ED25519, x)
            }
        }
    }

    /**
     * Convertit un COSE alg ID (int) en son encodage CBOR (byte value dans un map).
     *
     * CBOR negative integer encoding : -(n+1) → n, encodé 0x20+n pour n < 24.
     * - alg -7 (ECDSA P-256) → n=6 → 0x26
     * - alg -8 (EdDSA) → n=7 → 0x27
     */
    private fun coseAlgToCbor(alg: Int): Int {
        require(alg < 0 && alg >= -24) { "coseAlgToCbor: alg $alg out of 1-byte CBOR range" }
        return 0x20 + (-alg - 1)
    }

    /**
     * Cherche dans un CBOR map (déjà avancé après le header) un ByteString de 32 bytes
     * précédé par [label] (encodage CBOR d'un label négatif court).
     *
     * @param cbor Les bytes CBOR complets (incluant le map header déjà skippé)
     * @param startIdx Index de départ (après le map header)
     * @param label Encodage CBOR du label cible (ex. 0x21 pour -2, 0x22 pour -3)
     * @return ByteArray de 32 bytes si trouvé, null sinon
     */
    private fun findCborByteString(cbor: ByteArray, startIdx: Int, label: Int): ByteArray? {
        var i = startIdx
        while (i < cbor.size - 2) {
            val kb = cbor[i].toInt() and 0xFF
            if (kb == label) {
                i++
                if (i < cbor.size) {
                    val vb = cbor[i].toInt() and 0xFF
                    if (vb == 0x58 && i + 2 < cbor.size) {
                        // ByteString avec longueur 1-byte suivante
                        val len = cbor[i + 1].toInt() and 0xFF
                        i += 2
                        if (i + len <= cbor.size) {
                            return cbor.copyOfRange(i, i + len)
                        }
                    } else if ((vb and 0xE0) == 0x40) {
                        // ByteString avec longueur inline (major type 2, additional = length)
                        val len = vb and 0x1F
                        i++
                        if (i + len <= cbor.size) {
                            return cbor.copyOfRange(i, i + len)
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    companion object {
        /**
         * Retourne true si l'OS est Windows : vérification statique sans chargement DLL.
         * Utilisé pour décider si on doit tenter le chargement de WebAuthn.
         */
        val isPlatformSupported: Boolean =
            System.getProperty("os.name")?.lowercase()?.contains("windows") == true

        /**
         * Retourne le HWND de la fenêtre au premier plan, ou null si aucune.
         *
         * Libfido2 (référence) fait exactement cela :
         * ```c
         * if ((w = GetForegroundWindow()) == NULL) {
         *     if ((w = GetTopWindow(NULL)) == NULL) goto fail;
         * }
         * ```
         * Windows WebAuthn API nécessite un HWND valide pour afficher son dialog natif
         * de façon modale dans la hiérarchie de fenêtre correcte. Passer NULL peut causer
         * NTE_INVALID_PARAMETER sur certaines versions de Windows.
         *
         * Note : JNA User32 n'expose pas `GetTopWindow` : on utilise `GetDesktopWindow()`
         * comme fallback (fenêtre racine desktop), équivalent sémantique.
         *
         * @return Pointeur vers le HWND (natif) ou Pointer.NULL en fallback ultime.
         */
        internal fun getForegroundHwnd(): Pointer? {
            return runCatching {
                val user32 = User32.INSTANCE
                val hwnd: HWND? = user32.GetForegroundWindow()
                    ?: user32.GetDesktopWindow()
                hwnd?.pointer
            }.getOrElse { e ->
                Logger.w("WinWebAuthn", "getForegroundHwnd: failed to get HWND: ${e.message}, using NULL")
                Pointer.NULL
            }
        }

        /**
         * Convertit une signature ECDSA DER ASN.1 en format SSH mpint (r || s).
         *
         * Format DER attendu : SEQUENCE { INTEGER r, INTEGER s }
         * Encodage : 0x30 <seq_len> 0x02 <r_len> <r_bytes> 0x02 <s_len> <s_bytes>
         *
         * Format SSH SK pour ECDSA : string(mpint(r)) || string(mpint(s))
         * où mpint SSH = uint32 BE length + signed-extended big-endian bytes.
         *
         * @throws IllegalArgumentException si le DER est malformé
         */
        fun derEcdsaToSshMpint(derSig: ByteArray): ByteArray {
            require(derSig.size >= 8) { "DER signature too short: ${derSig.size}" }
            require(derSig[0] == 0x30.toByte()) { "DER: expected SEQUENCE (0x30), got 0x${derSig[0].toUByte().toString(16)}" }

            var offset = 2  // skip 0x30 + seq_len (assume < 128 bytes)
            if (derSig[1] == 0x81.toByte()) {
                offset = 3  // extended length: 0x81 + 1-byte length
            }

            // Parse r
            require(derSig[offset] == 0x02.toByte()) { "DER: expected INTEGER (0x02) for r, got 0x${derSig[offset].toUByte().toString(16)}" }
            offset++
            val rLen = derSig[offset].toInt() and 0xFF
            offset++
            val rBytes = derSig.copyOfRange(offset, offset + rLen)
            offset += rLen

            // Parse s
            require(derSig[offset] == 0x02.toByte()) { "DER: expected INTEGER (0x02) for s, got 0x${derSig[offset].toUByte().toString(16)}" }
            offset++
            val sLen = derSig[offset].toInt() and 0xFF
            offset++
            val sBytes = derSig.copyOfRange(offset, offset + sLen)

            // Encode r et s comme SSH mpints (SSH string = uint32 length + bytes)
            // SSH mpint = big-endian signé, leading zeros supprimés (sauf si high bit set → prepend 0x00)
            val buf = net.schmizz.sshj.common.Buffer.PlainBuffer()
            buf.putString(rBytes)
            buf.putString(sBytes)
            return buf.compactData
        }
    }

}

// ── Extension: HRESULT → FidoError ──────────────────────────────────────────

/**
 * Mappe un HRESULT WebAuthn vers l'erreur [YubiKitFidoManager.FidoError] appropriée.
 *
 * Codes HRESULT connus (source : ntstatus.h + webauthn.h) :
 * - `0x80090029` (NTE_NOT_SUPPORTED) → refus de l'algorithme ou feature non supportée
 * - `0x80090316` (NTE_USER_CANCELLED / SCARD_W_CANCELLED_BY_USER) → annulé par l'utilisateur
 * - `0x80090318` → opération expirée / timeout
 * - `0x80090020` → accès refusé
 * - `0x80070002` (ERROR_FILE_NOT_FOUND) → aucun authenticateur trouvé
 *
 * Note : les HRESULTs WebAuthn sont signés (négatifs) en Int Java.
 * On compare via `toUInt()` pour la lisibilité.
 */
internal fun Int.toFidoError(errorName: String = ""): YubiKitFidoManager.FidoError {
    return when {
        // User cancelled (NTE_USER_CANCELLED et variantes)
        errorName.contains("cancel", ignoreCase = true) ||
        errorName.contains("Cancel", ignoreCase = false) ->
            YubiKitFidoManager.FidoError.UserCancelled

        // Timeout
        errorName.contains("timeout", ignoreCase = true) ||
        errorName.contains("Timeout", ignoreCase = false) ->
            YubiKitFidoManager.FidoError.TouchTimeout

        // Algorithme non supporté → version API trop ancienne
        errorName.contains("notSupported", ignoreCase = true) ||
        errorName.contains("NotSupported", ignoreCase = false) ->
            YubiKitFidoManager.FidoError.CtapError(
                code = 0x27,
                msg = "Windows WebAuthn API: $errorName (HRESULT=0x${toUInt().toString(16)})"
            )

        // Device not found
        errorName.contains("notFound", ignoreCase = true) ||
        errorName.contains("NoDevice", ignoreCase = true) ->
            YubiKitFidoManager.FidoError.NoDeviceFound

        // Catch-all
        else -> YubiKitFidoManager.FidoError.TransportError(
            RuntimeException("Windows WebAuthn API error: $errorName (HRESULT=0x${toUInt().toString(16)})")
        )
    }
}
