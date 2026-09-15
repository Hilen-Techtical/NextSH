// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Download
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.shared.core.`import`.ImportFormatDetector
import fr.techtical.nextsh.shared.core.`import`.NEXTSH_IMPORT_FORMAT_EXAMPLE
import fr.techtical.nextsh.ui.theme.BioViolet
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Border2
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.BurgundyDark
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
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
import fr.techtical.nextsh.ui.theme.White
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Import des hôtes depuis un export natif NextSH (JSON), Termius (JSON) ou
 * KeePassXC (CSV/XML).
 *
 * Flow : bouton "Choisir un fichier" → SAF [ActivityResultContracts.OpenDocument]
 * (mime any-type) → contrôle de taille SAF (cap [ImportFormatDetector.MAX_IMPORT_FILE_BYTES])
 * → lecture du texte via `contentResolver` → auto-détection du format via
 * [ImportFormatDetector] → écran de prévisualisation/sélection (cases à
 * cocher ; une entrée porteuse d'un secret démarre décochée) → import via
 * [HostImportViewModel] → snackbar de résultat puis retour à la liste (qui
 * observe le repo et se rafraîchit).
 *
 * SÉCURITÉ : la preview n'affiche QUE des champs non-sensibles (label,
 * user@host:port, groupe, type d'auth, présence d'un secret), jamais de mot
 * de passe, PEM ou passphrase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostImportScreen(
    onBack: () -> Unit,
    viewModel: HostImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showFormatInfoDialog by remember { mutableStateOf(false) }
    var exampleCopiedTrigger by remember { mutableStateOf(0) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            try {
                when (val outcome = readImportDocument(context, it)) {
                    is DocumentReadOutcome.Content -> viewModel.parse(outcome.text)
                    DocumentReadOutcome.TooLarge -> viewModel.reportFileTooLarge()
                    DocumentReadOutcome.ReadError -> viewModel.reportReadError()
                }
            } catch (e: Exception) {
                Timber.w("Host import file read failed: ${e.message}")
                viewModel.reportReadError()
            }
        }
    }

    // Résultat : snackbar puis retour à la liste (qui se rafraîchit via le repo).
    val resultImported = stringResource(
        R.string.host_import_result,
        uiState.result?.imported ?: 0,
        uiState.result?.skipped ?: 0,
    )
    LaunchedEffect(uiState.result) {
        if (uiState.result != null) {
            snackbarHostState.showSnackbar(message = resultImported, duration = SnackbarDuration.Short)
            viewModel.clear()
            onBack()
        }
    }

    val exampleCopiedMessage = stringResource(R.string.host_import_format_info_copied)
    LaunchedEffect(exampleCopiedTrigger) {
        if (exampleCopiedTrigger > 0) {
            snackbarHostState.showSnackbar(message = exampleCopiedMessage, duration = SnackbarDuration.Short)
        }
    }

    val maxImportSizeMiB = (ImportFormatDetector.MAX_IMPORT_FILE_BYTES / (1024 * 1024)).toInt()
    val errorMessage = uiState.error?.let {
        when (it) {
            HostImportError.NoHosts -> stringResource(R.string.host_import_error_none)
            HostImportError.Generic -> stringResource(R.string.host_import_error_generic)
            HostImportError.TooLarge -> stringResource(R.string.host_import_error_too_large, maxImportSizeMiB)
        }
    }

    if (showFormatInfoDialog) {
        HostImportFormatInfoDialog(
            onDismiss = { showFormatInfoDialog = false },
            onCopyExample = {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboardManager.setPrimaryClip(ClipData.newPlainText("nextsh import example", NEXTSH_IMPORT_FORMAT_EXAMPLE))
                exampleCopiedTrigger++
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.host_import_title),
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Lucide.ArrowLeft,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showFormatInfoDialog = true }) {
                        Icon(
                            Lucide.Info,
                            contentDescription = stringResource(R.string.host_import_format_info_button),
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (uiState.hasPreview) {
                ImportBottomBar(
                    selectedCount = uiState.selectedCount,
                    enabled = uiState.selectedCount > 0 && !uiState.isImporting,
                    onImport = viewModel::import,
                )
            }
        },
        containerColor = NearBlack,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isParsing || uiState.isImporting -> BusyState(
                    label = if (uiState.isImporting) {
                        stringResource(R.string.host_import_importing)
                    } else {
                        stringResource(R.string.host_import_parsing)
                    },
                )
                errorMessage != null -> ErrorState(
                    message = errorMessage,
                    onRetry = { filePickerLauncher.launch(arrayOf("*/*")) },
                )
                uiState.hasPreview -> PreviewList(
                    uiState = uiState,
                    onToggle = viewModel::toggleSelection,
                    onToggleAll = viewModel::setAllSelected,
                )
                else -> PickState(onPick = { filePickerLauncher.launch(arrayOf("*/*")) })
            }
        }
    }
}

