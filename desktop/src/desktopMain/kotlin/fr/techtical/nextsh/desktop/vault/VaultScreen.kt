// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.Usb
import fr.techtical.nextsh.desktop.components.ConfirmDeleteDialog
import fr.techtical.nextsh.desktop.components.PageHeader
import fr.techtical.nextsh.desktop.components.TechticalDialogCard
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.GoldMuted
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
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.security.MessageDigest
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.vault_action_enroll_fido2
import fr.techtical.nextsh.desktop.generated.resources.vault_action_export
import fr.techtical.nextsh.desktop.generated.resources.vault_action_generate
import fr.techtical.nextsh.desktop.generated.resources.vault_action_import
import fr.techtical.nextsh.desktop.generated.resources.vault_action_import_short
import fr.techtical.nextsh.desktop.generated.resources.vault_delete_body
import fr.techtical.nextsh.desktop.generated.resources.vault_delete_title
import fr.techtical.nextsh.desktop.generated.resources.vault_empty_keys_hint_alt
import fr.techtical.nextsh.desktop.generated.resources.vault_empty_keys_title_alt
import fr.techtical.nextsh.desktop.generated.resources.vault_export_dialog_action
import fr.techtical.nextsh.desktop.generated.resources.vault_export_dialog_body
import fr.techtical.nextsh.desktop.generated.resources.vault_export_dialog_field_confirm
import fr.techtical.nextsh.desktop.generated.resources.vault_export_dialog_field_passphrase
import fr.techtical.nextsh.desktop.generated.resources.vault_export_dialog_subtitle
import fr.techtical.nextsh.desktop.generated.resources.vault_export_dialog_title
import fr.techtical.nextsh.desktop.generated.resources.vault_field_hide
import fr.techtical.nextsh.desktop.generated.resources.vault_field_show
import fr.techtical.nextsh.desktop.generated.resources.vault_generate_dialog_action
import fr.techtical.nextsh.desktop.generated.resources.vault_generate_dialog_label_field
import fr.techtical.nextsh.desktop.generated.resources.vault_generate_dialog_label_placeholder
import fr.techtical.nextsh.desktop.generated.resources.vault_generate_dialog_subtitle
import fr.techtical.nextsh.desktop.generated.resources.vault_generate_dialog_title_alt
import fr.techtical.nextsh.desktop.generated.resources.vault_generate_dialog_type_field
import fr.techtical.nextsh.desktop.generated.resources.vault_import_backup_dialog_action
import fr.techtical.nextsh.desktop.generated.resources.vault_import_backup_dialog_title
import fr.techtical.nextsh.desktop.generated.resources.vault_import_confirm_body
import fr.techtical.nextsh.desktop.generated.resources.vault_import_confirm_continue
import fr.techtical.nextsh.desktop.generated.resources.vault_import_confirm_subtitle
import fr.techtical.nextsh.desktop.generated.resources.vault_import_confirm_title
import fr.techtical.nextsh.desktop.generated.resources.vault_import_dialog_choose_title
import fr.techtical.nextsh.desktop.generated.resources.vault_import_key_action
import fr.techtical.nextsh.desktop.generated.resources.vault_import_key_dialog_title
import fr.techtical.nextsh.desktop.generated.resources.vault_import_key_label_field
import fr.techtical.nextsh.desktop.generated.resources.vault_import_key_label_placeholder
import fr.techtical.nextsh.desktop.generated.resources.vault_import_key_passphrase_field
import fr.techtical.nextsh.desktop.generated.resources.vault_import_key_passphrase_hint
import fr.techtical.nextsh.desktop.generated.resources.vault_key_action_copy_pub
import fr.techtical.nextsh.desktop.generated.resources.vault_key_action_delete_short
import fr.techtical.nextsh.desktop.generated.resources.vault_key_action_download_pub
import fr.techtical.nextsh.desktop.generated.resources.vault_key_unused
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_ecdsa256_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_ecdsa384_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_ecdsa521_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_ed25519_friendly
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_ed25519_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_rsa_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_sk_ecdsa256_friendly
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_sk_ecdsa256_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_sk_ed25519_friendly
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_sk_ed25519_label
import fr.techtical.nextsh.desktop.generated.resources.vault_passphrase_error_min
import fr.techtical.nextsh.desktop.generated.resources.vault_passphrase_error_mismatch
import fr.techtical.nextsh.desktop.generated.resources.vault_section_backup
import fr.techtical.nextsh.desktop.generated.resources.vault_section_keys_alt
import fr.techtical.nextsh.desktop.generated.resources.vault_subtitle
import fr.techtical.nextsh.desktop.generated.resources.vault_title
import org.jetbrains.compose.resources.stringResource

