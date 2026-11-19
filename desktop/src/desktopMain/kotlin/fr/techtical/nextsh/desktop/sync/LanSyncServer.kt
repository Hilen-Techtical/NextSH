// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.desktop.core.sync.DesktopSyncScheduler
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sync_error_no_lan_interface
import fr.techtical.nextsh.desktop.generated.resources.sync_error_unknown
import fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.DiscoveryProtocol
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.SyncBundleCodec
import fr.techtical.nextsh.shared.core.sync.SyncPayload
import fr.techtical.nextsh.shared.core.sync.SyncRepository
import fr.techtical.nextsh.shared.core.sync.HmacSigner
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.netty.Netty
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.getString

private const val TAG = "LanSyncServer"
private const val SYNC_PORT = 47731
private const val TLS_ALIAS = "sync"
private const val PROTOCOL_VERSION = 1

/**
 * Hard cap on the body of POST /sync/push, applied BEFORE any byte is read.
 *
 * A push carries one JSON-encoded SyncPayload: metadata (host labels, tunnel configs, public keys,
 * snippets) plus the encrypted credential entries, AES-GCM sealed and base64 encoded (+33%). Real
 * bundles weigh a few hundred KB; even a very large vault stays in the low megabytes. The `.nextsh`
 * backup format never goes through this route. 32 MiB leaves a wide margin for legitimate growth
 * while keeping the worst case (body, its String copy and the decoded tree, roughly 4x) bounded
 * for a peer that already holds an enrolled secret.
 */
internal const val SYNC_PUSH_MAX_BODY_BYTES = 32L * 1024 * 1024

/**
 * Push bodies read and verified at the same time. Each can weigh up to [SYNC_PUSH_MAX_BODY_BYTES]
 * plus its byte array copy, so the global worst case is this many times that, before the HMAC.
 */
private const val MAX_CONCURRENT_PUSH_BODIES = 2

/**
 * Anti-replay window of the pre-body freshness check: the same +/- 5 minutes as
 * `HmacSigner.verify` (which stays the authority and re-checks it after the body is read).
 */
private const val TIMESTAMP_SKEW_MS = DiscoveryProtocol.TIMESTAMP_SKEW_MS

/** Netty gives up on a request whose body stops arriving after this long (slow-body defence). */
private const val REQUEST_READ_TIMEOUT_SECONDS = 60

@Serializable
data class SyncStatusResponse(
    val deviceId: String,
    val protocolVersion: Int,
    val lastSyncAt: Long?,
)

@Serializable
data class SyncPushResponse(
    val cleanApplied: Int,
    val conflictsCount: Int,
)

/** Holds references needed by the routing module. */
data class LanSyncServerDeps(
    val enrolledDeviceRepository: EnrolledDeviceRepository,
    val secretStore: EnrolledDeviceSecretStore,
    val syncRepository: SyncRepository,
    val credentialSyncRepository: CredentialSyncRepository,
    val localDeviceId: String,
    val syncScheduler: DesktopSyncScheduler,
)

/**
 * Minimal control surface of the LAN sync server, decoupled from its heavy
 * concrete construction so [LanSyncServerLifecycle] can be unit-tested against
 * a fake. [LanSyncServer] is the production implementation.
 */
interface SyncServerControl {
    val state: StateFlow<LanSyncServer.State>

    /**
     * Démarre le serveur. [shouldRun] est ré-évalué au moment exact du bind (avant ET après) :
     * si la condition (vault déverrouillé + sync activée) est fausse à cet instant, le bind est
     * abandonné : c'est l'ultime garde-fou contre une race où le vault se verrouille / la sync se
     * désactive pendant le démarrage, qui laisserait sinon l'endpoint TLS LAN actif.
     */
    fun start(shouldRun: () -> Boolean = { true })
    fun stop()

    /**
     * Re-attempts the UDP discovery bind if it is not up while the TCP endpoint is listening.
     * The UDP bind can fail on its own (port taken, firewall refusal) without preventing sync;
     * this gives the network watcher a cheap way to heal it later instead of leaving discovery
     * permanently dead until the next full restart. No-op by default so fakes stay trivial.
     */
    fun ensureDiscoveryRunning() = Unit
}

