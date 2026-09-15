// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.browser

import dev.datlag.kcef.KCEF
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.cef.CefSettings
import org.cef.callback.CefCommandLine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

sealed interface KcefState {
    data class Initializing(val progressPercent: Int? = null, val message: String) : KcefState
    data object Ready : KcefState
    data class Failed(val reason: String) : KcefState
}

class KcefInitializer(
    private val bundleDir: File,
    private val coroutineScope: CoroutineScope,
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
            try {
                KCEF.init(
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
                        val reason = throwable?.message ?: "Erreur inconnue"
                        Logger.e(TAG, "KCEF init failed: $reason")
                        _state.value = KcefState.Failed(reason)
                    },
                    onRestartRequired = {
                        Logger.w(TAG, "KCEF requires app restart to complete Chromium setup")
                        _state.value = KcefState.Failed(
                            "Un redémarrage de l'application est nécessaire pour finaliser l'initialisation de Chromium."
                        )
                    }
                )
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
