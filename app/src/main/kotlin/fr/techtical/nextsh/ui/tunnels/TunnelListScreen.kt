// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Laptop
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Star
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.ui.components.NotificationPermissionEffect
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Border2
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.BurgundyLight
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.GoldMuted
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.SuccessGreen
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.SurfaceVariant
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.WarningAmber
import fr.techtical.nextsh.ui.theme.White

/**
 * TunnelListScreen : refonte Phase 3.2 (DA Techtical).
 *
 * Conventions Android conservées : Scaffold + TopAppBar + FAB. La
 * card a été refaite parité Desktop : Surface/Border1/Radii.Lg avec
 * StatusPill (dot + label UPPERCASE), description ports colorisée
 * (Burgundy ports + Gold "via {host}"), type badge Gold border, et
 * row d'actions Play/Stop + Browser (LOCAL only) + Favori + Edit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelListScreen(
    onNavigateToConfig: (String?) -> Unit,
    onBack: () -> Unit,
    onOpenBrowser: (String) -> Unit = {},
    startTunnelId: String? = null,
    viewModel: TunnelViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val browserActiveTunnelId by viewModel.browserActiveTunnelId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val serviceRefusedMessage = stringResource(R.string.tunnel_service_start_refused)

    LaunchedEffect(Unit) { viewModel.autoStartTunnels() }
    LaunchedEffect(startTunnelId) { startTunnelId?.let { viewModel.autoStartTunnelById(it) } }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            // Pas de snackbar pour `TunnelEvent.Error` : depuis le fix
            // `markStarting`/`markError` du StartTunnelUseCase, l'erreur
            // est déjà rendue dans la card via la StatusPill ERREUR + le
            // banner ErrorRed (avec le message). Doublon retiré.
            when (event) {
                is TunnelEvent.OpenBrowser -> onOpenBrowser(event.tunnelId)
                // Le tunnel tourne mais ne survivra pas à l'arrière-plan :
                // aucune card ne peut le montrer, son statut reste ACTIF.
                is TunnelEvent.ForegroundServiceRefused ->
                    snackbarHostState.showSnackbar(serviceRefusedMessage)
                else -> {}
            }
        }
    }

    val total = uiState.tunnels.size
    val active = uiState.tunnelStates.values.count {
        it.status in listOf(TunnelStatus.ACTIVE, TunnelStatus.STARTING, TunnelStatus.RECONNECTING)
    }
    val subtitle = when {
        total == 0 -> "Aucun tunnel configuré"
        active == 0 -> "$total tunnel${if (total > 1) "s" else ""} · 0 actif"
        else -> "$total tunnel${if (total > 1) "s" else ""} · $active actif${if (active > 1) "s" else ""}"
    }

    // Un tunnel actif implique une notification persistante : demander la
    // permission au moment où le premier tunnel démarre, pas avant.
    NotificationPermissionEffect(trigger = active > 0)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.title_ssh_tunnels),
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = subtitle,
                            color = TextDisabled,
                            fontSize = 12.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextPrimary,
                        )
                    }
                },
                actions = {
                    // Bouton "+" Burgundy en TopAppBar (parité HostList, FAB
                    // retiré pour libérer le bas de l'écran et garder l'action
                    // principale toujours visible côté droit).
                    BtnAddPrimary(onClick = { onNavigateToConfig(null) })
                    Spacer(Modifier.width(Spacing.Sm))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
            )
        },
        containerColor = NearBlack,
    ) { padding ->
        if (uiState.tunnels.isEmpty() && !uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(onCreate = { onNavigateToConfig(null) })
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = Spacing.Lg,
                    end = Spacing.Lg,
                    top = Spacing.Md,
                    bottom = Spacing.Lg,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                items(uiState.tunnels) { tunnel ->
                    TunnelCard(
                        config = tunnel,
                        tunnelState = uiState.tunnelStates[tunnel.id],
                        host = uiState.hosts.firstOrNull { it.id == tunnel.hostId },
                        hasBrowserSession = browserActiveTunnelId == tunnel.id,
                        onToggle = { viewModel.toggleTunnel(tunnel) },
                        onEdit = { onNavigateToConfig(tunnel.id) },
                        onOpenBrowser = { onOpenBrowser(tunnel.id) },
                        onToggleFavorite = { viewModel.toggleFavorite(tunnel) },
                    )
                }
            }
        }
    }
}

// ── TunnelCard ──────────────────────────────────────────────────────────────

@Composable
private fun TunnelCard(
    config: TunnelConfig,
    tunnelState: TunnelState?,
    host: Host?,
    hasBrowserSession: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onOpenBrowser: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val status = tunnelState?.status ?: TunnelStatus.STOPPED
    val isActive = status in listOf(TunnelStatus.ACTIVE, TunnelStatus.STARTING, TunnelStatus.RECONNECTING)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Lg))
            .background(Surface)
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .clickable(onClick = onEdit)
            .padding(Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        // ── Top row : icon-wrap + label + StatusPill + Favorite ──────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(Radii.Sm))
                    .background(SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.ArrowLeftRight,
                    contentDescription = null,
                    tint = GoldMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(Spacing.Sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.label,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeBadge(config.type)
                    Spacer(Modifier.width(Spacing.Xs))
                    StatusPill(status)
                }
            }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                Icon(
                    Lucide.Star,
                    contentDescription = if (config.isFavorite) {
                        stringResource(R.string.action_unfavorite)
                    } else {
                        stringResource(R.string.action_favorite)
                    },
                    tint = if (config.isFavorite) Gold else TextDisabled,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        // ── Diagramme 3 nœuds Local → Gateway → Distant (parité Desktop) ──
        // Erreur : on remplace le diagramme par un banner ErrorRed pour ne
        // pas montrer un état "actif visuel" trompeur.
        val errorMsg = tunnelState?.takeIf { it.status == TunnelStatus.ERROR }?.errorMessage
        if (errorMsg != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ErrorRed.copy(alpha = 0.08f), RoundedCornerShape(Radii.Md))
                    .border(1.dp, ErrorRed.copy(alpha = 0.30f), RoundedCornerShape(Radii.Md))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = errorMsg,
                    color = ErrorRed,
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFamily,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            TunnelDiagram(config = config, host = host, active = isActive)
        }

        // ── Bottom action bar : Démarrer/Stopper + Browser + Edit ─────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtnTogglePrimary(isActive = isActive, onClick = onToggle)
            if (isActive && config.type == TunnelType.LOCAL_FORWARD) {
                BtnIconAction(
                    icon = Lucide.Globe,
                    contentDescription = if (hasBrowserSession) {
                        stringResource(R.string.action_resume_browser)
                    } else {
                        stringResource(R.string.action_open_browser)
                    },
                    tint = if (hasBrowserSession) SuccessGreen else Gold,
                    onClick = onOpenBrowser,
                )
            }
            Spacer(Modifier.weight(1f))
            BtnIconAction(
                icon = Lucide.Pencil,
                contentDescription = stringResource(R.string.action_edit),
                tint = TextSecondary,
                onClick = onEdit,
            )
        }
    }
}

// ── Status pill ─────────────────────────────────────────────────────────────

@Composable
private fun StatusPill(status: TunnelStatus) {
    val (label, color) = when (status) {
        TunnelStatus.ACTIVE -> "ACTIF" to SuccessGreen
        TunnelStatus.STARTING -> "DÉMARRAGE" to GoldLight
        TunnelStatus.RECONNECTING -> "RECONNEXION" to WarningAmber
        TunnelStatus.ERROR -> "ERREUR" to ErrorRed
        TunnelStatus.STOPPED -> "ARRÊTÉ" to TextDisabled
    }
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(300),
        label = "statusPillColor",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Xs))
            .background(animatedColor.copy(alpha = 0.10f))
            .border(1.dp, animatedColor.copy(alpha = 0.30f), RoundedCornerShape(Radii.Xs))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(animatedColor, CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = animatedColor,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

// ── Type badge ──────────────────────────────────────────────────────────────

@Composable
private fun TypeBadge(type: TunnelType) {
    val label = when (type) {
        TunnelType.LOCAL_FORWARD -> "LOCAL"
        TunnelType.REMOTE_FORWARD -> "REMOTE"
        TunnelType.DYNAMIC_SOCKS5 -> "SOCKS5"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Xs))
            .background(Gold.copy(alpha = 0.08f))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(Radii.Xs))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

// ── Add primary (TopAppBar "+", parité HostList) ────────────────────────────

@Composable
private fun BtnAddPrimary(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Radii.Md))
            .background(Burgundy)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Lucide.Plus,
            contentDescription = stringResource(R.string.action_new_tunnel),
            tint = White,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── Action buttons ──────────────────────────────────────────────────────────

/**
 * Démarrer (Burgundy primary) / Stopper (outlined Border2 secondary),
 * parité Desktop : pas de couleurs sémantiques destructives sur le stop
 * (`.btn-secondary` du mockup), seul le démarrage utilise l'accent
 * primaire pour signaler l'action principale recommandée.
 */
