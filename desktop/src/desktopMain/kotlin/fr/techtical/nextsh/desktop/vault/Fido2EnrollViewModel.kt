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
 * Reason of an enrollment failure. The dialog turns it into a text in the language of the app
 * (the texts of the two Windows cases live in the string resources: `fido2_enroll_windows_hid_locked`
 * and `fido2_enroll_pcsc_windows_bug`).
 *
 * [WindowsHidLocked]: on Windows 10 1903+ / 11, the WebAuthn/Windows Hello service claims the
 * HID FIDO interface (usage page 0xF1D0) of YubiKeys, so non-elevated apps see HID:0. Solutions
 * without UAC: enable CCID in YubiKey Manager (PC/SC), use an NFC reader, or run NextSH as
 * administrator (temporary workaround). On Windows, Fido2ProviderDispatcher picks the native
 * WebAuthn API when available, which avoids the problem: this case only concerns the YubiKit fallback.
 *
 * [PcscWindowsBug]: the Windows + Java PC/SC bug (SCARD_E_NOT_TRANSACTED / 0x80100027) persists
 * even after the bypass attempts of [DirectPcscSmartCardConnection]. Same remark as above: only the
 * YubiKit fallback can end here.
 */
enum class Fido2EnrollError {
    LabelRequired,
    PinEmpty,
    WindowsHidLocked,
    PcscWindowsBug,
    NoDevice,
    TouchTimeout,
    UserCancelled,

    Ctap,
    Transport,
    Detection,
    Unexpected,
    Fido2Unknown,
}

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
     * @param reason Cause de l'erreur, traduite à l'affichage.
     * @param detail Message technique de la bibliothèque (cause, message CTAP), jamais traduit.
     * @param ctapCode Code CTAP, pour [Fido2EnrollError.Ctap] seulement.
     */
    data class Error(
        val reason: Fido2EnrollError,
        val detail: String? = null,
        val ctapCode: Int? = null,
    ) : Fido2EnrollState()
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
     * - Afficher "Windows va vous demander de toucher votre clé…" en [Fido2EnrollState.WaitingForTouch]
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
            _state.value = Fido2EnrollState.Error(Fido2EnrollError.LabelRequired)
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
                _state.value = Fido2EnrollState.Error(Fido2EnrollError.WindowsHidLocked)
            } catch (e: YubiKitFidoManager.FidoError.PcscWindowsBug) {
                _state.value = Fido2EnrollState.Error(Fido2EnrollError.PcscWindowsBug)
            } catch (e: YubiKitFidoManager.FidoError.NoDeviceFound) {
                _state.value = Fido2EnrollState.Error(Fido2EnrollError.NoDevice)
            } catch (e: YubiKitFidoManager.FidoError.TransportError) {
                // Affiche seulement la première ligne du message technique (évite la stack trace brute)
                _state.value = Fido2EnrollState.Error(
                    Fido2EnrollError.Transport,
                    detail = e.cause?.message?.lines()?.firstOrNull(),
                )
            } catch (e: YubiKitFidoManager.FidoError) {
                _state.value = Fido2EnrollState.Error(Fido2EnrollError.Detection, detail = e.message)
            } catch (e: Exception) {
                _state.value = Fido2EnrollState.Error(Fido2EnrollError.Unexpected, detail = e.message)
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
            _state.value = Fido2EnrollState.Error(Fido2EnrollError.PinEmpty)
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
            _state.value = Fido2EnrollState.Error(Fido2EnrollError.WindowsHidLocked)
        } catch (e: YubiKitFidoManager.FidoError.PcscWindowsBug) {
            _state.value = Fido2EnrollState.Error(Fido2EnrollError.PcscWindowsBug)
        } catch (e: YubiKitFidoManager.FidoError.NoDeviceFound) {
            _state.value = Fido2EnrollState.Error(Fido2EnrollError.NoDevice)
        } catch (e: YubiKitFidoManager.FidoError.TouchTimeout) {
            _state.value = Fido2EnrollState.Error(Fido2EnrollError.TouchTimeout)
        } catch (e: YubiKitFidoManager.FidoError.UserCancelled) {
            _state.value = Fido2EnrollState.Error(Fido2EnrollError.UserCancelled)
        } catch (e: YubiKitFidoManager.FidoError.CtapError) {
            _state.value = Fido2EnrollState.Error(Fido2EnrollError.Ctap, detail = e.msg, ctapCode = e.code)
        } catch (e: YubiKitFidoManager.FidoError.TransportError) {
            _state.value = Fido2EnrollState.Error(
                Fido2EnrollError.Transport,
                detail = e.cause?.message?.lines()?.firstOrNull(),
            )
        } catch (e: YubiKitFidoManager.FidoError) {
            _state.value = Fido2EnrollState.Error(Fido2EnrollError.Fido2Unknown, detail = e.message)
        } catch (e: Exception) {
            _state.value = Fido2EnrollState.Error(Fido2EnrollError.Unexpected, detail = e.message)
        }
    }
}