/**
 * Vault screen, refonte Phase 2.5.
 *
 * Layout : Column NearBlack → PageHeader "Vault" → scroll content
 * (boutons "Générer une clé" .btn-primary + "Importer une clé"
 * .btn-secondary, section "Clés SSH" avec key cards refondues, section
 * "Backup" Exporter/Importer .btn-secondary). Status message global
 * dans la zone PageHeader subtitle ou en banner sous le PageHeader.
 *
 * Suppression : Scaffold/TopAppBar/Material Card legacy. Cohérent avec
 * HostList/Tunnels/Transfers refondus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultScreen(onBack: () -> Unit) {
    val viewModel = remember { VaultScreenViewModel() }
    val keys by viewModel.keys.collectAsState()
    val status by viewModel.status.collectAsState()
    val hostsUsingKey by viewModel.hostsUsingKey.collectAsState()

    // Auto-dismiss success messages after 2.5 s, errors stay until user acts
    LaunchedEffect(status.message, status.isError) {
        if (status.message != null && !status.isError) {
            delay(2500)
            viewModel.clearStatus()
        }
    }

    var showGenerateDialog by remember { mutableStateOf(false) }
    var showEnrollFido2Dialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf<File?>(null) }
    var keyToDelete by remember { mutableStateOf<SshKey?>(null) }
    var showExportPassphraseDialog by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportFile by remember { mutableStateOf<File?>(null) }

    val importDialogTitle = stringResource(Res.string.vault_import_dialog_choose_title)

    val subtitle = stringResource(Res.string.vault_subtitle)

    Column(modifier = Modifier.fillMaxSize().background(NearBlack)) {
        PageHeader(title = stringResource(Res.string.vault_title), subtitle = subtitle)

        // ── Status banner (succès / erreur) ──────────────────────────────────
        val statusMsg = status.message
        if (statusMsg != null) {
            StatusBanner(text = statusMsg, isError = status.isError)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Xxl, vertical = Spacing.Xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.Xl),
        ) {
            // ── Actions principales : Générer + FIDO2 + Importer ─────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                BtnPrimaryFull(
                    onClick = { showGenerateDialog = true },
                    enabled = !status.isBusy,
                    icon = Lucide.KeyRound,
                    label = stringResource(Res.string.vault_action_generate),
                    modifier = Modifier.weight(1f),
                )
                BtnSecondaryFull(
                    onClick = { showEnrollFido2Dialog = true },
                    enabled = !status.isBusy,
                    icon = Lucide.Usb,
                    label = stringResource(Res.string.vault_action_enroll_fido2),
                    modifier = Modifier.weight(1f),
                )
                BtnSecondaryFull(
                    onClick = {
                        val dlg = FileDialog(null as Frame?, importDialogTitle, FileDialog.LOAD)
                        dlg.isVisible = true
                        val dir = dlg.directory
                        val name = dlg.file
                        if (dir != null && name != null) {
                            showImportDialog = File(dir, name)
                        }
                    },
                    enabled = !status.isBusy,
                    icon = Lucide.Download,
                    label = stringResource(Res.string.vault_action_import),
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Section "Clés SSH" ────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                SectionHeader(stringResource(Res.string.vault_section_keys_alt))
                if (keys.isEmpty()) {
                    EmptyKeysState()
                } else {
                    // FlowRow : KeyCard largeur stricte 400dp, alignée à gauche,
                    // wrap naturel. Cohérence avec TunnelListScreen (cf.
                    // docs/design/CARDS-RESPONSIVE.md).
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        keys.forEach { key ->
                            KeyCard(
                                key = key,
                                hostsUsing = hostsUsingKey[key.id] ?: 0,
                                onCopy = {
                                    Toolkit.getDefaultToolkit().systemClipboard
                                        .setContents(StringSelection(key.publicKey), null)
                                },
                                onDownload = { exportPublicKey(key) },
                                onDelete = { keyToDelete = key },
                                modifier = Modifier.width(400.dp),
                            )
                        }
                    }
                }
            }

            // ── Section "Backup" ──────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                SectionHeader(stringResource(Res.string.vault_section_backup))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    BtnSecondaryFull(
                        onClick = { showExportPassphraseDialog = true },
                        enabled = !status.isBusy,
                        icon = Lucide.Upload,
                        label = stringResource(Res.string.vault_action_export),
                        modifier = Modifier.weight(1f),
                    )
                    BtnSecondaryFull(
                        onClick = { showImportConfirmDialog = true },
                        enabled = !status.isBusy,
                        icon = Lucide.Download,
                        label = stringResource(Res.string.vault_action_import_short),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────
    if (showGenerateDialog) {
        GenerateKeyDialog(
            onDismiss = { showGenerateDialog = false },
            onConfirm = { label, keyType ->
                showGenerateDialog = false
                viewModel.generateKey(label, keyType)
            },
        )
    }

    if (showEnrollFido2Dialog) {
        Fido2EnrollDialog(
            onDismiss = { showEnrollFido2Dialog = false },
            onSuccess = { skKey ->
                showEnrollFido2Dialog = false
                viewModel.notifyFido2KeyEnrolled(skKey)
            },
        )
    }

    val importFile = showImportDialog
    if (importFile != null) {
        ImportKeyDialog(
            file = importFile,
            onDismiss = { showImportDialog = null },
            onConfirm = { label, passphrase ->
                showImportDialog = null
                viewModel.importKey(importFile, label, passphrase)
            },
        )
    }

    if (showExportPassphraseDialog) {
        ExportBackupDialog(
            onDismiss = { showExportPassphraseDialog = false },
            onConfirm = { passphrase ->
                showExportPassphraseDialog = false
                val dest = pickSaveNextshFile()
                if (dest != null) {
                    viewModel.exportBackup(dest, passphrase)
                } else {
                    java.util.Arrays.fill(passphrase, Char(0))
                }
            },
        )
    }

    if (showImportConfirmDialog) {
        TechticalDialogCard(
            title = stringResource(Res.string.vault_import_confirm_title),
            icon = Lucide.Download,
            subtitle = stringResource(Res.string.vault_import_confirm_subtitle),
            onDismiss = { showImportConfirmDialog = false },
            content = {
                Text(
                    text = stringResource(Res.string.vault_import_confirm_body),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            },
            footer = {
                BtnGhostSm(onClick = { showImportConfirmDialog = false }, label = stringResource(Res.string.action_cancel))
                Spacer(Modifier.width(Spacing.Sm))
                BtnPrimarySm(
                    onClick = {
                        showImportConfirmDialog = false
                        val src = pickOpenNextshFile()
                        if (src != null) {
                            pendingImportFile = src
                        }
                    },
                    icon = Lucide.Download,
                    label = stringResource(Res.string.vault_import_confirm_continue),
                )
            },
        )
    }

    val importFileToOpen = pendingImportFile
    if (importFileToOpen != null) {
        ImportBackupPassphraseDialog(
            fileName = importFileToOpen.name,
            onDismiss = { pendingImportFile = null },
            onConfirm = { passphrase ->
                pendingImportFile = null
                viewModel.importBackup(importFileToOpen, passphrase)
            },
        )
    }

    val kToDelete = keyToDelete
    if (kToDelete != null) {
        ConfirmDeleteDialog(
            title = stringResource(Res.string.vault_delete_title),
            message = stringResource(Res.string.vault_delete_body, kToDelete.label),
            onConfirm = {
                viewModel.deleteKey(kToDelete)
                keyToDelete = null
            },
            onDismiss = { keyToDelete = null },
        )
    }
}

// ── Status banner ────────────────────────────────────────────────────────────

@Composable
private fun StatusBanner(text: String, isError: Boolean) {
    val color = if (isError) ErrorRed else SuccessGreen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f))
            .border(BorderStroke(0.dp, Color.Transparent))
            .padding(horizontal = Spacing.Xxl, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, RoundedCornerShape(9999.dp)),
        )
        Spacer(Modifier.width(Spacing.Sm))
        Text(text = text, color = color, fontSize = 12.sp)
    }
}

// ── Section header ───────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = GoldLight,
        fontFamily = JetBrainsMonoFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

// ── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyKeysState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(vertical = 32.dp, horizontal = Spacing.Xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Lucide.KeyRound,
                contentDescription = null,
                tint = GoldMuted,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(Spacing.Sm))
            Text(stringResource(Res.string.vault_empty_keys_title_alt), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(Res.string.vault_empty_keys_hint_alt),
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

// ── Key card (grid 1/2/3 cols) ───────────────────────────────────────────────

/**
 * Hauteur fixe de la KeyCard pour le calcul du grid total. 76dp = même
 * densité que le KeyRow legacy (avant Phase 2.5), mais cette fois
 * disposé en grille 1/2/3 colonnes pour éviter d'étaler une ligne sur
 * toute la largeur disponible quand il y a peu de clés.
 */
