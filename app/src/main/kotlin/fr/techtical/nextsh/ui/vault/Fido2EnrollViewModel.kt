// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.auth.Fido2EnrollManager
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel pour l'écran d'enrôlement FIDO2 (sk-ssh-ed25519 / sk-ecdsa-sha2-nistp256 via YubiKey USB/NFC).
 *
 * États UI :
 *   Idle          → Formulaire (label + rpId + PIN optionnel), bouton "Brancher / Approcher"
 *   WaitingForKey → En attente de la clé physique (USB ou NFC) + enrôlement en une passe
 *   Success       → Clé enrôlée + sauvegardée dans le vault
 *   Error         → Message d'erreur + bouton réessayer
 *
 * Le PIN est collecté dans le formulaire AVANT de brancher/approcher la clé.
 * Cela permet un flow NFC-safe : detect + enroll se font dans la même connexion.
 */
@HiltViewModel
class Fido2EnrollViewModel @Inject constructor(
    private val fido2EnrollManager: Fido2EnrollManager,
    private val sshKeyRepository: SshKeyRepository,
) : ViewModel() {

    // ── État UI ───────────────────────────────────────────────────────────────

    sealed class EnrollState {
        /** Formulaire initial, aucune action en cours. */
        data object Idle : EnrollState()

        /**
         * En attente de la clé physique (USB plug ou NFC tap).
         * Couvre toute la phase detect + enroll en une seule connexion.
         */
        data object WaitingForKey : EnrollState()

        /**
         * Enrôlement réussi.
         * @param savedKey La [SshKey] SK-ED25519 ou SK-ECDSA-256 ajoutée dans le vault.
         */
        data class Success(val savedKey: SshKey) : EnrollState()

        /**
         * Erreur.
         * @param message      Message humain.
         * @param canRetry     Si vrai, le bouton "Réessayer" est affiché.
         * @param isPinRequired Si vrai, indique que la clé exige un PIN non fourni.
         */
        data class Error(
            val message: String,
            val canRetry: Boolean = true,
            val isPinRequired: Boolean = false,
        ) : EnrollState()
    }

    private val _state = MutableStateFlow<EnrollState>(EnrollState.Idle)
    val state: StateFlow<EnrollState> = _state.asStateFlow()

    // ── Champs formulaire ─────────────────────────────────────────────────────

    /** Label de la clé saisi par l'utilisateur (non-blank requis). */
    var label: String = ""
        private set

    /** rpId FIDO2 (défaut "ssh:"). */
    var rpId: String = "ssh:"
        private set

    // ── Setters formulaire ────────────────────────────────────────────────────

    fun onLabelChange(value: String) { label = value }
    fun onRpIdChange(value: String) { rpId = value.ifBlank { "ssh:" } }

    // ── Gestion cycle de vie ──────────────────────────────────────────────────

    /** Délègue à [Fido2EnrollManager.startListening]. Appeler depuis DisposableEffect. */
    fun startListening(activity: android.app.Activity) {
        fido2EnrollManager.startListening(activity)
    }

    /** Délègue à [Fido2EnrollManager.stopListening]. Appeler depuis DisposableEffect.onDispose. */
    fun stopListening() {
        fido2EnrollManager.stopListening()
    }

    // ── Actions publiques ─────────────────────────────────────────────────────

    /**
     * Lance la détection + enrôlement en une seule action (NFC-safe).
     *
     * Le PIN est passé en argument depuis le formulaire (peut être null/vide si la clé
     * n'en a pas). Si la clé exige un PIN et qu'aucun n'a été fourni, l'état passe à
     * [EnrollState.Error] avec [isPinRequired=true] pour inviter l'utilisateur à ressaisir.
     *
     * @param pin PIN saisi par l'utilisateur. Null ou vide si la clé n'a pas de PIN.
     *            Wipé automatiquement par [Fido2EnrollManager.detectAndEnroll].
     */
    fun startEnroll(pin: CharArray?) {
        if (label.isBlank()) {
            _state.value = EnrollState.Error("Le nom de la clé ne peut pas être vide")
            return
        }
        _state.value = EnrollState.WaitingForKey

        val currentLabel = label.trim()
        val currentRpId = rpId.trim().ifBlank { "ssh:" }

        viewModelScope.launch {
            try {
                val result = fido2EnrollManager.detectAndEnroll(
                    label = currentLabel,
                    rpId = currentRpId,
                    rpName = "NextSH",
                    pin = pin,
                )
                try {
                    val sshKey = buildSshKey(currentLabel, currentRpId, result)
                    sshKeyRepository.save(sshKey)
                    Timber.i("Fido2Enroll: key saved id=${sshKey.id} label=${sshKey.label} type=${result.keyType}")
                    _state.value = EnrollState.Success(savedKey = sshKey)
                } finally {
                    result.wipe()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Re-throw : viewModelScope cancel intentionnel (sortie d'écran).
                // Ne PAS afficher d'erreur UI.
                throw e
            } catch (e: Fido2EnrollManager.EnrollError.PinRequired) {
                _state.value = EnrollState.Error(
                    message = "Cette clé est protégée par un PIN. Saisissez-le dans le formulaire puis réessayez.",
                    canRetry = true,
                    isPinRequired = true,
                )
            } catch (e: Fido2EnrollManager.EnrollError.PinInvalid) {
                _state.value = EnrollState.Error(
                    message = "PIN incorrect. Vérifiez votre PIN et réessayez.",
                    canRetry = true,
                    isPinRequired = true,
                )
            } catch (e: Fido2EnrollManager.EnrollError.PinBlocked) {
                _state.value = EnrollState.Error(
                    message = "Clé bloquée après trop d'essais PIN incorrects. Un reset complet est requis.",
                    canRetry = false,
                )
            } catch (e: Fido2EnrollManager.EnrollError.UnsupportedAlgorithm) {
                _state.value = EnrollState.Error(
                    message = "Cette clé ne supporte ni Ed25519 ni ECDSA P-256. Seules les YubiKey 5+ sont compatibles.",
                    canRetry = false,
                )
            } catch (e: Fido2EnrollManager.EnrollError.Timeout) {
                _state.value = EnrollState.Error(
                    message = "Aucune clé détectée en 60 secondes. Branchez votre YubiKey ou approchez-la du téléphone (NFC).",
                )
            } catch (e: Fido2EnrollManager.EnrollError) {
                _state.value = EnrollState.Error(
                    message = e.message ?: "Erreur d'enrôlement",
                )
            } catch (e: Exception) {
                // CancellationException déjà filtrée plus haut
                Timber.e(e, "Fido2Enroll: unexpected error")
                _state.value = EnrollState.Error(
                    message = "Erreur inattendue : ${e.localizedMessage}",
                )
            }
        }
    }

    /** Retour à l'état Idle pour une nouvelle tentative. */
    fun reset() {
        _state.value = EnrollState.Idle
    }

    // ── Construction de la SshKey ─────────────────────────────────────────────

    /**
     * Construit une [SshKey] depuis le résultat d'enrôlement.
     *
     * Dispatche sur [result.keyType] pour produire le bon format OpenSSH :
     *   - SK_ED25519   → "sk-ssh-ed25519@openssh.com AAAA..."
     *   - SK_ECDSA_256 → "sk-ecdsa-sha2-nistp256@openssh.com AAAA..."
     *
     * Parité Desktop Fido2EnrollViewModel.kt:242-253.
     */
    private fun buildSshKey(
        label: String,
        rpId: String,
        result: Fido2EnrollManager.EnrollResult,
    ): SshKey {
        val authorizedKeysLine = when (result.keyType) {
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

        val sshKeyType = when (result.keyType) {
            SkKeyType.SK_ECDSA_256 -> SshKeyType.SK_ECDSA_256
            else -> SshKeyType.SK_ED25519
        }

        val credentialIdB64 = Base64.encodeToString(result.credentialId, Base64.NO_WRAP)

        return SshKey(
            id = UUID.randomUUID().toString(),
            label = label,
            keyType = sshKeyType,
            publicKey = authorizedKeysLine,
            isBiometric = false,
            keystoreAlias = null,
            fido2CredentialId = credentialIdB64,
            fido2RpId = rpId,
        )
    }
}
