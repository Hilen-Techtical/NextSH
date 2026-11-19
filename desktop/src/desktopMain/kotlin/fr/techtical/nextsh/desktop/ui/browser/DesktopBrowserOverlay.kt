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
import fr.techtical.nextsh.desktop.core.browser.KcefFailure
import fr.techtical.nextsh.desktop.core.browser.KcefPhase
import fr.techtical.nextsh.desktop.core.browser.KcefState
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.action_close
import fr.techtical.nextsh.desktop.generated.resources.action_continue
import fr.techtical.nextsh.desktop.generated.resources.browser_action_back
import fr.techtical.nextsh.desktop.generated.resources.browser_action_forward
import fr.techtical.nextsh.desktop.generated.resources.browser_action_minimize
import fr.techtical.nextsh.desktop.generated.resources.browser_action_reload
import fr.techtical.nextsh.desktop.generated.resources.browser_external_message
import fr.techtical.nextsh.desktop.generated.resources.browser_external_title
import fr.techtical.nextsh.desktop.generated.resources.browser_external_warning
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_error_profile_busy
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_error_profile_unknown
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_error_restart_required
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_error_timeout
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_error_unknown
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_phase_downloading
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_phase_extracting
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_phase_initializing
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_phase_installing
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_phase_locating
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_phase_preparing
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_title_failed
import fr.techtical.nextsh.desktop.generated.resources.browser_kcef_title_unavailable
import fr.techtical.nextsh.desktop.generated.resources.browser_tunnel_disconnected
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_interception_unavailable
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_other_address
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_unsupported
import fr.techtical.nextsh.desktop.generated.resources.browser_error_private_session
import fr.techtical.nextsh.desktop.generated.resources.browser_error_start_failed
import fr.techtical.nextsh.desktop.theme.*
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefDevToolsClient
import org.cef.browser.CefFrame
import org.cef.browser.CefRequestContext
import org.cef.callback.CefCallback
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import org.cef.security.CefSSLInfo
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import javax.swing.SwingUtilities

private const val TAG = "DesktopBrowserOverlay"

// Longest wait for Chromium to report the browser as created before giving up.
private const val BROWSER_CREATE_TIMEOUT_MS = 30_000L

// How long the "unsupported authentication" notice stays on screen.
private const val AUTH_NOTICE_DISPLAY_MS = 10_000L

/**
 * Gives a browser back to the engine, in the order that matters for the HTTP
 * authentication channel: DevTools client first (its listener holds the coordinator),
 * then the browser, then the client. Nothing typed in the sign-in panel survives the
 * browser: see [NextShOsrBrowser]. The client is released once the close has been started,
 * which for a browser that is not created yet means once it is: the creation callback must
 * find its client. Never throws: a failing JNI call must not leave the rest unreleased.
 */
