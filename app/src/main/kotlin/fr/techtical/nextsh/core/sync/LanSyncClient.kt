// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.shared.core.sync.ApplyResult
import fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.HmacSigner
import fr.techtical.nextsh.shared.core.sync.MergeResult
import fr.techtical.nextsh.shared.core.sync.SyncBundleCodec
import fr.techtical.nextsh.shared.core.sync.SyncError
import fr.techtical.nextsh.shared.core.sync.SyncPayload
import fr.techtical.nextsh.shared.core.sync.SyncProtocol
import fr.techtical.nextsh.shared.core.sync.SyncRepository
import fr.techtical.nextsh.shared.core.sync.SyncResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext

private const val TAG = "LanSyncClient"
private const val SYNC_PORT = 47731
private const val CONNECT_TIMEOUT_SEC = 5L
private const val READ_TIMEOUT_SEC = 30L
private const val WRITE_TIMEOUT_SEC = 30L
private const val MAX_RETRIES = 3

private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)

/** Best-effort hostname probe timeout: kept short since it is tried before LAN discovery. */
private const val HOSTNAME_PROBE_TIMEOUT_MS = 1_500L

/** Per-candidate `/sync/status` validation timeout during LAN discovery resolution. */
private const val DISCOVERY_VALIDATE_TIMEOUT_MS = 1_500L

/** Conservative RFC 1123 hostname/label check: rejects device names containing spaces or other non-hostname characters. */
private val PLAUSIBLE_HOSTNAME_REGEX =
    Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

/**
 * Android-side LAN sync client.
 *
 * Sends HMAC-signed, AES-GCM-encrypted [SyncPayload] requests to the Desktop
 * [LanSyncServer] over TLS with strict certificate pinning.
 *
 * Retry policy: up to [MAX_RETRIES] attempts with exponential back-off (1s/2s/4s)
 * on [IOException] / [SocketTimeoutException] only. HTTP 4xx/5xx are not retried.
 */
