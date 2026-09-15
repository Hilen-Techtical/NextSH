// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.model

import fr.techtical.nextsh.shared.util.randomUuid
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

// ── Host ──────────────────────────────────────────────────────────────────────
@Serializable
data class Host(
    val id          : String,
    val label       : String,
    val hostname    : String,
    val port        : Int           = 22,
    val username    : String,
    val authType    : AuthType,
    val credentialId: String,       // référence vers EncryptedPrefs
    val group       : String?       = null,
    val keepAliveSeconds: Int       = 30,
    val autoReconnect: Boolean      = true,
    val terminalTheme: String       = "TECHTICAL_DARK",
    val fido2Mode: Fido2Mode?       = null,
    val isFavorite: Boolean         = false,
)

@Serializable
enum class AuthType {
    PASSWORD,
    SSH_KEY,
    CERTIFICATE,
    FIDO2,
    BIOMETRIC_KEY,  // clé liée au Keystore biométrique
}

@Serializable
enum class Fido2Mode {
    HARDWARE_KEY,  // USB/NFC physical security key (CTAP2)
    PASSKEY,       // Credential Manager passkey → unlock SSH key
}

// ── SSH Key ───────────────────────────────────────────────────────────────────
@Serializable
data class SshKey(
    val id          : String,
    val label       : String,
    val keyType     : SshKeyType,
    val publicKey   : String,       // format OpenSSH
    val isBiometric : Boolean = false,
    val keystoreAlias: String? = null,
    val fido2CredentialId: String? = null,
    val fido2RpId: String? = null,
)

@Serializable
enum class SshKeyType {
    ED25519, RSA_4096, ECDSA_256, ECDSA_384, ECDSA_521,
    SK_ED25519,
    SK_ECDSA_256,
}

// ── Tunnel ────────────────────────────────────────────────────────────────────
@Serializable
data class TunnelConfig(
    val id          : String,
    val label       : String,
    val hostId      : String,       // référence Host
    val type        : TunnelType,
    val localPort   : Int,
    val remoteHost  : String,
    val remotePort  : Int,
    val autoStart   : Boolean = false,
    val openBrowserOnConnect: Boolean = false,
    val keepAliveAfterBrowserClose: Boolean = true,
    val isFavorite: Boolean = false,
)

@Serializable
enum class TunnelType {
    LOCAL_FORWARD,   // -L : localhost:localPort → remoteHost:remotePort
    REMOTE_FORWARD,  // -R : remoteHost:remotePort → localhost:localPort
    DYNAMIC_SOCKS5,  // -D : proxy SOCKS5 local
}

// ── Session état runtime (non persisté) ───────────────────────────────────────
data class SshSession(
    val id          : String,
    val host        : Host,
    val status      : SessionStatus,
    val connectedAt : Long? = null,
)

enum class SessionStatus {
    CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR
}

// ── Tunnel état runtime (non persisté) ────────────────────────────────────────
data class TunnelState(
    val config      : TunnelConfig,
    val status      : TunnelStatus,
    val errorMessage: String? = null,
)

enum class TunnelStatus {
    STARTING, ACTIVE, RECONNECTING, STOPPED, ERROR
}

// ── Résultat sealed : jamais d'exception non catchée vers l'UI ────────────────
sealed class SshResult<out T> {
    data class Success<T>(val data: T) : SshResult<T>()
    data class Error(val code: SshErrorCode, val message: String) : SshResult<Nothing>()
}

enum class SshErrorCode {
    AUTH_FAILED,
    HOST_UNREACHABLE,
    HOST_KEY_MISMATCH,
    PORT_IN_USE,
    TIMEOUT,
    UNKNOWN,
    FIDO2_NO_KEY,
    FIDO2_USER_CANCELLED,
    FIDO2_TIMEOUT,
    AUTH_EXPIRED,
}

// ── Snippet ───────────────────────────────────────────────────────────────────
@Serializable
data class Snippet(
    val id: String = randomUuid(),
    val label: String,
    val command: String,
    val category: String? = null,
    val hostId: String? = null,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
)

// ── Custom terminal theme ───────────────────────────────────────────────────────
/**
 * A user-defined terminal color palette, CRDT-synced like [Snippet] (metadata only).
 *
 * Referenced by [Host.terminalTheme]: that field holds either a preset enum NAME
 * (e.g. "DRACULA") or the UUID [id] of a custom theme. UUIDs never collide with
 * enum names, so the resolver can disambiguate without a schema change.
 *
 * [ansi] MUST contain exactly 16 entries (standard ANSI 0..15). Validate on save.
 * Colors are stored as ARGB ints (e.g. 0xFF0F0F0F).
 */
@Serializable
data class CustomTerminalTheme(
    val id: String = randomUuid(),
    val name: String,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selectionBg: Int,
    val ansi: List<Int>,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
) {
    companion object {
        const val ANSI_SIZE = 16
    }
}

// ── SFTP ─────────────────────────────────────────────────────────────────────

data class SftpFile(
    val name: String,
    val path: String,           // always absolute, sanitized
    val size: Long,
    val permissions: Int,       // octal (e.g. 0o755 = 493)
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val symlinkTarget: String? = null,
    val modifiedAt: Long = 0L,  // epoch millis
    val owner: String = "",
    val group: String = "",
) {
    val permissionsString: String
        get() {
            val sb = StringBuilder(9)
            for (i in 8 downTo 0) {
                val bit = (permissions shr i) and 1
                sb.append(
                    when {
                        bit == 0 -> '-'
                        i % 3 == 2 -> 'r'
                        i % 3 == 1 -> 'w'
                        else -> 'x'
                    }
                )
            }
            return sb.toString()
        }

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val isPreviewable: Boolean
        get() = isTextFile || isImageFile

    val isTextFile: Boolean
        get() = extension in TEXT_EXTENSIONS

    val isImageFile: Boolean
        get() = extension in IMAGE_EXTENSIONS

    companion object {
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "log", "json", "xml", "yml", "yaml", "toml",
            "csv", "conf", "cfg", "ini", "properties", "env",
            "sh", "bash", "zsh", "fish",
            "py", "rb", "js", "ts", "kt", "kts", "java", "c", "cpp", "h", "hpp",
            "go", "rs", "swift", "php", "pl", "lua", "r", "sql",
            "html", "css", "scss", "less",
            "makefile", "dockerfile", "gitignore", "gitattributes",
        )
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg")
    }
}

enum class SortOrder {
    NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC
}

data class TransferRequest(
    val id: String = randomUuid(),
    val sessionId: String,
    val remotePath: String,
    val localUri: String,       // SAF content:// URI
    val direction: TransferDirection,
    val fileSize: Long,
    val displayName: String,
    val hostLabel: String? = null,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
)

enum class TransferDirection { UPLOAD, DOWNLOAD }

sealed class TransferState {
    abstract val request: TransferRequest
    data class Queued(override val request: TransferRequest) : TransferState()
    data class InProgress(override val request: TransferRequest, val bytesTransferred: Long, val totalBytes: Long) : TransferState()
    data class Completed(override val request: TransferRequest) : TransferState()
    data class Failed(override val request: TransferRequest, val error: String) : TransferState()
    data class Cancelled(override val request: TransferRequest) : TransferState()
}
