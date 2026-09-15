// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2.winwebauthn

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.GUID

/**
 * Structs JNA correspondant aux types C de webauthn.h (Windows SDK).
 *
 * Référence canonique : https://github.com/microsoft/webauthn/blob/master/webauthn.h
 * (version récupérée le 2026-04-27 : structure courante VERSION_9)
 *
 * ## Règles de mapping
 * - `DWORD` → `Int` (32 bits, toujours >= 0 dans notre usage)
 * - `BOOL` → `Int` (0 = false, non-0 = true sur Windows : 4 bytes Win32, pas 1 byte Java)
 * - `LPCWSTR` / `PCWSTR` → `WString` (string wide constante C → Java WString via JNA)
 * - `PBYTE` + `DWORD cbXxx` → `Pointer` + `Int` (buffer byte géré par Windows)
 * - `GUID*` → `Pointer?` (pointeur vers GUID 16 bytes)
 * - `PVOID` → `Pointer?`
 *
 * ## Alignment
 * JNA sur Windows utilise `ALIGN_MSVC` par défaut : les champs sont alignés sur leur
 * taille propre, avec un maximum de 8 bytes (règle MSVC x64). JNA insère automatiquement
 * le padding entre un `Int` (4B) et un `Pointer` (8B), identique au comportement MSVC.
 *
 * ## Règle critique sur les versions des structs
 * Windows lit la totalité du struct C à partir du pointeur passé, en utilisant
 * `dwVersion` pour déterminer jusqu'à quel champ il peut aller. Si notre struct JNA
 * est trop courte (champs manquants), Windows lit du garbage au-delà → NTE_INVALID_PARAMETER.
 *
 * **Obligation** : inclure TOUS les champs de la struct C jusqu'à la version courante,
 * même si on ne les utilise pas (ils sont initialisés à 0 / null par la JVM).
 * Libfido2 (implémentation de référence) utilise `calloc` pour tout zéroïser d'abord,
 * puis `VERSION_1` si aucune feature avancée n'est nécessaire : on fait de même.
 *
 * Chaque struct utilise `@JvmField` pour éviter que JNA génère des getters/setters
 * qui casseraient la détection de champs via réflexion.
 *
 * Ordre des champs : il DOIT correspondre exactement à l'ordre du struct C
 * (ABI MSVC x86-64).
 */

// ── WEBAUTHN_RP_ENTITY_INFORMATION ──────────────────────────────────────────

/**
 * Informations sur la partie relying (RP).
 *
 * Ref: webauthn.h, WEBAUTHN_RP_ENTITY_INFORMATION_VERSION_1 (seule version)
 *
 * ```c
 * typedef struct _WEBAUTHN_RP_ENTITY_INFORMATION {
 *     DWORD    dwVersion;    // must be 1
 *     PCWSTR   pwszId;       // RP ID (ex: "ssh:")
 *     PCWSTR   pwszName;     // RP display name
 *     PCWSTR   pwszIcon;     // deprecated, pass NULL
 * } WEBAUTHN_RP_ENTITY_INFORMATION;
 * ```
 */
class WinWebAuthnRpEntityInfo : Structure() {
    @JvmField var dwVersion: Int = 1
    @JvmField var pwszId: WString? = null
    @JvmField var pwszName: WString? = null
    @JvmField var pwszIcon: WString? = null  // deprecated, always NULL

    override fun getFieldOrder(): List<String> =
        listOf("dwVersion", "pwszId", "pwszName", "pwszIcon")
}

// ── WEBAUTHN_USER_ENTITY_INFORMATION ────────────────────────────────────────

/**
 * Informations sur l'utilisateur.
 *
 * Ref: webauthn.h, WEBAUTHN_USER_ENTITY_INFORMATION_VERSION_1 (seule version)
 *
 * ```c
 * typedef struct _WEBAUTHN_USER_ENTITY_INFORMATION {
 *     DWORD    dwVersion;         // must be 1
 *     DWORD    cbId;              // size of pbId in bytes
 *     PBYTE    pbId;              // user ID bytes (non-secret identifier)
 *     PCWSTR   pwszName;          // user name
 *     PCWSTR   pwszIcon;          // deprecated, pass NULL
 *     PCWSTR   pwszDisplayName;   // display name
 * } WEBAUTHN_USER_ENTITY_INFORMATION;
 * ```
 */
class WinWebAuthnUserEntityInfo : Structure() {
    @JvmField var dwVersion: Int = 1
    @JvmField var cbId: Int = 0
    @JvmField var pbId: Pointer? = null
    @JvmField var pwszName: WString? = null
    @JvmField var pwszIcon: WString? = null  // deprecated, always NULL
    @JvmField var pwszDisplayName: WString? = null

    override fun getFieldOrder(): List<String> =
        listOf("dwVersion", "cbId", "pbId", "pwszName", "pwszIcon", "pwszDisplayName")
}

// ── WEBAUTHN_COSE_CREDENTIAL_PARAMETER ──────────────────────────────────────

/**
 * Un paramètre de credential COSE (algorithme + type).
 *
 * Ref: webauthn.h, WEBAUTHN_COSE_CREDENTIAL_PARAMETER_VERSION_1 (seule version)
 *
 * ```c
 * typedef struct _WEBAUTHN_COSE_CREDENTIAL_PARAMETER {
 *     DWORD    dwVersion;          // must be 1
 *     LPCWSTR  pwszCredentialType; // always "public-key"
 *     LONG     lAlg;               // COSE algorithm ID (-7 = ECDSA P-256, -8 = Ed25519)
 * } WEBAUTHN_COSE_CREDENTIAL_PARAMETER;
 * ```
 */
