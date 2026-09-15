// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Laptop
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MonitorPlay
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Square
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.BurgundyDark
import fr.techtical.nextsh.desktop.theme.BurgundyLight
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.InfoBlue
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SuccessGreen
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.WarningAmber
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.tunnels_status_active
import fr.techtical.nextsh.desktop.generated.resources.tunnels_status_error
import fr.techtical.nextsh.desktop.generated.resources.tunnels_status_reconnecting
import fr.techtical.nextsh.desktop.generated.resources.tunnels_status_starting
import fr.techtical.nextsh.desktop.generated.resources.tunnels_status_stopped
import fr.techtical.nextsh.desktop.generated.resources.tunnels_node_role_gateway
import fr.techtical.nextsh.desktop.generated.resources.tunnels_node_role_local
import fr.techtical.nextsh.desktop.generated.resources.tunnels_node_role_multi_target
import fr.techtical.nextsh.desktop.generated.resources.tunnels_node_role_remote
import fr.techtical.nextsh.desktop.generated.resources.tunnels_node_sub_socks5
import fr.techtical.nextsh.desktop.generated.resources.tunnels_type_dynamic
import fr.techtical.nextsh.desktop.generated.resources.tunnels_type_local
import fr.techtical.nextsh.desktop.generated.resources.tunnels_type_remote
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.TunnelType
import org.jetbrains.compose.resources.stringResource

private val TUNNEL_CARD_HEIGHT_DP = 180.dp

/**
 * Card densifiée pour un tunnel SSH (hauteur fixe 180dp pour grille 3-2-1).
 *
 * Layout :
 * - Zone 1 (tunnel-head) : icône + Column { label ; Row[TypeBadge + AutoBadge +
 *   StatusPill + LatencyChip] } + actions IconActionSm (Globe conditionnel sur
 *   LOCAL_FORWARD ACTIVE, Settings2, Play/Square)
 * - Zone 2 (tunnel-diagram) : 3 nodes + 2 wires (largeur dépendante de `columns`)
 * - Erreur conditionnelle : Text rouge tronqué entre Zone 1 et Zone 2 si ERROR
 */
@Composable
fun TunnelCard(
    tunnel: TunnelConfig,
    host: Host?,
    status: TunnelStatus,
    latencyMs: Long?,
    errorMessage: String?,
    hasBrowserSession: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = status == TunnelStatus.ACTIVE
        || status == TunnelStatus.STARTING
        || status == TunnelStatus.RECONNECTING
    val active = status == TunnelStatus.ACTIVE

    // Maquette spec : active card highlights with SuccessGreen (consistent with
    // the "Actif" status pill colour). Border `rgba(76,175,122,0.25)` ; bg gradient
    // `linear-gradient(135deg, rgba(76,175,122,0.03) 0%, var(--bg-2) 50%)`.
    val borderColor = if (active) SuccessGreen.copy(alpha = 0.25f) else Border1
    val cardBgBrush = if (active) {
        Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to SuccessGreen.copy(alpha = 0.03f),
                0.5f to Surface,
            ),
        )
    } else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(TUNNEL_CARD_HEIGHT_DP)
            .clip(RoundedCornerShape(10.dp))
            .let { if (cardBgBrush != null) it.background(brush = cardBgBrush) else it.background(Surface) }
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Zone 1 : tunnel-head
        TunnelHead(
            tunnel = tunnel,
            status = status,
            running = running,
            active = active,
            latencyMs = latencyMs,
            hasBrowserSession = hasBrowserSession,
            onEdit = onEdit,
            onToggle = onToggle,
            onOpenBrowser = onOpenBrowser,
        )

        // Error message (just below Zone 1 if ERROR)
        if (status == TunnelStatus.ERROR && !errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = ErrorRed,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Zone 2 : tunnel-diagram
        TunnelDiagram(
            tunnel = tunnel,
            host = host,
            active = active,
            latencyMs = latencyMs,
        )
    }
}

// ── Zone 1 : header ──────────────────────────────────────────────────────────

