// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Download
import fr.techtical.nextsh.desktop.components.ConfirmDeleteDialog
import fr.techtical.nextsh.desktop.components.PageHeader
import fr.techtical.nextsh.desktop.data.db.repository.HostFolderWithHosts
import fr.techtical.nextsh.desktop.theme.BioViolet
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.BurgundyDark
import fr.techtical.nextsh.desktop.theme.BurgundyLight
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.InfoBlue
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SuccessGreen
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.shared.core.`import`.ImportFormatDetector
import fr.techtical.nextsh.shared.core.`import`.NEXTSH_IMPORT_FORMAT_EXAMPLE
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.action_close
import fr.techtical.nextsh.desktop.generated.resources.hosts_action_import
import fr.techtical.nextsh.desktop.generated.resources.hosts_action_new
import fr.techtical.nextsh.desktop.generated.resources.snippets_action_manage
import fr.techtical.nextsh.desktop.generated.resources.hosts_card_action_delete
import fr.techtical.nextsh.desktop.generated.resources.hosts_card_action_edit
import fr.techtical.nextsh.desktop.generated.resources.hosts_card_more_actions
import fr.techtical.nextsh.desktop.generated.resources.hosts_card_status_offline
import fr.techtical.nextsh.desktop.generated.resources.hosts_card_status_online
import fr.techtical.nextsh.desktop.generated.resources.hosts_delete_body
import fr.techtical.nextsh.desktop.generated.resources.hosts_delete_title
import fr.techtical.nextsh.desktop.generated.resources.hosts_empty_hint
import fr.techtical.nextsh.desktop.generated.resources.hosts_empty_title
import fr.techtical.nextsh.desktop.generated.resources.hosts_folder_unsorted
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_dialog_title
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_dialog_subtitle
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_select_all
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_action_confirm
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_detected_format
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_empty_selection
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_error_none
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_error_file_too_large
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_error_generic
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_error_title
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_format_info_button
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_format_info_copied
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_format_info_copy
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_format_info_intro
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_format_info_optional
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_format_info_required
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_format_info_scope
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_format_info_secrets_warning
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_format_info_title
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_result
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_importing
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_parsing
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_secret_badge
import fr.techtical.nextsh.desktop.generated.resources.hosts_import_secret_warning
import fr.techtical.nextsh.desktop.generated.resources.hosts_relative_just_now
import fr.techtical.nextsh.desktop.generated.resources.hosts_subtitle
import fr.techtical.nextsh.desktop.generated.resources.hosts_title
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_days_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_hours_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_minutes_ago
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_never
import org.jetbrains.compose.resources.stringResource
import javax.swing.JFileChooser

// The ImportFormat alias for ImportFormatDetector.ImportFormat is declared
// once, internal, in HostImportViewModel.kt (same package): two same-named
// top-level typealiases in one package are a K2 redeclaration error.