// ── Empty / picker state ──────────────────────────────────────────────────────

@Composable
private fun PickState(onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Lucide.Download,
            contentDescription = null,
            tint = GoldMuted,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(R.string.host_import_pick_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(R.string.host_import_pick_hint),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Lg))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .background(Burgundy)
                .clickable(onClick = onPick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.FolderOpen, contentDescription = null, tint = White, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(Spacing.Sm))
                Text(
                    text = stringResource(R.string.host_import_select_file),
                    color = White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SpaceGroteskFamily,
                )
            }
        }
    }
}

// ── Busy state ────────────────────────────────────────────────────────────────

@Composable
private fun BusyState(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Gold, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(Spacing.Md))
        Text(text = label, color = TextSecondary, fontSize = 13.sp)
    }
}

// ── Error state ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Lucide.TriangleAlert,
            contentDescription = null,
            tint = ErrorRed,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = message,
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Lg))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
                .clickable(onClick = onRetry)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.host_import_select_file),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = SpaceGroteskFamily,
            )
        }
    }
}

// ── Preview list ──────────────────────────────────────────────────────────────

@Composable
private fun PreviewList(
    uiState: HostImportUiState,
    onToggle: (Int) -> Unit,
    onToggleAll: (Boolean) -> Unit,
) {
    val allState = when {
        uiState.selectedCount == 0 -> ToggleableState.Off
        uiState.selectedCount == uiState.rows.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Column(modifier = Modifier.fillMaxSize()) {
        uiState.detectedFormat?.let { format ->
            Text(
                text = stringResource(R.string.host_import_format_detected, format.displayName()),
                color = GoldMuted,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Xs),
            )
        }
        if (uiState.hasSecretEntries) {
            SecretWarningBanner(modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Xs))
        }
        // Sous-titre + tout sélectionner.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleAll(allState != ToggleableState.On) }
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
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
                text = stringResource(R.string.host_import_found, uiState.rows.size),
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.Lg,
                end = Spacing.Lg,
                bottom = Spacing.Lg,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            items(uiState.rows) { row ->
                PreviewItem(row = row, onToggle = { onToggle(row.index) })
            }
        }
    }
}

@Composable
private fun PreviewItem(row: HostImportPreviewRow, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Lg))
            .background(Surface)
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .clickable(onClick = onToggle)
            .padding(Spacing.Md),
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
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = row.username, color = Gold, fontFamily = JetBrainsMonoFamily, fontSize = 11.sp)
                Text(text = "@", color = Burgundy, fontFamily = JetBrainsMonoFamily, fontSize = 11.sp)
                Text(
                    text = row.hostname,
                    color = TextSecondary,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(text = ":${row.port}", color = TextDisabled, fontFamily = JetBrainsMonoFamily, fontSize = 11.sp)
            }
            row.group?.takeIf { it.isNotBlank() }?.let { grp ->
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(4.dp).background(GoldMuted, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(text = grp, color = GoldMuted, fontFamily = JetBrainsMonoFamily, fontSize = 10.sp)
                }
            }
            if (row.hasSecret) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Lock, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.host_import_secret_badge),
                        color = ErrorRed,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        Spacer(Modifier.width(Spacing.Xs))
        AuthBadge(row.authType)
    }
}

/** "NextSH" / "Termius" / "KeePassXC": proper-noun product names, identical in every locale. */
private fun HostImportDetectedFormat.displayName(): String = when (this) {
    HostImportDetectedFormat.NEXTSH -> "NextSH"
    HostImportDetectedFormat.TERMIUS -> "Termius"
    HostImportDetectedFormat.KEEPASSXC -> "KeePassXC"
}

@Composable
private fun SecretWarningBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Md))
            .background(ErrorRed.copy(alpha = 0.10f))
            .border(1.dp, ErrorRed.copy(alpha = 0.35f), RoundedCornerShape(Radii.Md))
            .padding(Spacing.Sm),
    ) {
        Icon(
            Lucide.TriangleAlert,
            contentDescription = null,
            tint = ErrorRed,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.Xs))
        Text(
            text = stringResource(R.string.host_import_secret_warning),
            color = TextSecondary,
            fontSize = 11.sp,
        )
    }
}

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

// ── Bottom bar ────────────────────────────────────────────────────────────────