@Composable
private fun TunnelHead(
    tunnel: TunnelConfig,
    status: TunnelStatus,
    running: Boolean,
    active: Boolean,
    latencyMs: Long?,
    hasBrowserSession: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.ArrowLeftRight,
            contentDescription = null,
            tint = if (active) BurgundyLight else TextDisabled,
            modifier = Modifier.size(14.dp),
        )

        Spacer(Modifier.width(Spacing.Sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tunnel.label,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // Order : type → AUTO (if autoStart) → status pill → latency
            // (réordonné par rapport à la maquette pour matcher la décision produit).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Type badge : rectangulaire, fond Gold α=0.08, texte GoldLight
                TypeBadge(tunnel.type)
                // AUTO badge (si activé) : rectangulaire, fond InfoBlue α=0.08, texte InfoBlue
                if (tunnel.autoStart) AutoBadge()
                // Status pill : arrondi, ACTIF/STOPPÉ
                StatusPill(status = status, active = active)
                // Live latency : affichée à droite du status pill quand active
                if (active) LatencyChip(latencyMs)
            }
        }

        Spacer(Modifier.width(6.dp))

        // Actions row : icônes compactes 28×28dp
        // Bouton Globe : LOCAL_FORWARD ACTIF uniquement
        // `openBrowserOnConnect` ne gate plus l'affichage (déclencheur d'auto-
        // ouverture seulement), parité Android `TunnelListScreen.kt:319`.
        val showBrowserAction = tunnel.type == TunnelType.LOCAL_FORWARD && status == TunnelStatus.ACTIVE
        if (showBrowserAction) {
            IconActionSm(
                icon = if (hasBrowserSession) Lucide.MonitorPlay else Lucide.Globe,
                tint = if (hasBrowserSession) SuccessGreen else GoldLight,
                onClick = onOpenBrowser,
            )
            Spacer(Modifier.width(6.dp))
        }
        // Bouton Configurer : .btn-secondary .btn-sm (transparent, border Border2)
        IconActionSm(
            icon = Lucide.Settings2,
            onClick = onEdit,
        )
        Spacer(Modifier.width(6.dp))
        // Bouton Démarrer / Stopper : Démarrer = primary (Burgundy + white)
        // Stopper = défaut (transparent, border Border2, cohérence maquette)
        IconActionSm(
            icon = if (running) Lucide.Square else Lucide.Play,
            onClick = onToggle,
            primary = !running,
        )
    }
}

/**
 * Type badge : rectangulaire (radius 3dp), fond Gold α=0.08, border Gold α=0.20,
 * texte GoldLight. Spec maquette `.tunnel-head .type`.
 */
@Composable
private fun TypeBadge(type: TunnelType) {
    val label = when (type) {
        TunnelType.LOCAL_FORWARD -> "${stringResource(Res.string.tunnels_type_local)} (-L)"
        TunnelType.REMOTE_FORWARD -> "${stringResource(Res.string.tunnels_type_remote)} (-R)"
        TunnelType.DYNAMIC_SOCKS5 -> "${stringResource(Res.string.tunnels_type_dynamic)} (-D)"
    }
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Gold.copy(alpha = 0.08f))
            .border(1.dp, Gold.copy(alpha = 0.20f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = GoldLight,
            letterSpacing = 0.04.sp,
        )
    }
}

/**
 * Status pill : full-rounded (pill), font-mono UPPERCASE, dot + label.
 * Inactive: bg White α=0.04, color TextDisabled, dot #555.
 * Active: bg SuccessGreen α=0.10, color SuccessGreen, dot SuccessGreen.
 * Spec maquette `.tunnel-head .status` + `.tunnel-card.active .status`.
 */
@Composable
private fun StatusPill(status: TunnelStatus, active: Boolean) {
    val (bg, fg, dotColor) = when (status) {
        TunnelStatus.ACTIVE -> Triple(
            SuccessGreen.copy(alpha = 0.10f),
            SuccessGreen,
            SuccessGreen,
        )
        TunnelStatus.STARTING, TunnelStatus.RECONNECTING -> Triple(
            WarningAmber.copy(alpha = 0.10f),
            WarningAmber,
            WarningAmber,
        )
        TunnelStatus.ERROR -> Triple(
            ErrorRed.copy(alpha = 0.10f),
            ErrorRed,
            ErrorRed,
        )
        else -> Triple(
            White.copy(alpha = 0.04f),
            TextDisabled,
            Color(0xFF555555),
        )
    }
    val label = when (status) {
        TunnelStatus.ACTIVE -> stringResource(Res.string.tunnels_status_active).uppercase()
        TunnelStatus.STARTING -> stringResource(Res.string.tunnels_status_starting).uppercase()
        TunnelStatus.RECONNECTING -> stringResource(Res.string.tunnels_status_reconnecting).uppercase()
        TunnelStatus.ERROR -> stringResource(Res.string.tunnels_status_error).uppercase()
        else -> stringResource(Res.string.tunnels_status_stopped).uppercase()
    }
    val shape = RoundedCornerShape(9999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(dotColor),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = fg,
            letterSpacing = 0.05.sp,
        )
    }
}