@Composable
fun HostListScreen(
    onAddHost: () -> Unit,
    onEditHost: (String) -> Unit,
    onConnectHost: (String) -> Unit,
    onConnectSftp: (String) -> Unit,
    onImportHost: (() -> Unit)? = null,
    onOpenSnippets: (() -> Unit)? = null,
) {
    val viewModel = remember { HostViewModel() }
    val foldersWithHosts by viewModel.foldersWithHosts.collectAsState()
    val activeHostIds by viewModel.activeHostIds.collectAsState()

    // Import des hôtes depuis Termius / KeePassXC (parsing + preview + import).
    val importViewModel = remember { HostImportViewModel() }
    val importState by importViewModel.state.collectAsState()

    // État de confirmation de suppression
    var hostToDelete by remember { mutableStateOf<Host?>(null) }

    // Overlay « Format de fichier NextSH » (bouton info à côté d'Importer).
    var showFormatInfo by remember { mutableStateOf(false) }

    val totalHosts = foldersWithHosts.sumOf { it.hosts.size }

    // Displayed unit for the file-too-large error, derived from the actual
    // cap rather than hardcoded, so the string and the real limit can never
    // drift apart (and the unit is MiB, matching MAX_IMPORT_FILE_BYTES's
    // binary-megabyte definition, not decimal MB).
    val maxImportSizeMiB = (ImportFormatDetector.MAX_IMPORT_FILE_BYTES / (1024 * 1024)).toInt()

    // Lance le sélecteur de fichier Swing (sur l'EDT, depuis un click handler
    // Compose) puis délègue le parsing au ViewModel. Pattern aligné sur les
    // pickers JFileChooser existants (SftpBrowserScreen) : pas de dialog
    // Compose-natif car JediTerm Swing perce les Popup/Dialog overlays.
    val launchImport: () -> Unit = {
        val picked = pickHostImportFile()
        if (picked != null) importViewModel.parseFile(picked)
    }

    // Box racine pour permettre aux overlays (ConfirmDeleteDialog, AlertDialog
    // import) de s'afficher au-dessus du Column principal : sinon la Column
    // donne tout l'espace à HostsContent et les overlays mesurés en zéro.
    Box(modifier = Modifier.fillMaxSize().background(NearBlack)) {
    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(Res.string.hosts_title),
            subtitle = stringResource(Res.string.hosts_subtitle),
            actions = {
                val importIdle = importState is HostImportState.Idle
                OutlinedButton(
                    onClick = { onImportHost?.invoke() ?: launchImport() },
                    enabled = importIdle,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border2),
                    shape = RoundedCornerShape(Radii.Sm),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = Spacing.Md,
                        vertical = Spacing.Xs,
                    ),
                ) {
                    Icon(
                        imageVector = Lucide.Download,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Spacing.Xs))
                    Text(text = stringResource(Res.string.hosts_action_import), fontSize = 13.sp)
                }
                ImportInfoButton(enabled = importIdle, onClick = { showFormatInfo = true })
                OutlinedButton(
                    onClick = { onOpenSnippets?.invoke() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border2),
                    shape = RoundedCornerShape(Radii.Sm),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = Spacing.Md,
                        vertical = Spacing.Xs,
                    ),
                ) {
                    Icon(
                        imageVector = Lucide.Code,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Spacing.Xs))
                    Text(text = stringResource(Res.string.snippets_action_manage), fontSize = 13.sp)
                }
                androidx.compose.material3.Button(
                    onClick = onAddHost,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Burgundy,
                        contentColor = White,
                    ),
                    shape = RoundedCornerShape(Radii.Sm),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = Spacing.Md,
                        vertical = Spacing.Xs,
                    ),
                ) {
                    Icon(
                        imageVector = Lucide.Plus,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Spacing.Xs))
                    Text(text = stringResource(Res.string.hosts_action_new), fontSize = 13.sp)
                }
            },
        )

        if (totalHosts == 0) {
            EmptyHostsPlaceholder(onAddHost = onAddHost)
        } else {
            HostsContent(
                foldersWithHosts = foldersWithHosts,
                activeHostIds = activeHostIds,
                onConnectHost = onConnectHost,
                onConnectSftp = onConnectSftp,
                onEditHost = onEditHost,
                onDeleteHost = { host ->
                    hostToDelete = host
                },
            )
        }
    } // end Column

    // ── Import des hôtes : overlays selon l'état du flow ──────────────────────
    when (val st = importState) {
        is HostImportState.Preview -> ImportPreviewDialog(
            rows = st.rows,
            format = st.format,
            onToggle = importViewModel::toggleSelection,
            onToggleAll = importViewModel::setAllSelected,
            onConfirm = importViewModel::confirmImport,
            onDismiss = importViewModel::reset,
        )
        is HostImportState.Done -> ImportResultDialog(
            message = stringResource(Res.string.hosts_import_result, st.imported, st.skipped),
            onDismiss = importViewModel::reset,
        )
        is HostImportState.Error -> ImportResultDialog(
            title = stringResource(Res.string.hosts_import_error_title),
            message = when (st.messageKey) {
                ImportErrorKind.NoHosts -> stringResource(Res.string.hosts_import_error_none)
                ImportErrorKind.Generic -> stringResource(Res.string.hosts_import_error_generic)
                ImportErrorKind.FileTooLarge -> stringResource(
                    Res.string.hosts_import_error_file_too_large,
                    maxImportSizeMiB,
                )
            },
            onDismiss = importViewModel::reset,
        )
        HostImportState.Parsing -> ImportBusyOverlay(label = stringResource(Res.string.hosts_import_parsing))
        HostImportState.Importing -> ImportBusyOverlay(label = stringResource(Res.string.hosts_import_importing))
        HostImportState.Idle -> Unit
    }

    // Overlay « Format de fichier NextSH » : documentation du format natif,
    // accessible indépendamment du flow d'import via le bouton info du header.
    if (showFormatInfo) {
        ImportFormatInfoDialog(onDismiss = { showFormatInfo = false })
    }

    // Confirmation destructive, DA refondue (overlay scrim + carte centrée).
    hostToDelete?.let { host ->
        ConfirmDeleteDialog(
            title = stringResource(Res.string.hosts_delete_title),
            message = stringResource(Res.string.hosts_delete_body, host.label, host.hostname),
            onConfirm = {
                viewModel.deleteHost(host.id)
                hostToDelete = null
            },
            onDismiss = { hostToDelete = null },
        )
    }
    } // end Box
}

