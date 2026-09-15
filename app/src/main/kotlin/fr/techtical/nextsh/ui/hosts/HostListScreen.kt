// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Download
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.ui.components.BrandText
import fr.techtical.nextsh.ui.theme.BioViolet
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Border2
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.BurgundyDark
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.GoldMuted
import fr.techtical.nextsh.ui.theme.InfoBlue
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
 * HostListScreen : refonte Phase 3.2 (DA Techtical portée du Desktop).
 *
 * Conventions Android conservées : Scaffold + TopAppBar + FAB +
 * SnackbarHost. La refonte porte sur :
 *  - TopAppBar : BrandText 22sp + subtitle compteur + icon actions
 *  - FilterChips Border1/Burgundy selected (parité visual chips)
 *  - HostCard : Surface/Border1/Radii.Lg, icon-wrap Gold avec icône
 *    auth Lucide, label Space Grotesk SemiBold, ligne `user@host:port`
 *    JetBrainsMono, AuthBadge Gold border, group tag, action buttons
 *    Star/SFTP/Edit IconButton compacts
 *  - Empty state : Lucide.Server 64dp + Space Grotesk SemiBold title
 *
 * Comportement existant préservé : tap card = SSH connect, FAB =
 * nouvel hôte, SyncBanner Android-only (warning conflits + pending).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    onNavigateToDetail: (String?) -> Unit,
    onNavigateToSession: (String) -> Unit,
    onNavigateToSnippets: () -> Unit = {},
    onNavigateToImport: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSftp: (sessionId: String, hostLabel: String) -> Unit = { _, _ -> },
    onNavigateToConflictResolution: () -> Unit = {},
    viewModel: HostViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HostEvent.Connected -> onNavigateToSession(event.sessionId)
                is HostEvent.SftpReady -> onNavigateToSftp(event.sessionId, event.hostLabel)
                is HostEvent.Error -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short,
                    )
                }
                else -> {}
            }
        }
    }

    val totalHosts = uiState.hosts.size
    val hostsLabel = when {
        totalHosts == 0 -> "Aucun hôte"
        totalHosts == 1 -> "1 hôte"
        else -> "$totalHosts hôtes"
    }
    val pendingConflicts = syncState.pendingConflicts
    val (syncLabel, syncColor, syncDot) = syncStatusDescriptor(syncState.status, pendingConflicts, syncState.lastSyncAt)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        BrandText(fontSize = 20.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = hostsLabel,
                                color = TextDisabled,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("·", color = TextDisabled, fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(syncDot, RoundedCornerShape(50)),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = syncLabel,
                                color = syncColor,
                                fontSize = 12.sp,
                                fontWeight = if (pendingConflicts > 0) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
                actions = {
                    IconButton(onClick = onNavigateToImport) {
                        Icon(
                            Lucide.Download,
                            contentDescription = stringResource(R.string.host_import_title),
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onNavigateToSnippets) {
                        Icon(
                            Lucide.Code,
                            contentDescription = stringResource(R.string.nav_snippets),
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    SettingsIconWithBadge(
                        badgeCount = pendingConflicts,
                        onClick = {
                            if (pendingConflicts > 0) onNavigateToConflictResolution() else onNavigateToSettings()
                        },
                    )
                },
            )
        },
        containerColor = NearBlack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // SyncBanner retiré : l'état sync (dot + label) et le badge des
            // conflits sont rendus directement dans la TopAppBar (subtitle +
            // Settings icon avec badge "1" Burgundy si conflits).

            // ── Barre groupes : LazyRow scrollable + bouton "+" fixe ─────────
            // FAB retiré au profit d'un bouton "+" dans la barre groupes pour
            // garder l'écran épuré. La LazyRow weight(1f) consomme l'espace
            // restant et scroll horizontalement si les chips dépassent.
            FilterAndAddBar(
                groups = uiState.groups,
                selectedGroup = uiState.selectedGroup,
                onSelectGroup = viewModel::filterByGroup,
                onAddHost = { onNavigateToDetail(null) },
            )

            if (uiState.hosts.isEmpty() && !uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(onAddHost = { onNavigateToDetail(null) })
                }
            } else {
                val listState = rememberLazyListState()
                // Reset scroll robuste : on ne peut pas se contenter de
                // `LaunchedEffect(selectedGroup)` qui scroll AVANT que la
                // nouvelle liste arrive : LazyColumn keyée par id replace
                // alors le scroll. On track la dernière valeur vue de
                // selectedGroup et on déclenche `scrollToItem(0)` lors du
                // *prochain* arrivage de hosts (post-filtre).
                var lastFilterApplied by remember { mutableStateOf(uiState.selectedGroup) }
                LaunchedEffect(uiState.selectedGroup, uiState.hosts) {
                    if (lastFilterApplied != uiState.selectedGroup) {
                        listState.scrollToItem(0)
                        lastFilterApplied = uiState.selectedGroup
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = Spacing.Lg,
                        end = Spacing.Lg,
                        top = Spacing.Sm,
                        bottom = Spacing.Lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    // Pas de `key = { it.id }` : avec stable keys, LazyColumn
                    // préserve l'identité de l'item visible quand la liste
                    // change (ici post-filtre). Le `scrollToItem(0)` suivant
                    // est alors écrasé par cette logique de préservation :
                    // d'où le bug "on reste sur l'hôte du filtre précédent".
                    // Sans key, items recyclent simplement les slots et
                    // scrollToItem(0) reste autoritaire.
                    items(uiState.hosts) { host ->
                        HostCard(
                            host = host,
                            isConnecting = uiState.connectingHostId == host.id,
                            onConnect = { viewModel.connectToHost(host) },
                            onEdit = { onNavigateToDetail(host.id) },
                            onSftpBrowse = { viewModel.connectForSftp(host) },
                            onToggleFavorite = { viewModel.toggleFavorite(host) },
                        )
                    }
                }
            }
        }
    }
}