@Singleton
class LanSyncClient @Inject constructor(
    private val syncRepository: SyncRepository,
    private val credentialSyncRepository: CredentialSyncRepository,
    private val secretStore: EnrolledDeviceSecretStore,
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val networkMonitor: NetworkMonitor,
    private val lanDiscoveryClient: LanDiscoveryClient,
) : SyncProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    // ── SyncProtocol ─────────────────────────────────────────────────────────

    override suspend fun push(device: EnrolledDevice): SyncResult {
        if (!networkMonitor.isOnLanTransport.value) return SyncResult.DeviceUnreachable

        val host = device.lastKnownHost ?: return SyncResult.Error(
            SyncError.UNKNOWN,
            "Device config incomplete: lastKnownHost is null",
        )
        val fingerprint = device.tlsCertFingerprint ?: return SyncResult.Error(
            SyncError.UNKNOWN,
            "Device config incomplete: tlsCertFingerprint is null",
        )

        val secret = secretStore.retrieve(device.deviceId) ?: return SyncResult.AuthFailed

        return try {
            val baseBundle = syncRepository.getAllLocalAsBundle()
            // Credentials are best-effort: a failure to export (e.g. vault locked,
            // meta store IO error) must not abort the whole sync: log and continue
            // with an empty credential list.
            val credentialEntries = try {
                credentialSyncRepository.exportEncryptedEntries(secret)
            } catch (e: Exception) {
                Timber.w(e, "Sync push: credential export failed for ${device.deviceId}, skipping credentials")
                emptyList()
            }
            val bundle = baseBundle.copy(credentials = credentialEntries)
            val payload = SyncBundleCodec.encrypt(bundle, syncRepository.deviceId(), secret)
            val bodyBytes = json.encodeToString(SyncPayload.serializer(), payload).toByteArray(Charsets.UTF_8)

            val now = System.currentTimeMillis()
            val path = "/sync/push"
            val signature = HmacSigner.sign("POST", path, now, bodyBytes, secret)

            val httpClient = buildHttpClient(fingerprint)
            try {
                val response = executeWithRetry(MAX_RETRIES) {
                    httpClient.post("https://$host:$SYNC_PORT$path") {
                        contentType(ContentType.Application.Json)
                        header("X-NextSH-Device-Id", DeviceIdentity.deviceId())
                        header("X-NextSH-Timestamp", now.toString())
                        header("X-NextSH-Signature", signature)
                        setBody(bodyBytes)
                    }
                }

                when (response.status.value) {
                    in 200..299 -> {
                        // Consume response body to drain the HTTP stream; server ack details not needed client-side.
                        response.body<PushAck>()
                        val syncedAt = System.currentTimeMillis()
                        enrolledDeviceRepository.updateLastSyncAt(device.deviceId, syncedAt)
                        SyncResult.Success(
                            pushedCount = bundle.totalCount,
                            pulledCount = 0,
                            conflicts = emptyList(),
                            syncedAt = syncedAt,
                        )
                    }
                    401 -> SyncResult.AuthFailed
                    in 500..599 -> SyncResult.Error(SyncError.SERVER_ERROR)
                    else -> SyncResult.Error(SyncError.UNKNOWN, "Unexpected HTTP ${response.status.value}")
                }
            } finally {
                httpClient.close()
            }
        } catch (e: SocketTimeoutException) {
            Timber.w(e, "Sync push timeout for ${device.deviceId}: ${e.message}")
            SyncResult.Error(SyncError.NETWORK_TIMEOUT, "Délai dépassé ($host:$SYNC_PORT)")
        } catch (e: IOException) {
            classifyIoError("push", device.deviceId, host, e)
        } catch (e: kotlinx.serialization.SerializationException) {
            Timber.w(e, "Sync push serialization error for ${device.deviceId}")
            SyncResult.Error(SyncError.UNKNOWN, "Serialisation: ${e.message}")
        } finally {
            secret.fill(0)
        }
    }

    override suspend fun pull(device: EnrolledDevice): SyncResult {
        if (!networkMonitor.isOnLanTransport.value) return SyncResult.DeviceUnreachable

        val host = device.lastKnownHost ?: return SyncResult.Error(
            SyncError.UNKNOWN,
            "Device config incomplete: lastKnownHost is null",
        )
        val fingerprint = device.tlsCertFingerprint ?: return SyncResult.Error(
            SyncError.UNKNOWN,
            "Device config incomplete: tlsCertFingerprint is null",
        )

        val secret = secretStore.retrieve(device.deviceId) ?: return SyncResult.AuthFailed

        return try {
            val bodyBytes = ByteArray(0)
            val now = System.currentTimeMillis()
            val path = "/sync/pull"
            val signature = HmacSigner.sign("GET", path, now, bodyBytes, secret)

            val httpClient = buildHttpClient(fingerprint)
            try {
                val response = executeWithRetry(MAX_RETRIES) {
                    httpClient.get("https://$host:$SYNC_PORT$path") {
                        header("X-NextSH-Device-Id", DeviceIdentity.deviceId())
                        header("X-NextSH-Timestamp", now.toString())
                        header("X-NextSH-Signature", signature)
                    }
                }

                when (response.status.value) {
                    in 200..299 -> {
                        val payload = response.body<SyncPayload>()
                        val bundle = try {
                            SyncBundleCodec.decrypt(payload, secret)
                        } catch (e: Exception) {
                            Timber.w(TAG, "Sync pull decrypt failed for ${device.deviceId}")
                            return SyncResult.Error(SyncError.DECRYPT_FAILED)
                        }
                        val applyResult: ApplyResult = syncRepository.applyRemoteBundle(bundle)
                        // Credentials applied after regular entities so their owning Hosts/SshKeys
                        // already exist locally before the secret lands in the vault. A credential
                        // apply failure (decrypt, IO) is swallowed so that regular-entity sync
                        // still succeeds: graceful degradation.
                        val credConflicts: List<MergeResult.Conflict<*>> = try {
                            credentialSyncRepository.applyRemoteEntries(bundle.credentials, secret).conflicts
                        } catch (e: Exception) {
                            Timber.w(e, "Sync pull: credential apply failed for ${device.deviceId}")
                            emptyList()
                        }
                        val syncedAt = System.currentTimeMillis()
                        enrolledDeviceRepository.updateLastSyncAt(device.deviceId, syncedAt)
                        SyncResult.Success(
                            pushedCount = 0,
                            pulledCount = bundle.totalCount,
                            conflicts = applyResult.conflicts + credConflicts,
                            syncedAt = syncedAt,
                        )
                    }
                    401 -> SyncResult.AuthFailed
                    in 500..599 -> SyncResult.Error(SyncError.SERVER_ERROR)
                    else -> SyncResult.Error(SyncError.UNKNOWN, "Unexpected HTTP ${response.status.value}")
                }
            } finally {
                httpClient.close()
            }
        } catch (e: SocketTimeoutException) {
            Timber.w(e, "Sync pull timeout for ${device.deviceId}: ${e.message}")
            SyncResult.Error(SyncError.NETWORK_TIMEOUT, "Délai dépassé ($host:$SYNC_PORT)")
        } catch (e: IOException) {
            classifyIoError("pull", device.deviceId, host, e)
        } catch (e: kotlinx.serialization.SerializationException) {
            Timber.w(e, "Sync pull serialization error for ${device.deviceId}")
            SyncResult.Error(SyncError.UNKNOWN, "Serialisation: ${e.message}")
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Runs a push+pull cycle against [device], resolving a fresh LAN address first when
     * needed (C4 discovery): [device] has no [EnrolledDevice.lastKnownHost] yet, or the
     * cycle against the known host fails specifically because the host is unreachable.
     *
     * At most one discovery attempt happens per call: [push] and [pull] never trigger
     * their own, whether called from here or directly by another caller.
     */
    override suspend fun fullSync(device: EnrolledDevice): SyncResult {
        // Transport gate first: [push]/[pull] each carry the same check, but the discovery
        // resolution path below sits outside them. Without this early return, a manual
        // "search on network" on a device with no lastKnownHost would broadcast UDP probes
        // while on cellular: off-LAN traffic the transport gate exists to prevent.
        if (!networkMonitor.isOnLanTransport.value) return SyncResult.DeviceUnreachable

        val initialResult = if (device.lastKnownHost != null) attemptSyncCycle(device) else null
        if (initialResult is SyncResult.Success) return initialResult
        if (initialResult != null && !isHostUnreachable(initialResult)) return initialResult

        val resolvedHost = resolveHost(device) ?: return initialResult ?: SyncResult.DeviceUnreachable
        val retryResult = attemptSyncCycle(device.copy(lastKnownHost = resolvedHost))
        persistResolvedHostIfSuccessful(device.deviceId, resolvedHost, retryResult)
        return retryResult
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun attemptSyncCycle(device: EnrolledDevice): SyncResult {
        val pulled = pull(device)
        if (pulled !is SyncResult.Success) return pulled
        val pushed = push(device)
        return if (pushed is SyncResult.Success) {
            SyncResult.Success(
                pushedCount = pushed.pushedCount,
                pulledCount = pulled.pulledCount,
                conflicts = pulled.conflicts + pushed.conflicts,
                syncedAt = System.currentTimeMillis(),
            )
        } else {
            pushed
        }
    }

    /**
     * True when [result] means "the stored address led nowhere": the only failure class that
     * justifies re-resolving the peer's address. Matched on the machine-readable
     * [SyncError.HOST_UNREACHABLE] reason, never on the user-facing message: a localized string
     * is not a protocol, and coupling the two silently disabled re-discovery whenever the
     * wording changed.
     *
     * Deliberately excludes [SyncError.NETWORK_TIMEOUT]: a read/write timeout means a live
     * server accepted the connection, so the address is correct and discovery would be noise.
     */
    internal fun isHostUnreachable(result: SyncResult): Boolean =
        result is SyncResult.Error && result.reason == SyncError.HOST_UNREACHABLE

    /**
     * SECURITY INVARIANT: [EnrolledDeviceRepository.updateLastKnownHost] is called ONLY
     * when [result] is [SyncResult.Success], i.e. only after a full authenticated
     * HTTPS+HMAC round-trip against [resolvedHost] actually succeeded. Never call this
     * from the UDP discovery ACK alone: that datagram is far cheaper for an on-LAN
     * attacker to forge than a TLS-pinned, HMAC-signed sync exchange, so treating it as
     * sufficient proof of address would let such an attacker redirect future syncs.
     * Kept as its own function (rather than inlined in [fullSync]) so this invariant has
     * a single, directly unit-testable choke point.
     */
    internal suspend fun persistResolvedHostIfSuccessful(deviceId: String, resolvedHost: String, result: SyncResult) {
        if (result is SyncResult.Success) {
            enrolledDeviceRepository.updateLastKnownHost(deviceId, resolvedHost)
        }
    }

    /**
     * Resolves a fresh LAN address for [device]: first a best-effort HTTPS probe of
     * [EnrolledDevice.deviceName] when it looks like a plausible hostname, then LAN
     * broadcast discovery via [lanDiscoveryClient]. Every candidate (hostname guess or
     * discovery result) is validated with an authenticated `GET /sync/status` (TLS pin +
     * HMAC) before being trusted; the first candidate whose response echoes
     * [EnrolledDevice.deviceId] wins. Returns null if nothing validates, including when
     * [EnrolledDevice.tlsCertFingerprint] is absent (re-enrollment required, cannot pin
     * a connection at all).
     */
    private suspend fun resolveHost(device: EnrolledDevice): String? {
        val fingerprint = device.tlsCertFingerprint ?: return null

        if (isPlausibleHostname(device.deviceName)) {
            validateCandidate(device.deviceName, device, fingerprint, HOSTNAME_PROBE_TIMEOUT_MS)?.let { return it }
        }

        val candidates = lanDiscoveryClient.discover(device)
        for (candidate in candidates) {
            validateCandidate(candidate, device, fingerprint, DISCOVERY_VALIDATE_TIMEOUT_MS)?.let { return it }
        }
        return null
    }

    private fun isPlausibleHostname(name: String): Boolean =
        name.isNotBlank() && PLAUSIBLE_HOSTNAME_REGEX.matches(name)

    /**
     * Authenticates [host] as [device] by signing a `GET /sync/status` request the same
     * way [pull] does, over a TLS connection pinned to [fingerprint]. Returns [host] only
     * on HTTP 2xx with a response body whose `deviceId` echoes [EnrolledDevice.deviceId]:
     * any other outcome (timeout, connect failure, wrong pin, wrong/absent deviceId) is
     * just "not this candidate", returned as null.
     */
    private suspend fun validateCandidate(
        host: String,
        device: EnrolledDevice,
        fingerprint: String,
        timeoutMs: Long,
    ): String? {
        val secret = try {
            secretStore.retrieve(device.deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null

        return try {
            withTimeout(timeoutMs) {
                val now = System.currentTimeMillis()
                val path = "/sync/status"
                val signature = HmacSigner.sign("GET", path, now, ByteArray(0), secret)

                val httpClient = buildHttpClient(fingerprint)
                try {
                    val response = httpClient.get("https://$host:$SYNC_PORT$path") {
                        header("X-NextSH-Device-Id", DeviceIdentity.deviceId())
                        header("X-NextSH-Timestamp", now.toString())
                        header("X-NextSH-Signature", signature)
                    }
                    if (response.status.value in 200..299) {
                        val ack = response.body<SyncStatusAck>()
                        if (ack.deviceId == device.deviceId) host else null
                    } else {
                        null
                    }
                } finally {
                    httpClient.close()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Timber.d(TAG, "validateCandidate: $host timed out for ${device.deviceId}")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(TAG, "validateCandidate, $host did not validate for ${device.deviceId}: ${e.javaClass.simpleName}")
            null
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Builds a Ktor [HttpClient] backed by a custom OkHttp instance with:
     * - strict TLS certificate pinning via [PinnedTrustManager]
     * - connect/read/write timeouts
     *
     * Each call to [push] or [pull] creates and closes its own client to ensure
     * the PinnedTrustManager is scoped to the specific [fingerprint] in play.
     */
    private fun buildHttpClient(fingerprint: String): HttpClient {
        val trustManager = PinnedTrustManager(fingerprint)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // hostname verification replaced by cert pinning
            .build()

        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    /**
     * Executes [block] up to [maxAttempts] times.
     * Retries only on [IOException] / [SocketTimeoutException] with exponential back-off.
     * 4xx/5xx HTTP responses are returned immediately without retry.
     *
     * On exhaustion it rethrows the FIRST exception that classified as "host unreachable",
     * falling back to the last one otherwise. Rationale: once the first attempt proves the
     * address is dead, later attempts often fail differently (a stale ARP entry flips
     * `ConnectException` into a plain `SocketTimeoutException`, for instance) and rethrowing
     * that last attempt would downgrade a correct "unreachable" verdict into "timeout",
     * suppressing the LAN re-discovery that should follow.
     */
    internal suspend fun <T> executeWithRetry(
        maxAttempts: Int,
        block: suspend () -> T,
    ): T {
        var lastException: IOException? = null
        var firstUnreachable: IOException? = null
        for (attempt in 0 until maxAttempts) {
            try {
                return block()
            } catch (e: SocketTimeoutException) {
                lastException = e
                Timber.d(TAG, "Retry ${attempt + 1}/$maxAttempts after timeout")
            } catch (e: IOException) {
                lastException = e
                if (firstUnreachable == null && isUnreachableCause(e)) firstUnreachable = e
                Timber.d(TAG, "Retry ${attempt + 1}/$maxAttempts after IO error: ${e.javaClass.simpleName}")
            }
            if (attempt < maxAttempts - 1) {
                kotlinx.coroutines.delay(RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.size - 1)])
            }
        }
        throw (firstUnreachable ?: lastException)!!
    }

    /**
     * Classifies an IOException raised during push/pull into a precise SyncResult.
     *
     * OkHttp wraps CertificateException (thrown by our PinnedTrustManager) inside
     * an SSLHandshakeException which is an IOException. Walking the cause chain
     * lets us surface a clear "TLS pin failed" error instead of a generic
     * "DeviceUnreachable", so the user can distinguish a compromised/re-enrolled
     * peer from an actual network outage.
     *
     * User-facing messages are built from [host] + [SYNC_PORT] rather than
     * `e.message`: OkHttp embeds Java's `InetSocketAddress.toString()` there
     * ("Failed to connect to /192.168.x.x:47731", leading slash included),
     * which is neither readable nor localizable. The full technical detail
     * stays in the Timber log.
     */
    internal fun classifyIoError(op: String, deviceId: String, host: String, e: IOException): SyncResult {
        val rootCause = generateSequence<Throwable>(e) { it.cause }.lastOrNull() ?: e
        val tlsFailure = generateSequence<Throwable>(e) { it.cause }
            .any { it is CertificateException || it is SSLException }
        val detail = "${e.javaClass.simpleName}: ${e.message ?: rootCause.message ?: "unknown"}"

        return when {
            tlsFailure -> {
                Timber.w(e, "Sync $op TLS error for $deviceId: $detail")
                SyncResult.Error(SyncError.UNKNOWN, "Certificat TLS refusé ($host) : ré-appairage requis ?")
            }
            isUnreachableCause(e) -> {
                Timber.w(e, "Sync $op host unreachable for $deviceId: $detail")
                SyncResult.Error(SyncError.HOST_UNREACHABLE, "Hôte injoignable ($host:$SYNC_PORT)")
            }
            else -> {
                Timber.w(e, "Sync $op network error for $deviceId: $detail")
                SyncResult.Error(SyncError.UNKNOWN, "Erreur réseau ($host:$SYNC_PORT)")
            }
        }
    }
}

/**
 * True for the I/O failures that mean "this address led nowhere", i.e. the ones that should
 * make [LanSyncClient.fullSync] re-resolve the peer instead of reporting a dead end:
 *
 * - [ConnectException]: connection refused, and also the connect-phase timeout
 *   (`io.ktor.client.plugins.ConnectTimeoutException` is a `ConnectException` subclass).
 * - [NoRouteToHostException]: EHOSTUNREACH, what the retries hit once the ARP entry for a
 *   departed peer goes NUD_FAILED.
 * - [UnknownHostException]: a persisted host name that no longer resolves. This one was the
 *   self-locking case: a device enrolled by hostname whose DNS/mDNS entry dies reported a plain
 *   "network error" forever, so discovery was never attempted and the entry never healed.
 *
 * Deliberately NOT [SocketTimeoutException] (a read/write timeout means the server accepted the
 * connection, so the address is right) and NOT the TLS exceptions (the peer answered, a pin
 * mismatch is a security signal, not a routing problem, and is classified before this check).
 */
private fun isUnreachableCause(e: IOException): Boolean =
    e is ConnectException || e is NoRouteToHostException || e is UnknownHostException

/** Deserialized response body from POST /sync/push. */
@kotlinx.serialization.Serializable
private data class PushAck(
    val cleanApplied: Int,
    val conflictsCount: Int,
)

/**
 * Deserialized response body from GET /sync/status: mirrors
 * `fr.techtical.nextsh.desktop.sync.SyncStatusResponse` (a private mirror rather than a
 * shared type since `:app` cannot depend on `:desktop`). Only [deviceId] is consulted by
 * [LanSyncClient.validateCandidate]; the other fields are decoded for completeness.
 */
@kotlinx.serialization.Serializable
private data class SyncStatusAck(
    val deviceId: String,
    val protocolVersion: Int,
    val lastSyncAt: Long?,
)