private const val KEY_CARD_HEIGHT_DP = 76

/**
 * Card de clé horizontale compacte. Layout :
 *  - icon-wrap 32×32 Gold à gauche
 *  - Column central :
 *      ligne 1 : label SemiBold 13sp + BIO badge éventuel
 *      ligne 2 : `ED25519 • SHA256:abc…def` mono 11sp
 *  - "n hôtes" 11sp + 3 IconActionButtons (Copy / Download / Trash) à droite
 */
@Composable
private fun KeyCard(
    key: SshKey,
    hostsUsing: Int,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fingerprint = remember(key.publicKey) { computeFingerprint(key.publicKey) }
    Row(
        modifier = modifier
            .height(KEY_CARD_HEIGHT_DP.dp)
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.KeyRound, contentDescription = null, tint = Gold, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(Spacing.Sm))

        Column(modifier = Modifier.weight(1f)) {
            // Ligne 1 : label + BIO badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = key.label,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (key.isBiometric) {
                    Spacer(Modifier.width(Spacing.Xs))
                    BioBadge()
                }
            }
            Spacer(Modifier.height(2.dp))
            // Ligne 2 : type • fingerprint
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = key.keyType.shortName(),
                    color = GoldLight,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(Spacing.Xs))
                Text("•", color = TextDisabled, fontSize = 11.sp)
                Spacer(Modifier.width(Spacing.Xs))
                Text(
                    text = fingerprint,
                    color = TextSecondary,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(Spacing.Sm))

        // Count hôtes
        Text(
            text = if (hostsUsing == 0) stringResource(Res.string.vault_key_unused)
            else "$hostsUsing hôte${if (hostsUsing > 1) "s" else ""}",
            color = if (hostsUsing == 0) TextDisabled else TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(Spacing.Xs))

        // Actions
        IconActionButton(icon = Lucide.Copy, tooltip = stringResource(Res.string.vault_key_action_copy_pub), onClick = onCopy)
        IconActionButton(icon = Lucide.Download, tooltip = stringResource(Res.string.vault_key_action_download_pub), onClick = onDownload)
        IconActionButton(icon = Lucide.Trash2, tooltip = stringResource(Res.string.vault_key_action_delete_short), onClick = onDelete, destructive = true)
    }
}

