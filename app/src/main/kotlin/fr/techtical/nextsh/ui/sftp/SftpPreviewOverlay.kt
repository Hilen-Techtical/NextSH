// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import android.app.Activity
import android.graphics.BitmapFactory
import fr.techtical.nextsh.core.ScreenCapturePolicy
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.*

/**
 * Overlay aperçu fichier : Phase 3.3 DA Techtical.
 *
 * Sécurité (inchangé) :
 *  - FLAG_SECURE positionné sur la Window pendant l'affichage.
 *  - ByteArray de contenu effacé via DisposableEffect à la fermeture.
 *
 * Chrome :
 *  - TopAppBar Surface : ← back + nom fichier (mono 13sp), action ✕.
 *  - Texte : LazyColumn numéros Gold mono 11sp.
 *  - Image : Skia decode + pinch-to-zoom 0.5×–8×.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpPreviewOverlay(
    previewState: PreviewState,
    onDismiss: () -> Unit,
    enableBackHandler: Boolean = true,
) {
    // Le retour ferme l apercu. Sans ce gestionnaire, le retour etait capte par
    // l ecran SFTP en dessous, qui remontait d un repertoire alors que l apercu
    // restait affiche. Compose apres le gestionnaire de l ecran, donc
    // prioritaire sur lui, en plein ecran comme en panneau de split.
    //
    // En split, le drapeau suit le panneau focalise : un apercu ouvert dans le
    // panneau qui n a pas le focus ne doit pas confisquer le retour, sans quoi
    // il empecherait de fermer le split.
    BackHandler(enabled = enableBackHandler) { onDismiss() }

    val activity = LocalContext.current as? Activity
    // Ne PAS retirer le drapeau à la fermeture : MainActivity le pose pour
    // toute l'application. Le nettoyer ici desactivait la protection contre les
    // captures d'ecran sur l'ensemble de l'app des la premiere fermeture d'un
    // apercu, jusqu'au prochain demarrage. Meme pattern que FirstLaunchScreen.
    DisposableEffect(activity) {
        activity?.window?.let(ScreenCapturePolicy::protect)
        onDispose { /* conserve FLAG_SECURE, pose globalement par MainActivity */ }
    }

    DisposableEffect(previewState.content) {
        onDispose {
            previewState.content?.let { java.util.Arrays.fill(it, 0) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack)
            .zIndex(10f),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text       = previewState.file.name,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize   = 13.sp,
                            color      = TextPrimary,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Lucide.ArrowLeft,
                                contentDescription = stringResource(R.string.action_back),
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Lucide.X,
                                contentDescription = stringResource(R.string.action_close),
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
                )
            },
            containerColor = NearBlack,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    previewState.isLoading -> {
                        CircularProgressIndicator(color = Gold, strokeWidth = 2.dp)
                    }
                    previewState.error != null -> {
                        PreviewErrorContent(error = previewState.error, onDismiss = onDismiss)
                    }
                    previewState.isImage && previewState.content != null -> {
                        PreviewImageContent(content = previewState.content)
                    }
                    previewState.isText && previewState.content != null -> {
                        PreviewTextContent(content = previewState.content)
                    }
                    else -> {
                        PreviewUnknownContent(file = previewState.file)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTextContent(content: ByteArray) {
    val text = remember(content) {
        try { String(content, Charsets.UTF_8) }
        catch (e: Exception) {
            try { String(content, Charsets.ISO_8859_1) }
            catch (e2: Exception) { String(content) }
        }
    }
    val lines = remember(text) { text.split('\n') }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Xs, vertical = Spacing.Xs),
    ) {
        itemsIndexed(lines) { index, line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment   = Alignment.Top,
            ) {
                Text(
                    text       = (index + 1).toString(),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize   = 11.sp,
                    color      = Gold,
                    textAlign  = TextAlign.End,
                    modifier   = Modifier
                        .width(44.dp)
                        .padding(end = Spacing.Sm),
                )
                Text(
                    text       = line,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize   = 12.sp,
                    color      = TextPrimary,
                    modifier   = Modifier.weight(1f),
                )
            }
        }
        item {
            Text(
                text       = stringResource(R.string.sftp_preview_line_count, lines.size),
                fontFamily = JetBrainsMonoFamily,
                fontSize   = 10.sp,
                color      = TextDisabled,
                textAlign  = TextAlign.End,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.Sm, bottom = Spacing.Lg),
            )
        }
    }
}

@Composable
private fun PreviewImageContent(content: ByteArray) {
    val bitmap = remember(content) {
        try { BitmapFactory.decodeByteArray(content, 0, content.size) }
        catch (e: Exception) { null }
    }

    if (bitmap == null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            modifier = Modifier.padding(Spacing.Xxl),
        ) {
            Icon(
                Lucide.TriangleAlert,
                contentDescription = null,
                tint     = ErrorRed,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text       = stringResource(R.string.sftp_preview_decode_error),
                fontFamily = SpaceGroteskFamily,
                fontSize   = 13.sp,
                color      = ErrorRed,
                textAlign  = TextAlign.Center,
            )
        }
        return
    }

    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale  = (scale * zoomChange).coerceIn(0.5f, 8f)
        offset += panChange
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(state = transformState),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap      = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier    = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX       = scale,
                    scaleY       = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

@Composable
private fun PreviewUnknownContent(file: fr.techtical.nextsh.domain.model.SftpFile) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Lg))
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(Spacing.Xl),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(Radii.Md))
                .background(TextSecondary.copy(alpha = 0.10f), RoundedCornerShape(Radii.Md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.File,
                contentDescription = null,
                tint     = TextSecondary,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text       = file.name,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp,
            color      = TextPrimary,
            textAlign  = TextAlign.Center,
        )
        if (file.size > 0L) {
            Text(
                text       = formatFileSize(file.size),
                fontFamily = JetBrainsMonoFamily,
                fontSize   = 11.sp,
                color      = TextSecondary,
            )
        }
        Text(
            text       = file.permissionsString,
            fontFamily = JetBrainsMonoFamily,
            fontSize   = 11.sp,
            color      = GoldMuted,
        )
    }
}

@Composable
private fun PreviewErrorContent(error: String, onDismiss: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        modifier = Modifier.padding(Spacing.Xxl),
    ) {
        Icon(
            Lucide.TriangleAlert,
            contentDescription = null,
            tint     = ErrorRed,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text       = stringResource(R.string.sftp_preview_error),
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp,
            color      = ErrorRed,
            textAlign  = TextAlign.Center,
        )
        Text(
            text       = error,
            fontFamily = SpaceGroteskFamily,
            fontSize   = 12.sp,
            color      = TextSecondary,
            textAlign  = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .background(Burgundy, RoundedCornerShape(Radii.Md))
                .clickable(onClick = onDismiss)
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        ) {
            Text(
                text       = stringResource(R.string.action_close),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 13.sp,
                color      = White,
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024L         -> "$bytes B"
    bytes < 1_048_576L     -> "${"%.1f".format(bytes / 1_024.0)} KB"
    bytes < 1_073_741_824L -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    else                   -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
}
