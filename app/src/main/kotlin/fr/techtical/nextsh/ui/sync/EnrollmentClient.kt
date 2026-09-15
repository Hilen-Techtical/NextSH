// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Client HTTP Ktor pour le serveur d'enrôlement Desktop.
 * Timeouts : connect 5s, request 10s.
 */
class EnrollmentClient {

    enum class Reason {
        NETWORK,
        TIMEOUT,
        INVALID_RESPONSE,
        HTTP_4XX,
        HTTP_5XX,
    }

    class EnrollmentException(val reason: Reason, message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    suspend fun fetchHello(host: String, port: Int): Result<ServerHello> {
        return runCatching {
            val response = client.get("http://$host:$port/enroll")
            when {
                response.status.value in 400..499 ->
                    throw EnrollmentException(Reason.HTTP_4XX, "HTTP ${response.status.value}")
                response.status.value in 500..599 ->
                    throw EnrollmentException(Reason.HTTP_5XX, "HTTP ${response.status.value}")
                !response.status.isSuccess() ->
                    throw EnrollmentException(Reason.INVALID_RESPONSE, "HTTP ${response.status.value}")
                else -> response.body<ServerHello>()
            }
        }.recoverCatching { e ->
            when (e) {
                is EnrollmentException -> throw e
                is SocketTimeoutException ->
                    throw EnrollmentException(Reason.TIMEOUT, "Request timed out", e)
                is ConnectException ->
                    throw EnrollmentException(Reason.NETWORK, "Connection refused: ${e.message}", e)
                else ->
                    throw EnrollmentException(Reason.NETWORK, "Network error: ${e.message}", e)
            }
        }
    }

    suspend fun submitEnroll(host: String, port: Int, payload: ClientEnroll): Result<EnrollAck> {
        return runCatching {
            val response = client.post("http://$host:$port/enroll") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            when {
                response.status.value in 400..499 ->
                    throw EnrollmentException(Reason.HTTP_4XX, "HTTP ${response.status.value}")
                response.status.value in 500..599 ->
                    throw EnrollmentException(Reason.HTTP_5XX, "HTTP ${response.status.value}")
                !response.status.isSuccess() ->
                    throw EnrollmentException(Reason.INVALID_RESPONSE, "HTTP ${response.status.value}")
                else -> response.body<EnrollAck>()
            }
        }.recoverCatching { e ->
            when (e) {
                is EnrollmentException -> throw e
                is SocketTimeoutException ->
                    throw EnrollmentException(Reason.TIMEOUT, "Request timed out", e)
                is ConnectException ->
                    throw EnrollmentException(Reason.NETWORK, "Connection refused: ${e.message}", e)
                else ->
                    throw EnrollmentException(Reason.NETWORK, "Network error: ${e.message}", e)
            }
        }
    }

    fun close() {
        client.close()
    }
}
