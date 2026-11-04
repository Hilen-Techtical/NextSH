// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.browser

import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFBuilder
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.callback.CefCommandLine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.roundToInt

sealed interface KcefState {
    data class Initializing(val progressPercent: Int? = null, val message: String) : KcefState
    data object Ready : KcefState
    data class Failed(
        val reason: String,
        val title: String = "Impossible d'initialiser Chromium",
    ) : KcefState
}

/**
 * Starts the Chromium engine. Exists only as a test seam: tests plug a fake so that
 * no test can ever reach the real [KCEF.init] (download and load of the natives).
 */
internal fun interface ChromiumStarter {
    suspend fun start(
        builder: KCEFBuilder.() -> Unit,
        onError: (Throwable?) -> Unit,
        onRestartRequired: () -> Unit,
    )
}

private val kcefStarter = ChromiumStarter { builder, onError, onRestartRequired ->
    KCEF.init(
        builder = builder,
        onError = { onError(it) },
        onRestartRequired = { onRestartRequired() },
    )
}

/** How long to wait for `CefApp` to report INITIALIZED once `KCEF.init` has returned. */
private const val CEF_INIT_TIMEOUT_MS = 30_000L

/**
 * Suspends until `CefApp` is really INITIALIZED, or gives up after
 * [CEF_INIT_TIMEOUT_MS] and returns `false`.
 *
 * `KCEF.init` can return while `CefApp.getState()` is still NEW (natives not loaded
 * yet). In that window `CefRequestContext.createContext(null)` returns null, whereas
 * `createBrowser` is delayed by JCEF itself. `CefApp.onInitialization` calls the
 * handler immediately when already initialized, and otherwise queues it until the
 * initialization completes. It is never called when the initialization fails, hence
 * the timeout.
 */
private suspend fun awaitCefAppInitialized(): Boolean =
    withTimeoutOrNull(CEF_INIT_TIMEOUT_MS) {
        suspendCancellableCoroutine<Unit> { cont ->
            val resumed = AtomicBoolean(false)
            CefApp.getInstance().onInitialization { state ->
                if (state == CefApp.CefAppState.INITIALIZED && cont.isActive && resumed.compareAndSet(false, true)) {
                    cont.resume(Unit)
                }
            }
        }
    } != null

/**
 * Applies the NextSH Chromium command line: software-only rendering switches and the
 * NextSH-owned profile directory ([profileDir], see BrowserProfile.kt). Extracted from
 * the app handler so the switches can be unit-tested without loading Chromium.
 */
internal fun configureChromiumCommandLine(commandLine: CefCommandLine, profileDir: File) {
    commandLine.appendSwitch("disable-gpu")
    commandLine.appendSwitch("disable-gpu-compositing")
    commandLine.appendSwitch("disable-gpu-rasterization")
    commandLine.appendSwitch("disable-software-rasterizer")
    commandLine.appendSwitch("disable-webgl")
    // PaintHolding delayed the first paint of each navigation
    // until the new page was renderable: harmful on windowed
    // JCEF because the gap surfaced as a white HWND fill.
    // Still off under OSR so Chromium's own cold-start fallback
    // (our NearBlack surface) is visible instantly.
    //
    // BackForwardCache held stale frames with a blank background
    // after refresh in some tests: disabling it is harmless in
    // our single-session context.
    //
    // Note: force-color-profile is intentionally NOT set. Under
    // software OSR, pinning it to sRGB desaturates sites that
    // embed colour profiles (dashboards, admin UIs with branded
    // colours), and the HDR10 pixel-format collision we were
    // guarding against only happened on the windowed HWND path.
    commandLine.appendSwitchWithValue(
        "disable-features",
        "PaintHolding,BackForwardCache",
    )
    // NextSH-owned Chromium profile (--user-data-dir): the shared
    // default CEF directory crashes Chromium 144 on a normal
    // launch. See BrowserProfile.kt.
    browserProfileSwitches(profileDir).forEach { (name, value) ->
        commandLine.appendSwitchWithValue(name, value)
    }
}

