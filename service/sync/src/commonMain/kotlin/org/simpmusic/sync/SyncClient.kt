package org.simpmusic.sync

import com.maxrave.ktorext.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json

class SyncClient(
    private val baseUrl: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }
    private val http = HttpClient(getEngine()) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
    }

    suspend fun register(credentials: SyncCredentials): SyncSession =
        authenticate("/v1/auth/register", credentials)

    suspend fun login(credentials: SyncCredentials): SyncSession =
        authenticate("/v1/auth/login", credentials)

    suspend fun recover(request: SyncRecoveryRequest): SyncSession =
        authenticate("/v1/auth/recover", request)

    suspend fun logout(token: String) {
        val response = http.post("$baseUrl/v1/auth/logout") {
            header("Authorization", "Bearer $token")
        }
        if (response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.Unauthorized) {
            error("logout failed with HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
    }

    suspend fun devices(token: String): List<SyncDevice> {
        val response = http.get("$baseUrl/v1/devices") {
            header("Authorization", "Bearer $token")
        }
        if (response.status != HttpStatusCode.OK) {
            error("device listing failed with HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun revokeDevice(token: String, deviceId: String) {
        val response = http.delete("$baseUrl/v1/devices/$deviceId") {
            header("Authorization", "Bearer $token")
        }
        if (response.status != HttpStatusCode.NoContent) {
            error("device revocation failed with HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
    }

    suspend fun connectNotifications(token: String): WebSocketSession {
        val websocketUrl = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return http.webSocketSession("$websocketUrl/v1/ws") {
            header("Authorization", "Bearer $token")
        }
    }

    suspend fun nextNotification(session: WebSocketSession): String? =
        when (val frame = session.incoming.receive()) {
            is Frame.Text -> frame.readText()
            is Frame.Close -> null
            else -> ""
        }

    suspend fun observeNotifications(
        token: String,
        onConnected: () -> Unit = {},
        onMessage: suspend (String) -> Unit,
    ) {
        val session = connectNotifications(token)
        onConnected()
        try {
            while (true) {
                val text = nextNotification(session) ?: break
                if (text.isNotBlank()) {
                    onMessage(text)
                }
            }
        } finally {
            runCatching { session.close() }
        }
    }

    fun close() {
        http.close()
    }

    suspend fun sync(token: String, request: SyncRequest): SyncResponse {
        val response = http.post("$baseUrl/v1/sync") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody(request)
        }
        if (response.status != HttpStatusCode.OK) {
            error("sync failed with HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
        return response.body()
    }

    private suspend fun authenticate(path: String, credentials: Any): SyncSession {
        val response = http.post("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(credentials)
        }
        if (response.status != HttpStatusCode.OK) {
            error("authentication failed with HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
        return response.body()
    }
}
