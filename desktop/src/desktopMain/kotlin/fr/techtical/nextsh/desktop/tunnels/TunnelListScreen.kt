// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Square
import fr.techtical.nextsh.desktop.components.PageHeader
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_close
import fr.techtical.nextsh.desktop.generated.resources.tunnels_action_new
import fr.techtical.nextsh.desktop.generated.resources.tunnels_action_start_all
import fr.techtical.nextsh.desktop.generated.resources.tunnels_action_stop_all
import fr.techtical.nextsh.desktop.generated.resources.tunnels_empty_hint_alt
import fr.techtical.nextsh.desktop.generated.resources.tunnels_empty_title
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_dialog_title
import fr.techtical.nextsh.desktop.generated.resources.tunnels_subtitle
import fr.techtical.nextsh.desktop.generated.resources.tunnels_title
import org.jetbrains.compose.resources.stringResource

/**
 * Écran liste des tunnels, refonte Phase 2.3.
 *
 * Layout : Column NearBlack → PageHeader → (EmptyState | LazyColumn de TunnelCard).
 * Suppression : Scaffold Material3 / TopAppBar / FAB / SnackbarHost / boutons "Démarrer tout / Arrêter tout".
 * Erreurs collectées via viewModel.errors → AlertDialog Compose simple (cohérent avec HostList).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TunnelListScreen(
    onBack: () -> Unit,
    onAddTunnel: () -> Unit,
    onEditTunnel: (String) -> Unit,
) {
    val viewModel = remember { TunnelListViewModel() }
    val tunnels by viewModel.tunnels.collectAsState()
    val hosts by viewModel.hosts.collectAsState()
    val states by viewModel.tunnelStates.collectAsState()
    val latencies by viewModel.latenciesByTunnel.collectAsState()
    val activeBrowserTunnelId by DesktopContainer.browserSessionHolder.activeTunnelId.collectAsState()

    // Errors → AlertDialog (pas de SnackbarHost dans la refonte)
    var errorDialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.errors.collect { message -> errorDialog = message }
    }

    val activeCount = states.values.count {
        it.status == TunnelStatus.ACTIVE
            || it.status == TunnelStatus.STARTING
            || it.status == TunnelStatus.RECONNECTING
    }
    // Bulk action buttons stay disabled when there's nothing to act on.
    val canStartAny = tunnels.isNotEmpty() && tunnels.any { t ->
        states[t.id]?.status.let { it == null || it == TunnelStatus.STOPPED || it == TunnelStatus.ERROR }
    }
    val canStopAny = activeCount > 0

    Column(modifier = Modifier.fillMaxSize().background(NearBlack)) {
        PageHeader(
            title = stringResource(Res.string.tunnels_title),
            subtitle = stringResource(Res.string.tunnels_subtitle),
            actions = {
                // Tout stopper : .btn-ghost .btn-sm (transparent, sans bordure,
                // bg uniquement au hover White α=0.05). Placé en premier.
                BtnGhostSm(
                    onClick = { viewModel.stopAll() },
                    enabled = canStopAny,
                    icon = Lucide.Square,
                    label = stringResource(Res.string.tunnels_action_stop_all),
                )
                Spacer(Modifier.width(Spacing.Sm))

                // Tout démarrer : .btn-gold .btn-sm (border Gold α=0.30 +
                // text GoldLight, hover bg Gold α=0.08).
                BtnGoldSm(
                    onClick = { viewModel.startAll() },
                    enabled = canStartAny,
                    icon = Lucide.Play,
                    label = stringResource(Res.string.tunnels_action_start_all),
                )
                Spacer(Modifier.width(Spacing.Sm))

                // Nouveau tunnel : .btn-primary (full size : padding 14×7, fontSize 13sp)
                Button(
                    onClick = onAddTunnel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Burgundy,
                        contentColor = White,
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text = stringResource(Res.string.tunnels_action_new), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            },
        )

        if (tunnels.isEmpty()) {
            EmptyTunnelsState(onAddTunnel = onAddTunnel)
        } else {
            // FlowRow scrollable : chaque TunnelCard fait EXACTEMENT 400dp,
            // jamais étirée. Les cards s'alignent à gauche, wrap automatique
            // sur la ligne suivante quand l'espace manque. Le surplus à
            // droite reste vide (pas de gouttière, pas de centrage).
            // 400dp permet 3 cards en ligne sur écran 15" Windows à 125% DPI
            // (~1286dp utiles). Le pire cas du contenu = 393dp utile (TypeBadge
            // raccourci "REMOTE (-R)" 80dp + AUTO 38 + ACTIF 57 + LatencyChip
            // 1299ms 48 + spacers + actions + paddings). Marge 7dp.
            // Fenêtre Desktop verrouillée ≥ 1024dp (Main.kt).
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    tunnels.forEach { tunnel ->
                        val tunnelStatus = states[tunnel.id]?.status ?: TunnelStatus.STOPPED
                        TunnelCard(
                            tunnel = tunnel,
                            host = hosts.firstOrNull { it.id == tunnel.hostId },
                            status = tunnelStatus,
                            latencyMs = latencies[tunnel.id],
                            errorMessage = states[tunnel.id]?.errorMessage,
                            hasBrowserSession = activeBrowserTunnelId == tunnel.id,
                            onEdit = { onEditTunnel(tunnel.id) },
                            onToggle = { viewModel.toggle(tunnel) },
                            onOpenBrowser = { DesktopContainer.browserSessionHolder.show(tunnel.id) },
                            modifier = Modifier.width(400.dp),
                        )
                    }
                }
            }
        }
    }

    // Error AlertDialog : affiché quand une erreur est émise par le ViewModel
    errorDialog?.let { message ->
        AlertDialog(
            onDismissRequest = { errorDialog = null },
            title = { Text(text = stringResource(Res.string.tunnels_error_dialog_title)) },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = { errorDialog = null }) {
                    Text(text = "OK")
                }
            },
        )
    }
}

/**
 * `.btn-gold .btn-sm` : fond transparent, border 1dp `Gold α=0.30`, text
 * `GoldLight`, hover bg `Gold α=0.08` + border `Gold α=0.50`. Padding 10×4,
 * font-size 12sp, gap 6dp, radius 6dp. Spec maquette `.btn .btn-gold .btn-sm`.
 */