// ─── Layout principal ─────────────────────────────────────────────────────────

@Composable
private fun HostsContent(
    foldersWithHosts: List<HostFolderWithHosts>,
    activeHostIds: Set<String>,
    onConnectHost: (String) -> Unit,
    onConnectSftp: (String) -> Unit,
    onEditHost: (String) -> Unit,
    onDeleteHost: (Host) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Xl, vertical = Spacing.Lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.Xxl),
    ) {
        foldersWithHosts.forEach { group ->
            item {
                FolderSection(
                    group = group,
                    activeHostIds = activeHostIds,
                    onConnectHost = onConnectHost,
                    onConnectSftp = onConnectSftp,
                    onEditHost = onEditHost,
                    onDeleteHost = onDeleteHost,
                )
            }
        }
        // Espace final pour que le dernier groupe ne colle pas au bas de la StatusBar.
        item { Spacer(Modifier.height(Spacing.Xl)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderSection(
    group: HostFolderWithHosts,
    activeHostIds: Set<String>,
    onConnectHost: (String) -> Unit,
    onConnectSftp: (String) -> Unit,
    onEditHost: (String) -> Unit,
    onDeleteHost: (Host) -> Unit,
) {
    Column {
        // ── En-tête du groupe ────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // TODO Phase 2.x : icône variable par folder (cf. maquette :
            //   building-2 / server / code-2 selon la nature du groupe).
            //   Nécessite une table `host_folder_metadata(group, icon, color)`
            //   qui sera introduite avec le mode Équipe ; en V1 Solo, on
            //   garde Lucide.Folder uniforme puisque `host.group` est juste
            //   un string libre sans typage.
            Icon(
                imageVector = Lucide.Folder,
                contentDescription = null,
                tint = GoldMuted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = group.folder?.name ?: stringResource(Res.string.hosts_folder_unsorted),
                color = TextSecondary,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(Spacing.Sm))
            // Badge compteur
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radii.Xs))
                    .background(SurfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = group.hosts.size.toString(),
                    color = TextDisabled,
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFamily,
                )
            }
            Spacer(Modifier.width(Spacing.Md))
            // Ligne séparatrice
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(Border1),
            )
        }

        // ── FlowRow : HostCards largeur stricte 400dp, alignées à gauche, ─────
        // wrap naturel. Cohérence avec TunnelListScreen / VaultScreen
        // (cf. docs/design/CARDS-RESPONSIVE.md).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            group.hosts.forEach { host ->
                HostCard(
                    host = host,
                    isOnline = host.id in activeHostIds,
                    lastConnectedAt = group.lastConnectedByHostId[host.id],
                    onConnect = { onConnectHost(host.id) },
                    onSftp = { onConnectSftp(host.id) },
                    onEdit = { onEditHost(host.id) },
                    onDelete = { onDeleteHost(host) },
                    modifier = Modifier.width(400.dp),
                )
            }
        }
    }
}

// ─── HostCard ─────────────────────────────────────────────────────────────────

// Hauteur fixe de la HostCard : uniformise la grille FlowRow.
private const val CARD_HEIGHT_DP = 148

