// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Enroller
import fr.techtical.nextsh.desktop.core.auth.fido2.YubiKitFidoManager
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.randomUuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Message affiché quand Windows Hello verrouille l'interface HID FIDO en accès exclusif.
 *
 * Sur Windows 10 1903+ / 11, le service WebAuthn/Windows Hello revendique l'interface
 * HID FIDO (usage page 0xF1D0) des YubiKey. Les applis non-élevées voient HID:0.
 * Solutions sans UAC : (a) activer CCID dans YubiKey Manager → PC/SC, (b) lecteur NFC,
 * (c) lancer NextSH en admin (contournement temporaire, non recommandé en prod).
 *
 * Note : une vraie intégration Windows WebAuthn API (ms-webauthn) permettrait d'éviter
 * ce problème sans droits admin, mais représente un travail significatif (Phase 3+).
 */
internal const val WINDOWS_HID_LOCKED_MESSAGE =
    "Aucune YubiKey détectée. Sur Windows, l'accès direct USB HID FIDO est réservé à Windows Hello.\n\n" +
    "Solutions :\n" +
    "  (a) Activer l'interface CCID dans YubiKey Manager pour exposer la clé en PC/SC,\n" +
    "  (b) Brancher via un lecteur NFC USB (mode CCID natif),\n" +
    "  (c) Lancer NextSH en tant qu'administrateur (contournement temporaire)."

/**
 * Message affiché quand le bug PC/SC Windows+Java (SCARD_E_NOT_TRANSACTED / 0x80100027)
 * persiste même après les tentatives de bypass via [DirectPcscSmartCardConnection].
 *
 * Cause : JDK 17 + Windows PC/SC driver gèrent les transactions CCID différemment.
 * NextSH contourne déjà ce bug en évitant beginExclusive/endExclusive : si ce message
 * s'affiche, le driver Windows est dans un état très transitoire ou la clé est monopolisée.
 *
 * Backlog : Windows WebAuthn API native (ms-webauthn), Phase 3+.
 */
internal const val PCSC_WINDOWS_BUG_MESSAGE =
    "L'accès PC/SC à ta YubiKey échoue avec un bug connu Windows+Java " +
    "(SCARD_E_NOT_TRANSACTED / 0x80100027).\n\n" +
    "Workarounds :\n" +
    "  (a) Débrancher et rebrancher la YubiKey, puis réessayer,\n" +
    "  (b) Utiliser un lecteur NFC USB (mode CCID natif),\n" +
    "  (c) Lancer NextSH en tant qu'administrateur.\n\n" +
    "Note : l'implémentation Windows WebAuthn API native est prévue dans une prochaine version."

/**
 * États possibles du dialog d'enrôlement FIDO2.
 */
sealed class Fido2EnrollState {
    /** État initial : champs du formulaire visibles, prêt à démarrer. */
    object Idle : Fido2EnrollState()

    /** Phase 1 : détection du YubiKey en cours. */
    object DetectingDevice : Fido2EnrollState()

    /**
     * Phase 1 terminée avec PIN requis : l'UI affiche le champ PIN.
     *
     * @param deviceLabel Nom du YubiKey détecté.
     */
    data class WaitingForPin(val deviceLabel: String) : Fido2EnrollState()

    /**
     * Phase 2 : MakeCredential en cours, attente du touch physique.
     *
     * @param deviceLabel Nom du YubiKey qui clignote.
     */
    data class WaitingForTouch(val deviceLabel: String) : Fido2EnrollState()

    /**
     * Enrôlement réussi.
     *
     * @param key La [SshKey] nouvellement créée et sauvegardée dans le vault.
     */
    data class Success(val key: SshKey) : Fido2EnrollState()

    /**
     * Erreur survenue : l'utilisateur peut réessayer.
     *
     * @param message Message d'erreur lisible par l'utilisateur.
     */
    data class Error(val message: String) : Fido2EnrollState()
}

/**
 * ViewModel du dialog d'enrôlement FIDO2 (MakeCredential).
 *
 * Architecture deux phases (Option A) :
 * 1. [startDetection] → détecte la YubiKey, détermine si un PIN est requis.
 *    - Si pas de PIN → déclenche directement [startMakeCredential] avec pin=null.
 *    - Si PIN requis → passe en état [Fido2EnrollState.WaitingForPin].
 * 2. [submitPin] → l'utilisateur saisit le PIN → déclenche [startMakeCredential].
 * 3. [startMakeCredential] → appelle [Fido2Enroller.makeCredential],
 *    sauvegarde la [SshKey] dans le repository, passe en [Fido2EnrollState.Success].
 *
 * Sur Windows avec WebAuthn API native actif :
 * - [isWindowsWebAuthnMode] = true → l'UI cache le champ PIN et adapte le bouton.
 * - [detectDevice] retourne pinConfigured=false → flux sans PIN.
 * - Windows affiche son propre dialog Hello/WebAuthn nativement.
 *
 * Le label et le rpId sont capturés lors de [startDetection] et réutilisés
 * lors de [startMakeCredential] pour garantir la cohérence.
 *
 * @param enroller Interface [Fido2Enroller] (production : dispatcher WebAuthn ou YubiKit).
 * @param sshKeyRepository Repository des clés SSH.
 * @param appScope Scope de coroutine de l'application.
 */
