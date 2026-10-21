package org.simpmusic.simpconnect

import com.maxrave.logger.Logger
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.random.Random

private const val TAG = "SimpConnectServer"

interface SimpConnectServerListener {
    fun onPlayPause()
    fun onPlay()
    fun onPause()
    fun onNext()
    fun onPrevious()
    fun onSeekTo(positionMs: Long)
    fun onSetVolume(volume: Float)
    fun onSetShuffle(enabled: Boolean)
    fun onSetRepeat(mode: Int)
    fun onPlayTrack(message: SimpConnectMessage.PlayTrack)
    fun getCurrentState(): SimpConnectMessage.StateSync
    fun getHandoffPayload(): SimpConnectMessage.HandoffPayload
}

class SimpConnectServer(
    private val localDeviceId: String,
    private val localDeviceName: () -> String,
    private val authStore: SimpConnectAuthStore,
    private val listener: SimpConnectServerListener,
    private val onAuthRequest: (AuthRequest) -> Unit,
    private val scope: CoroutineScope,
) {
    private var server: EmbeddedServer<*, *>? = null
    private var boundPort: Int = 0

    private val connectedSessions = Collections.synchronizedSet(mutableSetOf<WebSocketSession>())

    private val _connectedControllersCount = MutableStateFlow(0)
    val connectedControllersCount = _connectedControllersCount.asStateFlow()

    fun getPort(): Int = boundPort

    suspend fun start(preferredPort: Int = SIMP_CONNECT_DEFAULT_SERVER_PORT): Int {
        stop()

        var chosenPort = preferredPort
        var embedded: EmbeddedServer<*, *>? = null

        // Try preferred port first, then fall back to dynamic port 0
        val portsToTry = listOf(preferredPort, 0)
        for (p in portsToTry) {
            try {
                embedded = embeddedServer(CIO, port = p, host = "0.0.0.0") {
                    install(WebSockets)
                    routing {
                        webSocket("/simpconnect") {
                            handleSession(this)
                        }
                    }
                }
                embedded.start(wait = false)
                chosenPort = embedded.engine.resolvedConnectors().first().port
                break
            } catch (e: Exception) {
                Logger.w(TAG, "Port $p failed to bind: ${e.message}")
                runCatching { embedded?.stop(gracePeriodMillis = 0, timeoutMillis = 500) }
                embedded = null
            }
        }

        if (embedded == null) {
            Logger.e(TAG, "Failed to start SimpConnectServer on any port")
            return 0
        }

        server = embedded
        boundPort = chosenPort
        Logger.d(TAG, "SimpConnectServer started on port $boundPort")
        return boundPort
    }

    suspend fun stop() {
        val s = server ?: return
        server = null
        boundPort = 0
        connectedSessions.clear()
        _connectedControllersCount.value = 0
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { s.stop(gracePeriodMillis = 100, timeoutMillis = 500) }
        }
        Logger.d(TAG, "SimpConnectServer stopped")
    }

    fun broadcastStateSync(state: SimpConnectMessage.StateSync) {
        if (connectedSessions.isEmpty()) return
        val json = simpConnectJson.encodeToString(SimpConnectMessage.serializer(), state)
        val frame = Frame.Text(json)
        val sessionsCopy = synchronized(connectedSessions) { connectedSessions.toList() }

        scope.launch(Dispatchers.IO) {
            for (session in sessionsCopy) {
                try {
                    if (session.isActive) {
                        session.send(frame)
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to send StateSync to controller: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleSession(session: WebSocketSession) {
        Logger.d(TAG, "New incoming WebSocket connection")

        // 1. Handshake: Await Hello
        val firstFrame = try {
            session.incoming.receive()
        } catch (_: ClosedReceiveChannelException) {
            return
        } catch (e: Exception) {
            Logger.w(TAG, "Error waiting for hello: ${e.message}")
            return
        }

        if (firstFrame !is Frame.Text) {
            session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Expected text frame"))
            return
        }

        val hello = runCatching {
            simpConnectJson.decodeFromString(SimpConnectMessage.serializer(), firstFrame.readText())
        }.getOrNull()

        if (hello !is SimpConnectMessage.Hello) {
            session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Expected Hello message"))
            return
        }

        Logger.d(TAG, "Received Hello from ${hello.deviceName} (${hello.deviceId})")

        // 2. Auth check
        val isPreApproved = authStore.isDeviceApproved(hello.deviceId, hello.authKey)
        val sessionKey: String

        if (isPreApproved && hello.authKey != null) {
            sessionKey = hello.authKey
            val ack = SimpConnectMessage.HelloAck(
                status = SimpConnectMessage.HandshakeStatus.ACCEPTED,
                deviceName = localDeviceName(),
                authKey = sessionKey,
            )
            session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), ack)))
        } else {
            // Inform client that approval is pending on screen
            val pendingAck = SimpConnectMessage.HelloAck(
                status = SimpConnectMessage.HandshakeStatus.NEEDS_APPROVAL,
                deviceName = localDeviceName(),
            )
            session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), pendingAck)))

            val deferred = CompletableDeferred<Boolean>()
            val request = AuthRequest(
                deviceId = hello.deviceId,
                deviceName = hello.deviceName,
                onResponse = { approved -> deferred.complete(approved) }
            )
            onAuthRequest(request)

            val approved = withTimeoutOrNull(60_000L) {
                deferred.await()
            } ?: false

            if (!approved) {
                val rejectAck = SimpConnectMessage.HelloAck(
                    status = SimpConnectMessage.HandshakeStatus.REJECTED,
                    deviceName = localDeviceName(),
                )
                session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), rejectAck)))
                session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Connection rejected"))
                return
            }

            sessionKey = generateAuthKey()
            authStore.saveApprovedDevice(hello.deviceId, sessionKey)

            val ack = SimpConnectMessage.HelloAck(
                status = SimpConnectMessage.HandshakeStatus.ACCEPTED,
                deviceName = localDeviceName(),
                authKey = sessionKey,
            )
            session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), ack)))
        }

        // 3. Connection accepted!
        connectedSessions.add(session)
        _connectedControllersCount.value = connectedSessions.size
        Logger.d(TAG, "Controller ${hello.deviceName} connected. Total controllers: ${_connectedControllersCount.value}")

        // Send current state right away
        try {
            val initialState = listener.getCurrentState()
            session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), initialState)))
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to send initial StateSync: ${e.message}")
        }

        // 4. Message loop
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val msg = runCatching {
                        simpConnectJson.decodeFromString(SimpConnectMessage.serializer(), frame.readText())
                    }.getOrNull() ?: continue

                    when (msg) {
                        is SimpConnectMessage.PlayPause -> {
                            listener.onPlayPause()
                            kotlinx.coroutines.delay(50)
                            broadcastStateSync(listener.getCurrentState())
                        }
                        is SimpConnectMessage.Play -> {
                            listener.onPlay()
                            kotlinx.coroutines.delay(50)
                            broadcastStateSync(listener.getCurrentState())
                        }
                        is SimpConnectMessage.Pause -> {
                            listener.onPause()
                            kotlinx.coroutines.delay(50)
                            broadcastStateSync(listener.getCurrentState())
                        }
                        is SimpConnectMessage.Next -> {
                            listener.onNext()
                            kotlinx.coroutines.delay(50)
                            broadcastStateSync(listener.getCurrentState())
                        }
                        is SimpConnectMessage.Previous -> {
                            listener.onPrevious()
                            kotlinx.coroutines.delay(50)
                            broadcastStateSync(listener.getCurrentState())
                        }
                        is SimpConnectMessage.SeekTo -> {
                            listener.onSeekTo(msg.positionMs)
                            kotlinx.coroutines.delay(50)
                            broadcastStateSync(listener.getCurrentState())
                        }
                        is SimpConnectMessage.SetVolume -> {
                            listener.onSetVolume(msg.volume)
                            kotlinx.coroutines.delay(50)
                            broadcastStateSync(listener.getCurrentState())
                        }
                        is SimpConnectMessage.SetShuffle -> {
                            listener.onSetShuffle(msg.enabled)
                            kotlinx.coroutines.delay(50)
                            broadcastStateSync(listener.getCurrentState())
                        }
                        is SimpConnectMessage.SetRepeat -> {
                            listener.onSetRepeat(msg.mode)
                            kotlinx.coroutines.delay(50)
                            broadcastStateSync(listener.getCurrentState())
                        }
                        is SimpConnectMessage.PlayTrack -> {
                            listener.onPlayTrack(msg)
                            kotlinx.coroutines.delay(100)
                            broadcastStateSync(listener.getCurrentState())
                        }
                        is SimpConnectMessage.HandoffRequest -> {
                            val payload = listener.getHandoffPayload()
                            session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), payload)))
                        }
                        is SimpConnectMessage.Ping -> {
                            session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), SimpConnectMessage.Pong)))
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException && e !is ClosedReceiveChannelException) {
                Logger.w(TAG, "Controller session error: ${e.message}")
            }
        } finally {
            connectedSessions.remove(session)
            _connectedControllersCount.value = connectedSessions.size
            Logger.d(TAG, "Controller ${hello.deviceName} disconnected. Remaining: ${_connectedControllersCount.value}")
        }
    }

    private fun generateAuthKey(): String {
        val bytes = Random.Default.nextBytes(16)
        return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