@Composable
private fun BioBadge() {
    Box(
        modifier = Modifier
            .background(SuccessGreen.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = "BIO",
            color = SuccessGreen,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun IconActionButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val tint = when {
        destructive && hovered -> ErrorRed
        destructive -> ErrorRed.copy(alpha = 0.7f)
        hovered -> TextPrimary
        else -> TextSecondary
    }
    val bg = if (hovered) {
        if (destructive) ErrorRed.copy(alpha = 0.10f)
        else Color.White.copy(alpha = 0.06f)
    } else Color.Transparent

    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = tint,
        ),
        border = BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .size(28.dp)
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = tooltip, tint = tint, modifier = Modifier.size(13.dp))
    }
}

// ── Buttons (.btn-primary / .btn-secondary full size) ───────────────────────

@Composable
private fun BtnPrimaryFull(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
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
        modifier = modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BtnSecondaryFull(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val borderColor = when {
        !enabled -> Border2.copy(alpha = 0.5f)
        hovered -> Border2.copy(alpha = 1f)
        else -> Border2
    }
    val bg = if (enabled && hovered) Color.White.copy(alpha = 0.04f) else Color.Transparent
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = TextPrimary,
            disabledContentColor = TextDisabled,
        ),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Calcule un fingerprint SHA-256 OpenSSH-style du blob de clé publique.
 * Format : `SHA256:base64(sha256(blob))`, 12 chars puis "…" pour rester
 * compact dans la card. Le blob est le second token de la chaîne OpenSSH
 * (`ssh-type base64-blob comment`).
 */
private fun computeFingerprint(publicKeyOpenSsh: String): String {
    val parts = publicKeyOpenSsh.trim().split(' ')
    if (parts.size < 2) return "SHA256:-"
    val blob = try {
        java.util.Base64.getDecoder().decode(parts[1])
    } catch (e: IllegalArgumentException) {
        return "SHA256:-"
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    val b64 = java.util.Base64.getEncoder().encodeToString(digest).trimEnd('=')
    val short = if (b64.length > 12) "${b64.substring(0, 6)}…${b64.substring(b64.length - 4)}" else b64
    return "SHA256:$short"
}

/**
 * Export de la clé publique vers un `.pub` choisi par l'utilisateur.
 * Pas de chiffrement : c'est la partie publique uniquement.
 */
private fun exportPublicKey(key: SshKey) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Enregistrer la clé publique"
        fileFilter = FileNameExtensionFilter("OpenSSH public key (*.pub)", "pub")
        selectedFile = File("${key.label}.pub")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return
    val picked = chooser.selectedFile ?: return
    val target = if (picked.extension.equals("pub", ignoreCase = true)) picked
    else File(picked.parentFile, picked.name + ".pub")
    target.writeText(key.publicKey)
}

@Composable
private fun SshKeyType.shortName(): String = stringResource(when (this) {
    SshKeyType.ED25519 -> Res.string.vault_keytype_ed25519_label
    SshKeyType.RSA_4096 -> Res.string.vault_keytype_rsa_label
    SshKeyType.ECDSA_256 -> Res.string.vault_keytype_ecdsa256_label
    SshKeyType.ECDSA_384 -> Res.string.vault_keytype_ecdsa384_label
    SshKeyType.ECDSA_521 -> Res.string.vault_keytype_ecdsa521_label
    SshKeyType.SK_ED25519 -> Res.string.vault_keytype_sk_ed25519_label
    SshKeyType.SK_ECDSA_256 -> Res.string.vault_keytype_sk_ecdsa256_label
})

@Composable
private fun SshKeyType.displayName(): String = stringResource(when (this) {
    SshKeyType.ED25519 -> Res.string.vault_keytype_ed25519_friendly
    SshKeyType.RSA_4096 -> Res.string.vault_keytype_rsa_label
    SshKeyType.ECDSA_256 -> Res.string.vault_keytype_ecdsa256_label
    SshKeyType.ECDSA_384 -> Res.string.vault_keytype_ecdsa384_label
    SshKeyType.ECDSA_521 -> Res.string.vault_keytype_ecdsa521_label
    SshKeyType.SK_ED25519 -> Res.string.vault_keytype_sk_ed25519_friendly
    SshKeyType.SK_ECDSA_256 -> Res.string.vault_keytype_sk_ecdsa256_friendly
})

// ── Dialogs Vault refondus (Phase 2.9) ──────────────────────────────────────
// Tous basés sur TechticalDialogCard (overlay scrim + carte centrée Surface
// /Border1/Radii.Xl, ESC + click-outside dismissent). Form primitives
// (FieldGroup, FieldLabel, TechticalTextField, PasswordField, RowSelectFull,
// boutons) inlinés ci-dessous : duplication assumée avec HostDetail/Tunnel
// le temps qu'on extrait éventuellement un module shared.

@Composable
private fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, keyType: SshKeyType) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var keyType by remember { mutableStateOf(SshKeyType.ED25519) }
    val canSubmit = label.isNotBlank()

    TechticalDialogCard(
        title = stringResource(Res.string.vault_generate_dialog_title_alt),
        icon = Lucide.KeyRound,
        subtitle = stringResource(Res.string.vault_generate_dialog_subtitle),
        onDismiss = onDismiss,
        content = {
            FieldGroup {
                FieldLabel(stringResource(Res.string.vault_generate_dialog_label_field))
                TechticalTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = stringResource(Res.string.vault_generate_dialog_label_placeholder),
                )
            }
            FieldGroup {
                FieldLabel(stringResource(Res.string.vault_generate_dialog_type_field))
                RowSelectFull(
                    value = keyType.displayName(),
                    options = generateableTypes.map { type ->
                        type.displayName() to { keyType = type }
                    },
                )
            }
        },
        footer = {
            BtnGhostSm(onClick = onDismiss, label = stringResource(Res.string.action_cancel))
            Spacer(Modifier.width(Spacing.Sm))
            BtnPrimarySm(
                onClick = { onConfirm(label.trim(), keyType) },
                icon = Lucide.KeyRound,
                label = stringResource(Res.string.vault_generate_dialog_action),
                enabled = canSubmit,
            )
        },
    )
}

