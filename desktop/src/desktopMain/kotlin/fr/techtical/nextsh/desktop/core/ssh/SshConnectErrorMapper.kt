// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_cancelled
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_key_invalid
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_no_key
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_refused
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_touch_timeout
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_unavailable
import fr.techtical.nextsh.desktop.generated.resources.auth_error_refused
import fr.techtical.nextsh.desktop.generated.resources.connect_error_generic
import fr.techtical.nextsh.desktop.generated.resources.connect_error_generic_detail
import fr.techtical.nextsh.desktop.generated.resources.connect_error_host_not_found
import fr.techtical.nextsh.desktop.generated.resources.connect_error_refused
import fr.techtical.nextsh.desktop.generated.resources.connect_error_timeout
import fr.techtical.nextsh.desktop.generated.resources.error_ssh_unknown
import fr.techtical.nextsh.shared.core.ssh.SshConnectErrorClassification
import fr.techtical.nextsh.shared.core.ssh.classifySshConnectError
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import net.schmizz.sshj.userauth.UserAuthException
import org.jetbrains.compose.resources.getString
import java.io.IOException

/**
 * Résolveur de string Desktop pour [SshConnectErrorClassification] : la
 * classification pure (exception → catégorie + [SshErrorCode]) vit désormais
 * dans `:shared` (`fr.techtical.nextsh.shared.core.ssh.classifySshConnectError`,
 * source set `jvmCommon`), partagée à l'identique avec Android
 * (`app/.../core/ssh/SshConnectErrorMapper.kt`) : voir la table unifiée dans
 * le KDoc de `classifySshConnectError`.
 *
 * Ce fichier ne fait plus que résoudre les clés `connect_error_*`
 * (compose-resources) à partir de cette classification. Point d'entrée unique,
 * partagé par les 4 sites qui catchaient jusqu'ici `IOException` :
 *  - [DesktopSshSessionManager.connectWithPassword]
 *  - [DesktopSshSessionManager.connectWithFido2]
 *  - [DesktopSshSessionManager.connectInternal] (clé / certificat)
 *  - [VaultSshClientFactory.open]
 *
 * [GENERIC_ERROR_CODE] : Desktop utilisait `HOST_UNREACHABLE` pour TOUTE
 * `IOException` avant ce correctif : préservé comme code de repli pour le
 * panier fourre-tout (`SshConnectErrorClassification.Generic`) plutôt qu'un
 * changement de comportement non demandé. Les timeouts et refus DNS/TCP, eux,
 * obtiennent désormais leur code dédié via la table partagée.
 */
private val GENERIC_ERROR_CODE = SshErrorCode.HOST_UNREACHABLE

/**
 * Combine [classifySshConnectError] + résolution du message localisé
 * (compose-resources, clés `connect_error_*`) en un seul [SshResult.Error]
 * prêt à retourner depuis un bloc `catch (e: IOException)`. `suspend` car
 * [getString] l'est : tous les call sites sont déjà dans un contexte
 * `withContext(Dispatchers.IO)`/`suspend`.
 *
 * Remplace le pattern répété `SshResult.Error(SshErrorCode.HOST_UNREACHABLE,
 * connectErrorMessage(e, ...))` qui codait TOUJOURS `HOST_UNREACHABLE`, y
 * compris pour les timeouts : c'est le correctif de l'asymétrie Desktop/Android.
 */
internal suspend fun connectErrorResult(e: IOException, connectTimeoutMs: Int): SshResult.Error {
    val classification = classifySshConnectError(e, connectTimeoutMs, GENERIC_ERROR_CODE)
    return SshResult.Error(classification.errorCode, resolveMessage(classification))
}

// ── Messages d'échec d'AUTHENTIFICATION ─────────────────────────────────────
// Même logique que connect_error_* : les managers du package retournaient du
// français codé en dur ; la résolution passe par les ressources. Fonctions
// top-level internal : visibles des managers sans import (même package).

/** `AUTH_FAILED` générique : le détail technique reste en suffixe. */
internal suspend fun authRefusedMessage(detail: String?): String =
    getString(Res.string.auth_error_refused, detail ?: "?")

/** Fido2Signer absent sur cette plateforme (FIDO2 non câblé). */
internal suspend fun fido2UnavailableMessage(): String =
    getString(Res.string.auth_error_fido2_unavailable)

/** SkKeyHandle illisible depuis le vault. */
internal suspend fun fido2KeyInvalidMessage(detail: String?): String =
    getString(Res.string.auth_error_fido2_key_invalid, detail ?: "?")

/**
 * Décode les erreurs FIDO2 encodées dans le message de [UserAuthException]
 * (protocole interne `FIDO2_*` posé par SkAuthMethod) vers le bon
 * [SshErrorCode] + message localisé.
 */
internal suspend fun fido2AuthErrorResult(e: UserAuthException): SshResult.Error {
    val msg = e.message ?: ""
    return when {
        "FIDO2_NO_KEY" in msg ->
            SshResult.Error(SshErrorCode.FIDO2_NO_KEY, getString(Res.string.auth_error_fido2_no_key))
        "FIDO2_USER_CANCELLED" in msg ->
            SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, getString(Res.string.auth_error_fido2_cancelled))
        "FIDO2_TIMEOUT" in msg ->
            SshResult.Error(SshErrorCode.FIDO2_TIMEOUT, getString(Res.string.auth_error_fido2_touch_timeout))
        else ->
            SshResult.Error(SshErrorCode.AUTH_FAILED, getString(Res.string.auth_error_fido2_refused, e.message ?: "?"))
    }
}

/** Fallback `UNKNOWN` : le message technique s'il existe, sinon localisé. */
internal suspend fun unknownSshErrorMessage(e: Exception): String =
    e.message ?: getString(Res.string.error_ssh_unknown)

private suspend fun resolveMessage(classification: SshConnectErrorClassification): String =
    when (classification) {
        is SshConnectErrorClassification.TimeoutConnect ->
            getString(Res.string.connect_error_timeout, classification.timeoutSeconds)
        is SshConnectErrorClassification.ServerSilent ->
            getString(Res.string.connect_error_refused)
        is SshConnectErrorClassification.HostNotFound ->
            getString(Res.string.connect_error_host_not_found)
        is SshConnectErrorClassification.Generic -> {
            val detail = classification.detail
            if (detail != null) getString(Res.string.connect_error_generic_detail, detail)
            else getString(Res.string.connect_error_generic)
        }
    }