class WinWebAuthnCoseCredentialParameter : Structure() {
    @JvmField var dwVersion: Int = 1
    @JvmField var pwszCredentialType: WString? = WString("public-key")
    @JvmField var lAlg: Int = COSE_ALG_ECDSA_P256

    override fun getFieldOrder(): List<String> =
        listOf("dwVersion", "pwszCredentialType", "lAlg")

    companion object {
        /**
         * COSE algorithm identifier ECDSA P-256 with SHA-256 (alg -7).
         * Supporté depuis WebAuthn API version 1 (Windows 10 1903).
         *
         * Ref: webauthn.h, WEBAUTHN_COSE_ALGORITHM_ECDSA_P256_WITH_SHA256 = -7
         */
        const val COSE_ALG_ECDSA_P256 = -7

        /**
         * COSE algorithm identifier EdDSA / Ed25519 (alg -8).
         * Supporté depuis WebAuthn API version 3 (Windows 10 21H2 / Windows 11 21H2).
         *
         * Ref: webauthn.h, WEBAUTHN_COSE_ALGORITHM_EDDSA_ED25519 = -8
         */
        const val COSE_ALG_EDDSA = -8
    }
}

// ── WEBAUTHN_COSE_CREDENTIAL_PARAMETERS ─────────────────────────────────────

/**
 * Tableau de paramètres COSE.
 *
 * Ref: webauthn.h, struct sans version propre
 *
 * ```c
 * typedef struct _WEBAUTHN_COSE_CREDENTIAL_PARAMETERS {
 *     DWORD                               cCredentialParameters;
 *     PWEBAUTHN_COSE_CREDENTIAL_PARAMETER pCredentialParameters;
 * } WEBAUTHN_COSE_CREDENTIAL_PARAMETERS;
 * ```
 *
 * `pCredentialParameters` pointe vers le premier élément d'un tableau contigu
 * de [WinWebAuthnCoseCredentialParameter]. On alloue ce tableau via
 * [Structure.toArray] et on passe `params[0].pointer` ici.
 *
 * ## Pattern JNA correct pour tableaux de structs contiguës
 * ```kotlin
 * val first = WinWebAuthnCoseCredentialParameter()
 * val arr = first.toArray(2) as Array<WinWebAuthnCoseCredentialParameter>
 * arr[0].lAlg = -7 ; arr[0].write()
 * arr[1].lAlg = -8 ; arr[1].write()
 * val params = WinWebAuthnCoseCredentialParameters()
 * params.cCredentialParameters = 2
 * params.pCredentialParameters = arr[0].pointer  // ← adresse du PREMIER élément
 * params.write()
 * ```
 */
class WinWebAuthnCoseCredentialParameters : Structure() {
    @JvmField var cCredentialParameters: Int = 0
    @JvmField var pCredentialParameters: Pointer? = null

    override fun getFieldOrder(): List<String> =
        listOf("cCredentialParameters", "pCredentialParameters")
}

// ── WEBAUTHN_CLIENT_DATA ─────────────────────────────────────────────────────

/**
 * Client data passée à WebAuthn API.
 *
 * Ref: webauthn.h, WEBAUTHN_CLIENT_DATA_VERSION_1 (seule version)
 *
 * Pour SSH : on passe les bytes du challenge SSH dans `pbClientDataJSON` et
 * `pwszHashAlgId = "SHA-256"`. Windows calcule SHA-256(challenge) et l'utilise
 * comme clientDataHash dans l'assertion CTAP2 : exactement ce que SSH SK attend.
 *
 * ```c
 * typedef struct _WEBAUTHN_CLIENT_DATA {
 *     DWORD    dwVersion;          // must be 1
 *     DWORD    cbClientDataJSON;   // size of pbClientDataJSON in bytes
 *     PBYTE    pbClientDataJSON;   // raw bytes (challenge pour SSH)
 *     LPCWSTR  pwszHashAlgId;      // always "SHA-256"
 * } WEBAUTHN_CLIENT_DATA;
 * ```
 */
class WinWebAuthnClientData : Structure() {
    @JvmField var dwVersion: Int = 1
    @JvmField var cbClientDataJSON: Int = 0
    @JvmField var pbClientDataJSON: Pointer? = null
    @JvmField var pwszHashAlgId: WString? = WString("SHA-256")

    override fun getFieldOrder(): List<String> =
        listOf("dwVersion", "cbClientDataJSON", "pbClientDataJSON", "pwszHashAlgId")
}

// ── WEBAUTHN_AUTHENTICATOR_MAKE_CREDENTIAL_OPTIONS ───────────────────────────

