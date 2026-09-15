// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Demande `POST_NOTIFICATIONS` la première fois que [trigger] passe à vrai.
 *
 * Pourquoi c'est nécessaire : les deux services au premier plan de NextSH
 * s'exécutent sans cette permission, mais leur notification n'apparaît alors
 * pas dans le volet, seulement dans le gestionnaire de tâches du système.
 * L'utilisateur perd la progression d'un transfert, l'état de ses tunnels et
 * surtout l'action "Tout arrêter", qui n'existe que dans la notification.
 *
 * Le refus n'est pas bloquant : tunnels et transferts continuent de
 * fonctionner, seule leur visibilité est perdue.
 *
 * Sans effet en dessous d'Android 13, où la permission n'existe pas et les
 * notifications sont accordées d'office.
 *
 * @param trigger passe à vrai quand l'utilisateur déclenche une action qui
 *                produira une notification (démarrer un tunnel, lancer un
 *                transfert). Demander plus tôt, hors contexte, augmente le
 *                taux de refus définitif.
 */
@Composable
fun NotificationPermissionEffect(trigger: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    // Survit à la rotation : sans cela, tourner l'écran redemanderait la
    // permission à chaque recomposition suivant un trigger encore vrai.
    var alreadyAsked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Timber.i("Permission POST_NOTIFICATIONS ${if (granted) "accordée" else "refusée"}")
    }

    LaunchedEffect(trigger) {
        if (!trigger || alreadyAsked) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            alreadyAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