@Composable
private fun BtnTogglePrimary(isActive: Boolean, onClick: () -> Unit) {
    if (isActive) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Square, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Stopper",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .background(Burgundy)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Play, contentDescription = null, tint = White, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Démarrer",
                    color = White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun BtnIconAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Radii.Md))
            .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ── Diagramme 3 nœuds (parité Desktop, compact mobile) ─────────────────────

private enum class NodeRole { LOCAL, GATEWAY, REMOTE, MULTI_TARGET }

/**
 * Diagramme du chemin du tunnel : 3 nœuds Local / SSH Gateway / Distant
 * (ou Multi-cible pour SOCKS5) reliés par des fils. Reproduit le pattern
 * du Desktop refondu, version compacte mobile : icon-wrap 28dp + role
 * UPPERCASE 8sp + adresse mono 10sp + sub mono 9sp.
 *
 * Couleurs par rôle (quand actif) : Local Gold, Gateway BurgundyLight,
 * Distant SuccessGreen, Multi-cible SuccessGreen. Inactif : tout
 * TextSecondary sur SurfaceVariant.
 */
@Composable
private fun TunnelDiagram(
    config: TunnelConfig,
    host: Host?,
    active: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (config.type) {
            TunnelType.LOCAL_FORWARD -> {
                TunnelNode(
                    role = "Local",
                    nodeRole = NodeRole.LOCAL,
                    icon = Lucide.Laptop,
                    address = "localhost",
                    sub = ":${config.localPort}",
                    active = active,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active, modifier = Modifier.width(20.dp))
                TunnelNode(
                    role = "SSH Gateway",
                    nodeRole = NodeRole.GATEWAY,
                    icon = Lucide.Shield,
                    address = host?.label ?: host?.hostname ?: "?",
                    sub = "${host?.username ?: "?"}@${host?.hostname ?: "?"}",
                    active = active,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active, modifier = Modifier.width(20.dp))
                TunnelNode(
                    role = "Distant",
                    nodeRole = NodeRole.REMOTE,
                    icon = Lucide.Server,
                    address = config.remoteHost,
                    sub = ":${config.remotePort}",
                    active = active,
                    modifier = Modifier.weight(1f),
                )
            }
            TunnelType.REMOTE_FORWARD -> {
                TunnelNode(
                    role = "Distant",
                    nodeRole = NodeRole.REMOTE,
                    icon = Lucide.Server,
                    address = config.remoteHost,
                    sub = ":${config.remotePort}",
                    active = active,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active, modifier = Modifier.width(20.dp))
                TunnelNode(
                    role = "SSH Gateway",
                    nodeRole = NodeRole.GATEWAY,
                    icon = Lucide.Shield,
                    address = host?.label ?: host?.hostname ?: "?",
                    sub = "${host?.username ?: "?"}@${host?.hostname ?: "?"}",
                    active = active,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active, modifier = Modifier.width(20.dp))
                TunnelNode(
                    role = "Local",
                    nodeRole = NodeRole.LOCAL,
                    icon = Lucide.Laptop,
                    address = "localhost",
                    sub = ":${config.localPort}",
                    active = active,
                    modifier = Modifier.weight(1f),
                )
            }
            TunnelType.DYNAMIC_SOCKS5 -> {
                TunnelNode(
                    role = "Local",
                    nodeRole = NodeRole.LOCAL,
                    icon = Lucide.Laptop,
                    address = "localhost",
                    sub = ":${config.localPort}",
                    active = active,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active, modifier = Modifier.width(20.dp))
                TunnelNode(
                    role = "SSH Gateway",
                    nodeRole = NodeRole.GATEWAY,
                    icon = Lucide.Shield,
                    address = host?.label ?: host?.hostname ?: "?",
                    sub = "${host?.username ?: "?"}@${host?.hostname ?: "?"}",
                    active = active,
                    modifier = Modifier.weight(1f),
                )
                TunnelWire(active, modifier = Modifier.width(20.dp))
                TunnelNode(
                    role = "Multi-cible",
                    nodeRole = NodeRole.MULTI_TARGET,
                    icon = Lucide.Globe,
                    address = "★",
                    sub = "via SOCKS5",
                    active = active,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TunnelNode(
    role: String,
    nodeRole: NodeRole,
    icon: ImageVector,
    address: String,
    sub: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val (iconBg, iconTint) = when {
        !active -> SurfaceVariant to TextSecondary
        nodeRole == NodeRole.LOCAL -> Gold.copy(alpha = 0.08f) to Gold
        nodeRole == NodeRole.GATEWAY -> Burgundy.copy(alpha = 0.12f) to BurgundyLight
        nodeRole == NodeRole.REMOTE -> SuccessGreen.copy(alpha = 0.08f) to SuccessGreen
        nodeRole == NodeRole.MULTI_TARGET -> SuccessGreen.copy(alpha = 0.08f) to SuccessGreen
        else -> SurfaceVariant to TextSecondary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.Md))
            .background(Color(0xFF161616))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(Radii.Sm))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
        }
        Text(
            text = role.uppercase(),
            color = TextDisabled,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 8.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = address,
            color = TextPrimary,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = sub,
            color = GoldMuted,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Fil reliant deux nœuds, 2dp height. Inactif gris (Border2), actif
 * gradient horizontal BurgundyDark → BurgundyLight (parité Desktop).
 */
@Composable
private fun TunnelWire(active: Boolean, modifier: Modifier = Modifier) {
    val brush = if (active) {
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            colors = listOf(
                fr.techtical.nextsh.ui.theme.BurgundyDark,
                BurgundyLight,
            ),
        )
    } else {
        androidx.compose.ui.graphics.SolidColor(Border2)
    }
    Box(
        modifier = modifier
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(brush),
    )
}

// ── Empty state ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(onCreate: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = Spacing.Xl),
    ) {
        Icon(
            Lucide.ArrowLeftRight,
            contentDescription = null,
            tint = GoldMuted,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(R.string.empty_no_tunnels_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(R.string.empty_no_tunnels_subtitle),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Lg))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .background(Burgundy)
                .clickable(onClick = onCreate)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Plus, contentDescription = null, tint = White, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(Spacing.Sm))
                Text(
                    text = stringResource(R.string.action_create_tunnel),
                    color = White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SpaceGroteskFamily,
                )
            }
        }
    }
}