/**
 * Options pour MakeCredential : struct COMPLÈTE jusqu'à VERSION_9.
 *
 * Ref: webauthn.h, WEBAUTHN_AUTHENTICATOR_MAKE_CREDENTIAL_OPTIONS
 *
 * ## Règle critique de version
 * Windows lit la totalité du struct C en mémoire selon `dwVersion`.
 * Pour éviter NTE_INVALID_PARAMETER dû à du garbage dans les champs v4+,
 * cette struct JNA inclut TOUS les champs jusqu'à VERSION_9. Les champs
 * non utilisés restent à 0/null (init JVM).
 *
 * Pour SSH simple : utiliser `dwVersion = 1` (comme libfido2).
 * Les champs v2+ restent à 0/null → Windows les ignore.
 *
 * ## Layout mémoire C (MSVC x64, pas de pragma pack)
 * ```
 * v1: dwVersion(4) dwTimeoutMilliseconds(4)
 *     CredentialList.cCredentials(4) [pad4] CredentialList.pCredentials(8)
 *     Extensions.cExtensions(4) [pad4] Extensions.pExtensions(8)
 *     dwAuthenticatorAttachment(4) bRequireResidentKey(4)
 *     dwUserVerificationRequirement(4) dwAttestationConveyancePreference(4)
 *     dwFlags(4) [pad4]
 * v2: pCancellationId(8)
 * v3: pExcludeCredentialList(8)
 * v4: dwEnterpriseAttestation(4) dwLargeBlobSupport(4) bPreferResidentKey(4) [pad4]
 * v5: bBrowserInPrivateMode(4) [pad4]
 * v6: bEnablePrf(4) [pad4]
 * v7: pLinkedDevice(8) cbJsonExt(4) [pad4] pbJsonExt(8)
 * v8: pPRFGlobalEval(8) cCredentialHints(4) [pad4] ppwszCredentialHints(8) bThirdPartyPayment(4) [pad4]
 * v9: pwszRemoteWebOrigin(8) cbPublicKeyCredentialCreationOptionsJSON(4) [pad4]
 *     pbPublicKeyCredentialCreationOptionsJSON(8)
 *     cbAuthenticatorId(4) [pad4] pbAuthenticatorId(8)
 * ```
 *
 * ```c
 * // Constantes WEBAUTHN_AUTHENTICATOR_ATTACHMENT_*
 * #define WEBAUTHN_AUTHENTICATOR_ATTACHMENT_ANY              0  // ← valeur par défaut recommandée
 * #define WEBAUTHN_AUTHENTICATOR_ATTACHMENT_PLATFORM         1
 * #define WEBAUTHN_AUTHENTICATOR_ATTACHMENT_CROSS_PLATFORM   2
 *
 * // Constantes WEBAUTHN_USER_VERIFICATION_REQUIREMENT_*
 * #define WEBAUTHN_USER_VERIFICATION_REQUIREMENT_ANY          0  // ← valeur par défaut recommandée
 * #define WEBAUTHN_USER_VERIFICATION_REQUIREMENT_REQUIRED     1
 * #define WEBAUTHN_USER_VERIFICATION_REQUIREMENT_PREFERRED    2
 * #define WEBAUTHN_USER_VERIFICATION_REQUIREMENT_DISCOURAGED  3
 *
 * // Constantes WEBAUTHN_ATTESTATION_CONVEYANCE_PREFERENCE_*
 * #define WEBAUTHN_ATTESTATION_CONVEYANCE_PREFERENCE_ANY      0  // ← valeur par défaut recommandée
 * #define WEBAUTHN_ATTESTATION_CONVEYANCE_PREFERENCE_NONE     1
 * #define WEBAUTHN_ATTESTATION_CONVEYANCE_PREFERENCE_INDIRECT 2
 * #define WEBAUTHN_ATTESTATION_CONVEYANCE_PREFERENCE_DIRECT   3
 * ```
 */
class WinWebAuthnMakeCredentialOptions : Structure() {

    // ── VERSION_1 fields ──────────────────────────────────────────────────────

    /**
     * Version de cette struct. Utiliser VERSION_1 = 1 pour SSH simple.
     * Windows respecte les champs jusqu'à dwVersion ; les champs des versions
     * supérieures doivent quand même être présents en mémoire (initialisés à 0).
     *
     * Ref: WEBAUTHN_AUTHENTICATOR_MAKE_CREDENTIAL_OPTIONS_VERSION_1 = 1
     */
    @JvmField var dwVersion: Int = 1

    /** Timeout en ms. 60 000 = 60 secondes. */
    @JvmField var dwTimeoutMilliseconds: Int = 60_000

    // WEBAUTHN_CREDENTIALS CredentialList { DWORD cCredentials; PWEBAUTHN_CREDENTIAL pCredentials }
    // Struct embarquée inline : aplatie en 2 champs (JNA insère le padding DWORD→PTR automatiquement).
    @JvmField var cCredentials: Int = 0
    @JvmField var pCredentials: Pointer? = null

    // WEBAUTHN_EXTENSIONS Extensions { DWORD cExtensions; PWEBAUTHN_EXTENSION pExtensions }
    // Struct embarquée inline : aplatie en 2 champs (idem).
    @JvmField var cExtensions: Int = 0
    @JvmField var pExtensions: Pointer? = null

    /**
     * Authenticator attachment preference.
     *
     * Valeur recommandée pour SSH : `0` (ANY), laisse Windows choisir
     * entre Windows Hello (platform) et YubiKey (cross-platform).
     * Utiliser `2` (CROSS_PLATFORM) force l'utilisation d'un authenticateur externe
     * mais peut causer NTE_INVALID_PARAMETER si l'authenticateur n'est pas connecté.
     *
     * Ref: WEBAUTHN_AUTHENTICATOR_ATTACHMENT_ANY = 0
     */
    @JvmField var dwAuthenticatorAttachment: Int = 0  // ANY: laisse Windows choisir