@Composable
private fun ImportKeyDialog(
    file: File,
    onDismiss: () -> Unit,
    onConfirm: (label: String, passphrase: CharArray?) -> Unit,
) {
    var label by remember { mutableStateOf(file.nameWithoutExtension) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseVisible by remember { mutableStateOf(false) }
    val canSubmit = label.isNotBlank()

    TechticalDialogCard(
        title = stringResource(Res.string.vault_import_key_dialog_title),
        icon = Lucide.Download,
        subtitle = file.name,
        onDismiss = onDismiss,
        content = {
            FieldGroup {
                FieldLabel(stringResource(Res.string.vault_import_key_label_field))
                TechticalTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = stringResource(Res.string.vault_import_key_label_placeholder),
                )
            }
            FieldGroup {
                FieldLabel(stringResource(Res.string.vault_import_key_passphrase_field))
                PasswordField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    visible = passphraseVisible,
                    onToggleVisible = { passphraseVisible = !passphraseVisible },
                )
                HelperText(stringResource(Res.string.vault_import_key_passphrase_hint))
            }
        },
        footer = {
            BtnGhostSm(onClick = onDismiss, label = stringResource(Res.string.action_cancel))
            Spacer(Modifier.width(Spacing.Sm))
            BtnPrimarySm(
                onClick = {
                    val pass = if (passphrase.isNotEmpty()) passphrase.toCharArray() else null
                    passphrase = ""
                    onConfirm(label.trim(), pass)
                },
                icon = Lucide.Download,
                label = stringResource(Res.string.vault_import_key_action),
                enabled = canSubmit,
            )
        },
    )
}

