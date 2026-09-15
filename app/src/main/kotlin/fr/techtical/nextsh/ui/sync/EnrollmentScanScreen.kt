// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.ScanLine
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.TriangleAlert
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.*
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollmentScanScreen(
    onBack: () -> Unit,
    viewModel: EnrollmentScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.retry()
    }

    LaunchedEffect(Unit) {
        when (viewModel.state.value) {
            is ScannedEnrollment.Idle, is ScannedEnrollment.RequestingPermission -> {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> Unit
        }
    }

    val context = LocalContext.current
    LaunchedEffect(state) {
        if (state is ScannedEnrollment.RequestingPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text       = stringResource(R.string.title_enrollment_scan),
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
        containerColor = NearBlack,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is ScannedEnrollment.Idle,
                is ScannedEnrollment.RequestingPermission -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Gold, strokeWidth = 2.dp)
                    }
                }

                is ScannedEnrollment.PermissionDenied -> {
                    PermissionDeniedContent(
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            )
                        },
                    )
                }

                is ScannedEnrollment.Scanning -> {
                    QrScannerContent(onQrDetected = { raw -> viewModel.onQrScanned(raw) })
                }

                is ScannedEnrollment.FingerprintConfirm -> {
                    FingerprintConfirmContent(
                        state = s,
                        onConfirm = { viewModel.confirmFingerprint() },
                        onCancel  = onBack,
                    )
                }

                is ScannedEnrollment.FingerprintMismatch -> {
                    FingerprintMismatchContent(state = s, onBack = { viewModel.retry() })
                }

                is ScannedEnrollment.Submitting -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                        ) {
                            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp)
                            Text(
                                text       = stringResource(R.string.enrollment_submitting),
                                fontFamily = SpaceGroteskFamily,
                                fontSize   = 13.sp,
                                color      = TextSecondary,
                            )
                        }
                    }
                }

                is ScannedEnrollment.Success -> {
                    SuccessContent(state = s, onDone = onBack)
                }

                is ScannedEnrollment.Error -> {
                    ErrorContent(message = s.message, onBack = onBack, onRetry = { viewModel.retry() })
                }
            }
        }
    }
}

// ── Scanner caméra ───────────────────────────────────────────────────────────

@Composable
private fun QrScannerContent(onQrDetected: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var qrDetected by remember { mutableStateOf(false) }

    val barcodeOptions = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    }
    val barcodeScanner = remember { BarcodeScanning.getClient(barcodeOptions) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                                .build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        if (qrDetected) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees,
                            )
                            barcodeScanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        val raw = barcode.rawValue ?: continue
                                        if (raw.isNotBlank() && !qrDetected) {
                                            qrDetected = true
                                            imageAnalysis.clearAnalyzer()
                                            onQrDetected(raw)
                                            break
                                        }
                                    }
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        } else {
                            imageProxy.close()
                        }
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                    } catch (_: Exception) {
                        // Caméra indisponible
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
        )

        // Reticle visuelle Gold au centre
        Box(
            modifier = Modifier
                .size(240.dp)
                .align(Alignment.Center)
                .border(2.dp, Gold, RoundedCornerShape(Radii.Md)),
        )

        // Overlay instruction bottom: Surface@0.85 + Border1
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Surface.copy(alpha = 0.92f))
                .border(1.dp, Border1)
                .navigationBarsPadding()
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Icon(
                    Lucide.ScanLine,
                    contentDescription = null,
                    tint     = Gold,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text       = stringResource(R.string.enrollment_scan_hint),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 13.sp,
                    color      = TextPrimary,
                    textAlign  = TextAlign.Center,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            barcodeScanner.close()
            analysisExecutor.shutdown()
        }
    }
}

// ── Permission refusée ────────────────────────────────────────────────────────

@Composable
private fun PermissionDeniedContent(onOpenSettings: () -> Unit) {
    StateContent(
        accent = WarningAmber,
        icon   = Lucide.TriangleAlert,
        title  = stringResource(R.string.enrollment_camera_permission_required),
    ) {
        BurgundyButton(
            label   = stringResource(R.string.action_open_settings),
            onClick = onOpenSettings,
        )
    }
}

