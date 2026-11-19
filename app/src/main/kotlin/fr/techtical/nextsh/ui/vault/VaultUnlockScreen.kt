// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import fr.techtical.nextsh.ui.components.BrandText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Fingerprint
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White

/**
 * VaultUnlockScreen : refonte Phase 3.1 (DA Techtical portée du Desktop).
 *
 * C'est l'écran de démarrage, la route [fr.techtical.nextsh.ui.navigation.Routes.VaultUnlock] : son
 * contenu est celui de [VaultUnlockPanel], que l'écran de verrou (`AppLockScreen`) affiche aussi par-dessus
 * l'app. Le succès appelle `AppLock.unlock()` dans le ViewModel, avant la navigation.
 *
 * Conserve l'auth **biométrique** Android-only (BiometricPrompt + Keystore
 * TEE/StrongBox) : pas de PIN ici, contrairement au Desktop. La refonte
 * porte uniquement sur l'esthétique : background NearBlack explicite,
 * tagline Space Grotesk avec letter-spacing UPPERCASE, bouton primary
 * Burgundy avec icon-wrap Fingerprint, message d'erreur en banner
 * ErrorRed Border1.
 */
@Composable
fun VaultUnlockScreen(
    onUnlocked: () -> Unit,
    viewModel: VaultUnlockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isUnlocked) {
        if (state.isUnlocked) onUnlocked()
    }

    VaultUnlockPanel(
        biometricAvailable = state.biometricAvailable,
        isAuthenticating = state.isAuthenticating,
        errorRes = state.errorRes,
        onUnlock = viewModel::authenticate,
    )
}

/**
 * Le contenu du déverrouillage, partagé par l'écran de démarrage et l'écran de verrou : logo, marque,
 * slogan, bouton (ou la raison pour laquelle il n'y en a pas) et bandeau d'erreur, sur tout l'écran
 * en NearBlack. Il ne sait rien de ce qui l'entoure, ni route ni fenêtre.
 */
@Composable
internal fun VaultUnlockPanel(
    biometricAvailable: Boolean,
    isAuthenticating: Boolean,
    @StringRes errorRes: Int?,
    onUnlock: () -> Unit,
) {
    // Le fond couvre tout l'écran ; le contenu respecte les barres système (dans la fenêtre de l'écran de
    // verrou, ce padding est le seul à les porter) et défile : l'écran de verrou peut s'ouvrir en paysage
    // (terminal, SFTP), où le logo, le bouton et le bandeau d'erreur ne tiennent pas sur 360 dp de haut.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack)
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = Spacing.Md),
        ) {
            // ── Logo ────────────────────────────────────────────────────────
            Image(
                painter = painterResource(id = R.drawable.nextsh_logo),
                contentDescription = stringResource(R.string.label_nextsh_logo),
                modifier = Modifier.size(180.dp),
            )

            Spacer(Modifier.height(Spacing.Md))

            // ── Brand "Next" Gold + "SH" Burgundy (parité Desktop) ──────
            BrandText(fontSize = 28.sp)

            Spacer(Modifier.height(Spacing.Sm))

            // ── Tagline (Space Grotesk UPPERCASE letter-spacing) ─────────────
            Text(
                text = stringResource(R.string.label_secure_ssh_client).uppercase(),
                color = GoldLight,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.Huge))

            // ── Bouton déverrouillage ou message fallback ────────────────────
            if (biometricAvailable) {
                BtnUnlockPrimary(
                    label = stringResource(R.string.action_unlock_vault),
                    enabled = !isAuthenticating,
                    onClick = onUnlock,
                )
            } else {
                FallbackBlock(
                    text = stringResource(R.string.label_no_device_lock),
                )
            }

            // ── Message d'erreur ─────────────────────────────────────────────
            errorRes?.let { error ->
                Spacer(Modifier.height(Spacing.Md))
                ErrorBanner(text = stringResource(error))
            }
        }
    }
}

@Composable
private fun BtnUnlockPrimary(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Burgundy,
            contentColor = White,
        ),
        shape = RoundedCornerShape(Radii.Lg),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Icon(Lucide.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = SpaceGroteskFamily)
    }
}

@Composable
private fun FallbackBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(BorderStroke(1.dp, Border1), RoundedCornerShape(Radii.Lg))
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ErrorRed.copy(alpha = 0.08f), RoundedCornerShape(Radii.Md))
            .border(1.dp, ErrorRed.copy(alpha = 0.30f), RoundedCornerShape(Radii.Md))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.TriangleAlert,
            contentDescription = null,
            tint = ErrorRed,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Spacing.Sm))
        Text(
            text = text,
            color = ErrorRed,
            fontSize = 12.sp,
        )
    }
}