@Composable
private fun ExportBackupDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    val errMin = stringResource(Res.string.vault_passphrase_error_min)
    val errMismatch = stringResource(Res.string.vault_passphrase_error_mismatch)
    val errorText = when {
        passphrase.isEmpty() && confirm.isEmpty() -> null
        passphrase.length < 8 -> errMin
        passphrase != confirm -> errMismatch
        else -> null
    }
    val canSubmit = passphrase.length >= 8 && passphrase == confirm

    TechticalDialogCard(
        title = stringResource(Res.string.vault_export_dialog_title),
        icon = Lucide.Upload,
        subtitle = stringResource(Res.string.vault_export_dialog_subtitle),
        onDismiss = onDismiss,
        content = {
            Text(
                text = stringResource(Res.string.vault_export_dialog_body),
                color = TextSecondary,
                fontSize = 12.sp,
            )
            FieldGroup {
                FieldLabel(stringResource(Res.string.vault_export_dialog_field_passphrase))
                PasswordField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                )
            }
            FieldGroup {
                FieldLabel(stringResource(Res.string.vault_export_dialog_field_confirm))
                TechticalTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    placeholder = "••••••••",
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                )
                if (errorText != null) {
                    HelperText(errorText, error = true)
                }
            }
        },
        footer = {
            BtnGhostSm(onClick = onDismiss, label = stringResource(Res.string.action_cancel))
            Spacer(Modifier.width(Spacing.Sm))
            BtnPrimarySm(
                onClick = {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    confirm = ""
                    onConfirm(chars)
                },
                icon = Lucide.Upload,
                label = stringResource(Res.string.vault_export_dialog_action),
                enabled = canSubmit,
            )
        },
    )
}