// ── Sync status helpers (TopAppBar) ─────────────────────────────────────────

/**
 * Construit le triplet (label, color, dot) à afficher dans le subtitle
 * TopAppBar pour l'état de sync. Si conflits en attente, prend le pas
 * sur l'état brut (WarningAmber + texte explicite).
 */
@Composable
private fun syncStatusDescriptor(
    status: SyncStatus,
    pendingConflicts: Int,
    lastSyncAt: Long?,
): Triple<String, androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    if (pendingConflicts > 0) {
        val label = if (pendingConflicts == 1)
            stringResource(R.string.sync_subtitle_conflicts_one)
        else
            stringResource(R.string.sync_subtitle_conflicts_many, pendingConflicts)
        return Triple(label, WarningAmber, WarningAmber)
    }
    return when (status) {
        SyncStatus.IDLE    ->
            if (lastSyncAt != null)
                Triple(stringResource(R.string.sync_subtitle_uptodate), SuccessGreen, SuccessGreen)
            else
                Triple(stringResource(R.string.sync_status_pending), TextDisabled, TextDisabled)
        SyncStatus.SYNCING -> Triple(stringResource(R.string.sync_subtitle_syncing), GoldLight, GoldLight)
        SyncStatus.ERROR   -> Triple(stringResource(R.string.sync_subtitle_error), ErrorRed, ErrorRed)
        SyncStatus.OFFLINE -> Triple(stringResource(R.string.sync_subtitle_offline), TextDisabled, TextDisabled)
    }
}

/**
 * Icône Settings TopAppBar avec badge "n" Burgundy en haut-droite
 * quand des conflits de sync sont en attente. Tap → résolution conflits
 * directement (raccourci) si badge > 0, sinon Settings classique.
 */
@Composable
private fun SettingsIconWithBadge(badgeCount: Int, onClick: () -> Unit) {
    Box(modifier = Modifier.size(48.dp)) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Icon(
                Lucide.Settings,
                contentDescription = stringResource(R.string.action_settings),
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 4.dp)
                    .size(16.dp)
                    .background(Burgundy, RoundedCornerShape(50))
                    .border(1.dp, NearBlack, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                // `lineHeight = fontSize` retire le padding vertical implicite
                // de `Text` (sinon le chiffre apparaît collé en haut de la
                // pastille). `textAlign = Center` + `includeFontPadding = false`
                // affinent l'alignement horizontal vs. baseline.
                Text(
                    text = if (badgeCount > 9) "9+" else badgeCount.toString(),
                    color = White,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = androidx.compose.ui.text.TextStyle(
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                            includeFontPadding = false,
                        ),
                    ),
                )
            }
        }
    }
}

// ── Filter bar avec "+" fixe ────────────────────────────────────────────────

/**
 * Barre groupes : LazyRow weight(1f) qui scrolle horizontalement +
 * bouton primary "+" fixe à droite. Toujours rendue (chip "Tous"
 * minimum visible) pour exposer l'action principale "Nouvel hôte".
 */
@Composable
private fun FilterAndAddBar(
    groups: List<String>,
    selectedGroup: String?,
    onSelectGroup: (String?) -> Unit,
    onAddHost: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        ) {
            item {
                TechticalFilterChip(
                    label = stringResource(R.string.filter_all),
                    selected = selectedGroup == null,
                    onClick = { onSelectGroup(null) },
                )
            }
            items(groups) { group ->
                TechticalFilterChip(
                    label = group,
                    selected = selectedGroup == group,
                    onClick = { onSelectGroup(group) },
                )
            }
        }
        BtnAddPrimary(onClick = onAddHost)
    }
}