@Composable
private fun BtnGoldSm(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = GoldLight,
            disabledContentColor = GoldMuted,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (enabled) Gold.copy(alpha = 0.30f) else Gold.copy(alpha = 0.15f),
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * `.btn-ghost .btn-sm` : fond transparent, **sans bordure visible**, text
 * TextSecondary (fg-2). Au hover : bg `White α=0.05` + text TextPrimary (fg-1).
 * Padding 10×4, font-size 12sp, gap 6dp, radius 6dp. Spec maquette
 * `.btn .btn-ghost .btn-sm`.
 *
 * Implémenté via OutlinedButton + border 1dp Color.Transparent : la border
 * invisible réserve la même surface extérieure que les autres `.btn-*` pour
 * que la zone hover s'aligne au pixel près sur les voisins (sinon le ghost
 * paraît plus petit que le bouton .btn-gold à côté).
 */
@Composable
private fun BtnGhostSm(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (enabled && hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent
    val fg = when {
        !enabled -> TextDisabled
        hovered -> TextPrimary
        else -> TextSecondary
    }
    // Use OutlinedButton with a transparent border so the layout reserves the
    // exact same outer dimensions as the sibling .btn-gold/.btn-secondary
    // buttons (which carry a visible 1 dp border). Without this, the ghost
    // hover background is visibly smaller than its neighbours' frames.
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContentColor = TextDisabled,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyTunnelsState(onAddTunnel: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Icon(
                imageVector = Lucide.ArrowLeftRight,
                contentDescription = null,
                tint = GoldMuted,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(Spacing.Sm))
            Text(
                text = stringResource(Res.string.tunnels_empty_title),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.tunnels_empty_hint_alt),
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.Sm))
            // .btn-primary full-size : bg Burgundy + text white, padding 14×7
            Button(
                onClick = onAddTunnel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor = White,
                ),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(text = stringResource(Res.string.tunnels_action_new), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