@Composable
private fun HostCard(
    host: Host,
    isOnline: Boolean,
    lastConnectedAt: Long?,
    onConnect: () -> Unit,
    onSftp: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreMenuExpanded by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    // Hover "lift" : la card translate de -2 dp au survol. La maquette
    // utilise `transform: translateY(-1px)` ; on prend 2 dp pour rester
    // perceptible sur écran HiDPI. Réservé via un padding-top de 2 dp
    // sur le wrapper externe pour que le translate ne sorte pas du
    // viewport de la cellule LazyVerticalGrid (sinon le haut est
    // clippé et seul le bas semble bouger).
    val liftDp by animateDpAsState(
        targetValue = if (isHovered) (-2).dp else 0.dp,
        label = "HostCard hover lift",
    )

    Box(
        modifier = modifier
            .height(CARD_HEIGHT_DP.dp)
            .padding(top = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CARD_HEIGHT_DP.dp - 2.dp)
                .offset(y = liftDp)
                .clip(RoundedCornerShape(Radii.Lg))
                .hoverable(interactionSource)
                .background(Surface)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onConnect() },
                    )
                },
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.Md),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ── Ligne supérieure : icône + label + badge auth ──────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // Icône Server sur fond SurfaceVariant
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(Radii.Sm))
                        .background(SurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.Server,
                        contentDescription = null,
                        tint = GoldMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.Sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = host.label,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = host.username,
                            color = Gold,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFamily,
                        )
                        Text(
                            text = "@",
                            color = Burgundy,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFamily,
                        )
                        Text(
                            text = host.hostname,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFamily,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = ":${host.port}",
                            color = TextDisabled,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFamily,
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.Xs))
                AuthBadge(authType = host.authType)
            }

            // ── Ligne meta : status + last session ────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OnlineDot(isOnline = isOnline)
                Spacer(Modifier.width(Spacing.Xs))
                Text(
                    text = stringResource(if (isOnline) Res.string.hosts_card_status_online else Res.string.hosts_card_status_offline),
                    color = if (isOnline) SuccessGreen else TextDisabled,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Lucide.Clock,
                    contentDescription = null,
                    tint = TextDisabled,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = formatLastConnected(lastConnectedAt),
                    color = TextDisabled,
                    fontSize = 11.sp,
                )
            }

            // ── Actions : SSH + SFTP + More ───────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // SSH et SFTP : largeur intrinsèque (label + padding), pas
                // de `weight(1f)` : moins étirés que la version flexbox de
                // la maquette qui prenait tout l'espace dispo. Padding
                // vertical réduit (3 dp) pour aplatir.
                // Bouton SSH primaire
                androidx.compose.material3.Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Burgundy,
                        contentColor = White,
                    ),
                    shape = RoundedCornerShape(Radii.Md),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 3.dp,
                    ),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        imageVector = Lucide.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = "SSH", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                // Bouton SFTP secondaire
                OutlinedButton(
                    onClick = onSftp,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border2),
                    shape = RoundedCornerShape(Radii.Md),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 3.dp,
                    ),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        imageVector = Lucide.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = "SFTP", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                // Bouton More (…) compact à droite.
                Box {
                    OutlinedButton(
                        onClick = { moreMenuExpanded = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Border2),
                        shape = RoundedCornerShape(Radii.Md),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 10.dp,
                            vertical = 5.dp,
                        ),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            imageVector = Lucide.Ellipsis,
                            contentDescription = stringResource(Res.string.hosts_card_more_actions),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false },
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.Md))
                            .background(Surface)
                            .border(1.dp, Border1, RoundedCornerShape(Radii.Md)),
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Lucide.Pencil,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Spacer(Modifier.width(Spacing.Sm))
                                    Text(text = stringResource(Res.string.hosts_card_action_edit), color = TextPrimary, fontSize = 12.sp)
                                }
                            },
                            onClick = {
                                moreMenuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Lucide.Trash2,
                                        contentDescription = null,
                                        tint = ErrorRed,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Spacer(Modifier.width(Spacing.Sm))
                                    Text(
                                        text = stringResource(Res.string.hosts_card_action_delete),
                                        color = ErrorRed,
                                        fontSize = 12.sp,
                                    )
                                }
                            },
                            onClick = {
                                moreMenuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
        }
    }
}

// ─── Sous-composants ──────────────────────────────────────────────────────────

@Composable
private fun OnlineDot(isOnline: Boolean) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(if (isOnline) SuccessGreen else Border2),
    )
}

