// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.scope

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Base of the ViewModels that belong to ONE screen: created when the screen enters the composition, closed
 * when it leaves it (see [rememberScreenViewModel]).
 *
 * The rule, which is the reason for this class:
 *  - [appScope] lives as long as the app (cancelled at exit only). It must NOT host anything that observes
 *    for the sake of a screen: a `stateIn(appScope.coroutineScope, Eagerly, ...)` over a database flow, or a
 *    collector started in `init`, is never cancelled, so every visit of the screen leaves one more database
 *    listener behind (each re-runs its SELECT on every write to the table) and retains the ViewModel. In an
 *    app that stays in the tray for days the cost has no bound.
 *  - [screenScope] is for those observers: `stateIn(screenScope, ...)`, `screenScope.launch { flow.collect }`.
 *    It is a child of [appScope], so quitting the app still cancels it, and [close] cancels it when the
 *    screen is left.
 *  - A user action that must finish even if the user navigates away right after (start or stop of tunnels,
 *    save, delete, wipe, unlock, import, export) stays on `appScope.coroutineScope`. Cancelling it with the
 *    screen would abandon a write half done.
 *
 * Whatever the ViewModel loads for its own display (a `load()` that fills the form) is screen work too:
 * nobody reads the result once the screen is gone.
 *
 * A closed ViewModel is not reused: the next visit builds a new one.
 *
 * It is also a [RememberObserver]: once it is held by `remember`, the Compose runtime closes it when it is
 * forgotten (the screen leaves the composition, or the keys of the `remember` change) and when it is
 * abandoned (the composition that built it was thrown away before being applied, in which case no effect
 * ever ran, so an effect-based close would have missed it). Nothing else has to call [close].
 */
abstract class ScreenViewModel(protected val appScope: AppScope) : RememberObserver {

    private val screenJob: Job = SupervisorJob(appScope.coroutineScope.coroutineContext[Job])

    /**
     * Scope of the observers of this screen: the context of the app scope (same dispatcher) under its own
     * supervisor job, child of the app scope's job. Cancelled by [close].
     */
    protected val screenScope: CoroutineScope =
        CoroutineScope(appScope.coroutineScope.coroutineContext + screenJob)

    /**
     * Cancels every observer and every coroutine started in [screenScope]. Idempotent. Work started in the
     * app scope is not touched. Called by the Compose runtime when the screen leaves the composition (see
     * [RememberObserver]); callable by hand for a ViewModel that is not held by `remember`.
     */
    fun close() {
        screenJob.cancel()
    }

    final override fun onRemembered() = Unit

    final override fun onForgotten() = close()

    final override fun onAbandoned() = close()
}

/**
 * Remembers a [ScreenViewModel] for the lifetime of the calling composable. It is closed when the composable
 * leaves the composition, when [keys] change (a new ViewModel replaces it), or when the composition is
 * abandoned: see [ScreenViewModel].
 *
 * ```
 * val viewModel = rememberScreenViewModel { HostViewModel() }
 * ```
 */
@Composable
inline fun <T : ScreenViewModel> rememberScreenViewModel(
    vararg keys: Any?,
    crossinline factory: () -> T,
): T = remember(*keys) { factory() }
