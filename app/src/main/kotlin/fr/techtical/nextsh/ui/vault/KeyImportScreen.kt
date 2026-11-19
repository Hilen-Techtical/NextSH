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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import fr.techtical.nextsh.domain.usecase.MAX_PRIVATE_KEY_BYTES
import fr.techtical.nextsh.domain.usecase.MAX_PRIVATE_KEY_FILE_BYTES
import fr.techtical.nextsh.domain.usecase.isPrivateKeyTooLarge
import fr.techtical.nextsh.shared.util.errorClass
import fr.techtical.nextsh.ui.components.NextShMessage
import fr.techtical.nextsh.ui.components.SavedTextLimits
import fr.techtical.nextsh.ui.components.boundedTextSaver
import fr.techtical.nextsh.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyImportScreen(
    onBack: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Le libellé est saveable (borné). Le PEM collé reste en `remember` : un secret ne va jamais dans un
    // `Bundle`, il est à recoller après une recréation de l'activité.
    var label by rememberSaveable(stateSaver = boundedTextSaver(SavedTextLimits.LABEL_BYTES)) { mutableStateOf("") }
    var privateKeyPem by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    // A key file that could not be read (too large or unreadable) is told like the errors of the view model.
    var fileError by remember { mutableStateOf<String?>(null) }
    val pemTooLarge = remember(privateKeyPem) { isPrivateKeyTooLarge(privateKeyPem.trim()) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Off the main thread, and bounded: the size a provider announces is not trusted.
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        readKeyFile(context.contentResolver.openInputStream(it), MAX_PRIVATE_KEY_FILE_BYTES)
                    } catch (e: Exception) {
                        // Class chain only: the message of an I/O failure can quote the path of the file.
                        Timber.e("Key file open failed (${errorClass(e)})")
                        KeyFileRead.Failed
                    }
                }
                when (outcome) {
                    is KeyFileRead.Content -> {
                        privateKeyPem = outcome.text
                        if (label.isBlank()) {
                            val name = withContext(Dispatchers.IO) { documentDisplayName(context, it) }
                            if (name != null) label = name.substringBeforeLast('.')
                        }
                    }
                    KeyFileRead.TooLarge -> fileError = context.getString(R.string.vault_key_import_too_large)
                    KeyFileRead.Failed -> fileError = context.getString(R.string.vault_key_file_read_failed)
                }
            }
        }
    }

    // Sur succès : pas de message (latence inutile avant d'appeler `onBack()`). Retour immédiat
    // au Vault, l'apparition de la nouvelle clé dans la liste fait office de feedback. Le message
    // est juste consommé pour ne pas re-tirer.
    LaunchedEffect(uiState.successMessage, uiState.successRes) {
        if (uiState.hasSuccess) {
            viewModel.clearMessage()
            onBack()
        }
    }

    val canImport = label.isNotBlank() && privateKeyPem.isNotBlank() && !pemTooLarge && !uiState.isGenerating

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
        // Le slot du Scaffold dessine le message juste au-dessus de la barre Importer, sans décaler
        // le formulaire. Une erreur reste 7 s.
        snackbarHost = {
            NextShMessage(
                message   = uiState.errorText(context) ?: fileError,
                isError   = true,
                onDismiss = {
                    fileError = null
                    viewModel.clearMessage()
                },
                modifier  = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
            )
        },
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
                // La marge basse de padding est la barre Importer, qui passe
                // sous le clavier ouvert. Non consommee, imePadding l ajoutait a
                // la hauteur du clavier : une bande noire de la hauteur de la
                // barre restait au-dessus du clavier et masquait le formulaire.
                .consumeWindowInsets(padding)
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
                    // A huge paste is cut at twice the limit: it stays flagged as too large below,
                    // and the field never holds megabytes.
                    onValueChange = { privateKeyPem = it.take(MAX_PRIVATE_KEY_BYTES * 2) },
                    errorText     = if (pemTooLarge) stringResource(R.string.vault_key_import_too_large) else null,
                    placeholder   = "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----",
                    mono          = true,
                    singleLine    = false,
                    minLines      = 6,
                    minHeight     = 160.dp,
                    // Pas de correction automatique sur une clé privée collée. Les suggestions du
                    // clavier restent possibles : Compose 1.6 n'expose pas NO_SUGGESTIONS. Le type
                    // de clavier reste celui d'un champ multiligne, le champ n'est pas masqué.
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
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
                        text       = stringResource(R.string.key_import_passphrase_title),
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
                        text       = stringResource(R.string.key_import_passphrase_message),
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 13.sp,
                        color      = TextSecondary,
                    )
                    OutlinedTextField(
                        value         = passphraseInput,
                        onValueChange = { passphraseInput = it },
                        label = {
                            Text(
                                stringResource(R.string.label_passphrase),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize   = 13.sp,
                            )
                        },
                        singleLine    = true,
                        visualTransformation = PasswordVisualTransformation(),
                        // Clavier de mot de passe sans correction : il ne doit pas apprendre la passphrase.
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrect = false,
                        ),
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
                        text       = stringResource(R.string.key_import_passphrase_unlock),
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    errorText: String? = null,
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
            keyboardOptions = keyboardOptions,
            isError       = errorText != null,
            supportingText = if (errorText != null) {
                { Text(text = errorText, fontFamily = SpaceGroteskFamily, fontSize = 12.sp) }
            } else null,
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

/** The display name of a document, or null when the provider gives none. Blocking: off the main thread. */
private fun documentDisplayName(context: android.content.Context, uri: android.net.Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) c.getString(nameIndex) else null
        } else {
            null
        }
    }
} catch (e: Exception) {
    Timber.w("Key file name read failed (${errorClass(e)})")
    null
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
