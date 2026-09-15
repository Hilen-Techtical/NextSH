// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.FileBadge
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Save
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.*
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyImportScreen(
    onBack: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var label by remember { mutableStateOf("") }
    var privateKeyPem by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val content = inputStream?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
                privateKeyPem = content
                if (label.isBlank()) {
                    val cursor = context.contentResolver.query(it, null, null, null, null)
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                val fileName = c.getString(nameIndex)
                                label = fileName?.substringBeforeLast('.') ?: ""
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Erreur lecture fichier")
            }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    // Sur succès : pas de snackbar (latence inutile, `showSnackbar` suspend
    // ~4s avant d'appeler `onBack()`). Retour immédiat au Vault, l'apparition
    // de la nouvelle clé dans la liste fait office de feedback. Le message
    // est juste consommé pour ne pas re-tirer.
    LaunchedEffect(uiState.successMessage) {
        if (uiState.successMessage != null) {
            viewModel.clearMessage()
            onBack()
        }
    }

    val canImport = label.isNotBlank() && privateKeyPem.isNotBlank() && !uiState.isGenerating

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text       = stringResource(R.string.title_import_key),
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 20.sp,
                        color      = TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Lucide.ArrowLeft,
                            contentDescription = stringResource(R.string.action_back),
                            tint     = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomSaveBar(
                label   = if (uiState.isGenerating)
                    stringResource(R.string.status_importing)
                else
                    stringResource(R.string.action_import),
                enabled = canImport,
                onClick = { viewModel.importKey(label, privateKeyPem) },
            )
        },
        containerColor = NearBlack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            FormSection(
                title = stringResource(R.string.section_information),
                accent = Gold,
                icon  = Lucide.FileBadge,
            ) {
                FormField(
                    label         = stringResource(R.string.label_key_name),
                    value         = label,
                    onValueChange = { label = it },
                    placeholder   = stringResource(R.string.placeholder_server_key_name),
                    mono          = false,
                    singleLine    = true,
                )
            }

            FormSection(
                title  = stringResource(R.string.section_private_key),
                accent = GoldLight,
                icon   = Lucide.KeyRound,
            ) {
                Text(
                    text       = stringResource(R.string.placeholder_key_formats),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 12.sp,
                    color      = TextSecondary,
                )
                FormField(
                    label         = stringResource(R.string.label_private_key_content),
                    value         = privateKeyPem,
                    onValueChange = { privateKeyPem = it },
                    placeholder   = "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----",
                    mono          = true,
                    singleLine    = false,
                    minLines      = 6,
                    minHeight     = 160.dp,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.Md))
                        .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
                        .clickable { filePickerLauncher.launch(arrayOf("*/*")) }
                        .padding(vertical = Spacing.Sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        Icon(
                            Lucide.FolderOpen,
                            contentDescription = null,
                            tint     = Gold,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text       = stringResource(R.string.action_select_file),
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 13.sp,
                            color      = Gold,
                        )
                    }
                }
            }
        }
    }

    uiState.pendingEncryptedImport?.let { pending ->
        var passphraseInput by remember(pending.pem) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { /* modal, only Cancel explicitly dismisses */ },
            containerColor   = Surface,
            shape            = RoundedCornerShape(Radii.Lg),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(Radii.Sm))
                            .background(InfoBlue.copy(alpha = 0.12f), RoundedCornerShape(Radii.Sm)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Lucide.Lock,
                            contentDescription = null,
                            tint     = InfoBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text       = "Passphrase requise",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 16.sp,
                        color      = TextPrimary,
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    Text(
                        text       = "La clé importée est chiffrée. Entrez la passphrase pour la déverrouiller.",
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 13.sp,
                        color      = TextSecondary,
                    )
                    OutlinedTextField(
                        value         = passphraseInput,
                        onValueChange = { passphraseInput = it },
                        label = {
                            Text(
                                "Passphrase",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize   = 13.sp,
                            )
                        },
                        singleLine    = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = JetBrainsMonoFamily,
                            fontSize   = 13.sp,
                            color      = TextPrimary,
                        ),
                        shape         = RoundedCornerShape(Radii.Md),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Gold,
                            unfocusedBorderColor = Border2,
                            cursorColor          = Gold,
                            focusedContainerColor   = SurfaceVariant,
                            unfocusedContainerColor = SurfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val chars = passphraseInput.toCharArray()
                        passphraseInput = ""
                        viewModel.importKey(pending.label, pending.pem, chars)
                    },
                    enabled = passphraseInput.isNotEmpty(),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = Burgundy,
                        contentColor   = White,
                        disabledContainerColor = BurgundyDark,
                        disabledContentColor   = TextSecondary,
                    ),
                    shape = RoundedCornerShape(Radii.Md),
                ) {
                    Text(
                        text       = "Déverrouiller",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    passphraseInput = ""
                    viewModel.cancelEncryptedImport()
                }) {
                    Text(
                        text       = stringResource(R.string.action_cancel),
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 13.sp,
                        color      = TextSecondary,
                    )
                }
            },
        )
    }
}

// ── Section card (parité Phase 3.2) ──────────────────────────────────────────

@Composable
private fun FormSection(
    title: String,
    accent: androidx.compose.ui.graphics.Color = Gold,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Lg))
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(horizontal = Spacing.Md, vertical = Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text       = title.uppercase(),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 11.sp,
                letterSpacing = 0.8.sp,
                color      = accent,
            )
        }
        content()
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        Text(
            text       = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 11.sp,
            color      = TextDisabled,
        )
        OutlinedTextField(
            value         = value,
            onValueChange = onValueChange,
            singleLine    = singleLine,
            minLines      = minLines,
            placeholder = if (placeholder.isNotEmpty()) {
                {
                    Text(
                        text       = placeholder,
                        fontFamily = if (mono) JetBrainsMonoFamily else SpaceGroteskFamily,
                        fontSize   = 13.sp,
                        color      = TextDisabled,
                    )
                }
            } else null,
            shape         = RoundedCornerShape(Radii.Md),
            textStyle     = androidx.compose.ui.text.TextStyle(
                fontFamily = if (mono) JetBrainsMonoFamily else SpaceGroteskFamily,
                fontSize   = 13.sp,
                color      = TextPrimary,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Gold,
                unfocusedBorderColor = Border2,
                cursorColor          = Gold,
                focusedContainerColor   = SurfaceVariant,
                unfocusedContainerColor = SurfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .let { if (minHeight > 0.dp) it.heightIn(min = minHeight) else it },
        )
    }
}

@Composable
private fun BottomSaveBar(label: String, enabled: Boolean, onClick: () -> Unit) {
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
                .background(
                    if (enabled) Burgundy else BurgundyDark,
                    RoundedCornerShape(Radii.Md),
                )
                .let { if (enabled) it.clickable(onClick = onClick) else it }
                .padding(vertical = Spacing.Md),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Icon(
                    Lucide.Save,
                    contentDescription = null,
                    tint     = if (enabled) White else TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text       = label,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp,
                    color      = if (enabled) White else TextSecondary,
                )
            }
        }
    }
}
