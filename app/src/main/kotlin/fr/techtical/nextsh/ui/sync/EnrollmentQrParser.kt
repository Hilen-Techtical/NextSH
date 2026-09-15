// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import android.net.Uri

/**
 * Parse un QR d'enrôlement Desktop au format :
 *   nextsh://enroll?addr=<IP>:<PORT>&fp=<fingerprint>
 *
 * Retourne null si le format est invalide ou si un champ requis manque.
 */
object EnrollmentQrParser {

    data class Parsed(
        val host: String,
        val port: Int,
        val fingerprint: String,
    )

    fun parse(raw: String): Parsed? {
        return try {
            val uri = Uri.parse(raw) ?: return null
            if (uri.scheme != "nextsh") return null
            if (uri.host != "enroll") return null

            val addr = uri.getQueryParameter("addr") ?: return null
            val fp = uri.getQueryParameter("fp") ?: return null

            if (fp.isBlank()) return null

            val colonIdx = addr.lastIndexOf(':')
            if (colonIdx < 1) return null

            val host = addr.substring(0, colonIdx)
            val port = addr.substring(colonIdx + 1).toIntOrNull() ?: return null

            if (host.isBlank()) return null
            if (port !in 1..65535) return null

            Parsed(host = host, port = port, fingerprint = fp)
        } catch (_: Exception) {
            null
        }
    }
}