    /**
     * Require resident key (discoverable credential). `0` = false pour SSH SK.
     * Note : BOOL Win32 = 4 bytes (pas 1 byte Java boolean).
     */
    @JvmField var bRequireResidentKey: Int = 0  // BOOL = Int, false

    /**
     * User verification requirement.
     *
     * Valeur recommandée : `0` (ANY) : laisse l'authenticateur décider.
     * Ne pas forcer DISCOURAGED (3) car certains authenticateurs peuvent
     * retourner une erreur si UV est discouraged mais configuré comme requis.
     *
     * Ref: WEBAUTHN_USER_VERIFICATION_REQUIREMENT_ANY = 0
     */
    @JvmField var dwUserVerificationRequirement: Int = 0  // ANY

    /**
     * Attestation conveyance preference.
     *
     * Valeur recommandée : `0` (ANY) : laisse Windows décider.
     * `1` (NONE) peut causer des rejets sur certains authenticateurs qui
     * ne supportent pas l'attestation "none" explicite.
     *
     * Ref: WEBAUTHN_ATTESTATION_CONVEYANCE_PREFERENCE_ANY = 0
     */
    @JvmField var dwAttestationConveyancePreference: Int = 0  // ANY

    /** Reserved for future use. Must be 0. */
    @JvmField var dwFlags: Int = 0

    // ── VERSION_2 fields ──────────────────────────────────────────────────────

    /**
     * Cancellation ID : GUID* alloué par WebAuthNGetCancellationId().
     * null = pas d'annulation gérée.
     */
    @JvmField var pCancellationId: Pointer? = null  // GUID*

    // ── VERSION_3 fields ──────────────────────────────────────────────────────

    /**
     * Exclude credential list : si présent, CredentialList est ignoré.
     * null = pas d'exclusion.
     */
    @JvmField var pExcludeCredentialList: Pointer? = null  // PWEBAUTHN_CREDENTIAL_LIST

    // ── VERSION_4 fields ──────────────────────────────────────────────────────

    /** Enterprise attestation mode. 0 = none. */
    @JvmField var dwEnterpriseAttestation: Int = 0

    /**
     * Large blob support preference.
     * 0 = none, 1 = required, 2 = preferred.
     * Note: required/preferred nécessite bRequireResidentKey = true.
     */
    @JvmField var dwLargeBlobSupport: Int = 0

    /** Prefer resident key. BOOL Win32 = Int. 0 = false. Overrides bRequireResidentKey si true. */
    @JvmField var bPreferResidentKey: Int = 0  // BOOL

    // ── VERSION_5 fields ──────────────────────────────────────────────────────

    /** Browser InPrivate mode. BOOL Win32 = Int. 0 = false. */
    @JvmField var bBrowserInPrivateMode: Int = 0  // BOOL

    // ── VERSION_6 fields ──────────────────────────────────────────────────────

    /** Enable PRF (Pseudo-Random Function) extension. BOOL Win32 = Int. 0 = false. */
    @JvmField var bEnablePrf: Int = 0  // BOOL

    // ── VERSION_7 fields ──────────────────────────────────────────────────────

    /** Linked device data (deprecated, hybrid flow). null = not used. */
    @JvmField var pLinkedDevice: Pointer? = null  // PCTAPCBOR_HYBRID_STORAGE_LINKED_DATA

    /** JSON extension size in bytes. 0 = no JSON extension. */
    @JvmField var cbJsonExt: Int = 0

    /** JSON extension data bytes. null = no JSON extension. */
    @JvmField var pbJsonExt: Pointer? = null

    // ── VERSION_8 fields ──────────────────────────────────────────────────────

    /** PRF global eval HMAC-SECRET salt. null = not used. */
    @JvmField var pPRFGlobalEval: Pointer? = null  // PWEBAUTHN_HMAC_SECRET_SALT

    /** Number of credential hints. 0 = no hints. */
    @JvmField var cCredentialHints: Int = 0

    /** Credential hints array (LPCWSTR*). null = no hints. */
    @JvmField var ppwszCredentialHints: Pointer? = null  // LPCWSTR*

    /** Third-party payment flag. BOOL Win32 = Int. 0 = false. */
    @JvmField var bThirdPartyPayment: Int = 0  // BOOL

    // ── VERSION_9 fields ──────────────────────────────────────────────────────

    /** Remote web origin for remote web app scenario. null = not used. */
    @JvmField var pwszRemoteWebOrigin: Pointer? = null  // PCWSTR (as raw Pointer, not WString)

    /** Size of JSON creation options. 0 = not used. */
    @JvmField var cbPublicKeyCredentialCreationOptionsJSON: Int = 0

    /** JSON creation options bytes. null = not used. */
    @JvmField var pbPublicKeyCredentialCreationOptionsJSON: Pointer? = null

    /** Authenticator ID size. 0 = not used. */
    @JvmField var cbAuthenticatorId: Int = 0