@Composable
private fun BtnAddPrimary(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(Radii.Md))
            .background(Burgundy)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Lucide.Plus,
            contentDescription = stringResource(R.string.action_add_host_fab),
            tint = White,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ── HostCard refondue ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostCard(
    host: Host,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onSftpBrowse: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Lg))
            .background(Surface)
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .clickable(onClick = onConnect)
            .padding(Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        // ── Top row : icon-wrap Server + label + user@host:port + auth badge ─
        Row(verticalAlignment = Alignment.Top) {
            // Parité Desktop : icône `Server` uniforme (pas auth-type),
            // icon-wrap SurfaceVariant 32dp + GoldMuted tint, le badge auth
            // à droite porte l'info de méthode.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(Radii.Sm))
                    .background(SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Server,
                    contentDescription = host.authType.name,
                    tint = GoldMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(Spacing.Sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.label,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                // user@host:port, colorisation par segment (parité Desktop) :
                // user Gold / @ Burgundy / hostname TextSecondary / :port TextDisabled.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = host.username,
                        color = Gold,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "@",
                        color = Burgundy,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = host.hostname,
                        color = TextSecondary,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = ":${host.port}",
                        color = TextDisabled,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                    )
                }
                host.group?.takeIf { it.isNotBlank() }?.let { grp ->
                    Spacer(Modifier.height(3.dp))
                    GroupTag(group = grp)
                }
            }
            Spacer(Modifier.width(Spacing.Xs))
            AuthBadge(host.authType)
        }

        // ── Bottom action bar : SSH primary + SFTP secondary + Star + Edit ─
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtnSshPrimary(onClick = onConnect, loading = isConnecting)
            BtnSftpSecondary(onClick = onSftpBrowse)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                Icon(
                    Lucide.Star,
                    contentDescription = if (host.isFavorite) {
                        stringResource(R.string.action_unfavorite)
                    } else {
                        stringResource(R.string.action_favorite)
                    },
                    tint = if (host.isFavorite) Gold else TextDisabled,
                    modifier = Modifier.size(16.dp),
                )
            }
            BtnIconSecondary(
                icon = Lucide.Pencil,
                contentDescription = stringResource(R.string.action_edit),
                onClick = onEdit,
            )
        }
    }
}

// ── Sub-components ──────────────────────────────────────────────────────────

@Composable
private fun TechticalFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = if (selected) SpaceGroteskFamily else JetBrainsMonoFamily,
            )
        },
        shape = RoundedCornerShape(Radii.Md),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Burgundy,
            selectedLabelColor = White,
            containerColor = Surface,
            labelColor = TextSecondary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Border1,
            selectedBorderColor = Burgundy,
        ),
    )
}

/**
 * Badge auth type, palette par-couleur calquée sur le Desktop refondu :
 *   - PWD       : ErrorRed sur dark-tinted #2A1A1A
 *   - SSH_KEY   : Gold sur BurgundyDark
 *   - CERT      : InfoBlue sur dark-tinted #1A1F2A
 *   - FIDO2     : SuccessGreen sur dark-tinted #1A2A1A
 *   - BIO       : BioViolet sur dark-tinted #1A1A2A
 * Mono Bold 10sp + radius 2dp, pas de border.
 */
@Composable
private fun AuthBadge(authType: AuthType) {
    val (label, bg, fg) = when (authType) {
        AuthType.PASSWORD -> Triple("PWD", Color(0xFF2A1A1A), ErrorRed)
        AuthType.SSH_KEY -> Triple("SSH_KEY", BurgundyDark, Gold)
        AuthType.CERTIFICATE -> Triple("CERT", Color(0xFF1A1F2A), InfoBlue)
        AuthType.FIDO2 -> Triple("FIDO2", Color(0xFF1A2A1A), SuccessGreen)
        AuthType.BIOMETRIC_KEY -> Triple("BIO", Color(0xFF1A1A2A), BioViolet)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Xs))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GroupTag(group: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .background(GoldMuted, CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = group,
            color = GoldMuted,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

/**
 * Bouton SSH primaire, parité Desktop : Burgundy bg + `Lucide.Terminal`
 * 12dp + label "SSH" 13sp Medium. Largeur intrinsèque (pas de weight),
 * padding 12×6 pour rester compact à côté du SFTP. Spinner si loading.
 */
@Composable
private fun BtnSshPrimary(onClick: () -> Unit, loading: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Md))
            .background(Burgundy)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = White,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Terminal, contentDescription = null, tint = White, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text("SSH", color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Bouton SFTP secondaire, parité Desktop : OutlinedButton Border2 1dp
 * + `Lucide.Folder` 12dp + label "SFTP" 13sp Medium. Largeur intrinsèque.
 */
@Composable
private fun BtnSftpSecondary(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Md))
            .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Folder, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text("SFTP", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun BtnIconSecondary(
    icon: ImageVector,
    contentDescription: String,
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
            tint = TextSecondary,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(onAddHost: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = Spacing.Xl),
    ) {
        Icon(
            Lucide.Server,
            contentDescription = null,
            tint = GoldMuted,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(R.string.empty_no_hosts_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(R.string.empty_no_hosts_subtitle),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Lg))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .background(Burgundy)
                .clickable(onClick = onAddHost)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Plus, contentDescription = null, tint = White, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(Spacing.Sm))
                Text(
                    text = stringResource(R.string.action_add_host),
                    color = White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SpaceGroteskFamily,
                )
            }
        }
    }
}