@Composable
private fun AuthBadge(authType: AuthType) {
    val (label, bg, fg) = when (authType) {
        AuthType.PASSWORD -> Triple("PWD", Color(0xFF2A1A1A), ErrorRed) // dark-tinted background, badge-local
        AuthType.SSH_KEY -> Triple("SSH_KEY", BurgundyDark, Gold)
        AuthType.CERTIFICATE -> Triple("CERT", Color(0xFF1A1F2A), InfoBlue) // dark-tinted background, badge-local
        AuthType.FIDO2 -> Triple("FIDO2", Color(0xFF1A2A1A), SuccessGreen) // dark-tinted background, badge-local
        AuthType.BIOMETRIC_KEY -> Triple("BIO", Color(0xFF1A1A2A), BioViolet) // dark-tinted background, badge-local
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
            fontSize = 10.sp,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ─── État vide ────────────────────────────────────────────────────────────────

@Composable
private fun EmptyHostsPlaceholder(onAddHost: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(Radii.Lg))
                    .background(SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.Server,
                    contentDescription = null,
                    tint = GoldMuted,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = stringResource(Res.string.hosts_empty_title),
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Text(
                text = stringResource(Res.string.hosts_empty_hint),
                color = TextDisabled,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(Spacing.Xs))
            androidx.compose.material3.Button(
                onClick = onAddHost,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor = White,
                ),
                shape = RoundedCornerShape(Radii.Sm),
            ) {
                Icon(
                    imageVector = Lucide.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.Xs))
                Text(text = stringResource(Res.string.hosts_action_new))
            }
        }
    }
}

/**
 * Formate un timestamp `Hosts.lastConnectedAt` (epoch ms) en delta
 * relatif court : `< 1 min`, `il y a 12 min`, `il y a 3 h`, `il y a 2 j`.
 * Renvoie la chaîne localisée « jamais » si jamais connecté. Doublon
 * volontaire de la fonction homonyme dans `StatusBar.kt`. Un fichier
 * utility partagé sera extrait quand un 3e usage apparaîtra.
 */
@Composable
private fun formatLastConnected(ts: Long?): String {
    if (ts == null) return stringResource(Res.string.statusbar_relative_never)
    val deltaSec = ((System.currentTimeMillis() - ts) / 1000).coerceAtLeast(0)
    return when {
        deltaSec < 60 -> stringResource(Res.string.hosts_relative_just_now)
        deltaSec < 3600 -> stringResource(Res.string.statusbar_relative_minutes_ago, (deltaSec / 60).toInt())
        deltaSec < 86_400 -> stringResource(Res.string.statusbar_relative_hours_ago, (deltaSec / 3600).toInt())
        else -> stringResource(Res.string.statusbar_relative_days_ago, (deltaSec / 86_400).toInt())
    }
}

// ─── Import hôtes (Termius / KeePassXC) ─────────────────────────────────────────

/**
 * Info button next to "Importer", wrapped in a [TooltipArea] (same pattern as
 * [fr.techtical.nextsh.desktop.sessions.SessionTabsScreen]'s `TabBarIconButton`)
 * so its purpose is discoverable on hover, not just via the icon-only button's
 * accessibility label.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImportInfoButton(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(Res.string.hosts_import_format_info_button)
    TooltipArea(
        tooltip = { ImportInfoTooltip(label) },
        delayMillis = 350,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Lucide.Info,
                contentDescription = label,
                tint = TextSecondary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun ImportInfoTooltip(label: String) {
    Row(
        modifier = Modifier
            .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
            .border(1.dp, Border1, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text = label, color = TextPrimary, fontFamily = SpaceGroteskFamily, fontSize = 11.sp)
    }
}

/**
 * Sélecteur de fichier Swing pour choisir un export à importer. Appelé depuis
 * un click handler Compose (EDT) : lancer le chooser modal inline est sûr.
 * Pattern identique aux pickers JFileChooser de SftpBrowserScreen. Renvoie
 * null si l'utilisateur annule.
 */
private fun pickHostImportFile(): java.io.File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.FILES_ONLY
        isMultiSelectionEnabled = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.takeIf { it.isFile }
    } else {
        null
    }
}

/**
 * Dialog de prévisualisation / sélection des hôtes parsés, overlay Compose
 * (scrim + carte centrée), même pattern que [ConfirmDeleteDialog] pour
 * éviter que JediTerm Swing ne perce un Popup/Dialog.
 *
 * SÉCURITÉ : n'affiche QUE des champs non-sensibles (label, user@host:port,
 * groupe, type d'auth), jamais de mot de passe, PEM ou passphrase.
 */
