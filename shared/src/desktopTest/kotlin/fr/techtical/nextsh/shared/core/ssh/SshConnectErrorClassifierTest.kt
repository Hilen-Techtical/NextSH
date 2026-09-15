// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * [classifySshConnectError] is the single, shared decision table behind both
 * platform mappers (`app/.../core/ssh/SshConnectErrorMapper.kt` and
 * `desktop/.../core/ssh/SshConnectErrorMapper.kt`): moved here (`:shared`,
 * source set `jvmCommon`) so a single test suite locks in the exception →
 * category → [SshErrorCode] mapping for BOTH platforms at once.
 *
 * Regression coverage for the Desktop/Android asymmetry: Desktop used to
 * code every network failure as `SshErrorCode.HOST_UNREACHABLE`, including
 * timeouts, while Android already distinguished `TIMEOUT`. Each test below
 * asserts the returned [SshErrorCode] (not just the message category) so a
 * regression back to the old "everything is HOST_UNREACHABLE" behavior would
 * fail here immediately.
 */
class SshConnectErrorClassifierTest {

    @Test
    fun `SocketTimeoutException maps to TimeoutConnect with SshErrorCode TIMEOUT`() {
        val classification = classifySshConnectError(
            SocketTimeoutException("connect timed out"),
            connectTimeoutMs = 10_000,
            genericErrorCode = SshErrorCode.HOST_UNREACHABLE,
        )
        val timeout = assertIs<SshConnectErrorClassification.TimeoutConnect>(classification)
        assertEquals(10, timeout.timeoutSeconds)
        assertEquals(SshErrorCode.TIMEOUT, classification.errorCode)
    }

    @Test
    fun `SocketTimeoutException rounds a non-exact ms value up to the next second`() {
        val classification = classifySshConnectError(
            SocketTimeoutException(),
            connectTimeoutMs = 4_500,
            genericErrorCode = SshErrorCode.HOST_UNREACHABLE,
        )
        val timeout = assertIs<SshConnectErrorClassification.TimeoutConnect>(classification)
        assertEquals(5, timeout.timeoutSeconds)
    }

    @Test
    fun `SocketTimeoutException never reports zero seconds even for a near-zero timeout`() {
        val classification = classifySshConnectError(
            SocketTimeoutException(),
            connectTimeoutMs = 0,
            genericErrorCode = SshErrorCode.HOST_UNREACHABLE,
        )
        val timeout = assertIs<SshConnectErrorClassification.TimeoutConnect>(classification)
        assertEquals(1, timeout.timeoutSeconds)
    }

    @Test
    fun `SocketTimeoutException maps to TIMEOUT regardless of the platform's generic fallback`() {
        // The generic fallback (HOST_UNREACHABLE on Desktop, UNKNOWN on Android)
        // must never leak into the Timeout category: TIMEOUT is unconditional.
        val desktopSide = classifySshConnectError(SocketTimeoutException(), 10_000, SshErrorCode.HOST_UNREACHABLE)
        val androidSide = classifySshConnectError(SocketTimeoutException(), 10_000, SshErrorCode.UNKNOWN)
        assertEquals(SshErrorCode.TIMEOUT, desktopSide.errorCode)
        assertEquals(SshErrorCode.TIMEOUT, androidSide.errorCode)
    }

    @Test
    fun `ConnectException maps to ServerSilent with SshErrorCode HOST_UNREACHABLE regardless of message`() {
        val classification = classifySshConnectError(
            ConnectException("Connection refused: connect"),
            connectTimeoutMs = 10_000,
            genericErrorCode = SshErrorCode.UNKNOWN,
        )
        assertIs<SshConnectErrorClassification.ServerSilent>(classification)
        assertEquals(SshErrorCode.HOST_UNREACHABLE, classification.errorCode)
    }

    @Test
    fun `UnknownHostException maps to HostNotFound with SshErrorCode UNKNOWN regardless of message`() {
        // UNKNOWN here reproduces Android's pre-existing behaviour for this
        // exception type (it fell through to the generic catch-all before this
        // classifier existed): there is no dedicated enum value for it.
        val classification = classifySshConnectError(
            UnknownHostException("nope.invalid"),
            connectTimeoutMs = 10_000,
            genericErrorCode = SshErrorCode.HOST_UNREACHABLE,
        )
        assertIs<SshConnectErrorClassification.HostNotFound>(classification)
        assertEquals(SshErrorCode.UNKNOWN, classification.errorCode)
    }

    @Test
    fun `other IOException maps to Generic with the caller-provided genericErrorCode and keeps the detail`() {
        val desktopSide = classifySshConnectError(
            IOException("Broken transport; encountered EOF"),
            connectTimeoutMs = 10_000,
            genericErrorCode = SshErrorCode.HOST_UNREACHABLE,
        )
        val generic = assertIs<SshConnectErrorClassification.Generic>(desktopSide)
        assertEquals("Broken transport; encountered EOF", generic.detail)
        assertEquals(SshErrorCode.HOST_UNREACHABLE, desktopSide.errorCode)

        val androidSide = classifySshConnectError(
            IOException("Broken transport; encountered EOF"),
            connectTimeoutMs = 10_000,
            genericErrorCode = SshErrorCode.UNKNOWN,
        )
        assertEquals(SshErrorCode.UNKNOWN, androidSide.errorCode)
    }

    @Test
    fun `other IOException with a blank or null message drops the detail`() {
        val blank = assertIs<SshConnectErrorClassification.Generic>(
            classifySshConnectError(IOException("   "), 10_000, SshErrorCode.UNKNOWN),
        )
        assertNull(blank.detail)

        val noMessage = assertIs<SshConnectErrorClassification.Generic>(
            classifySshConnectError(IOException(), 10_000, SshErrorCode.UNKNOWN),
        )
        assertNull(noMessage.detail)
    }
}
