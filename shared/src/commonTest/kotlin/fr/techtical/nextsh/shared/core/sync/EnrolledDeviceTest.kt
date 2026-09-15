// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnrolledDeviceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `enrolled device serializes tls fingerprint when present`() {
        val fingerprint = "a".repeat(64)
        val device = EnrolledDevice(
            deviceId = "desktop-1",
            deviceName = "My Desktop",
            platform = Platform.DESKTOP,
            publicKeyFingerprint = "0102030405060708",
            tlsCertFingerprint = fingerprint,
            lastSyncAt = null,
            enrolledAt = 1_700_000_000_000L,
            lastKnownHost = "192.168.1.42",
        )

        val encoded = json.encodeToString(device)
        val decoded = json.decodeFromString<EnrolledDevice>(encoded)

        assertEquals(fingerprint, decoded.tlsCertFingerprint)
        assertEquals(Platform.DESKTOP, decoded.platform)
        assertEquals("192.168.1.42", decoded.lastKnownHost)
    }

    @Test
    fun `enrolled device serializes null tls fingerprint for Android platform`() {
        val device = EnrolledDevice(
            deviceId = "android-1",
            deviceName = "My Phone",
            platform = Platform.ANDROID,
            publicKeyFingerprint = "0102030405060708",
            tlsCertFingerprint = null,
            lastSyncAt = null,
            enrolledAt = 1_700_000_000_000L,
            lastKnownHost = null,
        )

        val encoded = json.encodeToString(device)
        val decoded = json.decodeFromString<EnrolledDevice>(encoded)

        assertNull(decoded.tlsCertFingerprint)
        assertNull(decoded.lastKnownHost)
        assertEquals(Platform.ANDROID, decoded.platform)
    }

    @Test
    fun `enrolled device defaults lastKnownHost to null when missing from json`() {
        // JSON without lastKnownHost field: simulates old serialized data
        val rawJson = """{"deviceId":"old-device","deviceName":"Old","platform":"DESKTOP",""" +
            """"publicKeyFingerprint":"abcd","tlsCertFingerprint":null,"lastSyncAt":null,"enrolledAt":1700000000000}"""
        val decoded = json.decodeFromString<EnrolledDevice>(rawJson)

        assertNull(decoded.lastKnownHost)
        assertEquals("old-device", decoded.deviceId)
    }
}
