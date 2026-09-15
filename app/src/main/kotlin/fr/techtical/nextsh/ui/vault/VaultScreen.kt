// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Fingerprint
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Usb
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.ui.components.NextShTextField
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Border2
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.GoldMuted
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.SurfaceVariant
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.WarningAmber
import fr.techtical.nextsh.ui.theme.White

/**
 * VaultScreen : refonte Phase 3.1 (DA Techtical portée du Desktop).
 *
 * Conventions plateforme conservées : Scaffold + TopAppBar +
 * Snackbar + AlertDialog (Material Design Android). La refonte porte
 * sur :
 *   - title TopAppBar Space Grotesk SemiBold + subtitle 12sp TextDisabled
 *     (compteur de clés)
 *   - sections cards Surface/Border1/Radii.Lg avec header GoldLight
 *     UPPERCASE 11sp mono (parité Desktop)
 *   - SshKeyCard refondue : icon-wrap Gold@0.08, label SpaceGrotesk
 *     SemiBold 13sp, fingerprint JetBrainsMono 11sp, AlgorithmBadge
 *     Gold border, BIO badge WarningAmber pour les clés biométriques
 *   - dialogs restylés : header icon-wrap Burgundy@0.16 + 1dp Border1
 *     separator + body sections + boutons primary/ghost
 *
 * Features Android-only **préservées** :
 *   - section "Credentials" (compteur de mots de passe stockés)
 *   - option biométrique dans GenerateKeyDialog (Keystore TEE / StrongBox)
 *   - SAF launchers (CreateDocument / OpenDocument)
 *   - écran KeyImportScreen séparé (route distincte)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onNavigateToKeyImport: () -> Unit,
    onNavigateToFido2Enroll: () -> Unit = {},
    onBack: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    var showGenerateDialog by remember { mutableStateOf(false) }
    var keyToDelete by remember { mutableStateOf<SshKey?>(null) }
    // rememberSaveable pour tout ce qui traverse un aller-retour vers le
    // sélecteur de fichiers : celui-ci est une activité séparée, pendant
    // laquelle le système peut détruire la nôtre. Avec un simple remember, la
    // sauvegarde ou la restauration du coffre repartait sans destination et
    // échouait en silence. Uri est Parcelable, donc sauvegardable tel quel.
    var exportUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    var importUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    // Seule la clé publique est écrite dans le fichier : la conserver en clair
    // suffit et evite de rendre SshKey sauvegardable. Une clé publique n'est
    // pas un secret.
    var pubKeyPendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var pubKeySavedTrigger by remember { mutableStateOf(0) }
    var pubKeySavedError by remember { mutableStateOf(false) }
    var pubKeyCopiedTrigger by remember { mutableStateOf(0) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { exportUri = it; showExportDialog = true } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { importUri = it; showImportDialog = true } }

    val pubKeyExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pending = pubKeyPendingExport
        if (uri != null && pending != null) {
            try {
                val stream = context.contentResolver.openOutputStream(uri)
                if (stream != null) {
                    stream.use { it.write(pending.toByteArray(Charsets.UTF_8)) }
                    pubKeySavedError = false
                } else {
                    pubKeySavedError = true
                }
            } catch (_: Throwable) {
                pubKeySavedError = true
            }
            pubKeySavedTrigger++
        }
        pubKeyPendingExport = null
    }

    LaunchedEffect(pubKeySavedTrigger) {
        if (pubKeySavedTrigger > 0) {
            if (pubKeySavedError) {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.error_pub_key_save_failed),
                    duration = SnackbarDuration.Short,
                )
            } else {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.vault_message_pub_key_downloaded),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    LaunchedEffect(pubKeyCopiedTrigger) {
        if (pubKeyCopiedTrigger > 0) {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.vault_message_pub_key_copied),
                duration = SnackbarDuration.Short,
            )
        }
    }

    val keysSubtitle = when {
        uiState.sshKeys.isEmpty() -> stringResource(R.string.empty_no_keys_title)
        uiState.sshKeys.size == 1 -> "1 clé · chiffrée localement"
        else -> "${uiState.sshKeys.size} clés · chiffrées localement"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.title_vault),
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = keysSubtitle,
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
                    BtnAddSecondaryUsb(onClick = onNavigateToFido2Enroll)
                    Spacer(Modifier.width(Spacing.Sm))
                    BtnAddPrimary(onClick = { showGenerateDialog = true })
                    Spacer(Modifier.width(Spacing.Sm))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = NearBlack,
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Gold)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
        ) {
            // ── Section clés SSH ────────────────────────────────────────────────
            item {
                FormSection(
                    title = stringResource(R.string.section_ssh_keys),
                    badge = uiState.sshKeys.size.takeIf { it > 0 }?.toString(),
                ) {
                    if (uiState.sshKeys.isEmpty()) {
                        EmptyKeysBlock()
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                            uiState.sshKeys.forEach { key ->
                                SshKeyRow(
                                    sshKey = key,
                                    onCopyPub = {
                                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboardManager.setPrimaryClip(ClipData.newPlainText("ssh public key", key.publicKey))
                                        pubKeyCopiedTrigger++
                                    },
                                    onDownloadPub = {
                                        pubKeyPendingExport = key.publicKey
                                        pubKeyExportLauncher.launch(sanitizeFilename(key.label) + ".pub")
                                    },
                                    onDelete = { keyToDelete = key },
                                )
                            }
                        }
                    }
                    BtnSecondaryFull(
                        onClick = onNavigateToKeyImport,
                        icon = Lucide.Download,
                        label = stringResource(R.string.action_import_key),
                    )
                }
            }

            // ── Section credentials (Android-only, compteur passwords) ─────────
            item {
                FormSection(
                    title = stringResource(R.string.section_credentials),
                    badge = uiState.storedCredentialCount.takeIf { it > 0 }?.toString(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(Radii.Md))
                            .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Sm)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Lucide.Lock,
                                contentDescription = null,
                                tint = Gold,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        Spacer(Modifier.width(Spacing.Md))
                        Text(
                            text = when {
                                uiState.storedCredentialCount == 0 -> stringResource(R.string.label_no_passwords_stored)
                                uiState.storedCredentialCount == 1 -> stringResource(R.string.label_passwords_stored_one)
                                else -> stringResource(R.string.label_passwords_stored_plural, uiState.storedCredentialCount)
                            },
                            color = if (uiState.storedCredentialCount == 0) TextDisabled else TextPrimary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            // ── Section backup ─────────────────────────────────────────────────
            item {
                FormSection(title = stringResource(R.string.section_backup)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        BtnSecondaryFull(
                            onClick = {
                                val date = java.time.LocalDate.now().toString().replace("-", "")
                                exportLauncher.launch("nextsh_backup_$date.nextsh")
                            },
                            enabled = !uiState.isExporting,
                            icon = Lucide.Upload,
                            label = stringResource(R.string.action_export),
                            loading = uiState.isExporting,
                            modifier = Modifier.weight(1f),
                        )
                        BtnSecondaryFull(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            enabled = !uiState.isImporting,
                            icon = Lucide.Download,
                            label = stringResource(R.string.action_import),
                            loading = uiState.isImporting,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(Spacing.Lg)) }
        }
    }

    if (showGenerateDialog) {
        GenerateKeyDialog(
            isGenerating = uiState.isGenerating,
            onGenerate = { label, type, isBiometric ->
                if (isBiometric) viewModel.generateBiometricKey(label)
                else viewModel.generateKey(label, type)
                showGenerateDialog = false
            },
            onDismiss = { showGenerateDialog = false },
        )
    }

    keyToDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { keyToDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.dialog_delete_key_title),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_delete_key_message, key.label),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                BtnDangerSolid(
                    onClick = {
                        viewModel.deleteKey(key.id)
                        keyToDelete = null
                    },
                    label = stringResource(R.string.action_delete),
                )
            },
            dismissButton = {
                TextButton(onClick = { keyToDelete = null }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = Surface,
            shape = RoundedCornerShape(Radii.Lg),
        )
    }

    if (showExportDialog) {
        val uri = exportUri
        if (uri != null) {
            BackupPassphraseDialog(
                isExportMode = true,
                onConfirm = { passphrase ->
                    showExportDialog = false
                    viewModel.exportVault(passphrase, uri, context)
                    exportUri = null
                },
                onDismiss = {
                    showExportDialog = false
                    exportUri = null
                },
            )
        }
    }

    if (showImportDialog) {
        val uri = importUri
        if (uri != null) {
            BackupPassphraseDialog(
                isExportMode = false,
                onConfirm = { passphrase ->
                    showImportDialog = false
                    viewModel.importVault(passphrase, uri, context)
                    importUri = null
                },
                onDismiss = {
                    showImportDialog = false
                    importUri = null
                },
            )
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun sanitizeFilename(label: String): String = label.replace(Regex("[^A-Za-z0-9_-]"), "_")

// ── Section primitive (Surface + Border1 + header UPPERCASE) ───────────────

@Composable
private fun FormSection(
    title: String,
    badge: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title.uppercase(),
                color = GoldLight,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .background(Burgundy, RoundedCornerShape(Radii.Sm))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badge,
                        color = White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(Radii.Lg))
                .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
                .padding(horizontal = Spacing.Md, vertical = Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            content = content,
        )
    }
}

// ── SshKey row ──────────────────────────────────────────────────────────────

@Composable
private fun SshKeyRow(
    sshKey: SshKey,
    onCopyPub: () -> Unit,
    onDownloadPub: () -> Unit,
    onDelete: () -> Unit,
) {
    val fingerprint = remember(sshKey.publicKey) {
        sshKey.publicKey.split(" ").getOrNull(1)?.let { b64 ->
            if (b64.length > 24) "…${b64.takeLast(24)}" else b64
        } ?: "-"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(Radii.Md))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.KeyRound, contentDescription = null, tint = Gold, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(Spacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sshKey.label,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(Spacing.Xs))
                AlgorithmBadge(sshKey.keyType)
                if (sshKey.isBiometric) {
                    Spacer(Modifier.width(4.dp))
                    BioBadge()
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = fingerprint,
                color = TextSecondary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCopyPub, modifier = Modifier.size(32.dp)) {
            Icon(
                Lucide.Copy,
                contentDescription = stringResource(R.string.vault_key_action_copy_pub),
                tint = GoldMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onDownloadPub, modifier = Modifier.size(32.dp)) {
            Icon(
                Lucide.Download,
                contentDescription = stringResource(R.string.vault_key_action_download_pub),
                tint = GoldMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Lucide.Trash2,
                contentDescription = stringResource(R.string.action_delete),
                tint = ErrorRed.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun AlgorithmBadge(keyType: SshKeyType) {
    val label = when (keyType) {
        SshKeyType.ED25519 -> "ED25519"
        SshKeyType.RSA_4096 -> "RSA"
        SshKeyType.ECDSA_256 -> "ECDSA-256"
        SshKeyType.ECDSA_384 -> "ECDSA-384"
        SshKeyType.ECDSA_521 -> "ECDSA-521"
        SshKeyType.SK_ED25519 -> "SK-ED25519"
        SshKeyType.SK_ECDSA_256 -> "SK-ECDSA"
    }
    Box(
        modifier = Modifier
            .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(3.dp))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun BioBadge() {
    Box(
        modifier = Modifier
            .background(WarningAmber.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
            .border(1.dp, WarningAmber.copy(alpha = 0.20f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Fingerprint, null, modifier = Modifier.size(9.dp), tint = WarningAmber)
            Spacer(Modifier.width(3.dp))
            Text(
                text = stringResource(R.string.badge_biometric),
                color = WarningAmber,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
private fun EmptyKeysBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Lucide.KeyRound,
            contentDescription = null,
            tint = GoldMuted,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(R.string.empty_no_keys_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.empty_no_keys_subtitle),
            color = TextSecondary,
            fontSize = 11.sp,
        )
    }
}

// ── Boutons ─────────────────────────────────────────────────────────────────

@Composable
private fun BtnSecondaryFull(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary,
            disabledContentColor = TextDisabled,
        ),
        border = BorderStroke(1.dp, if (enabled) Border2 else Border2.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = Spacing.Md, vertical = Spacing.Sm),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Gold, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BtnPrimary(
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Burgundy,
            contentColor = White,
            disabledContainerColor = Burgundy.copy(alpha = 0.4f),
            disabledContentColor = White.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BtnDangerSolid(
    onClick: () -> Unit,
    label: String,
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = ErrorRed,
            contentColor = White,
        ),
        border = BorderStroke(0.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Add primary "+", parité Tunnel/HostList ─────────────────────────────────

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
            contentDescription = stringResource(R.string.action_generate_key),
            tint = White,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── Add secondary USB (FIDO2 enrollment): outlined Border2 ─────────────────

@Composable
private fun BtnAddSecondaryUsb(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Radii.Md))
            .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Lucide.Usb,
            contentDescription = stringResource(R.string.vault_action_enroll_fido2),
            tint = TextPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── Generate Key Dialog (option biométrique Android-only) ───────────────────

@Composable
fun GenerateKeyDialog(
    isGenerating: Boolean,
    onGenerate: (label: String, type: SshKeyType, isBiometric: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(SshKeyType.ED25519) }
    var isBiometric by remember { mutableStateOf(false) }

    val keyTypeOptions = listOf(
        SshKeyType.ED25519 to "ED25519",
        SshKeyType.RSA_4096 to "RSA-4096",
        SshKeyType.ECDSA_256 to "ECDSA-256",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Burgundy.copy(alpha = 0.16f), RoundedCornerShape(Radii.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.KeyRound, contentDescription = null, tint = GoldLight, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(Spacing.Md))
                Text(
                    text = stringResource(R.string.dialog_generate_key_title),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                NextShTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = stringResource(R.string.label_key_name),
                    placeholder = stringResource(R.string.placeholder_key_name),
                )

                if (!isBiometric) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        Text(
                            text = stringResource(R.string.label_key_type).uppercase(),
                            color = GoldMuted,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.6.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                            keyTypeOptions.forEach { (type, displayName) ->
                                FilterChip(
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
                                    label = {
                                        Text(
                                            displayName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = JetBrainsMonoFamily,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Burgundy,
                                        selectedLabelColor = White,
                                        containerColor = SurfaceVariant,
                                        labelColor = TextSecondary,
                                    ),
                                    shape = RoundedCornerShape(Radii.Md),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selectedType == type,
                                        borderColor = Border1,
                                        selectedBorderColor = Burgundy,
                                    ),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(Radii.Md))
                        .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_biometric_key),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.label_biometric_key_desc),
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = isBiometric,
                        onCheckedChange = { isBiometric = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = White,
                            checkedTrackColor = Burgundy,
                            checkedBorderColor = Burgundy,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = SurfaceVariant,
                            uncheckedBorderColor = Border2,
                        ),
                    )
                }

                if (isGenerating) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Gold,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.status_generating),
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            BtnPrimary(
                onClick = {
                    onGenerate(label, if (isBiometric) SshKeyType.ECDSA_256 else selectedType, isBiometric)
                },
                label = stringResource(R.string.action_generate),
                enabled = label.isNotBlank() && !isGenerating,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = TextSecondary)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(Radii.Lg),
    )
}

// ── Backup Passphrase Dialog ────────────────────────────────────────────────

@Composable
fun BackupPassphraseDialog(
    isExportMode: Boolean,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }

    val passphraseError = when {
        passphrase.isNotEmpty() && passphrase.length < 8 -> stringResource(R.string.error_passphrase_too_short)
        else -> null
    }
    val confirmationError = when {
        isExportMode && confirmation.isNotEmpty() && confirmation != passphrase -> stringResource(R.string.error_passphrases_mismatch)
        else -> null
    }
    val isValid = passphrase.length >= 8 &&
        (!isExportMode || (confirmation == passphrase))

    AlertDialog(
        onDismissRequest = {
            passphrase = ""
            confirmation = ""
            onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Burgundy.copy(alpha = 0.16f), RoundedCornerShape(Radii.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isExportMode) Lucide.Upload else Lucide.Download,
                        contentDescription = null,
                        tint = GoldLight,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.Md))
                Text(
                    text = if (isExportMode) stringResource(R.string.dialog_export_vault_title) else stringResource(R.string.dialog_import_backup_title),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                Text(
                    text = if (isExportMode) {
                        stringResource(R.string.dialog_export_vault_message)
                    } else {
                        stringResource(R.string.dialog_import_backup_message)
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                )

                NextShTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.label_passphrase),
                    isError = passphraseError != null,
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            Icon(
                                imageVector = if (showPassphrase) Lucide.EyeOff else Lucide.Eye,
                                contentDescription = if (showPassphrase) stringResource(R.string.action_hide_password) else stringResource(R.string.action_show_password),
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                )
                if (passphraseError != null) {
                    Text(
                        text = passphraseError,
                        color = ErrorRed,
                        fontSize = 11.sp,
                    )
                }

                if (isExportMode) {
                    NextShTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = stringResource(R.string.label_confirm_passphrase),
                        isError = confirmationError != null,
                        visualTransformation = if (showConfirmation) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showConfirmation = !showConfirmation }) {
                                Icon(
                                    imageVector = if (showConfirmation) Lucide.EyeOff else Lucide.Eye,
                                    contentDescription = if (showConfirmation) stringResource(R.string.action_hide_password) else stringResource(R.string.action_show_password),
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    )
                    if (confirmationError != null) {
                        Text(
                            text = confirmationError,
                            color = ErrorRed,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            BtnPrimary(
                onClick = {
                    onConfirm(passphrase.toCharArray())
                    passphrase = ""
                    confirmation = ""
                },
                label = if (isExportMode) stringResource(R.string.action_export) else stringResource(R.string.action_import),
                enabled = isValid,
            )
        },
        dismissButton = {
            TextButton(onClick = {
                passphrase = ""
                confirmation = ""
                onDismiss()
            }) {
                Text(stringResource(R.string.action_cancel), color = TextSecondary)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(Radii.Lg),
    )
}
