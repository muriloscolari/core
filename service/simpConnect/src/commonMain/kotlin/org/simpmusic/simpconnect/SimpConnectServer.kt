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
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Collections

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
    private val sessionDeviceIds = Collections.synchronizedMap(mutableMapOf<WebSocketSession, String>())

    // All StateSync broadcasts go through a single conflated channel. Playback progress used to
    // produce one broadcast per player tick and every tick launched its own coroutine, which
    // reordered frames and made the controller's timeline jump back and forth. The conflated
    // channel keeps only the latest state and guarantees they are sent in order.
    private val stateChannel = Channel<SimpConnectMessage.StateSync>(Channel.CONFLATED)
    private var broadcasterJob: Job? = null

    private val secureRandom = SecureRandom()

    /**
     * The player may only be touched from its own thread (Android main looper for ExoPlayer, the
     * single desktop player thread on JVM). Ktor delivers frames on its I/O threads, and calling
     * the listener there threw "Player is accessed on the wrong thread", which escaped the read
     * loop and silently killed the controller's session.
     */
    private val playerDispatcher: CoroutineDispatcher =
        (scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default

    private suspend fun <T> onPlayer(block: () -> T): T = withContext(playerDispatcher) { block() }

    private suspend fun currentState(): SimpConnectMessage.StateSync = onPlayer { listener.getCurrentState() }

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
        broadcasterJob =
            scope.launch(Dispatchers.IO) {
                for (state in stateChannel) {
                    sendStateToControllers(state)
                }
            }
        Logger.d(TAG, "SimpConnectServer started on port $boundPort")
        return boundPort
    }

    suspend fun stop() {
        broadcasterJob?.cancel()
        broadcasterJob = null
        val s = server ?: return
        server = null
        boundPort = 0
        val sessions = synchronized(connectedSessions) { connectedSessions.toList() }
        for (session in sessions) {
            runCatching {
                session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
            }
        }
        connectedSessions.clear()
        sessionDeviceIds.clear()
        _connectedControllersCount.value = 0
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { s.stop(gracePeriodMillis = 100, timeoutMillis = 500) }
        }
        Logger.d(TAG, "SimpConnectServer stopped")
    }

    fun broadcastStateSync(state: SimpConnectMessage.StateSync) {
        if (connectedSessions.isEmpty()) return
        stateChannel.trySend(state)
    }

    private suspend fun sendStateToControllers(state: SimpConnectMessage.StateSync) {
        val sessionsCopy = synchronized(connectedSessions) { connectedSessions.toList() }
        if (sessionsCopy.isEmpty()) return
        val json = simpConnectJson.encodeToString(SimpConnectMessage.serializer(), state)
        val frame = Frame.Text(json)
        for (session in sessionsCopy) {
            try {
                if (session.isActive) {
                    session.send(frame)
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to send StateSync to controller: ${e.message}")
                connectedSessions.remove(session)
                sessionDeviceIds.remove(session)
                _connectedControllersCount.value = connectedSessions.size
            }
        }
    }

    private suspend fun handleSession(session: WebSocketSession) {
        Logger.d(TAG, "New incoming WebSocket connection")

        // 1. Handshake: Await Hello
        val firstFrame = try {
            // A connection that never sends Hello must not leak a coroutine forever.
            withTimeoutOrNull(10_000L) { session.incoming.receive() }
                ?: run {
                    session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Hello timeout"))
                    return
                }
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
                // Tell the repository to drop the approval dialog if the request timed out while
                // the user was not looking at the screen, otherwise it would stay there forever.
                runCatching { request.onResponse(false) }
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
        // A controller that reconnects (app restart, network change) leaves its previous session
        // behind until the read loop notices. Close the stale one so commands and state updates
        // are not duplicated.
        val staleSessions =
            synchronized(sessionDeviceIds) {
                sessionDeviceIds
                    .filterValues { it == hello.deviceId }
                    .keys
                    .filter { it !== session }
            }
        for (stale in staleSessions) {
            connectedSessions.remove(stale)
            sessionDeviceIds.remove(stale)
            runCatching { stale.close(CloseReason(CloseReason.Codes.NORMAL, "Replaced by new session")) }
        }

        connectedSessions.add(session)
        sessionDeviceIds[session] = hello.deviceId
        _connectedControllersCount.value = connectedSessions.size
        Logger.d(TAG, "Controller ${hello.deviceName} connected. Total controllers: ${_connectedControllersCount.value}")

        // Send current state right away
        try {
            val initialState = currentState()
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

                    // One failing command must never end the session.
                    try { when (msg) {
                        is SimpConnectMessage.PlayPause -> {
                            onPlayer { listener.onPlayPause() }
                            kotlinx.coroutines.delay(150)
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.Play -> {
                            onPlayer { listener.onPlay() }
                            kotlinx.coroutines.delay(150)
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.Pause -> {
                            onPlayer { listener.onPause() }
                            kotlinx.coroutines.delay(150)
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.Next -> {
                            onPlayer { listener.onNext() }
                            kotlinx.coroutines.delay(150)
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.Previous -> {
                            onPlayer { listener.onPrevious() }
                            kotlinx.coroutines.delay(150)
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.SeekTo -> {
                            onPlayer { listener.onSeekTo(msg.positionMs) }
                            kotlinx.coroutines.delay(150)
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.SetVolume -> {
                            onPlayer { listener.onSetVolume(msg.volume) }
                            kotlinx.coroutines.delay(150)
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.SetShuffle -> {
                            onPlayer { listener.onSetShuffle(msg.enabled) }
                            kotlinx.coroutines.delay(150)
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.SetRepeat -> {
                            onPlayer { listener.onSetRepeat(msg.mode) }
                            kotlinx.coroutines.delay(150)
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.PlayTrack -> {
                            onPlayer { listener.onPlayTrack(msg) }
                            kotlinx.coroutines.delay(150)
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.HandoffRequest -> {
                            // Read the play state BEFORE pausing: the payload tells the controller
                            // whether to resume, and after onPause() the target always reports
                            // isPlaying = false — which used to hand back a paused session.
                            val wasPlaying = currentState().isPlaying
                            // Pause *here* before handing the payload over. If only the controller
                            // paused itself, both devices would keep playing after the transfer.
                            onPlayer { listener.onPause() }
                            kotlinx.coroutines.delay(150)
                            val payload = onPlayer { listener.getHandoffPayload() }.copy(isPlaying = wasPlaying)
                            session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), payload)))
                            broadcastStateSync(currentState())
                        }
                        is SimpConnectMessage.Ping -> {
                            session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), SimpConnectMessage.Pong)))
                        }
                        else -> {}
                    } } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to handle ${msg::class.simpleName}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException && e !is ClosedReceiveChannelException) {
                Logger.w(TAG, "Controller session error: ${e.message}")
            }
        } finally {
            connectedSessions.remove(session)
            sessionDeviceIds.remove(session)
            _connectedControllersCount.value = connectedSessions.size
            Logger.d(TAG, "Controller ${hello.deviceName} disconnected. Remaining: ${_connectedControllersCount.value}")
        }
    }

    private fun generateAuthKey(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