@Composable
private fun ImportPreviewDialog(
    rows: List<ImportPreviewRow>,
    format: ImportFormat,
    onToggle: (Int) -> Unit,
    onToggleAll: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val selectedCount = rows.count { it.selected }
    val allState = when {
        selectedCount == 0 -> ToggleableState.Off
        selectedCount == rows.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val hasSecrets = rows.any { it.hasSecret }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack.copy(alpha = 0.65f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onDismiss(); true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(Radii.Xl))
                .background(Surface)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Xl))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Burgundy.copy(alpha = 0.14f), RoundedCornerShape(Radii.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Download, contentDescription = null, tint = Gold, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(Spacing.Md))
                Column {
                    Text(
                        text = stringResource(Res.string.hosts_import_dialog_title),
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = stringResource(Res.string.hosts_import_dialog_subtitle, rows.size),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = stringResource(Res.string.hosts_import_detected_format, formatDisplayName(format)),
                        color = GoldMuted,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFamily,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))

            // Bandeau d'avertissement : au moins une entrée porte un secret en clair
            // (password / privateKeyPem / keyPassphrase). Ces entrées sont décochées
            // par défaut dans la liste ci-dessous ; l'utilisateur les recoche s'il
            // veut vraiment les importer.
            if (hasSecrets) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A1A1A))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.hosts_import_secret_warning),
                        color = ErrorRed,
                        fontSize = 11.sp,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
            }

            // Tout sélectionner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleAll(allState != ToggleableState.On) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TriStateCheckbox(
                    state = allState,
                    onClick = { onToggleAll(allState != ToggleableState.On) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Burgundy,
                        uncheckedColor = Border2,
                        checkmarkColor = White,
                    ),
                )
                Spacer(Modifier.width(Spacing.Xs))
                Text(
                    text = stringResource(Res.string.hosts_import_select_all),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))

            // Liste des hôtes parsés (scroll si nombreux).
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                items(rows) { row ->
                    ImportPreviewItem(row = row, onToggle = { onToggle(row.index) })
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))

            // Footer
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Transparent),
                    shape = RoundedCornerShape(Radii.Md),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
                ) {
                    Text(text = stringResource(Res.string.action_cancel), fontSize = 12.sp)
                }
                Spacer(Modifier.width(Spacing.Sm))
                androidx.compose.material3.Button(
                    onClick = onConfirm,
                    enabled = selectedCount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Burgundy,
                        contentColor = White,
                        disabledContainerColor = BurgundyDark,
                        disabledContentColor = TextDisabled,
                    ),
                    shape = RoundedCornerShape(Radii.Md),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
                ) {
                    Text(
                        text = if (selectedCount > 0) {
                            stringResource(Res.string.hosts_import_action_confirm, selectedCount)
                        } else {
                            stringResource(Res.string.hosts_import_empty_selection)
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportPreviewItem(row: ImportPreviewRow, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = row.selected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Burgundy,
                uncheckedColor = Border2,
                checkmarkColor = White,
            ),
        )
        Spacer(Modifier.width(Spacing.Xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = row.username, color = Gold, fontSize = 11.sp, fontFamily = JetBrainsMonoFamily)
                Text(text = "@", color = Burgundy, fontSize = 11.sp, fontFamily = JetBrainsMonoFamily)
                Text(
                    text = row.hostname,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFamily,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(text = ":${row.port}", color = TextDisabled, fontSize = 11.sp, fontFamily = JetBrainsMonoFamily)
            }
            if (row.hasSecret) {
                Spacer(Modifier.height(2.dp))
                SecretBadge()
            }
            row.group?.takeIf { it.isNotBlank() }?.let { grp ->
                Spacer(Modifier.height(2.dp))
                Text(text = grp, color = GoldMuted, fontSize = 10.sp, fontFamily = JetBrainsMonoFamily)
            }
        }
        Spacer(Modifier.width(Spacing.Xs))
        AuthBadge(authType = row.authType)
    }
}

/**
 * Badge d'alerte pour une entrée d'import porteuse d'un secret en clair
 * (password / privateKeyPem / keyPassphrase), même palette que le badge
 * `AuthType.PASSWORD` d'[AuthBadge], réutilisée intentionnellement pour
 * garder une seule teinte "attention" dans cet écran.
 */