class KcefInitializer internal constructor(
    private val bundleDir: File,
    private val profileDir: File?,
    private val coroutineScope: CoroutineScope,
    private val lockProfile: (File) -> LockResult = BrowserProfileLock::tryAcquire,
    private val chromiumStarter: ChromiumStarter = kcefStarter,
    private val awaitEngineReady: suspend () -> Boolean = ::awaitCefAppInitialized,
) {
    private val TAG = "KcefInitializer"

    private val _state = MutableStateFlow<KcefState>(
        KcefState.Initializing(progressPercent = null, message = "Préparation de Chromium…")
    )
    val state: StateFlow<KcefState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)

    fun initialize() {
        if (!started.compareAndSet(false, true)) return

        coroutineScope.launch(Dispatchers.IO) {
            // A previous attempt may have ended on a busy profile: show the neutral
            // state again while this one runs.
            _state.value = KcefState.Initializing(progressPercent = null, message = "Préparation de Chromium…")
            try {
                // Chromium needs a profile directory owned by NextSH, and only one
                // process may use it at a time: see BrowserProfile.kt for the why.
                val profile = profileDir
                if (profile == null) {
                    Logger.e(TAG, "Browser profile directory could not be resolved")
                    _state.value = KcefState.Failed("Le dossier du profil du navigateur n'a pas pu être déterminé.")
                    return@launch
                }
                when (val lockResult = lockProfile(profile)) {
                    LockResult.Acquired -> Unit
                    LockResult.Busy -> {
                        Logger.w(TAG, "Browser profile is in use by another NextSH instance")
                        // Re-armed before the state is published: the user can close the
                        // other instance and reopen the browser without restarting NextSH.
                        started.set(false)
                        _state.value = KcefState.Failed(
                            reason = "Le navigateur intégré est déjà utilisé par une autre instance de NextSH, " +
                                "peut-être réduite dans la zone de notification. " +
                                "Quittez-la, puis rouvrez le navigateur.",
                            title = "Navigateur intégré indisponible",
                        )
                        return@launch
                    }
                    // Best effort: a lock failure must not disable the browser.
                    is LockResult.Error -> Logger.w(
                        TAG,
                        "Browser profile lock unavailable, continuing without it: ${lockResult.reason}",
                    )
                }

                // Set by the callbacks below: the starter returns normally even when
                // Chromium reported an error or asked for a restart.
                val engineFailed = AtomicBoolean(false)
                chromiumStarter.start(
                    builder = {
                        installDir(bundleDir)
                        // Point every native path at the KCEF bundle we installed ourselves
                        // in ~/.nextsh/kcef/. Without this, JCEF finds the jcef_helper.exe
                        // bundled inside the Android Studio JBR (which our JAVA_HOME points
                        // at) and its GPU subprocess fails to launch on Windows with
                        // "GPU process launch failed: error_code=63" → FATAL.
                        settings {
                            browserSubProcessPath = java.io.File(bundleDir, "jcef_helper.exe").absolutePath
                            resourcesDirPath = bundleDir.absolutePath
                            localesDirPath = java.io.File(bundleDir, "locales").absolutePath
                            // OSR custom path: windowlessRenderingEnabled=true is required
                            // for CefBrowserOsrWithHandler to be accepted by JCEF. Without
                            // it, createBrowser() ignores the CefRenderHandler and falls
                            // back to windowed AWT, which re-introduces the white HWND flash.
                            //
                            // JCEF's built-in OSR (CefRendering.OFFSCREEN) uses JOGL, which
                            // on Windows WGL with an HDR10 display hard-selects a GL4/10:10:10:2
                            // profile that AWT cannot bind. We bypass JOGL entirely by
                            // using SoftwareOsrRenderHandler + SoftwareOsrPanel: Chromium
                            // delivers BGRA frames via onPaint() which the panel blits with
                            // Graphics.drawImage(): pure software, no OpenGL dependency.
                            windowlessRenderingEnabled = true
                            backgroundColor = CefSettings().ColorType(0xFF, 0x0F, 0x0F, 0x0F)
                        }
                        // Defence in depth: the switches below are honoured by every child
                        // process (main / renderer / gpu / utility). Software OSR avoids
                        // the JOGL/GPU path, but the GPU subprocess can still be spawned
                        // for video decode or compositing: these switches ensure it exits
                        // cleanly rather than crashing or hanging the renderer.
                        appHandler(object : KCEF.AppHandler() {
                            override fun onBeforeCommandLineProcessing(
                                processType: String?,
                                commandLine: CefCommandLine?,
                            ) {
                                commandLine ?: return
                                configureChromiumCommandLine(commandLine, profile)
                            }
                        })
                        progress {
                            onLocating {
                                _state.value = KcefState.Initializing(
                                    progressPercent = null,
                                    message = "Localisation de Chromium…"
                                )
                            }
                            onDownloading { percent ->
                                _state.value = KcefState.Initializing(
                                    progressPercent = (percent * 100f).roundToInt().coerceIn(0, 100),
                                    message = "Téléchargement de Chromium…"
                                )
                            }
                            onExtracting {
                                _state.value = KcefState.Initializing(
                                    progressPercent = null,
                                    message = "Extraction de Chromium…"
                                )
                            }
                            onInstall {
                                _state.value = KcefState.Initializing(
                                    progressPercent = null,
                                    message = "Installation de Chromium…"
                                )
                            }
                            onInitializing {
                                _state.value = KcefState.Initializing(
                                    progressPercent = null,
                                    message = "Initialisation de Chromium…"
                                )
                            }
                        }
                    },
                    onError = { throwable ->
                        engineFailed.set(true)
                        val reason = throwable?.message ?: "Erreur inconnue"
                        Logger.e(TAG, "KCEF init failed: $reason")
                        _state.value = KcefState.Failed(reason)
                    },
                    onRestartRequired = {
                        engineFailed.set(true)
                        Logger.w(TAG, "KCEF requires app restart to complete Chromium setup")
                        _state.value = KcefState.Failed(
                            "Un redémarrage de l'application est nécessaire pour finaliser l'initialisation de Chromium."
                        )
                    }
                )
                if (engineFailed.get()) return@launch

                // Ready only once Chromium is really initialized: consumers create
                // request contexts as soon as they see Ready, and that fails while
                // CefApp is still NEW.
                if (!awaitEngineReady()) {
                    Logger.e(TAG, "Chromium did not finish initializing within ${CEF_INIT_TIMEOUT_MS / 1000} s")
                    _state.value = KcefState.Failed(
                        "Chromium n'a pas terminé son initialisation. Réessayez ou redémarrez NextSH."
                    )
                    return@launch
                }
                _state.value = KcefState.Ready
                Logger.d(TAG, "KCEF ready")
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                Logger.e(TAG, "KCEF init exception: $reason")
                _state.value = KcefState.Failed(reason)
            }
        }
    }
}