@Composable
private fun ImportBackupPassphraseDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val canSubmit = passphrase.isNotEmpty()

    TechticalDialogCard(
        title = stringResource(Res.string.vault_import_backup_dialog_title),
        icon = Lucide.Download,
        subtitle = fileName,
        onDismiss = onDismiss,
        content = {
            FieldGroup {
                FieldLabel(stringResource(Res.string.vault_export_dialog_field_passphrase))
                PasswordField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                )
            }
        },
        footer = {
            BtnGhostSm(onClick = onDismiss, label = stringResource(Res.string.action_cancel))
            Spacer(Modifier.width(Spacing.Sm))
            BtnPrimarySm(
                onClick = {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    onConfirm(chars)
                },
                icon = Lucide.Download,
                label = stringResource(Res.string.vault_import_backup_dialog_action),
                enabled = canSubmit,
            )
        },
    )
}

// ── Form primitives (inlinés, extraction shared possible plus tard) ────────

@Composable
private fun FieldGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs), content = content)
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = GoldMuted,
        fontFamily = JetBrainsMonoFamily,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    )
}

@Composable
private fun HelperText(text: String, error: Boolean = false) {
    Text(
        text = text,
        color = if (error) ErrorRed else TextDisabled,
        fontSize = 10.sp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TechticalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(text = placeholder, color = TextDisabled, fontSize = 12.sp)
        },
        singleLine = true,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
        colors = formTextFieldColors(),
        shape = RoundedCornerShape(Radii.Md),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun formTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold.copy(alpha = 0.55f),
    unfocusedBorderColor = Border1,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Gold,
    focusedContainerColor = Surface,
    unfocusedContainerColor = Surface,
)

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    TechticalTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "••••••••",
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisible, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = if (visible) Lucide.EyeOff else Lucide.Eye,
                    contentDescription = stringResource(if (visible) Res.string.vault_field_hide else Res.string.vault_field_show),
                    tint = TextSecondary,
                    modifier = Modifier.size(13.dp),
                )
            }
        },
    )
}

@Composable
private fun RowSelectFull(
    value: String,
    options: List<Pair<String, () -> Unit>>,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val borderColor = if (hovered) Border2 else Border1

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { triggerWidthPx = it.width },
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            interactionSource = interactionSource,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Surface,
                contentColor = TextPrimary,
            ),
            border = BorderStroke(1.dp, borderColor),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(value, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(Lucide.ChevronDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .background(Surface)
                .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
                .width(with(density) { triggerWidthPx.toDp() }),
        ) {
            options.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label, color = TextPrimary, fontSize = 12.sp) },
                    onClick = {
                        action()
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BtnGhostSm(onClick: () -> Unit, label: String) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent
    val fg = if (hovered) TextPrimary else TextSecondary
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = bg, contentColor = fg),
        border = BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BtnPrimarySm(
    onClick: () -> Unit,
    icon: ImageVector,
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
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun pickSaveNextshFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Enregistrer le backup .nextsh"
        fileFilter = FileNameExtensionFilter("NextSH backup (*.nextsh)", "nextsh")
        selectedFile = File("nextsh-backup.nextsh")
    }
    val result = chooser.showSaveDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val picked = chooser.selectedFile ?: return null
    return if (picked.extension.equals("nextsh", ignoreCase = true)) picked
    else File(picked.parentFile, picked.name + ".nextsh")
}

private fun pickOpenNextshFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Ouvrir un backup .nextsh"
        fileFilter = FileNameExtensionFilter("NextSH backup (*.nextsh)", "nextsh")
    }
    val result = chooser.showOpenDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile
}

private val generateableTypes = listOf(
    SshKeyType.ED25519,
    SshKeyType.RSA_4096,
    SshKeyType.ECDSA_256,
    SshKeyType.ECDSA_384,
    SshKeyType.ECDSA_521,
)
