// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.KeyRound
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.fido2_touch_cancel
import fr.techtical.nextsh.desktop.generated.resources.fido2_touch_subtitle
import fr.techtical.nextsh.desktop.generated.resources.fido2_touch_title
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection
import org.jetbrains.compose.resources.stringResource

/**
 * Dialog affiché pendant l'attente du touch physique sur le YubiKey.
 *
 * Affiché depuis App.kt quand [Fido2UiState.touchInProgress] est non-null.
 * [DialogWindow] est utilisé (pas un Compose Popup) pour flotter au-dessus
 * des SwingPanel JediTerm : même pattern que [UnknownHostKeyDialog].
 *
 * L'annulation via le bouton "Annuler" appelle [onCancel] qui est actuellement
 * un no-op : CTAP2 GetAssertion ne peut pas être interrompu proprement sans
 * support dédié dans YubiKit. L'opération finira par timeout (30s par défaut).
 *
 * @param visible    true tant que l'attente du touch est en cours.
 * @param deviceLabel Nom du YubiKey détecté (ex. "YubiKey 5 NFC").
 * @param onCancel   Callback bouton Annuler (actuellement no-op).
 */
@Composable
fun YubiKeyTouchDialog(
    visible: Boolean,
    deviceLabel: String?,
    onCancel: () -> Unit,
) {
    if (!visible) return

    DialogWindow(
        onCloseRequest = onCancel,
        title = stringResource(Res.string.fido2_touch_title),
        state = rememberDialogState(size = DpSize(480.dp, 280.dp)),
        resizable = false,
    ) {
        WindowCaptureProtection(window)
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "iconAlpha",
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NearBlack)
                .padding(horizontal = 40.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Icône clé avec animation de pulse
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(Gold.copy(alpha = alpha * 0.12f))
                    .border(2.dp, Gold.copy(alpha = alpha * 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.KeyRound,
                    contentDescription = null,
                    tint = Gold.copy(alpha = alpha),
                    modifier = Modifier.size(40.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            // Titre
            Text(
                text = stringResource(Res.string.fido2_touch_title),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            // Sous-titre avec nom du device
            Text(
                text = stringResource(Res.string.fido2_touch_subtitle, deviceLabel ?: ""),
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // Bouton Annuler (outlined Burgundy)
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Burgundy),
                border = androidx.compose.foundation.BorderStroke(1.dp, Burgundy.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(Radii.Md),
            ) {
                Text(
                    text = stringResource(Res.string.fido2_touch_cancel),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