    /** Authenticator ID bytes from WebAuthNGetAuthenticatorList. null = not used. */
    @JvmField var pbAuthenticatorId: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf(
        // v1
        "dwVersion", "dwTimeoutMilliseconds",
        "cCredentials", "pCredentials",
        "cExtensions", "pExtensions",
        "dwAuthenticatorAttachment",
        "bRequireResidentKey",
        "dwUserVerificationRequirement",
        "dwAttestationConveyancePreference",
        "dwFlags",
        // v2
        "pCancellationId",
        // v3
        "pExcludeCredentialList",
        // v4
        "dwEnterpriseAttestation",
        "dwLargeBlobSupport",
        "bPreferResidentKey",
        // v5
        "bBrowserInPrivateMode",
        // v6
        "bEnablePrf",
        // v7
        "pLinkedDevice",
        "cbJsonExt",
        "pbJsonExt",
        // v8
        "pPRFGlobalEval",
        "cCredentialHints",
        "ppwszCredentialHints",
        "bThirdPartyPayment",
        // v9
        "pwszRemoteWebOrigin",
        "cbPublicKeyCredentialCreationOptionsJSON",
        "pbPublicKeyCredentialCreationOptionsJSON",
        "cbAuthenticatorId",
        "pbAuthenticatorId",
    )

    companion object {
        // WEBAUTHN_AUTHENTICATOR_ATTACHMENT_*
        const val ATTACHMENT_ANY = 0
        const val ATTACHMENT_PLATFORM = 1
        const val ATTACHMENT_CROSS_PLATFORM = 2

        // WEBAUTHN_USER_VERIFICATION_REQUIREMENT_*
        const val UV_ANY = 0
        const val UV_REQUIRED = 1
        const val UV_PREFERRED = 2
        const val UV_DISCOURAGED = 3

        // WEBAUTHN_ATTESTATION_CONVEYANCE_PREFERENCE_*
        const val ATTESTATION_ANY = 0
        const val ATTESTATION_NONE = 1
        const val ATTESTATION_INDIRECT = 2
        const val ATTESTATION_DIRECT = 3
    }
}

// ── WEBAUTHN_CREDENTIAL_ATTESTATION ──────────────────────────────────────────

/**
 * Résultat de MakeCredential retourné par Windows WebAuthn API : struct COMPLÈTE jusqu'à VERSION_8.
 *
 * Ref: webauthn.h, WEBAUTHN_CREDENTIAL_ATTESTATION
 *
 * Cette struct est allouée par Windows et libérée via `WebAuthNFreeCredentialAttestation`.
 * On ne l'alloue jamais depuis Java : seulement reçue via un `Pointer*` out-parameter,
 * puis lue avec [fromPointer].
 *
 * ## Champs utilisés pour SSH
 * - `cbCredentialId` + `pbCredentialId` : raw credential ID bytes
 * - `cbAuthenticatorData` + `pbAuthenticatorData` : authData contenant la COSE pubkey
 *
 * On inclut tous les champs jusqu'à v8 pour que JNA mappe correctement la mémoire
 * Windows même si on ne lit que les champs v1.
 */
class WinWebAuthnCredentialAttestation : Structure() {
    // ── VERSION_1 fields ──────────────────────────────────────────────────────
    @JvmField var dwVersion: Int = 0
    @JvmField var pwszFormatType: WString? = null
    @JvmField var cbAuthenticatorData: Int = 0
    @JvmField var pbAuthenticatorData: Pointer? = null
    @JvmField var cbAttestation: Int = 0
    @JvmField var pbAttestation: Pointer? = null
    @JvmField var dwAttestationDecodeType: Int = 0
    @JvmField var pvAttestationDecode: Pointer? = null
    @JvmField var cbAttestationObject: Int = 0
    @JvmField var pbAttestationObject: Pointer? = null
    @JvmField var cbCredentialId: Int = 0
    @JvmField var pbCredentialId: Pointer? = null

    // ── VERSION_2 fields ──────────────────────────────────────────────────────
    // WEBAUTHN_EXTENSIONS Extensions { DWORD cExtensions; PWEBAUTHN_EXTENSION pExtensions }
    @JvmField var cAttestationExtensions: Int = 0
    @JvmField var pAttestationExtensions: Pointer? = null

    // ── VERSION_3 fields ──────────────────────────────────────────────────────
    @JvmField var dwUsedTransport: Int = 0

    // ── VERSION_4 fields ──────────────────────────────────────────────────────
    @JvmField var bEpAtt: Int = 0          // BOOL
    @JvmField var bLargeBlobSupported: Int = 0  // BOOL
    @JvmField var bResidentKey: Int = 0    // BOOL

    // ── VERSION_5 fields ──────────────────────────────────────────────────────
    @JvmField var bPrfEnabled: Int = 0     // BOOL

    // ── VERSION_6 fields ──────────────────────────────────────────────────────
    @JvmField var cbUnsignedExtensionOutputs: Int = 0
    @JvmField var pbUnsignedExtensionOutputs: Pointer? = null

    // ── VERSION_7 fields ──────────────────────────────────────────────────────
    @JvmField var pHmacSecret: Pointer? = null  // PWEBAUTHN_HMAC_SECRET_SALT
    @JvmField var bThirdPartyPayment: Int = 0   // BOOL

    // ── VERSION_8 fields ──────────────────────────────────────────────────────
    @JvmField var dwTransports: Int = 0
    @JvmField var cbClientDataJSON: Int = 0
    @JvmField var pbClientDataJSON: Pointer? = null
    @JvmField var cbRegistrationResponseJSON: Int = 0
    @JvmField var pbRegistrationResponseJSON: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf(
        // v1
        "dwVersion", "pwszFormatType",
        "cbAuthenticatorData", "pbAuthenticatorData",
        "cbAttestation", "pbAttestation",
        "dwAttestationDecodeType", "pvAttestationDecode",
        "cbAttestationObject", "pbAttestationObject",
        "cbCredentialId", "pbCredentialId",
        // v2 (Extensions inline)
        "cAttestationExtensions", "pAttestationExtensions",
        // v3
        "dwUsedTransport",
        // v4
        "bEpAtt", "bLargeBlobSupported", "bResidentKey",
        // v5
        "bPrfEnabled",
        // v6
        "cbUnsignedExtensionOutputs", "pbUnsignedExtensionOutputs",
        // v7
        "pHmacSecret", "bThirdPartyPayment",
        // v8
        "dwTransports",
        "cbClientDataJSON", "pbClientDataJSON",
        "cbRegistrationResponseJSON", "pbRegistrationResponseJSON",
    )