// ── Confirmation fingerprint ──────────────────────────────────────────────────

@Composable
private fun FingerprintConfirmContent(
    state: ScannedEnrollment.FingerprintConfirm,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.Lg))
                .background(Surface, RoundedCornerShape(Radii.Lg))
                .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
                .padding(Spacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(Radii.Md))
                    .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(Radii.Md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Monitor,
                    contentDescription = null,
                    tint     = Gold,
                    modifier = Modifier.size(28.dp),
                )
            }

            Text(
                text       = stringResource(R.string.enrollment_desktop_detected),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 15.sp,
                color      = Gold,
                textAlign  = TextAlign.Center,
            )

            if (state.serverDeviceName.isNotBlank()) {
                Text(
                    text       = state.serverDeviceName,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize   = 14.sp,
                    color      = TextPrimary,
                    textAlign  = TextAlign.Center,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Gold,
                    strokeWidth = 2.dp,
                )
            }

            Text(
                text       = "${state.parsed.host}:${state.parsed.port}",
                fontFamily = JetBrainsMonoFamily,
                fontSize   = 11.sp,
                color      = TextSecondary,
            )

            // ── Section fingerprint ─────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.Md))
                    .background(NearBlack, RoundedCornerShape(Radii.Md))
                    .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
                    .padding(Spacing.Md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
            ) {
                Text(
                    text       = stringResource(R.string.label_fingerprint).uppercase(),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 10.sp,
                    letterSpacing = 0.8.sp,
                    color      = TextDisabled,
                )
                Text(
                    text       = state.parsed.fingerprint,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp,
                    color      = Gold,
                    textAlign  = TextAlign.Center,
                )
            }

            Text(
                text       = stringResource(R.string.enrollment_fingerprint_hint),
                fontFamily = SpaceGroteskFamily,
                fontSize   = 12.sp,
                color      = TextSecondary,
                textAlign  = TextAlign.Center,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                OutlinedActionButton(
                    label   = stringResource(R.string.action_cancel),
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                BurgundyButton(
                    label   = stringResource(R.string.action_confirm),
                    onClick = onConfirm,
                    enabled = state.serverDeviceName.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── Fingerprint mismatch ──────────────────────────────────────────────────────

@Composable
private fun FingerprintMismatchContent(
    state: ScannedEnrollment.FingerprintMismatch,
    onBack: () -> Unit,
) {
    StateContent(
        accent = ErrorRed,
        icon   = Lucide.TriangleAlert,
        title  = stringResource(
            R.string.error_fingerprint_mismatch,
            state.expected,
            state.actual,
        ),
    ) {
        BurgundyButton(label = stringResource(R.string.action_back), onClick = onBack)
    }
}

// ── Succès ────────────────────────────────────────────────────────────────────

@Composable
private fun SuccessContent(
    state: ScannedEnrollment.Success,
    onDone: () -> Unit,
) {
    StateContent(
        accent = SuccessGreen,
        icon   = Lucide.ShieldCheck,
        title  = stringResource(R.string.enrollment_success, state.desktopDeviceName),
    ) {
        BurgundyButton(label = stringResource(R.string.enrollment_action_done), onClick = onDone)
    }
}

// ── Erreur ────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    StateContent(
        accent = ErrorRed,
        icon   = Lucide.TriangleAlert,
        title  = message,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            OutlinedActionButton(label = stringResource(R.string.action_back), onClick = onBack)
            BurgundyButton(label = stringResource(R.string.action_retry), onClick = onRetry)
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

@Composable
private fun StateContent(
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(Radii.Md))
                .background(accent.copy(alpha = 0.10f), RoundedCornerShape(Radii.Md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint     = accent,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text       = title,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp,
            color      = accent,
            textAlign  = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Lg))
        actions()
    }
}

@Composable
private fun BurgundyButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.Md))
            .background(
                if (enabled) Burgundy else BurgundyDark,
                RoundedCornerShape(Radii.Md),
            )
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 13.sp,
            color      = if (enabled) White else TextSecondary,
        )
    }
}

@Composable
private fun OutlinedActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.Md))
            .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 13.sp,
            color      = TextSecondary,
        )
    }
}