class LanSyncServer(
    private val appScope: AppScope,
    private val tlsCertificateManager: TlsCertificateManager,
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val secretStore: EnrolledDeviceSecretStore,
    private val syncRepository: SyncRepository,
    private val credentialSyncRepository: CredentialSyncRepository,
    private val deviceIdentity: DeviceIdentity,
    private val syncScheduler: DesktopSyncScheduler,
) : SyncServerControl {

    sealed class State {
        data object Stopped : State()
        data object Starting : State()
        data class Listening(val addr: String, val port: Int) : State()
        data class Error(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    override val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var engine: EmbeddedServer<*, *>? = null

    // C4: UDP discovery responder shares the same enrolled-device secrets as the HTTPS sync API:
    // started right after the TCP bind so the two Windows Firewall prompts (TCP + UDP) land together,
    // stopped alongside the TCP endpoint so there is never a dangling discovery listener.
    private val discoveryResponder = LanDiscoveryResponder(
        appScope = appScope,
        enrolledDeviceRepository = enrolledDeviceRepository,
        secretStore = secretStore,
        // The responder advertises where to sync, not where it listens: the UDP discovery port
        // and the TCP sync port are separate values that merely happen to share a number today.
        syncPort = SYNC_PORT,
    )

    override fun start(shouldRun: () -> Boolean) {
        if (_state.value is State.Listening || _state.value is State.Starting) return

        appScope.coroutineScope.launch {
            _state.value = State.Starting

            // Inside the try: LanAddressDetector walks live NetworkInterface state, which can
            // fail outright during a VPN/adapter switch. Left outside, any throw here escaped
            // the launch and pinned the server in Starting forever: a state the lifecycle's
            // await-settled loop treats as "still coming up", so no retry ever fired.
            try {
                val lan = LanAddressDetector.detect()
                if (lan == null) {
                    Logger.w(TAG, "Aucune interface LAN détectée : serveur de sync non démarré")
                    _state.value = State.Error(getString(Res.string.sync_error_no_lan_interface))
                    return@launch
                }

                val lanAddr = lan.address.hostAddress
                Logger.d(TAG, "Démarrage du serveur de sync sur l'interface LAN détectée")

                val tlsMaterial = tlsCertificateManager.loadOrGenerate()
                val deps = LanSyncServerDeps(
                    enrolledDeviceRepository = enrolledDeviceRepository,
                    secretStore = secretStore,
                    syncRepository = syncRepository,
                    credentialSyncRepository = credentialSyncRepository,
                    localDeviceId = DeviceIdentity.deviceId(),
                    syncScheduler = syncScheduler,
                )

                val srv = embeddedServer(Netty, configure = {
                    // Ktor/Netty exposes no body size limit: the cap lives in the push route
                    // (SYNC_PUSH_MAX_BODY_BYTES). The read timeout bounds slow-body connections.
                    requestReadTimeoutSeconds = REQUEST_READ_TIMEOUT_SECONDS
                    // Ktor 3.0.0 installs the read-timeout handler only in the HTTP/1.1 pipeline.
                    // With sslConnector, HTTP/2 is on by default and OkHttp (Android client) would
                    // pick h2 through ALPN, bypassing the timeout. Off: clients fall back to
                    // HTTP/1.1 by themselves, nothing to change on their side.
                    enableHttp2 = false
                    sslConnector(
                        keyStore = tlsMaterial.keystore,
                        keyAlias = TLS_ALIAS,
                        // Each lambda receives a fresh copy so Ktor can zero it independently.
                        keyStorePassword = { tlsMaterial.passphrase.copyOf() },
                        privateKeyPassword = { tlsMaterial.passphrase.copyOf() },
                    ) {
                        host = lanAddr
                        port = SYNC_PORT
                    }
                }) {
                    install(ContentNegotiation) { json() }
                    routing { syncRoutes(deps) }
                }

                // Dernière vérification AVANT le bind : si le vault s'est verrouillé ou la sync a été
                // désactivée pendant la construction du serveur, ne pas exposer l'endpoint TLS LAN.
                if (!shouldRun()) {
                    Logger.d(TAG, "Bind abandonné : condition fermée avant l'écoute (vault/sync)")
                    _state.value = State.Stopped
                    return@launch
                }

                engine = srv
                srv.start(wait = false)
                if (!discoveryResponder.start()) {
                    // Sync itself is fully usable without discovery: only re-locating a Desktop
                    // whose IP changed is degraded. Warn and let the network watcher retry the
                    // bind later (see ensureDiscoveryRunning) rather than failing the server.
                    Logger.w(
                        TAG,
                        "LAN discovery unavailable (UDP bind failed): sync stays active, retrying at the next network check",
                    )
                }

                Logger.d(TAG, "Serveur de sync actif")
                _state.value = State.Listening(addr = lanAddr, port = SYNC_PORT)

                // Re-vérification APRÈS le bind : si la condition s'est fermée pendant le bind,
                // démonter immédiatement l'endpoint (mêmes paramètres de teardown que stop()).
                if (!shouldRun()) {
                    Logger.d(TAG, "Condition fermée pendant le bind : démontage de l'endpoint de sync")
                    srv.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                    discoveryResponder.stop()
                    engine = null
                    _state.value = State.Stopped
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Impossible de démarrer le serveur de sync", e)
                _state.value = State.Error(getString(Res.string.sync_error_unknown))
            }
        }
    }

    /**
     * Retries the UDP discovery bind when the TCP endpoint is up but the responder is not.
     * Called from the network watcher's tick, so a bind that lost the race with another process
     * (or with a firewall prompt) heals on its own within one tick instead of staying dead for
     * the rest of the session. No-op when discovery is already running or sync is not listening.
     */
    override fun ensureDiscoveryRunning() {
        if (_state.value !is State.Listening) return
        if (discoveryResponder.isRunning) return
        if (discoveryResponder.start()) {
            Logger.d(TAG, "Découverte LAN rétablie")
        }
        // A still-failing bind stays silent here: the tick repeats every 20 s and one warning
        // per tick would flood the log for a condition the user cannot act on.
    }

    override fun stop() {
        engine?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        engine = null
        discoveryResponder.stop()
        if (_state.value !is State.Stopped) {
            Logger.d(TAG, "Serveur de sync arrêté")
            _state.value = State.Stopped
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// HMAC validation helper
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Validates the HMAC-SHA256 signature on the request.
 *
 * The body is passed in explicitly because it must be read ONCE before routing
 * (Ktor CIO does not allow double-reading the request body). The caller reads
 * [receiveBytes] first, passes it here for verification, then re-uses it for
 * JSON decoding.
 *
 * Returns a (deviceId, sharedSecret) pair on success, or null if validation
 * fails. The caller is responsible for wiping the secret ByteArray after use.
 * Logs carry a [HmacRejection] reason only: before authentication the claimed device id is
 * attacker controlled, so it is never printed, and the secret never is.
 */
suspend fun ApplicationCall.validateHmac(
    body: ByteArray,
    enrolledDeviceRepository: EnrolledDeviceRepository,
    secretStore: EnrolledDeviceSecretStore,
): Pair<String, ByteArray>? {
    val deviceId = request.headers["X-NextSH-Device-Id"]
    val timestampHeader = request.headers["X-NextSH-Timestamp"]
    val signature = request.headers["X-NextSH-Signature"]

    if (deviceId == null || timestampHeader == null || signature == null) {
        Logger.w("LanSyncServer", "Requête rejetée : reason=${HmacRejection.MISSING_HEADERS}")
        return null
    }

    val timestamp = timestampHeader.toLongOrNull()
    if (timestamp == null) {
        Logger.w("LanSyncServer", "Requête rejetée : reason=${HmacRejection.INVALID_TIMESTAMP}")
        return null
    }

    val device = enrolledDeviceRepository.getByDeviceId(deviceId)
    if (device == null) {
        Logger.w("LanSyncServer", "Requête rejetée : reason=${HmacRejection.UNKNOWN_DEVICE}")
        return null
    }

    val secret = secretStore.retrieve(deviceId)
    if (secret == null) {
        Logger.w("LanSyncServer", "Requête rejetée : reason=${HmacRejection.SECRET_NOT_FOUND}")
        return null
    }

    val valid = HmacSigner.verify(
        method = request.httpMethod.value,
        path = request.uri,
        timestamp = timestamp,
        body = body,
        expectedSignature = signature,
        sharedSecret = secret,
    )

    return if (valid) {
        deviceId to secret
    } else {
        Logger.w("LanSyncServer", "Requête rejetée : reason=${HmacRejection.BAD_SIGNATURE}")
        secret.fill(0)
        null
    }
}

/**
 * Why a sync request failed authentication. Only the enum name is logged: the device id of an
 * unauthenticated request is whatever the sender claims, never a trusted value.
 */
internal enum class HmacRejection {
    MISSING_HEADERS,
    INVALID_TIMESTAMP,
    UNKNOWN_DEVICE,
    SECRET_NOT_FOUND,
    BAD_SIGNATURE,
    STALE_TIMESTAMP,
    BODY_LENGTH_MISSING,
    BODY_TOO_LARGE,
    BODY_LENGTH_MISMATCH,
    TOO_MANY_PUSHES,
}

/**
 * Cheap checks run BEFORE any body byte is read, so an unenrolled peer cannot make the server
 * buffer anything: signed headers present, timestamp inside the anti-replay window, device known,
 * and a declared Content-Length within [maxBodyBytes]. The caller answers every failure with the
 * same 401 as a bad signature: the reply never says which check failed. The HMAC itself is still
 * verified afterwards, on the exact body.
 */
internal suspend fun ApplicationCall.passesPreBodyChecks(
    enrolledDeviceRepository: EnrolledDeviceRepository,
    maxBodyBytes: Long,
): Boolean {
    val deviceId = request.headers["X-NextSH-Device-Id"]
    val timestampHeader = request.headers["X-NextSH-Timestamp"]
    val signature = request.headers["X-NextSH-Signature"]
    if (deviceId == null || timestampHeader == null || signature == null) {
        Logger.w(TAG, "Requête rejetée : reason=${HmacRejection.MISSING_HEADERS}")
        return false
    }
    val timestamp = timestampHeader.toLongOrNull()
    if (timestamp == null) {
        Logger.w(TAG, "Requête rejetée : reason=${HmacRejection.INVALID_TIMESTAMP}")
        return false
    }
    if (kotlin.math.abs(System.currentTimeMillis() - timestamp) > TIMESTAMP_SKEW_MS) {
        Logger.w(TAG, "Requête rejetée : reason=${HmacRejection.STALE_TIMESTAMP}")
        return false
    }
    if (enrolledDeviceRepository.getByDeviceId(deviceId) == null) {
        Logger.w(TAG, "Requête rejetée : reason=${HmacRejection.UNKNOWN_DEVICE}")
        return false
    }
    val declared = request.contentLength()
    if (declared == null || declared < 0) {
        Logger.w(TAG, "Requête rejetée : reason=${HmacRejection.BODY_LENGTH_MISSING}")
        return false
    }
    if (declared > maxBodyBytes) {
        Logger.w(TAG, "Requête rejetée : reason=${HmacRejection.BODY_TOO_LARGE}")
        return false
    }
    return true
}

/**
 * Reads the request body with an effective bound: never more than the declared Content-Length
 * (already checked against the cap) plus one probe byte. A body that does not match its declared
 * length exactly is refused (null).
 */
internal suspend fun ApplicationCall.receiveBoundedBody(): ByteArray? {
    val declared = request.contentLength() ?: return null
    val bytes = receiveChannel().readRemaining(declared + 1).readByteArray()
    if (bytes.size.toLong() != declared) {
        Logger.w(TAG, "Requête rejetée : reason=${HmacRejection.BODY_LENGTH_MISMATCH}")
        return null
    }
    return bytes
}

// ──────────────────────────────────────────────────────────────────────────────
// Routes
// ──────────────────────────────────────────────────────────────────────────────

fun Route.syncRoutes(deps: LanSyncServerDeps) = syncRoutes(deps, Semaphore(MAX_CONCURRENT_PUSH_BODIES))

internal fun Route.syncRoutes(deps: LanSyncServerDeps, pushGate: Semaphore) {

    // GET /sync/status: state check pour le peer
    get("/sync/status") {
        val body = ByteArray(0)
        val auth = call.validateHmac(body, deps.enrolledDeviceRepository, deps.secretStore)
        if (auth == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }
        val (deviceId, secret) = auth
        secret.fill(0)

        val device = deps.enrolledDeviceRepository.getByDeviceId(deviceId)
        // Logged before the reply: once the response is on the wire the client (a test, say)
        // may move on and read the log before this line is written.
        Logger.d(TAG, "sync status (GET): device=${LogId.of(deviceId)}")
        call.respond(
            SyncStatusResponse(
                deviceId = deps.localDeviceId,
                protocolVersion = PROTOCOL_VERSION,
                lastSyncAt = device?.lastSyncAt,
            )
        )
    }

    // GET /sync/pull: renvoie le SyncBundle local chiffré
    get("/sync/pull") {
        val body = ByteArray(0)
        val auth = call.validateHmac(body, deps.enrolledDeviceRepository, deps.secretStore)
        if (auth == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }
        val (deviceId, secret) = auth

        try {
            val baseBundle = deps.syncRepository.getAllLocalAsBundle()
            val credentialEntries = try {
                deps.credentialSyncRepository.exportEncryptedEntries(secret)
            } catch (e: Exception) {
                Logger.w(TAG, "Credential export failed during pull for device=${LogId.of(deviceId)} (${errorClass(e)}), returning empty list")
                emptyList()
            }
            val bundle = baseBundle.copy(credentials = credentialEntries)
            val payload = SyncBundleCodec.encrypt(bundle, deps.localDeviceId, secret)
            // Wipe secret immédiatement après chiffrement
            secret.fill(0)

            call.respond(payload)
            // NE PAS mettre à jour lastSyncAt ici : c'est le client qui confirme si l'apply réussit
            Logger.d(TAG, "sync pull (GET): device=${LogId.of(deviceId)} entries=${bundle.totalCount} (credentialCount=${credentialEntries.size})")
        } catch (e: Exception) {
            secret.fill(0)
            // Class chain only: a serialization message can embed a JSON excerpt (browserPath and its query).
            Logger.e(TAG, "Erreur sync pull pour device=${LogId.of(deviceId)}", e)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    // POST /sync/push: reçoit un SyncPayload chiffré et l'applique
    post("/sync/push") {
        // Contrôles bon marché AVANT de lire le moindre octet : en-têtes signés, fenêtre
        // d'horodatage, appareil connu, Content-Length présent et borné. Même 401 que pour une
        // mauvaise signature : la réponse ne dit pas quel contrôle a échoué.
        if (!call.passesPreBodyChecks(deps.enrolledDeviceRepository, SYNC_PUSH_MAX_BODY_BYTES)) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }

        // Mémoire bornée globalement : au plus MAX_CONCURRENT_PUSH_BODIES corps en cours de
        // lecture et de vérification. Sans permis, refus immédiat, corps non lu. Ici la requête a
        // déjà passé les contrôles d'avant lecture (appareil connu, taille bornée) : ce n'est pas
        // un échec d'authentification mais une surcharge. 503 et non 401 : le client Android lit
        // 401 comme AuthFailed et inviterait à ré-enrôler un appareil sain, alors qu'il classe
        // 5xx en erreur serveur et retente au cycle suivant. Aucun corps, rien à apprendre.
        if (!pushGate.tryAcquire()) {
            Logger.w(TAG, "Requête rejetée : reason=${HmacRejection.TOO_MANY_PUSHES}")
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@post
        }
        val rawBody: ByteArray?
        val auth: Pair<String, ByteArray>?
        try {
            // Lire le body en raw bytes AVANT validation HMAC, avec une borne effective.
            // receiveChannel() contourne ContentNegotiation et donne accès aux octets bruts,
            // ce qui est nécessaire car le HMAC est calculé sur le contenu exact du payload.
            rawBody = call.receiveBoundedBody()
            auth = if (rawBody == null) null
            else call.validateHmac(rawBody, deps.enrolledDeviceRepository, deps.secretStore)
        } finally {
            pushGate.release()
        }
        if (rawBody == null || auth == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        val (deviceId, secret) = auth

        try {
            val payload = kotlinx.serialization.json.Json.decodeFromString(
                SyncPayload.serializer(),
                rawBody.toString(Charsets.UTF_8),
            )

            // Vérification cohérence : le senderDeviceId du payload doit correspondre au header
            if (payload.senderDeviceId != deviceId) {
                Logger.w(TAG, "sync push (POST) rejeté : senderDeviceId incohérent (device=${LogId.of(deviceId)})")
                secret.fill(0)
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            val bundle = SyncBundleCodec.decrypt(payload, secret)

            val result = deps.syncRepository.applyRemoteBundle(bundle)
            // Apply credentials AFTER regular entities so owning Hosts/SshKeys
            // exist locally before the secret lands in the vault. A failure here
            // is swallowed so the regular-entity sync ack still reports success.
            try {
                deps.credentialSyncRepository.applyRemoteEntries(bundle.credentials, secret)
            } catch (e: Exception) {
                Logger.w(TAG, "Credential apply failed during push from device=${LogId.of(deviceId)} (${errorClass(e)})")
            }
            // Wipe secret only after both regular + credential applies are done.
            secret.fill(0)

            val now = System.currentTimeMillis()
            deps.enrolledDeviceRepository.updateLastSyncAt(deviceId, now)

            // Notify the scheduler so the Desktop UI can reflect the last received sync time.
            deps.syncScheduler.onPushReceived(
                fromDeviceId = deviceId,
                cleanApplied = result.cleanApplied,
                conflicts = result.conflicts.size,
            )

            call.respond(
                SyncPushResponse(
                    cleanApplied = result.cleanApplied,
                    conflictsCount = result.conflicts.size,
                )
            )
            Logger.d(
                TAG,
                "sync push (POST): device=${LogId.of(deviceId)} cleanApplied=${result.cleanApplied} conflicts=${result.conflicts.size}",
            )
        } catch (e: Exception) {
            secret.fill(0)
            // Class chain only: a serialization message can embed a JSON excerpt (browserPath and its query).
            Logger.e(TAG, "Erreur sync push pour device=${LogId.of(deviceId)}", e)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }
}