    companion object {
        fun fromPointer(p: Pointer): WinWebAuthnCredentialAttestation {
            val s = WinWebAuthnCredentialAttestation()
            s.useMemory(p)
            s.read()
            return s
        }
    }
}

// ── WEBAUTHN_CREDENTIAL_EX ────────────────────────────────────────────────────

/**
 * Credential étendue pour la liste allow/exclude.
 *
 * Ref: webauthn.h, WEBAUTHN_CREDENTIAL_EX_VERSION_1 (seule version)
 *
 * ```c
 * typedef struct _WEBAUTHN_CREDENTIAL_EX {
 *     DWORD    dwVersion;            // must be 1
 *     DWORD    cbId;                 // size of pbId
 *     PBYTE    pbId;                 // credential ID bytes
 *     LPCWSTR  pwszCredentialType;   // always "public-key"
 *     DWORD    dwTransports;         // transport bitmask (0 = any)
 * } WEBAUTHN_CREDENTIAL_EX;
 * ```
 */
class WinWebAuthnCredentialEx : Structure() {
    @JvmField var dwVersion: Int = 1
    @JvmField var cbId: Int = 0
    @JvmField var pbId: Pointer? = null
    @JvmField var pwszCredentialType: WString? = WString("public-key")
    @JvmField var dwTransports: Int = 0  // 0 = any transport

    override fun getFieldOrder(): List<String> =
        listOf("dwVersion", "cbId", "pbId", "pwszCredentialType", "dwTransports")
}

// ── WEBAUTHN_CREDENTIAL_LIST ──────────────────────────────────────────────────

/**
 * Liste de credentials étendues (allow/exclude list).
 *
 * Ref: webauthn.h, struct sans version propre
 *
 * ```c
 * typedef struct _WEBAUTHN_CREDENTIAL_LIST {
 *     DWORD                       cCredentials;
 *     PWEBAUTHN_CREDENTIAL_EX*    ppCredentials;  // tableau de POINTEURS vers CredentialEx
 * } WEBAUTHN_CREDENTIAL_LIST;
 * ```
 *
 * `ppCredentials` est un tableau de POINTEURS vers [WinWebAuthnCredentialEx].
 * Voir [WindowsWebAuthnProvider.buildCredentialList] pour la construction.
 */
class WinWebAuthnCredentialList : Structure() {
    @JvmField var cCredentials: Int = 0
    @JvmField var ppCredentials: Pointer? = null  // PWEBAUTHN_CREDENTIAL_EX*

    override fun getFieldOrder(): List<String> =
        listOf("cCredentials", "ppCredentials")
}

// ── WEBAUTHN_AUTHENTICATOR_GET_ASSERTION_OPTIONS ─────────────────────────────

/**
 * Options pour GetAssertion : struct COMPLÈTE jusqu'à VERSION_9.
 *
 * Ref: webauthn.h, WEBAUTHN_AUTHENTICATOR_GET_ASSERTION_OPTIONS
 *
 * Même règle que MakeCredentialOptions : inclure TOUS les champs jusqu'à v9
 * pour éviter du garbage lu par Windows au-delà de la struct JNA.
 *
 * Pour SSH : utiliser `dwVersion = 4` pour bénéficier de `pAllowCredentialList`
 * (version 4+) qui permet de passer une liste de credentials autorisées.
 *
 * ```c
 * // WEBAUTHN_AUTHENTICATOR_GET_ASSERTION_OPTIONS_VERSION_4 = 4
 * ```
 */
class WinWebAuthnGetAssertionOptions : Structure() {

    // ── VERSION_1 fields ──────────────────────────────────────────────────────

    /**
     * Version de cette struct.
     * Version 4 permet d'utiliser pAllowCredentialList (nécessaire pour SSH SK).
     *
     * Ref: WEBAUTHN_AUTHENTICATOR_GET_ASSERTION_OPTIONS_VERSION_4 = 4
     */
    @JvmField var dwVersion: Int = 4

    /** Timeout en ms. */
    @JvmField var dwTimeoutMilliseconds: Int = 30_000

    // WEBAUTHN_CREDENTIALS CredentialList { DWORD cCredentials; PWEBAUTHN_CREDENTIAL pCredentials }
    @JvmField var cCredentials: Int = 0
    @JvmField var pCredentials: Pointer? = null

    // WEBAUTHN_EXTENSIONS Extensions { DWORD cExtensions; PWEBAUTHN_EXTENSION pExtensions }
    @JvmField var cExtensions: Int = 0
    @JvmField var pExtensions: Pointer? = null

    /**
     * Authenticator attachment. 0 = ANY (laisse Windows choisir).
     * Ref: WEBAUTHN_AUTHENTICATOR_ATTACHMENT_ANY = 0
     */
    @JvmField var dwAuthenticatorAttachment: Int = 0  // ANY

