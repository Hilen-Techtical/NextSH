// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.core.ssh.SshConnectErrorClassification
import fr.techtical.nextsh.shared.core.ssh.classifySshConnectError
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import java.io.IOException

/**
 * Résolveur de string Android pour [SshConnectErrorClassification] : la
 * classification pure (exception → catégorie + [SshErrorCode]) vit désormais
 * dans `:shared` (`fr.techtical.nextsh.shared.core.ssh.classifySshConnectError`,
 * source set `jvmCommon`), partagée à l'identique avec Desktop
 * (`desktop/.../core/ssh/SshConnectErrorMapper.kt`), voir la table unifiée
 * dans le KDoc de `classifySshConnectError`.
 *
 * Ce fichier ne fait plus que résoudre les clés `R.string.connect_error_*` à
 * partir de cette classification.
 *
 * [GENERIC_ERROR_CODE] : Android retombait déjà en `UNKNOWN` pour toute
 * `IOException` non explicitement branchée (`ConnectException`/
 * `SocketTimeoutException`) via son catch générique, comportement préservé
 * à l'identique pour le panier fourre-tout
 * ([SshConnectErrorClassification.Generic]).
 */
private val GENERIC_ERROR_CODE = SshErrorCode.UNKNOWN

/**
 * Résout [classifySshConnectError] en message localisé (`R.string.connect_error_*`).
 * Remplace `e.message` brut (JDK, en anglais) par un message compréhensible
 * par un utilisateur non-technicien.
 *
 * @param timeoutSeconds Réglage « Timeout de connexion » (Settings, 5-30 s), déjà en secondes.
 */
internal fun connectErrorMessage(context: Context, e: IOException, timeoutSeconds: Int): String =
    resolveMessage(context, classifySshConnectError(e, timeoutSeconds * 1000, GENERIC_ERROR_CODE))

/**
 * Combine [classifySshConnectError] + résolution du message en un seul
 * [SshResult.Error] prêt à retourner depuis un bloc `catch (e: IOException)`.
 * Miroir de `connectErrorResult` côté Desktop : voir
 * `desktop/.../core/ssh/SshConnectErrorMapper.kt`.
 */
internal fun connectErrorResult(context: Context, e: IOException, timeoutSeconds: Int): SshResult.Error {
    val classification = classifySshConnectError(e, timeoutSeconds * 1000, GENERIC_ERROR_CODE)
    return SshResult.Error(classification.errorCode, resolveMessage(context, classification))
}

private fun resolveMessage(context: Context, classification: SshConnectErrorClassification): String =
    when (classification) {
        is SshConnectErrorClassification.TimeoutConnect ->
            context.getString(R.string.connect_error_timeout, classification.timeoutSeconds)
        is SshConnectErrorClassification.ServerSilent ->
            context.getString(R.string.connect_error_refused)
        is SshConnectErrorClassification.HostNotFound ->
            context.getString(R.string.connect_error_host_not_found)
        is SshConnectErrorClassification.Generic -> {
            val detail = classification.detail
            if (detail != null) context.getString(R.string.connect_error_generic_detail, detail)
            else context.getString(R.string.connect_error_generic)
        }
    }
