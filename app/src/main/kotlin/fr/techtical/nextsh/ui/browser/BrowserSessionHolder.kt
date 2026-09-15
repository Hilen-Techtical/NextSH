// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.browser

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface BrowserSessionHolderProvider {
    fun sessionHolder(): BrowserSessionHolder
}

@Singleton
class BrowserSessionHolder @Inject constructor() {

    private val _visibleTunnelId = MutableStateFlow<String?>(null)
    val visibleTunnelId: StateFlow<String?> = _visibleTunnelId.asStateFlow()

    private val _activeTunnelId = MutableStateFlow<String?>(null)
    val activeTunnelId: StateFlow<String?> = _activeTunnelId.asStateFlow()

    private var _webView: WebView? = null

    fun show(tunnelId: String) {
        _activeTunnelId.value = tunnelId
        _visibleTunnelId.value = tunnelId
    }

    fun hide() {
        _visibleTunnelId.value = null
    }

    fun hasSession(): Boolean = _activeTunnelId.value != null

    fun hasSession(tunnelId: String): Boolean = _activeTunnelId.value == tunnelId

    fun setWebView(webView: WebView) {
        _webView = webView
    }

    /**
     * Rend le WebView conservé, prêt à être rattaché, ou null s'il faut le recréer.
     *
     * Ce singleton survit à la destruction de l'activité, contrairement au
     * WebView qu'il détient : celui-ci est construit avec le contexte de
     * l'activité et reste rattaché à une hiérarchie de vues morte. Le
     * réutiliser tel quel lève « The specified child already has a parent » et
     * retient l'activité détruite en mémoire.
     *
     * Deux cas :
     *  - même activité, cas de la réduction puis restauration : on détache le
     *    WebView de son parent et on le rend, l'état de la page est préservé ;
     *  - activité recréée : on le détruit et on rend null, l'appelant en
     *    fabrique un neuf qui rechargera l'URL.
     *
     * @param host contexte de l'activité qui va l'accueillir.
     */
    fun acquireWebView(host: Context): WebView? {
        val cached = _webView ?: return null
        if (cached.context !== host) {
            try {
                cached.stopLoading()
                cached.destroy()
            } catch (_: Exception) { }
            _webView = null
            return null
        }
        (cached.parent as? ViewGroup)?.removeView(cached)
        return cached
    }

    /** Détache le WebView de sa hiérarchie sans le détruire, pour pouvoir le rattacher. */
    fun detachWebView() {
        val cached = _webView ?: return
        (cached.parent as? ViewGroup)?.removeView(cached)
    }

    fun close() {
        _visibleTunnelId.value = null
        _activeTunnelId.value = null
        try {
            _webView?.stopLoading()
            _webView?.destroy()
        } catch (_: Exception) { }
        _webView = null
    }
}