private fun releaseBrowserStack(
    browser: CefBrowser?,
    client: CefClient?,
    panel: SoftwareOsrPanel?,
    devTools: CefDevToolsClient?,
) {
    runCatching { devTools?.close() }
    panel?.detachBrowser()
    val releaseClient = { runCatching { client?.dispose() }; Unit }
    if (browser != null) closeBrowserForGood(browser, releaseClient) else releaseClient()
}

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
    var devTools by remember { mutableStateOf<CefDevToolsClient?>(null) }

    // The tunnel and URL the live browser was created for, so a tunnel switch is detected
    // without relying on browser.getURL() which may not reflect pending loads.
    var loadedUrl by remember { mutableStateOf("") }
    var loadedTunnelId by remember { mutableStateOf<String?>(null) }

    // Ends the live browser: HTTP authentication state first (pending requests answered,
    // credentials wiped), then the engine objects. Used by the Close button, a tunnel
    // switch (each tunnel gets its own in-memory request context, so Chromium's
    // authentication cache never carries credentials from one tunnel to the next) and a
    // failed start.
    fun closeBrowserStack() {
        val b = browser
        val c = client
        val p = panel
        val dt = devTools
        browser = null
        client = null
        panel = null
        devTools = null
        loadedUrl = ""
        loadedTunnelId = null
        viewModel.detachAuth()
        sessionHolder.setKcefBrowser(null)
        releaseBrowserStack(b, c, p, dt)
    }

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
            (b as? CefBrowser)?.let { closeBrowserForGood(it) }
        }
        onDispose {
            sessionHolder.onDispose = null
            // The overlay leaves the composition when the vault locks. The browser and its
            // state held by remember die with it, and re-entering creates a fresh browser:
            // the old one would otherwise live on, unreachable, with its page, cookies and
            // HTTP authentication cache. Release it, and the credentials typed for it.
            viewModel.detachAuth()
            sessionHolder.setKcefBrowser(null)
            releaseBrowserStack(browser, client, panel, devTools)
        }
    }

    // The authentication notices are informational and go away by themselves, after being
    // on screen for AUTH_NOTICE_DISPLAY_MS. The clock only runs while the overlay is visible
    // (it restarts when the overlay comes back): a notice raised while the user was looking
    // elsewhere must not expire unseen.
    LaunchedEffect(uiState.authNotice, isVisible) {
        if (uiState.authNotice != null && isVisible) {
            delay(AUTH_NOTICE_DISPLAY_MS)
            viewModel.dismissAuthNotice()
        }
    }

    // While the sign-in panel is up the keyboard belongs to it: the OSR panel gives its
    // keyboard focus up, and cannot take it back by itself (mouse click included), so
    // nothing typed for the sign-in form can reach an input of the web page, an autofocused
    // one for instance. The panel focuses its own field.
    // When the form goes away while the overlay is visible, the page gets the keyboard back.
    LaunchedEffect(panel, uiState.authPrompt != null, isVisible) {
        panel?.setKeyboardBlocked(uiState.authPrompt != null, restoreFocus = isVisible)
    }

    // External navigation warning dialog, shown even when the overlay is minimised
    // so the alert doesn't vanish if the user hid the panel mid-navigation.
    if (uiState.showExternalNavWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelExternalNavigation() },
            containerColor = Surface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text(stringResource(Res.string.browser_external_title)) },
            text = {
                Column {
                    Text(stringResource(Res.string.browser_external_message))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.browser_external_warning),
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
                ) { Text(stringResource(Res.string.action_continue)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.cancelExternalNavigation() },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                ) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    // The overlay stays in the composition tree for the whole tunnel session so the
    // CefBrowser + SoftwareOsrPanel are never destroyed on minimise, only on close().
    // The SwingPanel below is collapsed to size 0 when hidden, because zIndex alone
    // cannot push an AWT heavyweight behind Compose content (it always pierces through).
    // Heavyweight also pierces Compose dialogs and the inline HTTP sign-in panel, so we
    // also collapse while the external nav AlertDialog is open or a sign-in prompt is
    // pending, to keep them legible above the Chromium surface.
    val shouldRenderBrowser = isVisible &&
        !uiState.showExternalNavWarning &&
        uiState.authPrompt == null
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
                                    // Releases the browser, its client (prevents a native
                                    // leak on open → close → open cycles within the same app
                                    // session) and the HTTP authentication state.
                                    closeBrowserStack()
                                    viewModel.onBrowserClosed()
                                    // Also invokes the onDispose callback installed above,
                                    // which has nothing left to close here.
                                    sessionHolder.close()
                                }) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = stringResource(Res.string.action_close),
                                        tint = TextPrimary,
                                    )
                                }

                                IconButton(onClick = { sessionHolder.hide() }) {
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = stringResource(Res.string.browser_action_minimize),
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

                                // Back / forward / reload are disabled while the sign-in panel
                                // is shown: the page is waiting for the answer, and a
                                // navigation would orphan the request the panel answers.
                                // Close and minimise stay available.
                                val navEnabled = uiState.authPrompt == null

                                IconButton(
                                    onClick = {
                                        viewModel.onUserNavigation()
                                        browser?.goBack()
                                    },
                                    enabled = uiState.canGoBack && navEnabled,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = stringResource(Res.string.browser_action_back),
                                        tint = if (uiState.canGoBack && navEnabled) Gold else TextSecondary,
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        viewModel.onUserNavigation()
                                        browser?.goForward()
                                    },
                                    enabled = uiState.canGoForward && navEnabled,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = stringResource(Res.string.browser_action_forward),
                                        tint = if (uiState.canGoForward && navEnabled) Gold else TextSecondary,
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        viewModel.onUserNavigation()
                                        browser?.reload()
                                    },
                                    enabled = navEnabled,
                                ) {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = stringResource(Res.string.browser_action_reload),
                                        tint = if (navEnabled) Gold else TextSecondary,
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
                                        text = stringResource(Res.string.browser_tunnel_disconnected),
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

                            if (uiState.authNotice != null) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = WarningAmber.copy(alpha = 0.15f),
                                ) {
                                    Text(
                                        text = stringResource(
                                            when (uiState.authNotice) {
                                                AuthNotice.OTHER_ADDRESS -> Res.string.browser_auth_other_address
                                                AuthNotice.INTERCEPTION_UNAVAILABLE ->
                                                    Res.string.browser_auth_interception_unavailable
                                                else -> Res.string.browser_auth_unsupported
                                            },
                                        ),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = WarningAmber,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 3,
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
                                    text = stringResource(kcefPhaseText(state.phase)),
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
                                    text = stringResource(kcefFailureTitle(state.failure)),
                                    color = ErrorRed,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = kcefFailureText(state),
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
                                // During a tunnel switch initialUrl is still the previous
                                // tunnel's until loadTunnel has loaded the new config: only
                                // a config whose id is the active tunnel's is acted upon.
                                val configTunnelId = uiState.tunnelConfig?.id
                                // Read with the id, from the same state: the port of the tunnel
                                // this very browser is created for, the one origin allowed to
                                // receive credentials from it.
                                val configPort = uiState.tunnelConfig?.localPort

                                // Create the CEF client + browser once per tunnel. We use
                                // LaunchedEffect rather than remember { } because
                                // CefApp.getInstance().createClient() must not run during
                                // composition: it belongs in an effect.
                                LaunchedEffect(activeTunnelId, configTunnelId, initialUrl) {
                                    val configIsCurrent = configTunnelId != null &&
                                        configPort != null &&
                                        configTunnelId == activeTunnelId
                                    val alreadyOpen = browser != null &&
                                        loadedTunnelId == configTunnelId &&
                                        loadedUrl == initialUrl
                                    if (configIsCurrent && !alreadyOpen) {
                                        val tunnelPort = configPort ?: return@LaunchedEffect
                                        // Tunnel switch: the previous browser goes away with its
                                        // in-memory request context, so the HTTP authentication
                                        // credentials Chromium cached for the previous tunnel
                                        // (keyed by 127.0.0.1:<port>, a port a later tunnel may
                                        // reuse) cannot reach the next tunnel's service.
                                        if (browser != null) closeBrowserStack()

                                        // OSR custom path: build our own CefBrowserOsrWithHandler
                                        // (NextShOsrBrowser, which closes without notifying the
                                        // main window) backed by SoftwareOsrRenderHandler +
                                        // SoftwareOsrPanel.
                                        // This bypasses both windowed rendering (white HWND flashes)
                                        // and JCEF's default OSR (JOGL crash on HDR10). See
                                        // SoftwareOsrPanel for the render pipeline.
                                        val newPanel = SoftwareOsrPanel()
                                        val renderHandler = SoftwareOsrRenderHandler(newPanel)

                                        val newClient = withContext(Dispatchers.IO) {
                                            runCatching { CefApp.getInstance().createClient() }.getOrNull()
                                        } ?: return@LaunchedEffect
                                        if (!isActive) {
                                            // A newer tunnel switch superseded this one while
                                            // the client was being created.
                                            newClient.dispose()
                                            return@LaunchedEffect
                                        }

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
                                             * A link opened in a new tab (middle click, Ctrl+click). No
                                             * second browser is ever created: see resolvePopupTarget.
                                             */
                                            override fun onOpenURLFromTab(
                                                browser: CefBrowser,
                                                frame: CefFrame?,
                                                targetUrl: String?,
                                                userGesture: Boolean,
                                            ): Boolean {
                                                val target = viewModel.resolvePopupTarget(targetUrl)
                                                if (target != null) {
                                                    SwingUtilities.invokeLater { runCatching { browser.loadURL(target) } }
                                                }
                                                return true
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

                                        // In-memory request context (createContext(null) has no
                                        // cache path): cookies, history and cache of tunnelled
                                        // services never reach the disk. One context per browser
                                        // session, released with the browser.
                                        //
                                        // Fail closed: JCEF can return null WITHOUT throwing (it
                                        // swallows UnsatisfiedLinkError internally), and
                                        // CefBrowser_N would then silently fall back to the
                                        // global persistent context. The browser is not opened
                                        // at all in that case, never on the global context.
                                        val requestContext = runCatching { CefRequestContext.createContext(null) }.getOrNull()
                                        if (requestContext == null) {
                                            Logger.w(
                                                TAG,
                                                "in-memory request context unavailable, browser not opened",
                                            )
                                            newClient.dispose()
                                            viewModel.onWebError(getString(Res.string.browser_error_private_session))
                                            return@LaunchedEffect
                                        }
                                        // Created without a URL: the page must only be requested
                                        // once the HTTP authentication channel below is up, or
                                        // its first 401 could reach Chromium's native sign-in
                                        // window (see startAuthInterception).
                                        val created = CompletableDeferred<Unit>()
                                        newClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                                            override fun onAfterCreated(b: CefBrowser) {
                                                // Lets a close that was asked for before the creation go through.
                                                (b as? NextShOsrBrowser)?.markCreated()
                                                created.complete(Unit)
                                            }

                                            /**
                                             * window.open and target=_blank. A popup would be a second
                                             * browser without the HTTP authentication channel (its first
                                             * 401 would open Chromium's own window) and, windowless, would
                                             * draw into this very panel: none is ever created. A loopback
                                             * target opens in this browser instead, see resolvePopupTarget.
                                             */
                                            override fun onBeforePopup(
                                                b: CefBrowser,
                                                frame: CefFrame?,
                                                targetUrl: String?,
                                                targetFrameName: String?,
                                            ): Boolean {
                                                val target = viewModel.resolvePopupTarget(targetUrl)
                                                if (target != null) {
                                                    SwingUtilities.invokeLater { runCatching { b.loadURL(target) } }
                                                }
                                                return true
                                            }
                                        })
                                        val newBrowser = NextShOsrBrowser(
                                            newClient,
                                            "",
                                            requestContext,
                                            renderHandler,
                                            newPanel,
                                        )
                                        // Attach before createImmediately so the panel is
                                        // already wired when the native browser starts
                                        // posting resize / paint callbacks.
                                        newPanel.attachBrowser(newBrowser)
                                        newBrowser.createImmediately()

                                        // Published at once, before anything suspends: if this
                                        // effect is cancelled from here on (tunnel switch, overlay
                                        // leaving the composition), the browser is still reachable
                                        // and gets released instead of leaking.
                                        client = newClient
                                        browser = newBrowser
                                        panel = newPanel
                                        loadedUrl = initialUrl
                                        loadedTunnelId = configTunnelId
                                        sessionHolder.setKcefBrowser(newBrowser)

                                        // True while this effect still owns the browser it created:
                                        // not cancelled, and not replaced in the state by a tunnel
                                        // switch or released by the composition leaving. Checked
                                        // after every suspension, before touching anything shared.
                                        fun stillCurrent() = isActive && browser === newBrowser

                                        val reported = withTimeoutOrNull(BROWSER_CREATE_TIMEOUT_MS) {
                                            created.await()
                                            true
                                        } ?: false
                                        if (!reported) {
                                            Logger.w(TAG, "browser creation not reported by Chromium, browser not opened")
                                            // A browser that is never created keeps its close gate waiting, and its
                                            // client alive: deliberate, disposing a client while a native creation is
                                            // still pending is the very hazard the gate exists to avoid.
                                            if (browser === newBrowser) {
                                                closeBrowserStack()
                                                viewModel.onWebError(getString(Res.string.browser_error_start_failed))
                                            } else {
                                                // Superseded: this effect's own browser, whatever the
                                                // state says now (releasing twice is harmless).
                                                releaseBrowserStack(newBrowser, newClient, newPanel, null)
                                            }
                                            return@LaunchedEffect
                                        }
                                        if (!stillCurrent()) return@LaunchedEffect

                                        // HTTP authentication through the DevTools Fetch domain:
                                        // Chromium never opens its own sign-in window, challenges
                                        // reach the coordinator that drives the NextSH panel.
                                        val devToolsClient = awaitDevToolsClient(newBrowser)
                                        if (!stillCurrent()) return@LaunchedEffect
                                        var intercepted = false
                                        if (devToolsClient != null) {
                                            devTools = devToolsClient
                                            val coordinator = viewModel.attachAuth(tunnelPort, CefDevToolsSender(devToolsClient))
                                            intercepted = startAuthInterception(devToolsClient, coordinator)
                                            if (!stillCurrent()) return@LaunchedEffect
                                        }
                                        if (!intercepted) {
                                            // Degraded, not fatal: the page still loads, but Chromium
                                            // may show its own sign-in window if the service asks for a
                                            // password, and the user is told so. Nothing identifying
                                            // is logged.
                                            Logger.w(TAG, "HTTP sign-in interception unavailable")
                                            viewModel.onAuthInterceptionUnavailable()
                                        }
                                        newBrowser.loadURL(initialUrl)
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
                                    // Keyed on the panel: a tunnel switch swaps the browser, and
                                    // the interop view must follow instead of keeping the old one.
                                    key(currentPanel) {
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
                                    }
                                } else {
                                    // Browser is being created asynchronously: show a spinner
                                    // until the LaunchedEffect above completes.
                                    CircularProgressIndicator(color = Gold)
                                }
                            }
                        }
                    }

                    // HTTP sign-in panel (401 Basic from a tunnelled service). Replaces
                    // Chromium's native sign-in window. Drawn inline over the NearBlack
                    // container, not as a Dialog or Popup: those open as separate OS
                    // windows on Desktop and would outlive the overlay. The SwingPanel
                    // above stays composed (the CEF browser must stay attached) but is
                    // collapsed to 0.dp while a prompt is pending, see shouldRenderBrowser.
                    //
                    // Only while the overlay is visible: a focus-taking form must never
                    // show up over another screen (a terminal, say) and swallow keystrokes
                    // meant for it, such as a password. While minimised the request stays
                    // paused (the page just waits) and the panel reappears when the browser
                    // is reopened; closing the browser cancels it.
                    uiState.authPrompt?.takeIf { isVisible }?.let { prompt ->
                        HttpAuthPanel(
                            prompt = prompt,
                            onSubmit = viewModel::submitAuth,
                            onCancel = viewModel::cancelAuth,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun kcefPhaseText(phase: KcefPhase): StringResource = when (phase) {
    KcefPhase.Preparing -> Res.string.browser_kcef_phase_preparing
    KcefPhase.Locating -> Res.string.browser_kcef_phase_locating
    KcefPhase.Downloading -> Res.string.browser_kcef_phase_downloading
    KcefPhase.Extracting -> Res.string.browser_kcef_phase_extracting
    KcefPhase.Installing -> Res.string.browser_kcef_phase_installing
    KcefPhase.Initializing -> Res.string.browser_kcef_phase_initializing
}

private fun kcefFailureTitle(failure: KcefFailure): StringResource = when (failure) {
    KcefFailure.ProfileBusy -> Res.string.browser_kcef_title_unavailable
    else -> Res.string.browser_kcef_title_failed
}

@Composable
private fun kcefFailureText(state: KcefState.Failed): String = when (state.failure) {
    KcefFailure.ProfileUnknown -> stringResource(Res.string.browser_kcef_error_profile_unknown)
    KcefFailure.ProfileBusy -> stringResource(Res.string.browser_kcef_error_profile_busy)
    KcefFailure.RestartRequired -> stringResource(Res.string.browser_kcef_error_restart_required)
    KcefFailure.Timeout -> stringResource(Res.string.browser_kcef_error_timeout)
    KcefFailure.EngineError -> state.detail ?: stringResource(Res.string.browser_kcef_error_unknown)
}