@Composable
private fun SecretBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Xs))
            .background(Color(0xFF2A1A1A))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(Res.string.hosts_import_secret_badge),
            color = ErrorRed,
            fontSize = 10.sp,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Nom d'affichage du format détecté, noms de produits, volontairement non
 * traduits (même convention que les mentions "Termius" / "KeePassXC" en dur
 * dans les strings d'erreur existantes).
 */
private fun formatDisplayName(format: ImportFormat): String = when (format) {
    ImportFormat.NEXTSH -> "NextSH"
    ImportFormat.TERMIUS -> "Termius"
    ImportFormat.KEEPASSXC -> "KeePassXC"
}

/**
 * Overlay documentant le format d'import natif NextSH, ouvert depuis le
 * bouton info du header, indépendamment du flow d'import en cours. Même
 * patron que [ImportResultDialog] (scrim + carte centrée + Escape), avec un
 * corps scrollable pour le texte + le bloc d'exemple JSON.
 *
 * SÉCURITÉ : [NEXTSH_IMPORT_FORMAT_EXAMPLE] est un exemple statique (mots de
 * passe vides / PEM tronqué), jamais de données utilisateur réelles.
 */
@Composable
private fun ImportFormatInfoDialog(onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // The "Copied!" label was previously permanent for the rest of the
    // dialog's lifetime once clicked once: reset it back to "Copy example"
    // after a couple of seconds so a second copy click gives the same
    // feedback again.
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack.copy(alpha = 0.65f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onDismiss(); true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.9f)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(Radii.Xl))
                .background(Surface)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Xl))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Burgundy.copy(alpha = 0.14f), RoundedCornerShape(Radii.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Info, contentDescription = null, tint = Gold, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(Spacing.Md))
                Text(
                    text = stringResource(Res.string.hosts_import_format_info_title),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))

            // Corps scrollable : intro, champs obligatoires/optionnels, périmètre
            // "hôtes uniquement", avertissement secrets, exemple JSON copiable.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Text(
                    text = stringResource(Res.string.hosts_import_format_info_intro),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Text(
                    text = stringResource(Res.string.hosts_import_format_info_required),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = JetBrainsMonoFamily,
                )
                Text(
                    text = stringResource(Res.string.hosts_import_format_info_optional),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = JetBrainsMonoFamily,
                )
                Text(
                    text = stringResource(Res.string.hosts_import_format_info_scope),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Text(
                    text = stringResource(Res.string.hosts_import_format_info_secrets_warning),
                    color = ErrorRed,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(Spacing.Xs))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(Radii.Md))
                        .background(NearBlack)
                        .border(1.dp, Border1, RoundedCornerShape(Radii.Md)),
                ) {
                    Text(
                        text = NEXTSH_IMPORT_FORMAT_EXAMPLE,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFamily,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))

            // Footer
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(NEXTSH_IMPORT_FORMAT_EXAMPLE))
                        copied = true
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border2),
                    shape = RoundedCornerShape(Radii.Md),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (copied) Res.string.hosts_import_format_info_copied else Res.string.hosts_import_format_info_copy,
                        ),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(Spacing.Sm))
                androidx.compose.material3.Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
                    shape = RoundedCornerShape(Radii.Md),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
                ) {
                    Text(text = stringResource(Res.string.action_close), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Carte de résultat (succès ou erreur), overlay scrim simple, un seul bouton. */
@Composable
private fun ImportResultDialog(
    message: String,
    onDismiss: () -> Unit,
    title: String = stringResource(Res.string.hosts_import_dialog_title),
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack.copy(alpha = 0.65f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Escape || ev.key == Key.Enter)) {
                    onDismiss(); true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(Radii.Xl))
                .background(Surface)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Xl))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
            Text(
                text = message,
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
                    shape = RoundedCornerShape(Radii.Md),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
                ) {
                    Text(text = stringResource(Res.string.action_close), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Overlay bloquant pendant le parsing / l'import (spinner + label). */
@Composable
private fun ImportBusyOverlay(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack.copy(alpha = 0.65f))
            // Modifier.background alone draws pixels but creates no hit-test
            // node: without this, every click during Parsing/Importing fell
            // straight through to whatever was underneath (host cards, the
            // header buttons). This scrim consumes the tap and does nothing
            // with it, which is exactly the point.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            CircularProgressIndicator(color = Gold, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            Text(text = label, color = TextSecondary, fontSize = 13.sp)
        }
    }
}
