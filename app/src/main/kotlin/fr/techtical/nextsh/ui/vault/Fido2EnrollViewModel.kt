// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.auth.EnrollFailure
import fr.techtical.nextsh.core.auth.Fido2EnrollManager
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.util.LogId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Arrays
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel pour l'écran d'enrôlement FIDO2 (sk-ssh-ed25519 / sk-ecdsa-sha2-nistp256 via YubiKey USB/NFC).
 *
 * États UI :
 *   Idle          → Formulaire (label + rpId + PIN optionnel), bouton "Brancher / Approcher"
 *   WaitingForKey → En attente de la clé physique (USB ou NFC) + enrôlement en une passe
 *   Success       → Clé enrôlée + sauvegardée dans le vault
 *   Error         → Raison de l'échec (l'écran la traduit) + bouton réessayer
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
         * The enrollment failed, for [reason]. A reason and not a sentence: the screen turns it
         * into text in the language of the user (see `enrollFailureMessageRes`).
         */
        data class Error(val reason: EnrollFailure) : EnrollState() {
            /** Whether "Try again" is offered: not for a key that cannot do it, whatever is tried. */
            val canRetry: Boolean
                get() = reason != EnrollFailure.PinBlocked && reason != EnrollFailure.UnsupportedKey

            /** Whether the PIN field of the form is what to look at: no PIN was given, or it was refused. */
            val isPinRequired: Boolean
                get() = reason is EnrollFailure.PinRequired || reason is EnrollFailure.PinIncorrect
        }
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

    // ── Actions publiques ─────────────────────────────────────────────────────

    /**
     * Lance la détection + enrôlement en une seule action (NFC-safe).
     *
     * Le PIN est passé en argument depuis le formulaire (peut être null/vide si la clé
     * n'en a pas). Si la clé exige un PIN et qu'aucun n'a été fourni, l'état passe à
     * [EnrollState.Error] avec [EnrollFailure.PinRequired] pour inviter l'utilisateur à ressaisir.
     *
     * @param pin PIN saisi par l'utilisateur. Null ou vide si la clé n'a pas de PIN: un tableau vide
     *            est pris pour l'absence de PIN, la clé ne le verrait pas autrement.
     *            This ViewModel owns the array from here on and zeroes it, whatever the outcome
     *            ([Fido2EnrollManager.detectAndEnroll] zeroes it too, once it has it).
     *
     * Ignored while an enrollment is under way (or done): a second press during the fade of the
     * screen would otherwise start a second one on the same key.
     */
    fun startEnroll(pin: CharArray?) {
        val current = _state.value
        if (current is EnrollState.WaitingForKey || current is EnrollState.Success) {
            // Nothing will use this PIN either: it does not stay in memory as typed.
            pin?.let(::wipe)
            return
        }
        if (label.isBlank()) {
            // Nothing will use the PIN: it does not stay in memory as typed.
            pin?.let(::wipe)
            _state.value = EnrollState.Error(EnrollFailure.NameMissing)
            return
        }
        _state.value = EnrollState.WaitingForKey

        val currentLabel = label.trim()
        val currentRpId = rpId.trim().ifBlank { "ssh:" }
        // An empty array is no PIN (the manager would ask the key to check one): null says so.
        val secret = pin?.takeIf { it.isNotEmpty() }

        viewModelScope.launch {
            try {
                val result = fido2EnrollManager.detectAndEnroll(
                    label = currentLabel,
                    rpId = currentRpId,
                    rpName = "NextSH",
                    pin = secret,
                )
                try {
                    val sshKey = buildSshKey(currentLabel, currentRpId, result)
                    sshKeyRepository.save(sshKey)
                    Timber.i("Fido2Enroll: key saved id=${LogId.of(sshKey.id)} type=${result.keyType}")
                    _state.value = EnrollState.Success(savedKey = sshKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The key made the credential, the vault did not keep it: not a failure of the key.
                    Timber.e("Fido2Enroll: key not saved, %s", e.javaClass.simpleName)
                    _state.value = EnrollState.Error(EnrollFailure.NotSaved)
                } finally {
                    result.wipe()
                }
            } catch (e: CancellationException) {
                // Re-throw : viewModelScope cancel intentionnel (sortie d'écran).
                // Ne PAS afficher d'erreur UI.
                throw e
            } catch (e: Fido2EnrollManager.EnrollError) {
                _state.value = EnrollState.Error(e.failure)
            } catch (e: Exception) {
                // CancellationException déjà filtrée plus haut. Only the kind of failure is logged.
                Timber.e("Fido2Enroll: unexpected error, %s", e.javaClass.simpleName)
                _state.value = EnrollState.Error(EnrollFailure.Unexpected)
            } finally {
                pin?.let(::wipe)
            }
        }
    }

    private fun wipe(pin: CharArray) = Arrays.fill(pin, '\u0000')

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