class Fido2EnrollViewModel(
    private val enroller: Fido2Enroller = DesktopContainer.fido2Enroller,
    private val sshKeyRepository: SshKeyRepository = DesktopContainer.sshKeyRepository,
    private val appScope: AppScope = DesktopContainer.appScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * true si Windows WebAuthn API native est active (dispatcher a sélectionné WindowsWebAuthnProvider).
     * L'UI utilise ce flag pour :
     * - Masquer le champ PIN (Hello gère nativement)
     * - Changer le texte du bouton "Brancher la clé" → "Démarrer l'enrôlement"
     * - Afficher "Windows va te demander de toucher ta clé…" en [Fido2EnrollState.WaitingForTouch]
     *   au lieu de l'animation pulse Gold
     */
    val isWindowsWebAuthnMode: Boolean = runCatching {
        DesktopContainer.fido2Dispatcher.isUsingWindowsWebAuthn
    }.getOrDefault(false)
    private val _state = MutableStateFlow<Fido2EnrollState>(Fido2EnrollState.Idle)
    val state: StateFlow<Fido2EnrollState> = _state.asStateFlow()

    // Paramètres capturés lors de startDetection, réutilisés dans startMakeCredential
    private var capturedLabel: String = ""
    private var capturedRpId: String = "ssh:"
    private var capturedDeviceLabel: String = ""

    /**
     * Phase 1 : détecte la YubiKey et décide si un PIN est requis.
     *
     * Si pas de PIN → enchaîne directement sur [startMakeCredential].
     * Si PIN → passe en [Fido2EnrollState.WaitingForPin].
     *
     * @param label Label de la clé à créer (affiché dans le vault).
     * @param rpId  RP-ID FIDO2 (défaut "ssh:").
     */
    fun startDetection(label: String, rpId: String) {
        if (label.isBlank()) {
            _state.value = Fido2EnrollState.Error("Le label est requis.")
            return
        }
        capturedLabel = label.trim()
        capturedRpId = rpId.trim().ifBlank { "ssh:" }

        _state.value = Fido2EnrollState.DetectingDevice

        appScope.coroutineScope.launch {
            try {
                val detection = withContext(ioDispatcher) { enroller.detectDevice() }
                capturedDeviceLabel = detection.deviceLabel

                if (detection.pinConfigured) {
                    _state.value = Fido2EnrollState.WaitingForPin(detection.deviceLabel)
                } else {
                    // Pas de PIN → MakeCredential direct
                    startMakeCredential(pin = null)
                }
            } catch (e: YubiKitFidoManager.FidoError.WindowsHidLocked) {
                _state.value = Fido2EnrollState.Error(WINDOWS_HID_LOCKED_MESSAGE)
            } catch (e: YubiKitFidoManager.FidoError.PcscWindowsBug) {
                _state.value = Fido2EnrollState.Error(PCSC_WINDOWS_BUG_MESSAGE)
            } catch (e: YubiKitFidoManager.FidoError.NoDeviceFound) {
                _state.value = Fido2EnrollState.Error(
                    "Aucun YubiKey détecté. Branche ta clé via USB ou approche-la d'un lecteur NFC, puis réessaie."
                )
            } catch (e: YubiKitFidoManager.FidoError.TransportError) {
                // Affiche seulement la première ligne du message technique (évite la stack trace brute)
                val shortMsg = e.cause?.message?.lines()?.firstOrNull() ?: "erreur de transport"
                _state.value = Fido2EnrollState.Error("Erreur de transport : $shortMsg")
            } catch (e: YubiKitFidoManager.FidoError) {
                _state.value = Fido2EnrollState.Error(
                    "Erreur de détection : ${e.message ?: "erreur inconnue"}"
                )
            } catch (e: Exception) {
                _state.value = Fido2EnrollState.Error(
                    "Erreur inattendue : ${e.message ?: "erreur inconnue"}"
                )
            }
        }
    }

    /**
     * Phase 2 avec PIN : l'utilisateur a saisi le PIN → démarre le MakeCredential.
     *
     * Le tableau [pin] est wipé après usage par [Fido2Enroller.makeCredential].
     *
     * @param pin PIN saisi par l'utilisateur. Ne doit pas être vide.
     */
    fun submitPin(pin: CharArray) {
        if (pin.isEmpty()) {
            _state.value = Fido2EnrollState.Error("Le PIN ne peut pas être vide.")
            return
        }
        appScope.coroutineScope.launch {
            startMakeCredential(pin = pin)
        }
    }

    /**
     * Réinitialise l'état vers [Fido2EnrollState.Idle] pour permettre une nouvelle tentative.
     */
    fun reset() {
        _state.value = Fido2EnrollState.Idle
    }

    // ── Private ─────────────────────────────────────────────────────────────

    private suspend fun startMakeCredential(pin: CharArray?) {
        _state.value = Fido2EnrollState.WaitingForTouch(capturedDeviceLabel)

        try {
            val result = withContext(ioDispatcher) {
                enroller.makeCredential(
                    rpId = capturedRpId,
                    rpName = "NextSH",
                    userName = capturedLabel,
                    userDisplayName = capturedLabel,
                    pin = pin,
                    timeoutMs = 60_000,
                )
            }

            // Construire la clé publique au format OpenSSH selon le type effectif
            val publicKeyOpenSsh = when (result.keyType) {
                SkKeyType.SK_ECDSA_256 -> SkSshPublicKey.toOpenSshPublicKeyStringEcdsaP256(
                    application = result.application,
                    ecdsaP256PubKeyRaw = result.publicKey,
                    comment = "nextsh-fido2",
                )
                else -> SkSshPublicKey.toOpenSshPublicKeyString(
                    application = result.application,
                    ed25519PubKeyRaw = result.publicKey,
                    comment = "nextsh-fido2",
                )
            }

            // Mapper SkKeyType → SshKeyType (domain model)
            val sshKeyType = when (result.keyType) {
                SkKeyType.SK_ECDSA_256 -> SshKeyType.SK_ECDSA_256
                else -> SshKeyType.SK_ED25519
            }

            // Encoder le credentialId en Base64 standard (compatible sync Android)
            val credentialIdBase64 = Base64.getEncoder().encodeToString(result.credentialId)

            val skKey = SshKey(
                id = randomUuid(),
                label = capturedLabel,
                keyType = sshKeyType,
                publicKey = publicKeyOpenSsh,
                isBiometric = false,
                keystoreAlias = null,
                fido2CredentialId = credentialIdBase64,
                fido2RpId = result.application,
            )

            withContext(ioDispatcher) { sshKeyRepository.save(skKey) }

            _state.value = Fido2EnrollState.Success(skKey)
        } catch (e: YubiKitFidoManager.FidoError.WindowsHidLocked) {
            _state.value = Fido2EnrollState.Error(WINDOWS_HID_LOCKED_MESSAGE)
        } catch (e: YubiKitFidoManager.FidoError.PcscWindowsBug) {
            _state.value = Fido2EnrollState.Error(PCSC_WINDOWS_BUG_MESSAGE)
        } catch (e: YubiKitFidoManager.FidoError.NoDeviceFound) {
            _state.value = Fido2EnrollState.Error(
                "Aucun YubiKey détecté. Branche ta clé via USB ou approche-la d'un lecteur NFC, puis réessaie."
            )
        } catch (e: YubiKitFidoManager.FidoError.TouchTimeout) {
            _state.value = Fido2EnrollState.Error(
                "Timeout : tu n'as pas touché la clé à temps. Réessaie."
            )
        } catch (e: YubiKitFidoManager.FidoError.UserCancelled) {
            _state.value = Fido2EnrollState.Error(
                "PIN incorrect ou opération annulée."
            )
        } catch (e: YubiKitFidoManager.FidoError.CtapError) {
            _state.value = Fido2EnrollState.Error(
                "Erreur CTAP (0x${e.code.toString(16).uppercase()}) : ${e.msg}"
            )
        } catch (e: YubiKitFidoManager.FidoError.TransportError) {
            val shortMsg = e.cause?.message?.lines()?.firstOrNull() ?: "erreur de transport"
            _state.value = Fido2EnrollState.Error("Erreur de transport : $shortMsg")
        } catch (e: YubiKitFidoManager.FidoError) {
            _state.value = Fido2EnrollState.Error(
                e.message ?: "Erreur FIDO2 inconnue"
            )
        } catch (e: Exception) {
            _state.value = Fido2EnrollState.Error(
                "Erreur inattendue : ${e.message ?: "erreur inconnue"}"
            )
        }
    }
}