    /**
     * User verification requirement. 0 = ANY.
     * Ref: WEBAUTHN_USER_VERIFICATION_REQUIREMENT_ANY = 0
     */
    @JvmField var dwUserVerificationRequirement: Int = 0  // ANY

    /** Reserved for future use. Must be 0. */
    @JvmField var dwFlags: Int = 0

    // ── VERSION_2 fields ──────────────────────────────────────────────────────

    /** U2F app ID (legacy U2F interop). null = not used. */
    @JvmField var pwszU2fAppId: WString? = null

    /** Pointer to bool flag for U2F app ID. null = not used. */
    @JvmField var pbU2fAppId: Pointer? = null  // BOOL*

    // ── VERSION_3 fields ──────────────────────────────────────────────────────

    /**
     * Cancellation ID : GUID* alloué par WebAuthNGetCancellationId().
     * null = pas d'annulation gérée.
     */
    @JvmField var pCancellationId: Pointer? = null  // GUID*

    // ── VERSION_4 fields ──────────────────────────────────────────────────────

    /**
     * Allow credential list. Si présent (non-null), CredentialList est ignoré.
     * Requis pour SSH SK (assertion sur credential spécifique).
     */
    @JvmField var pAllowCredentialList: Pointer? = null  // PWEBAUTHN_CREDENTIAL_LIST

    // ── VERSION_5 fields ──────────────────────────────────────────────────────

    /** Large blob operation. 0 = none. */
    @JvmField var dwCredLargeBlobOperation: Int = 0

    /** Large blob size. 0 = not used. */
    @JvmField var cbCredLargeBlob: Int = 0

    /** Large blob data. null = not used. */
    @JvmField var pbCredLargeBlob: Pointer? = null

    // ── VERSION_6 fields ──────────────────────────────────────────────────────

    /** HMAC-SECRET salt values. null = not used. */
    @JvmField var pHmacSecretSaltValues: Pointer? = null  // PWEBAUTHN_HMAC_SECRET_SALT_VALUES

    /** Browser InPrivate mode. BOOL Win32 = Int. 0 = false. */
    @JvmField var bBrowserInPrivateMode: Int = 0  // BOOL

    // ── VERSION_7 fields ──────────────────────────────────────────────────────

    /** Linked device data (deprecated, hybrid flow). null = not used. */
    @JvmField var pLinkedDevice: Pointer? = null  // PCTAPCBOR_HYBRID_STORAGE_LINKED_DATA

    /** Auto-fill flag for passkey UI. BOOL Win32 = Int. 0 = false. */
    @JvmField var bAutoFill: Int = 0  // BOOL

    /** JSON extension size. 0 = not used. */
    @JvmField var cbJsonExt: Int = 0

    /** JSON extension data. null = not used. */
    @JvmField var pbJsonExt: Pointer? = null

    // ── VERSION_8 fields ──────────────────────────────────────────────────────

    /** Number of credential hints. 0 = no hints. */
    @JvmField var cCredentialHints: Int = 0

    /** Credential hints array. null = no hints. */
    @JvmField var ppwszCredentialHints: Pointer? = null  // LPCWSTR*

    // ── VERSION_9 fields ──────────────────────────────────────────────────────

    /** Remote web origin. null = not used. */
    @JvmField var pwszRemoteWebOrigin: Pointer? = null  // PCWSTR

    /** JSON request options size. 0 = not used. */
    @JvmField var cbPublicKeyCredentialRequestOptionsJSON: Int = 0

    /** JSON request options bytes. null = not used. */
    @JvmField var pbPublicKeyCredentialRequestOptionsJSON: Pointer? = null

    /** Authenticator ID size. 0 = not used. */
    @JvmField var cbAuthenticatorId: Int = 0

    /** Authenticator ID bytes. null = not used. */
    @JvmField var pbAuthenticatorId: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf(
        // v1
        "dwVersion", "dwTimeoutMilliseconds",
        "cCredentials", "pCredentials",
        "cExtensions", "pExtensions",
        "dwAuthenticatorAttachment",
        "dwUserVerificationRequirement",
        "dwFlags",
        // v2
        "pwszU2fAppId", "pbU2fAppId",
        // v3
        "pCancellationId",
        // v4
        "pAllowCredentialList",
        // v5
        "dwCredLargeBlobOperation",
        "cbCredLargeBlob",
        "pbCredLargeBlob",
        // v6
        "pHmacSecretSaltValues",
        "bBrowserInPrivateMode",
        // v7
        "pLinkedDevice",
        "bAutoFill",
        "cbJsonExt",
        "pbJsonExt",
        // v8
        "cCredentialHints",
        "ppwszCredentialHints",
        // v9
        "pwszRemoteWebOrigin",
        "cbPublicKeyCredentialRequestOptionsJSON",
        "pbPublicKeyCredentialRequestOptionsJSON",
        "cbAuthenticatorId",
        "pbAuthenticatorId",
    )
}

// ── WEBAUTHN_ASSERTION ────────────────────────────────────────────────────────

