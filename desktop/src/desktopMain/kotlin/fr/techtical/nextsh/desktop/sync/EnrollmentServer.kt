// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.EcdhHandshake
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EphemeralKeypair
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.util.Base64

private const val TAG = "EnrollmentServer"
private const val PORT_RANGE_START = 47732
private const val PORT_RANGE_END = 49151
private const val TIMEOUT_MS = 5L * 60_000L
private const val SHUTDOWN_DELAY_MS = 1_000L

class EnrollmentServer(
    private val appScope: AppScope,
    private val onEnrolled: suspend (EnrolledDevice) -> Unit,
) {

    sealed class State {
        object Idle : State()
        object Starting : State()
        data class Listening(val addr: String, val port: Int, val fingerprint: String) : State()
        data class Enrolled(val device: EnrolledDevice) : State()
        data class Error(val reason: String) : State()
        object Cancelled : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var keypair: EphemeralKeypair? = null
    @Volatile private var engine: EmbeddedServer<*, *>? = null
    @Volatile private var enrollmentDone = false

    fun start() {
        appScope.coroutineScope.launch {
            _state.value = State.Starting

            val lan = LanAddressDetector.detect()
            if (lan == null) {
                _state.value = State.Error("Aucune interface LAN détectée")
                return@launch
            }

            val port = findFreePort(lan.address.hostAddress)
            if (port == null) {
                _state.value = State.Error("Aucun port libre disponible dans la plage $PORT_RANGE_START-$PORT_RANGE_END")
                return@launch
            }

            val kp = EcdhHandshake.generateEphemeralKeypair()
            keypair = kp

            val fp = EcdhHandshake.fingerprint(kp.publicKey)
            val addr = lan.address.hostAddress

            try {
                val server = embeddedServer(CIO, host = addr, port = port) {
                    install(ContentNegotiation) { json() }
                    routing {
                        get("/enroll") {
                            val pubKeyB64 = Base64.getEncoder().encodeToString(kp.publicKey)
                            call.respond(
                                ServerHello(
                                    publicKey = pubKeyB64,
                                    deviceName = DeviceIdentity.deviceName(),
                                    tlsCertFingerprint = DesktopContainer.tlsCertificateManager.fingerprintHex(),
                                )
                            )
                        }
                        post("/enroll") {
                            if (enrollmentDone) {
                                call.respond(io.ktor.http.HttpStatusCode.Gone)
                                return@post
                            }
                            handleEnroll(call.receive<ClientEnroll>(), kp, fp)
                            call.respond(
                                EnrollAck(
                                    deviceId = DeviceIdentity.deviceId(),
                                    deviceName = DeviceIdentity.deviceName(),
                                )
                            )
                        }
                    }
                }
                engine = server
                server.start(wait = false)
                Logger.d(TAG, "Enrôlement en écoute sur $addr:$port (fingerprint=$fp)")
                _state.value = State.Listening(addr = addr, port = port, fingerprint = fp)
            } catch (e: Exception) {
                keypair?.wipePrivate()
                keypair = null
                Logger.e(TAG, "Impossible de démarrer le serveur d'enrôlement", e)
                _state.value = State.Error("Erreur de démarrage du serveur : ${e.message}")
                return@launch
            }

            // Timeout auto-shutdown
            appScope.coroutineScope.launch {
                delay(TIMEOUT_MS)
                if (_state.value is State.Listening) {
                    Logger.w(TAG, "Timeout 5 min atteint : annulation automatique")
                    cancel()
                }
            }
        }
    }

    fun cancel() {
        engine?.stop(0, 0)
        engine = null
        keypair?.wipePrivate()
        keypair = null
        if (_state.value !is State.Enrolled) {
            _state.value = State.Cancelled
        }
    }

    private suspend fun handleEnroll(client: ClientEnroll, kp: EphemeralKeypair, fp: String) {
        enrollmentDone = true

        val androidPubBytes = Base64.getDecoder().decode(client.publicKey)
        val sharedSecret = EcdhHandshake.deriveSharedSecret(
            localPrivateKey = kp.privateKey(),
            remotePublicKey = androidPubBytes,
            salt = fp.toByteArray(Charsets.UTF_8),
            info = "nextsh-lan-sync-v1",
        )
        kp.wipePrivate()
        keypair = null

        DesktopContainer.enrolledDeviceSecretStore.store(client.deviceId, sharedSecret)
        sharedSecret.fill(0)

        val device = EnrolledDevice(
            deviceId = client.deviceId,
            deviceName = client.deviceName,
            platform = Platform.valueOf(client.platform),
            publicKeyFingerprint = EcdhHandshake.fingerprint(androidPubBytes),
            tlsCertFingerprint = null, // Android does not expose a sync server
            lastSyncAt = null,
            enrolledAt = System.currentTimeMillis(),
            lastKnownHost = null, // Android peer does not serve sync requests
        )

        _state.value = State.Enrolled(device)
        onEnrolled(device)

        appScope.coroutineScope.launch {
            delay(SHUTDOWN_DELAY_MS)
            engine?.stop(0, 0)
            engine = null
        }
    }

    private fun findFreePort(host: String): Int? {
        for (port in PORT_RANGE_START..PORT_RANGE_END) {
            try {
                ServerSocket().use { ss ->
                    ss.bind(java.net.InetSocketAddress(host, port))
                }
                return port
            } catch (_: Exception) {
                // port occupé, on essaie le suivant
            }
        }
        return null
    }
}