/**
 * AUTO badge : rectangulaire, fond InfoBlue α=0.08, border InfoBlue α=0.20,
 * texte InfoBlue. Mêmes specs que [TypeBadge] avec couleur info.
 */
@Composable
private fun AutoBadge() {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(InfoBlue.copy(alpha = 0.08f))
            .border(1.dp, InfoBlue.copy(alpha = 0.20f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "AUTO",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = InfoBlue,
            letterSpacing = 0.04.sp,
        )
    }
}

/**
 * Latency chip : affichée à droite du StatusPill quand le tunnel est ACTIVE.
 * Format `● Xms` SuccessGreen quand mesurée, `● -` TextDisabled en attente.
 */
@Composable
private fun LatencyChip(latencyMs: Long?) {
    val color = if (latencyMs != null) SuccessGreen else TextDisabled
    val text = if (latencyMs != null) "● ${latencyMs}ms" else "● -"
    Text(
        text = text,
        fontFamily = JetBrainsMonoFamily,
        fontSize = 10.sp,
        color = color,
    )
}

// ── Zone 2 : diagramme ───────────────────────────────────────────────────────

@Composable
private fun TunnelDiagram(
    tunnel: TunnelConfig,
    host: Host?,
    active: Boolean,
    latencyMs: Long?,
) {
    // Largeur fixe : la card faisant toujours ≥480dp (cf. GridCells.Adaptive
    // côté écran liste), 32dp suffit visuellement pour les 3 wires sans casse.
    val wireWidth = 32.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val roleLocal = stringResource(Res.string.tunnels_node_role_local)
        val roleRemote = stringResource(Res.string.tunnels_node_role_remote)
        val roleGateway = stringResource(Res.string.tunnels_node_role_gateway)
        val roleMultiTarget = stringResource(Res.string.tunnels_node_role_multi_target)
        val subSocks5 = stringResource(Res.string.tunnels_node_sub_socks5)
        when (tunnel.type) {
            TunnelType.LOCAL_FORWARD -> {
                // Local → Gateway → Remote
                TunnelNode(
                    role = roleLocal,
                    nodeRole = NodeRole.LOCAL,
                    address = "localhost",
                    sub = ":${tunnel.localPort}",
                    icon = Lucide.Laptop,
                    active = active,
                    isDashed = false,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active = active, modifier = Modifier.width(wireWidth))
                TunnelNode(
                    role = roleGateway,
                    nodeRole = NodeRole.GATEWAY,
                    address = host?.label ?: host?.hostname ?: "?",
                    sub = "${host?.username ?: "?"}@${host?.hostname ?: "?"}",
                    icon = Lucide.Shield,
                    active = active,
                    isDashed = false,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active = active, modifier = Modifier.width(wireWidth))
                TunnelNode(
                    role = roleRemote,
                    nodeRole = NodeRole.REMOTE,
                    address = tunnel.remoteHost,
                    sub = ":${tunnel.remotePort}",
                    icon = Lucide.Server,
                    active = active,
                    isDashed = false,
                    modifier = Modifier.weight(1f),
                )
            }

            TunnelType.REMOTE_FORWARD -> {
                // Remote → Gateway → Local (reversed)
                TunnelNode(
                    role = roleRemote,
                    nodeRole = NodeRole.REMOTE,
                    address = tunnel.remoteHost,
                    sub = ":${tunnel.remotePort}",
                    icon = Lucide.Server,
                    active = active,
                    isDashed = false,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active = active, modifier = Modifier.width(wireWidth))
                TunnelNode(
                    role = roleGateway,
                    nodeRole = NodeRole.GATEWAY,
                    address = host?.label ?: host?.hostname ?: "?",
                    sub = "${host?.username ?: "?"}@${host?.hostname ?: "?"}",
                    icon = Lucide.Shield,
                    active = active,
                    isDashed = false,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active = active, modifier = Modifier.width(wireWidth))
                TunnelNode(
                    role = roleLocal,
                    nodeRole = NodeRole.LOCAL,
                    address = "localhost",
                    sub = ":${tunnel.localPort}",
                    icon = Lucide.Laptop,
                    active = active,
                    isDashed = false,
                    modifier = Modifier.weight(1f),
                )
            }

            TunnelType.DYNAMIC_SOCKS5 -> {
                // Local → Gateway → ★ (multi-target dashed)
                TunnelNode(
                    role = roleLocal,
                    nodeRole = NodeRole.LOCAL,
                    address = "localhost",
                    sub = ":${tunnel.localPort}",
                    icon = Lucide.Laptop,
                    active = active,
                    isDashed = false,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active = active, modifier = Modifier.width(wireWidth))
                TunnelNode(
                    role = roleGateway,
                    nodeRole = NodeRole.GATEWAY,
                    address = host?.label ?: host?.hostname ?: "?",
                    sub = "${host?.username ?: "?"}@${host?.hostname ?: "?"}",
                    icon = Lucide.Shield,
                    active = active,
                    isDashed = false,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active = active, modifier = Modifier.width(wireWidth))
                TunnelNode(
                    role = roleMultiTarget,
                    nodeRole = NodeRole.MULTI_TARGET,
                    address = "★",
                    sub = subSocks5,
                    icon = Lucide.Globe,
                    active = active,
                    isDashed = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Role of a node in the tunnel diagram: drives the active-state colour. */
private enum class NodeRole { LOCAL, GATEWAY, REMOTE, MULTI_TARGET }

@Composable
private fun TunnelNode(
    role: String,
    nodeRole: NodeRole,
    address: String,
    sub: String,
    icon: ImageVector,
    active: Boolean,
    isDashed: Boolean,
    modifier: Modifier = Modifier,
) {
    // Icon-wrap colours per maquette spec :
    //   inactive       : bg #0d0d0d, tint TextSecondary
    //   active.local   : bg Gold α=0.08, tint Gold
    //   active.gateway : bg Burgundy α=0.12, tint BurgundyLight
    //   active.remote  : bg SuccessGreen α=0.08, tint SuccessGreen
    //   active.multi-target (DYNAMIC_SOCKS5 ★) : bg SuccessGreen α=0.08, tint SuccessGreen
    //   (the dashed node still gets the "active" treatment when the tunnel is up)
    val (iconBg, iconTint) = when {
        !active -> Color(0xFF0D0D0D) to TextSecondary
        nodeRole == NodeRole.LOCAL -> Gold.copy(alpha = 0.08f) to Gold
        nodeRole == NodeRole.GATEWAY -> Burgundy.copy(alpha = 0.12f) to BurgundyLight
        nodeRole == NodeRole.REMOTE -> SuccessGreen.copy(alpha = 0.08f) to SuccessGreen
        nodeRole == NodeRole.MULTI_TARGET -> SuccessGreen.copy(alpha = 0.08f) to SuccessGreen
        else -> Color(0xFF0D0D0D) to TextSecondary
    }

    // Cache the PathEffect across recompositions: without `remember`, a fresh
    // PathEffect is allocated on every draw frame.
    val dashEffect = remember(isDashed) {
        if (isDashed) PathEffect.dashPathEffect(floatArrayOf(4f, 4f), phase = 0f) else null
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161616))
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                val stroke = Stroke(width = strokeWidth, pathEffect = dashEffect)
                drawRoundRect(
                    color = Color(0xFF232323),
                    style = stroke,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Icon wrap: 26×26, radius 6dp, bg according to active state + role
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )
        }

        // Role label: 8sp UPPERCASE letter-spacing 0.08em
        Text(
            text = role.uppercase(),
            color = TextDisabled,
            fontSize = 8.sp,
            letterSpacing = 0.08.sp,
        )

        // Address: 10sp medium fg-1
        Text(
            text = address,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Sub-label: 9sp gold-muted
        Text(
            text = sub,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = GoldMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Wire connecting two nodes: 2dp height, radius 2dp.
 * Inactive: solid `#232323`.
 * Active: linear gradient `BurgundyDark → BurgundyLight` (left → right).
 * Spec maquette `.tunnel-link .wire`.
 */
@Composable
private fun TunnelWire(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(2.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(2.dp))
                .let {
                    if (active) {
                        it.background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(BurgundyDark, BurgundyLight),
                            ),
                        )
                    } else {
                        it.background(Color(0xFF232323))
                    }
                },
        )
    }
}

// ── Helper bouton icône compact ───────────────────────────────────────────────

/**
 * Bouton icône compact 28×28dp pour la TunnelHead refondue. Variante `primary`
 * = fond Burgundy + tint White (action CTA principale, ex. Démarrer). Variante
 * par défaut = transparent + border `Border2` + tint configurable.
 */
@Composable
private fun IconActionSm(
    icon: ImageVector,
    onClick: () -> Unit,
    primary: Boolean = false,
    tint: Color = TextPrimary,
) {
    val bg = if (primary) Burgundy else Color.Transparent
    val borderColor = if (primary) Color.Transparent else Border2
    val iconTint = if (primary) White else tint
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
    }
}