/**
 * Résultat de GetAssertion retourné par Windows WebAuthn API : struct COMPLÈTE jusqu'à VERSION_6.
 *
 * Ref: webauthn.h, WEBAUTHN_ASSERTION (CURRENT_VERSION = 6)
 *
 * Structure C complète :
 * ```c
 * typedef struct _WEBAUTHN_ASSERTION {
 *     // v1
 *     DWORD                dwVersion;
 *     DWORD                cbAuthenticatorData;
 *     PBYTE                pbAuthenticatorData;
 *     DWORD                cbSignature;
 *     PBYTE                pbSignature;
 *     WEBAUTHN_CREDENTIAL  Credential;  // inline { DWORD dwVersion; DWORD cbId; PBYTE pbId; LPCWSTR pwszCredentialType }
 *     DWORD                cbUserId;
 *     PBYTE                pbUserId;
 *     // v2
 *     WEBAUTHN_EXTENSIONS  Extensions;  // inline { DWORD cExtensions; PWEBAUTHN_EXTENSION pExtensions }
 *     DWORD                cbCredLargeBlob;
 *     PBYTE                pbCredLargeBlob;
 *     DWORD                dwCredLargeBlobStatus;
 *     // v3
 *     PWEBAUTHN_HMAC_SECRET_SALT pHmacSecret;
 *     // v4
 *     DWORD                dwUsedTransport;
 *     // v5
 *     DWORD                cbUnsignedExtensionOutputs;
 *     PBYTE                pbUnsignedExtensionOutputs;
 *     // v6
 *     DWORD                cbClientDataJSON;
 *     PBYTE                pbClientDataJSON;
 *     DWORD                cbAuthenticationResponseJSON;
 *     PBYTE                pbAuthenticationResponseJSON;
 * } WEBAUTHN_ASSERTION;
 * ```
 *
 * ## Règle critique
 * Windows alloue et retourne cette struct à la version courante. Si on ne déclare
 * que les champs v1, JNA lit correctement les champs v1, mais si Windows retourne
 * une struct v2+ contenant des champs remplis, une struct JNA tronquée ne lirait
 * que les champs v1 correctement (les autres seraient ignorés). Cependant, la taille
 * déclarée de la struct JNA affecte aussi comment JNA interprète le buffer : inclure
 * tous les champs évite tout risque de lecture décalée.
 *
 * Libération via `WebAuthNFreeAssertion`.
 */
class WinWebAuthnAssertion : Structure() {

    // ── VERSION_1 fields ──────────────────────────────────────────────────────
    @JvmField var dwVersion: Int = 0
    @JvmField var cbAuthenticatorData: Int = 0
    @JvmField var pbAuthenticatorData: Pointer? = null
    @JvmField var cbSignature: Int = 0
    @JvmField var pbSignature: Pointer? = null

    // WEBAUTHN_CREDENTIAL Credential (aplati) :
    // { DWORD dwVersion; DWORD cbId; PBYTE pbId; LPCWSTR pwszCredentialType }
    @JvmField var credDwVersion: Int = 0
    @JvmField var credCbId: Int = 0
    @JvmField var credPbId: Pointer? = null
    @JvmField var credPwszCredentialType: Pointer? = null  // LPCWSTR → Pointer (pas WString, c'est Windows-owned)

    @JvmField var cbUserId: Int = 0
    @JvmField var pbUserId: Pointer? = null

    // ── VERSION_2 fields ──────────────────────────────────────────────────────
    // WEBAUTHN_EXTENSIONS Extensions (aplati) :
    // { DWORD cExtensions; PWEBAUTHN_EXTENSION pExtensions }
    @JvmField var cExtensions: Int = 0
    @JvmField var pExtensions: Pointer? = null

    @JvmField var cbCredLargeBlob: Int = 0
    @JvmField var pbCredLargeBlob: Pointer? = null
    @JvmField var dwCredLargeBlobStatus: Int = 0

    // ── VERSION_3 fields ──────────────────────────────────────────────────────
    @JvmField var pHmacSecret: Pointer? = null   // PWEBAUTHN_HMAC_SECRET_SALT

    // ── VERSION_4 fields ──────────────────────────────────────────────────────
    @JvmField var dwUsedTransport: Int = 0

    // ── VERSION_5 fields ──────────────────────────────────────────────────────
    @JvmField var cbUnsignedExtensionOutputs: Int = 0
    @JvmField var pbUnsignedExtensionOutputs: Pointer? = null

    // ── VERSION_6 fields ──────────────────────────────────────────────────────
    @JvmField var cbClientDataJSON: Int = 0
    @JvmField var pbClientDataJSON: Pointer? = null
    @JvmField var cbAuthenticationResponseJSON: Int = 0
    @JvmField var pbAuthenticationResponseJSON: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf(
        // v1
        "dwVersion",
        "cbAuthenticatorData", "pbAuthenticatorData",
        "cbSignature", "pbSignature",
        "credDwVersion", "credCbId", "credPbId", "credPwszCredentialType",
        "cbUserId", "pbUserId",
        // v2
        "cExtensions", "pExtensions",
        "cbCredLargeBlob", "pbCredLargeBlob",
        "dwCredLargeBlobStatus",
        // v3
        "pHmacSecret",
        // v4
        "dwUsedTransport",
        // v5
        "cbUnsignedExtensionOutputs", "pbUnsignedExtensionOutputs",
        // v6
        "cbClientDataJSON", "pbClientDataJSON",
        "cbAuthenticationResponseJSON", "pbAuthenticationResponseJSON",
    )

    companion object {
        fun fromPointer(p: Pointer): WinWebAuthnAssertion {
            val s = WinWebAuthnAssertion()
            s.useMemory(p)
            s.read()
            return s
        }
    }
}
