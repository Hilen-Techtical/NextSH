// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.browser

import android.annotation.SuppressLint
import android.webkit.*
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCcw
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.ui.theme.*
import timber.log.Timber

/**
 * Overlay navigateur tunnel : Phase 3.3 DA Techtical (re-style shell).
 *
 * Le moteur WebView et toute la logique de navigation (WebViewClient,
 * WebChromeClient, gestion d'erreurs SSL, intent picker externe) sont
 * conservés verbatim, seul le chrome (top bar, bandeaux, dialog) est
 * porté sur les tokens DA.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserOverlay(
    sessionHolder: BrowserSessionHolder,
    viewModel: TunnelBrowserViewModel = hiltViewModel(),
) {
    val visibleTunnelId by sessionHolder.visibleTunnelId.collectAsState()
    val isVisible = visibleTunnelId != null
    val activeTunnelId by sessionHolder.activeTunnelId.collectAsState()

    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(activeTunnelId) {
        activeTunnelId?.let { viewModel.loadTunnel(it) }
    }

    BackHandler(enabled = isVisible) {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else sessionHolder.hide()
    }

    if (uiState.showExternalNavWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelExternalNavigation() },
            containerColor = Surface,
            shape = RoundedCornerShape(Radii.Lg),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(Radii.Sm))
                            .background(WarningAmber.copy(alpha = 0.12f), RoundedCornerShape(Radii.Sm)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Lucide.TriangleAlert,
                            contentDescription = null,
                            tint     = WarningAmber,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text       = stringResource(R.string.dialog_external_nav_title),
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
                        text = stringResource(R.string.dialog_external_nav_message),
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 13.sp,
                        color      = TextPrimary,
                    )
                    Text(
                        text = stringResource(R.string.dialog_external_nav_warning),
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 12.sp,
                        color      = WarningAmber,
                    )
                    Text(
                        text     = uiState.pendingExternalUrl ?: "",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize   = 11.sp,
                        color    = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = uiState.pendingExternalUrl
                        viewModel.confirmExternalNavigation()
                        if (url != null) {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url),
                                )
                            )
                        }
                    },
                ) {
                    Text(
                        text       = stringResource(R.string.action_continue),
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp,
                        color      = WarningAmber,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelExternalNavigation() }) {
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

    if (activeTunnelId != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (isVisible) 10f else -1f),
        ) {
            Scaffold(
                topBar = {
                    if (isVisible) {
                        Column {
                            BrowserTopBar(
                                label       = uiState.tunnelConfig?.label ?: "",
                                canGoBack   = uiState.canGoBack,
                                canGoForward = uiState.canGoForward,
                                onClose     = {
                                    viewModel.onBrowserClosed()
                                    sessionHolder.close()
                                    webView = null
                                },
                                onMinimize  = { sessionHolder.hide() },
                                onBack      = {
                                    webView?.let { wv ->
                                        wv.goBack()
                                        wv.postDelayed({
                                            viewModel.onNavigationStateChanged(wv.canGoBack(), wv.canGoForward())
                                        }, 300)
                                    }
                                },
                                onForward   = {
                                    webView?.let { wv ->
                                        wv.goForward()
                                        wv.postDelayed({
                                            viewModel.onNavigationStateChanged(wv.canGoBack(), wv.canGoForward())
                                        }, 300)
                                    }
                                },
                                onRefresh   = { webView?.reload() },
                            )

                            if (uiState.isLoading) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Gold,
                                    trackColor = SurfaceVariant,
                                )
                            }

                            if (uiState.tunnelStatus == TunnelStatus.ERROR ||
                                uiState.tunnelStatus == TunnelStatus.STOPPED
                            ) {
                                BrowserBanner(
                                    text  = stringResource(R.string.status_tunnel_disconnected),
                                    accent = ErrorRed,
                                )
                            }

                            if (uiState.webError != null) {
                                BrowserBanner(
                                    text   = uiState.webError ?: "",
                                    accent = WarningAmber,
                                )
                            }
                        }
                    }
                },
                containerColor = NearBlack,
            ) { padding ->
                if (uiState.initialUrl.isNotEmpty()) {
                    AndroidView(
                        factory = { ctx ->
                            sessionHolder.acquireWebView(ctx)?.also {
                                webView = it
                                Timber.d("WebView reused from session holder")
                            } ?: WebView(ctx).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    // WebSQL est mort et l API est depreciee : le
                                    // reglage n a plus d effet, il est retire.
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    @Suppress("DEPRECATION")
                                    allowFileAccessFromFileURLs = false
                                    @Suppress("DEPRECATION")
                                    allowUniversalAccessFromFileURLs = false
                                    setSupportMultipleWindows(false)
                                    @Suppress("DEPRECATION")
                                    saveFormData = false
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    mediaPlaybackRequiresUserGesture = false
                                    userAgentString = settings.userAgentString.replace("; wv", "")
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                }

                                setWebViewClient(object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        val url = request.url.toString()
                                        Timber.d("WebView shouldOverrideUrlLoading: $url")
                                        return !viewModel.onNavigationRequested(url)
                                    }

                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        Timber.d("WebView onPageStarted: $url")
                                        viewModel.clearWebError()
                                        viewModel.onLoadingChanged(true)
                                        url?.let { viewModel.onUrlChanged(it) }
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        Timber.d("WebView onPageFinished: $url")
                                        viewModel.onLoadingChanged(false)
                                        url?.let { viewModel.onUrlChanged(it) }
                                        viewModel.onNavigationStateChanged(
                                            view.canGoBack(), view.canGoForward(),
                                        )
                                    }

                                    override fun doUpdateVisitedHistory(
                                        view: WebView,
                                        url: String?,
                                        isReload: Boolean,
                                    ) {
                                        viewModel.onNavigationStateChanged(view.canGoBack(), view.canGoForward())
                                        url?.let { viewModel.onUrlChanged(it) }
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        Timber.e("WebView onReceivedError: ${error.errorCode} ${error.description} url=${request.url}")
                                        if (request.isForMainFrame) {
                                            viewModel.onWebError("Erreur ${error.errorCode}: ${error.description}")
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        errorResponse: WebResourceResponse,
                                    ) {
                                        Timber.e("WebView onReceivedHttpError: ${errorResponse.statusCode} ${errorResponse.reasonPhrase} url=${request.url}")
                                        if (request.isForMainFrame) {
                                            viewModel.onWebError("HTTP ${errorResponse.statusCode}: ${errorResponse.reasonPhrase}")
                                        }
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView,
                                        handler: SslErrorHandler,
                                        error: android.net.http.SslError,
                                    ) {
                                        val url = error.url ?: ""
                                        Timber.d("WebView onReceivedSslError: $url primaryError=${error.primaryError}")
                                        if (viewModel.isLocalhostUrl(url)) handler.proceed() else handler.cancel()
                                    }
                                })

                                setWebChromeClient(object : WebChromeClient() {
                                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                        msg?.let {
                                            Timber.d("WebView JS [${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                                        }
                                        return true
                                    }

                                    override fun onGeolocationPermissionsShowPrompt(
                                        origin: String,
                                        callback: GeolocationPermissions.Callback,
                                    ) {
                                        callback.invoke(origin, false, false)
                                    }

                                    override fun onShowFileChooser(
                                        webView: WebView,
                                        filePathCallback: ValueCallback<Array<android.net.Uri>>,
                                        fileChooserParams: FileChooserParams,
                                    ): Boolean {
                                        filePathCallback.onReceiveValue(null)
                                        return true
                                    }
                                })

                                setOnLongClickListener { true }
                                setBackgroundColor(android.graphics.Color.parseColor("#0F0F0F"))

                                if (fr.techtical.nextsh.BuildConfig.DEBUG) {
                                    WebView.setWebContentsDebuggingEnabled(true)
                                }

                                sessionHolder.setWebView(this)
                                webView = this

                                Timber.d("WebView created, loading initialUrl=${uiState.initialUrl}")
                                post { loadUrl(uiState.initialUrl) }
                            }
                        },
                        // Détacher sans détruire : le WebView est conservé par
                        // le singleton pour survivre à une réduction puis
                        // restauration sans recharger la page.
                        onRelease = { sessionHolder.detachWebView() },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            // BrowserOverlay est compose hors du NavHost, ses
                            // insets ne sont donc pas consommes par NavGraph et
                            // le Scaffold ci-dessus applique deja la barre de
                            // navigation. Sans consumeWindowInsets, imePadding
                            // la rajouterait une seconde fois, le clavier
                            // ouvert laissant une bande vide au-dessus de lui.
                            .consumeWindowInsets(padding)
                            // La WebView occupe toute la hauteur et la
                            // fenetre ne se redimensionne plus depuis le bord a
                            // bord : sans cela le clavier recouvre le champ de
                            // saisie de la page.
                            .imePadding(),
                    )
                }
            }
        }
    }
}

// ── Top bar ──────────────────────────────────────────────────────────────────

@Composable
private fun BrowserTopBar(
    label: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .border(1.dp, Border1)
            .statusBarsPadding()
            .padding(horizontal = Spacing.Xs, vertical = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Lucide.X,
                contentDescription = stringResource(R.string.action_close),
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onMinimize) {
            Icon(
                Lucide.ChevronDown,
                contentDescription = stringResource(R.string.action_minimize),
                tint = Gold,
                modifier = Modifier.size(18.dp),
            )
        }

        Text(
            text       = label,
            modifier   = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.Sm),
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp,
            color      = Gold,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )

        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(
                Lucide.ArrowLeft,
                contentDescription = stringResource(R.string.action_nav_back),
                tint = if (canGoBack) Gold else TextDisabled,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(
                Lucide.ArrowRight,
                contentDescription = stringResource(R.string.action_nav_forward),
                tint = if (canGoForward) Gold else TextDisabled,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(
                Lucide.RefreshCcw,
                contentDescription = stringResource(R.string.action_refresh),
                tint = Gold,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun BrowserBanner(text: String, accent: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.30f))
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        Icon(
            Lucide.TriangleAlert,
            contentDescription = null,
            tint     = accent,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text       = text,
            fontFamily = SpaceGroteskFamily,
            fontSize   = 12.sp,
            color      = accent,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
        )
    }
}
