// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

import io.mockk.MockKException

/**
 * Polls [block] (typically a MockK `verify { … }` / `coVerify { … }`, or any
 * assertion that depends on a background coroutine completing) until it succeeds
 * or [timeoutMs] elapses.
 *
 * Several NextSH ViewModels dispatch their work on a real [kotlinx.coroutines.Dispatchers.IO]
 * thread (`viewModelScope.launch(Dispatchers.IO)`), which escapes the
 * `UnconfinedTestDispatcher` installed on Main. Setting Main to a test dispatcher
 * therefore does NOT make the IO coroutine run synchronously, so `advanceUntilIdle()`
 * cannot drain it. An immediate `verify { … }` right after the call races that IO
 * thread (passes warm/isolated, flakes under full-suite load).
 *
 * Wrapping such assertions in [awaitVerified] removes the race deterministically:
 * it returns the instant the verification passes (the moment the IO coroutine
 * completes), and only rethrows the genuine failure if the expected interaction
 * never occurs within the (generous) timeout. No assertion is ever weakened: if
 * the call genuinely never happens, the verification fails exactly as it would
 * synchronously.
 */
internal fun awaitVerified(timeoutMs: Long = 2_000, block: () -> Unit) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            block()
            return
        } catch (e: MockKException) {
            last = e
            Thread.sleep(5)
        } catch (e: AssertionError) {
            last = e
            Thread.sleep(5)
        }
    }
    // Final attempt: let the genuine failure propagate with full MockK detail.
    try {
        block()
    } catch (e: Throwable) {
        throw last ?: e
    }
}
