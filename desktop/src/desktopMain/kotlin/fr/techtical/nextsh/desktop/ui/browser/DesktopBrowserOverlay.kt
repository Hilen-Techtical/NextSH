// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import fr.techtical.nextsh.desktop.core.browser.KcefState
import fr.techtical.nextsh.desktop.theme.*
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefBrowserOsrWithHandler
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import org.cef.security.CefSSLInfo
import java.awt.Desktop

@Composable
fun DesktopBrowserOverlay(
    sessionHolder: DesktopBrowserSessionHolder,
    viewModel: DesktopTunnelBrowserViewModel,
    kcefState: StateFlow<KcefState>,
    /**
     * Lazy-trigger for the KCEF (Chromium) bootstrap. Called the first time
     * a tunnel becomes active in this composition (i.e. the user opens an
     * in-app browser session). Subsequent calls are no-ops thanks to
     * [KcefInitializer.initialize]'s internal `started` flag.
     *
     * Deferred from app startup → ~800 ms-2 s faster cold launch (the
     * Chromium native download / extract used to run unconditionally even
     * when the user never touched a tunnel browser).
     */
    onActivateKcef: () -> Unit,
) {
    val visibleTunnelId by sessionHolder.visibleTunnelId.collectAsState()
    val activeTunnelId by sessionHolder.activeTunnelId.collectAsState()
    val isVisible = visibleTunnelId != null

    val uiState by viewModel.uiState.collectAsState()
    val currentKcefState by kcefState.collectAsState()

    // CEF instance created once per active session and kept alive across hides.
    var browser by remember { mutableStateOf<CefBrowser?>(null) }
    var client by remember { mutableStateOf<CefClient?>(null) }
    var panel by remember { mutableStateOf<SoftwareOsrPanel?>(null) }

    // Track the URL currently loaded into the browser so we can detect tunnel switches
    // without relying on browser.getURL() which may not reflect pending loads.
    var loadedUrl by remember { mutableStateOf("") }

    LaunchedEffect(activeTunnelId) {
        activeTunnelId?.let {
            // Kick off the Chromium bootstrap on first browser activation.
            // No-op after the first call.
            onActivateKcef()
            viewModel.loadTunnel(it)
        }
    }

    // Wire the disposal callback so sessionHolder.close() tears down the CEF browser.
    // Done once on composition entry; cleared on composition exit.
    DisposableEffect(Unit) {
        sessionHolder.onDispose = { b ->
            (b as? CefBrowser)?.close(true)
        }
        onDispose {
            sessionHolder.onDispose = null
        }
    }

    // External navigation warning dialog, shown even when the overlay is minimised
    // so the alert doesn't vanish if the user hid the panel mid-navigation.
    if (uiState.showExternalNavWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelExternalNavigation() },
            containerColor = Surface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Navigation externe") },
            text = {
                Column {
                    Text("Vous allez quitter le service tunnelisé et ouvrir une URL dans votre navigateur système.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Vérifiez que l'URL est sûre avant de continuer.",
                        color = WarningAmber,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = uiState.pendingExternalUrl ?: "",
                        color = TextSecondary,
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
                            // Only http/https reach Desktop.browse: blocks file://,
                            // javascript:, shell:, etc. which could execute local resources
                            // if a tunnelled service emits a malicious link.
                            val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
                            if (scheme == "http" || scheme == "https") {
                                try {
                                    Desktop.getDesktop().browse(java.net.URI(url))
                                } catch (_: Exception) { }
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = WarningAmber),
                ) { Text("Continuer") }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.cancelExternalNavigation() },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                ) { Text("Annuler") }
            },
        )
    }

    // The overlay stays in the composition tree for the whole tunnel session so the
    // CefBrowser + SoftwareOsrPanel are never destroyed on minimise, only on close().
    // The SwingPanel below is collapsed to size 0 when hidden, because zIndex alone
    // cannot push an AWT heavyweight behind Compose content (it always pierces through).
    // Heavyweight also pierces Compose dialogs, so we also collapse while the external
    // nav AlertDialog is open to keep it legible above the Chromium surface.
    val shouldRenderBrowser = isVisible && !uiState.showExternalNavWarning
    if (activeTunnelId != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (isVisible) 10f else -1f),
        ) {
            Scaffold(
                topBar = {
                    // Top bar is only rendered when the overlay is actually visible.
                    // Keeping it out of the hidden state avoids measuring and drawing it
                    // when the user is working in the main UI.
                    if (isVisible) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Surface)
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = {
                                    val b = browser
                                    val c = client
                                    val p = panel
                                    browser = null
                                    client = null
                                    panel = null
                                    loadedUrl = ""
                                    viewModel.onBrowserClosed()
                                    sessionHolder.close()
                                    // sessionHolder.close triggers onDispose (DisposableEffect
                                    // above) which calls b.close(true). Belt-and-suspenders
                                    // in case onDispose was not yet installed (race on first
                                    // composition).
                                    p?.detachBrowser()
                                    b?.close(true)
                                    // Releasing the client prevents a native leak on
                                    // open → close → open cycles within the same app session.
                                    c?.dispose()
                                }) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Fermer",
                                        tint = TextPrimary,
                                    )
                                }

                                IconButton(onClick = { sessionHolder.hide() }) {
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "Réduire",
                                        tint = Gold,
                                    )
                                }

                                Text(
                                    text = uiState.tunnelConfig?.label ?: "",
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    color = Gold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )

                                IconButton(
                                    onClick = { browser?.goBack() },
                                    enabled = uiState.canGoBack,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = "Précédent",
                                        tint = if (uiState.canGoBack) Gold else TextSecondary,
                                    )
                                }

                                IconButton(
                                    onClick = { browser?.goForward() },
                                    enabled = uiState.canGoForward,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = "Suivant",
                                        tint = if (uiState.canGoForward) Gold else TextSecondary,
                                    )
                                }

                                IconButton(onClick = { browser?.reload() }) {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = "Actualiser",
                                        tint = Gold,
                                    )
                                }
                            }

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
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = ErrorRed.copy(alpha = 0.15f),
                                ) {
                                    Text(
                                        text = "Tunnel déconnecté",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = ErrorRed,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }

                            if (uiState.webError != null) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = WarningAmber.copy(alpha = 0.15f),
                                ) {
                                    Text(
                                        text = uiState.webError ?: "",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = WarningAmber,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                },
                containerColor = NearBlack,
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val state = currentKcefState) {
                        is KcefState.Initializing -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(color = Gold)
                                Text(
                                    text = state.message,
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (state.progressPercent != null) {
                                    Text(
                                        text = "${state.progressPercent} %",
                                        color = GoldMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }

                        is KcefState.Failed -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(24.dp),
                            ) {
                                Text(
                                    text = "Impossible d'initialiser Chromium",
                                    color = ErrorRed,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = state.reason,
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        KcefState.Ready -> {
                            val initialUrl = uiState.initialUrl
                            if (initialUrl.isNotEmpty()) {
                                // Create the CEF client + browser once per active session.
                                // We use LaunchedEffect rather than remember { } because
                                // CefApp.getInstance().createClient() must not run during
                                // composition: it belongs in an effect.
                                LaunchedEffect(activeTunnelId) {
                                    if (browser == null) {
                                        // OSR custom path: build our own CefBrowserOsrWithHandler
                                        // backed by SoftwareOsrRenderHandler + SoftwareOsrPanel.
                                        // This bypasses both windowed rendering (white HWND flashes)
                                        // and JCEF's default OSR (JOGL crash on HDR10). See
                                        // SoftwareOsrPanel for the render pipeline.
                                        val newPanel = SoftwareOsrPanel()
                                        val renderHandler = SoftwareOsrRenderHandler(newPanel)

                                        val newClient = withContext(Dispatchers.IO) {
                                            runCatching { CefApp.getInstance().createClient() }.getOrNull()
                                        } ?: return@LaunchedEffect

                                        val loadHandler = object : CefLoadHandlerAdapter() {
                                            override fun onLoadStart(
                                                b: CefBrowser,
                                                frame: CefFrame,
                                                transitionType: CefRequest.TransitionType,
                                            ) {
                                                if (frame.isMain) {
                                                    viewModel.clearWebError()
                                                    viewModel.onLoadingChanged(true)
                                                }
                                            }

                                            override fun onLoadEnd(
                                                b: CefBrowser,
                                                frame: CefFrame,
                                                httpStatusCode: Int,
                                            ) {
                                                if (frame.isMain) {
                                                    viewModel.onLoadingChanged(false)
                                                    viewModel.onNavigationStateChanged(
                                                        b.canGoBack(),
                                                        b.canGoForward(),
                                                    )
                                                    viewModel.onUrlChanged(b.getURL() ?: "")
                                                }
                                            }

                                            override fun onLoadError(
                                                b: CefBrowser,
                                                frame: CefFrame,
                                                errorCode: org.cef.handler.CefLoadHandler.ErrorCode,
                                                errorText: String?,
                                                failedUrl: String?,
                                            ) {
                                                // ERR_ABORTED (-3) fires on every user-initiated
                                                // navigation (e.g. goBack), not a real error.
                                                if (frame.isMain && errorCode.code != -3) {
                                                    viewModel.onLoadingChanged(false)
                                                    viewModel.onWebError(
                                                        buildString {
                                                            if (errorText != null) append(errorText)
                                                            append(" (${errorCode.code})")
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                        newClient.addLoadHandler(loadHandler)

                                        // Phase 5, security handlers:
                                        //   A. onBeforeBrowse: intercept external URL navigations
                                        //   B. onCertificateError: accept self-signed TLS on localhost
                                        val requestHandler = object : CefRequestHandlerAdapter() {
                                            /**
                                             * Called on every top-level and sub-frame navigation before
                                             * the request is sent. Return true to cancel navigation.
                                             *
                                             * Sub-frames (iframes, CDN scripts, fonts, …) are allowed
                                             * unconditionally: filtering them would break any modern
                                             * self-hosted service that pulls assets from external CDNs.
                                             * The protection only targets top-level navigations so the
                                             * user cannot leave the tunnelled service context unknowingly.
                                             *
                                             * CEF IO thread: must return quickly, no suspend/blocking.
                                             */
                                            override fun onBeforeBrowse(
                                                browser: CefBrowser,
                                                frame: CefFrame,
                                                request: CefRequest,
                                                userGesture: Boolean,
                                                isRedirect: Boolean,
                                            ): Boolean {
                                                if (!frame.isMain) return false
                                                val url = request.url ?: return false
                                                return viewModel.shouldCancelNavigation(url)
                                            }

                                            /**
                                             * Called when a TLS certificate error is encountered.
                                             * Self-signed certificates on loopback addresses are accepted
                                             * because many self-hosted services (Proxmox, Portainer, …)
                                             * ship with auto-generated certificates.
                                             *
                                             * Return true = we handled it (callback decides outcome).
                                             * Return false = let CEF apply default behaviour (reject).
                                             */
                                            override fun onCertificateError(
                                                browser: CefBrowser,
                                                certError: CefLoadHandler.ErrorCode,
                                                requestUrl: String,
                                                sslInfo: CefSSLInfo,
                                                callback: CefCallback,
                                            ): Boolean {
                                                return if (viewModel.isLocalhostUrl(requestUrl)) {
                                                    callback.Continue() // accept self-signed on localhost
                                                    true
                                                } else {
                                                    callback.cancel() // reject invalid cert on external URLs
                                                    true
                                                }
                                            }
                                        }
                                        newClient.addRequestHandler(requestHandler)

                                        val newBrowser = CefBrowserOsrWithHandler(
                                            newClient,
                                            initialUrl,
                                            null, // CefRequestContext: null = default
                                            renderHandler,
                                            newPanel,
                                        )
                                        // Attach before createImmediately so the panel is
                                        // already wired when the native browser starts
                                        // posting resize / paint callbacks.
                                        newPanel.attachBrowser(newBrowser)
                                        newBrowser.createImmediately()

                                        client = newClient
                                        browser = newBrowser
                                        panel = newPanel
                                        loadedUrl = initialUrl
                                        sessionHolder.setKcefBrowser(newBrowser)
                                    }
                                }

                                // On tunnel switch (activeTunnelId unchanged but URL changed),
                                // navigate the existing browser to the new service URL instead
                                // of disposing and recreating it.
                                LaunchedEffect(initialUrl) {
                                    val b = browser ?: return@LaunchedEffect
                                    if (initialUrl != loadedUrl) {
                                        b.loadURL(initialUrl)
                                        loadedUrl = initialUrl
                                    }
                                }

                                val currentPanel = panel
                                if (currentPanel != null) {
                                    // SoftwareOsrPanel is a plain JPanel: embedded the same
                                    // way JediTerm widgets are via SwingPanel. Size is
                                    // collapsed to 0.dp while hidden or while the external-nav
                                    // dialog is open so the heavyweight does not pierce through
                                    // Compose content.
                                    //
                                    // background=NearBlack is the fallback colour shown while
                                    // Chromium has not yet delivered the first onPaint frame.
                                    //
                                    // update is invoked on each recomposition; when the panel
                                    // goes from size(0.dp) back to fillMaxSize() we force
                                    // revalidate()+repaint() so AWT re-draws the last frame
                                    // rather than leaving a blank canvas after minimise→restore.
                                    SwingPanel(
                                        background = NearBlack,
                                        modifier = if (shouldRenderBrowser) {
                                            Modifier.fillMaxSize()
                                        } else {
                                            Modifier.size(0.dp)
                                        },
                                        factory = { currentPanel },
                                        update = {
                                            if (shouldRenderBrowser) {
                                                it.revalidate()
                                                it.repaint()
                                            }
                                        },
                                    )
                                } else {
                                    // Browser is being created asynchronously: show a spinner
                                    // until the LaunchedEffect above completes.
                                    CircularProgressIndicator(color = Gold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
