// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import fr.techtical.nextsh.core.auth.HardwareKeyAuthenticator
import fr.techtical.nextsh.core.ssh.Fido2PromptCoordinator
import fr.techtical.nextsh.ui.sessions.Fido2ChallengeBottomSheet

/**
 * Hôte global de la demande de clé FIDO2, rendu par MainActivity au-dessus du NavGraph (comme
 * [HostKeyPromptDialog] pour la clé hôte). Observe le [Fido2PromptCoordinator] Singleton : la
 * demande s'affiche quel que soit l'écran qui a lancé la connexion (liste d'hôtes, SFTP, tunnels,
 * terminal).
 *
 * `key(request)` : deux demandes qui se suivent sans que l'écran ait le temps de recomposer sur
 * `null` doivent chacune repartir d'un état neuf. Sans cela, la seconde réutiliserait la feuille
 * de la première, et sa lecture de la clé ne démarrerait jamais. La clé est la demande, pas
 * l'état entier : prolonger l'échéance (saisie du PIN) ne doit pas recréer la feuille.
 *
 * Le compte à rebours de la feuille est lu sur l'échéance du coordinator, et la saisie du PIN la
 * repousse : la feuille ne disparaît pas pendant qu'on tape son PIN, et une rotation ne remet pas
 * le compteur à 60 s.
 */
@Composable
fun Fido2PromptHost(
    coordinator: Fido2PromptCoordinator,
    hardwareKeyAuthenticator: HardwareKeyAuthenticator,
) {
    val prompt by coordinator.state.collectAsState()
    val current = prompt ?: return
    val request = current.request

    key(request) {
        Fido2ChallengeBottomSheet(
            prompt = current,
            nowMs = { coordinator.nowMs() },
            hardwareKeyAuthenticator = hardwareKeyAuthenticator,
            onSigned = { signature -> coordinator.complete(request, signature) },
            onCancel = { coordinator.cancel(request) },
            onKeyError = { reason -> coordinator.failKey(request, reason) },
            onPinRequested = {
                coordinator.extendDeadline(request, Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)
            },
        )
    }
}