@Composable
private fun ImportBottomBar(selectedCount: Int, enabled: Boolean, onImport: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .border(1.dp, Border1)
            .navigationBarsPadding()
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.Md))
                .background(if (enabled) Burgundy else BurgundyDark)
                .let { if (enabled) it.clickable(onClick = onImport) else it }
                .padding(vertical = Spacing.Md),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Lucide.Download,
                    contentDescription = null,
                    tint = if (enabled) White else TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.Sm))
                Text(
                    text = stringResource(R.string.host_import_action_confirm, selectedCount),
                    color = if (enabled) White else TextSecondary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

// ── Format info dialog ───────────────────────────────────────────────────────

/**
 * "Format de fichier NextSH" info dialog, opened from the TopAppBar info
 * button. Documents the native `nextsh-hosts` JSON schema (required/optional
 * fields, hosts-only scope, plaintext-secret warning) and lets the user copy
 * [NEXTSH_IMPORT_FORMAT_EXAMPLE] verbatim, same clipboard pattern as the SSH
 * public-key copy button in `VaultScreen`.
 */
@Composable
private fun HostImportFormatInfoDialog(onDismiss: () -> Unit, onCopyExample: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Info, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.Sm))
                Text(
                    text = stringResource(R.string.host_import_format_info_title),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.host_import_format_info_intro),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Spacing.Sm))
                Text(
                    text = stringResource(R.string.host_import_format_info_required),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.host_import_format_info_optional),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(Spacing.Sm))
                Text(
                    text = stringResource(R.string.host_import_format_info_scope),
                    color = GoldMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(Spacing.Sm))
                Text(
                    // Dedicated copy for this dialog: host_import_secret_warning
                    // is the PREVIEW screen's banner ("les entrées concernées sont
                    // décochées…"), which reads as a non sequitur here since this
                    // dialog isn't showing any preview rows.
                    text = stringResource(R.string.host_import_format_info_secrets_warning),
                    color = ErrorRed,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(Spacing.Md))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(Radii.Md))
                        .background(SurfaceVariant)
                        .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
                        .padding(Spacing.Sm),
                ) {
                    Text(
                        text = NEXTSH_IMPORT_FORMAT_EXAMPLE,
                        color = TextSecondary,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopyExample) {
                Icon(Lucide.Copy, contentDescription = null, tint = Gold, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.host_import_format_info_copy_example), color = Gold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = TextSecondary)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(Radii.Lg),
    )
}

// ── SAF read: size cap + bounded read ────────────────────────────────────────

/** Outcome of reading a picked SAF document, bounded by the host-import size cap. */
private sealed interface DocumentReadOutcome {
    data class Content(val text: String) : DocumentReadOutcome
    object TooLarge : DocumentReadOutcome
    object ReadError : DocumentReadOutcome
}

/**
 * Reads [uri]'s content, enforcing [ImportFormatDetector.MAX_IMPORT_FILE_BYTES]
 * *before* the whole document is materialized as a `String`.
 *
 * The SAF document's declared size (via [OpenableColumns.SIZE]) is checked
 * first when the provider reports one. Many providers omit it, though: in
 * that case the size is indeterminable up front, so the content is instead
 * streamed and counted as it's read, aborting as soon as it exceeds the cap
 * rather than after buffering an arbitrarily large file into memory.
 */
private fun readImportDocument(context: Context, uri: Uri): DocumentReadOutcome {
    val declaredSize = resolveDocumentSize(context, uri)
    if (declaredSize != null) {
        if (ImportFormatDetector.isFileTooLarge(declaredSize)) return DocumentReadOutcome.TooLarge
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Timber.w("Host import: read failed for a document with known size (${e.message})")
            null
        }
        return text?.let { DocumentReadOutcome.Content(it) } ?: DocumentReadOutcome.ReadError
    }
    return readBoundedDocument(context, uri, ImportFormatDetector.MAX_IMPORT_FILE_BYTES)
}

/** Declared byte size of [uri] via the SAF [OpenableColumns.SIZE] projection, or null if unavailable. */
private fun resolveDocumentSize(context: Context, uri: Uri): Long? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
        } else {
            null
        }
    }
} catch (e: Exception) {
    Timber.w("Host import: failed to resolve document size (${e.message})")
    null
}

/**
 * Streams [uri] and decodes it as UTF-8, aborting with [DocumentReadOutcome.TooLarge]
 * the moment more than [maxBytes] raw bytes have been read: used when the SAF
 * provider didn't report a size up front, so the cap can't be checked before
 * opening the stream.
 */
private fun readBoundedDocument(context: Context, uri: Uri, maxBytes: Long): DocumentReadOutcome = try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_READ_CHUNK_BYTES)
        var total = 0L
        var outcome: DocumentReadOutcome? = null
        while (outcome == null) {
            val read = stream.read(chunk)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                outcome = DocumentReadOutcome.TooLarge
            } else {
                buffer.write(chunk, 0, read)
            }
        }
        outcome ?: DocumentReadOutcome.Content(buffer.toString(Charsets.UTF_8.name()))
    } ?: DocumentReadOutcome.ReadError
} catch (e: Exception) {
    Timber.w("Host import: bounded read failed (${e.message})")
    DocumentReadOutcome.ReadError
}

private const val DEFAULT_READ_CHUNK_BYTES = 8192
