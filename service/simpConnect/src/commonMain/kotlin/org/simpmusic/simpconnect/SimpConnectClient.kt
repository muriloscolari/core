package org.simpmusic.simpconnect

import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SimpConnectClient"
private const val CONNECT_TIMEOUT_MS = 10_000L

/** A healthy target answers the Hello immediately. */
private const val HANDSHAKE_TIMEOUT_MS = 10_000L

/** After NEEDS_APPROVAL the target's user has up to 60 s to accept; leave room for the reply. */
private const val APPROVAL_TIMEOUT_MS = 75_000L

class SimpConnectClient(
    private val localDeviceId: String,
    private val localDeviceName: () -> String,
    private val authStore: SimpConnectAuthStore,
    private val onHandoffReceived: (SimpConnectMessage.HandoffPayload) -> Unit,
    private val scope: CoroutineScope,
) {
    private val httpClient = HttpClient(getEngine()) {
        install(WebSockets)
        install(HttpTimeout) {
            // Only the WebSocket upgrade is bounded. The session itself stays open indefinitely.
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
        }
    }

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _activeTargetDevice = MutableStateFlow<DiscoveredDevice?>(null)
    val activeTargetDevice: StateFlow<DiscoveredDevice?> = _activeTargetDevice.asStateFlow()

    private val _remotePlaybackState = MutableStateFlow<RemotePlaybackState?>(null)
    val remotePlaybackState: StateFlow<RemotePlaybackState?> = _remotePlaybackState.asStateFlow()

    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var currentSession: DefaultClientWebSocketSession? = null

    /**
     * Bumped by every connect()/disconnect(). The connection coroutine captures it and refuses to
     * publish a terminal state when a newer session has already taken over — otherwise the old
     * coroutine's `finally` would mark the new connection as DISCONNECTED.
     */
    @Volatile
    private var generation: Long = 0L

    /**
     * Commands are queued and delivered by a single coroutine (started after the handshake), so
     * they keep their order and a command issued while connecting — a handoff PlayTrack, for
     * instance — is not dropped. The old code stored only the last PlayTrack and had a window in
     * which it was neither sent nor kept.
     */
    private val outgoing = Channel<SimpConnectMessage>(Channel.UNLIMITED)

    fun connect(target: DiscoveredDevice) {
        val myGeneration = ++generation
        cancelConnection()
        drainOutgoing()
        Logger.d(TAG, "Connecting to remote device: ${target.deviceName} (${target.hostAddress}:${target.tcpPort})")
        _activeTargetDevice.value = target
        _remotePlaybackState.value = null
        _connectionStatus.value = ConnectionStatus.CONNECTING

        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                httpClient.webSocket(
                    method = HttpMethod.Get,
                    host = target.hostAddress,
                    port = target.tcpPort,
                    path = "/simpconnect",
                ) {
                    currentSession = this
                    runSession(this, target)
                }
                if (myGeneration == generation && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (myGeneration == generation) {
                    Logger.e(TAG, "Connection to ${target.deviceName} failed: ${e.message}")
                    _connectionStatus.value = ConnectionStatus.FAILED
                }
            } finally {
                if (myGeneration == generation) {
                    currentSession = null
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    // Nothing queued for the failed session may leak into the next one.
                    drainOutgoing()
                    if (_connectionStatus.value == ConnectionStatus.CONNECTING ||
                        _connectionStatus.value == ConnectionStatus.CONNECTED ||
                        _connectionStatus.value == ConnectionStatus.WAITING_FOR_APPROVAL
                    ) {
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    }
                    _remotePlaybackState.value = null
                    _activeTargetDevice.value = null
                }
            }
        }
    }

    fun disconnect() {
        generation++
        cancelConnection()
        drainOutgoing()
        _activeTargetDevice.value = null
        _connectionStatus.value = ConnectionStatus.IDLE
        _remotePlaybackState.value = null
    }

    private fun drainOutgoing() {
        while (outgoing.tryReceive().isSuccess) {
            // Discard: they belong to a session that no longer exists.
        }
    }

    private fun cancelConnection() {
        connectionJob?.cancel()
        connectionJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        val session = currentSession
        currentSession = null
        if (session != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "User disconnected")) }
            }
        }
    }

    fun sendPlayPause() = sendCommand(SimpConnectMessage.PlayPause)
    fun sendPlay() = sendCommand(SimpConnectMessage.Play)
    fun sendPause() = sendCommand(SimpConnectMessage.Pause)
    fun sendNext() = sendCommand(SimpConnectMessage.Next)
    fun sendPrevious() = sendCommand(SimpConnectMessage.Previous)
    fun sendSeekTo(positionMs: Long) = sendCommand(SimpConnectMessage.SeekTo(positionMs))
    fun sendSetVolume(volume: Float) = sendCommand(SimpConnectMessage.SetVolume(volume))
    fun sendSetShuffle(enabled: Boolean) = sendCommand(SimpConnectMessage.SetShuffle(enabled))
    fun sendSetRepeat(mode: Int) = sendCommand(SimpConnectMessage.SetRepeat(mode))
    fun sendPlayTrack(
        videoId: String,
        playlistId: String? = null,
        playlistName: String? = null,
        queue: List<TrackSummary> = emptyList(),
        startIndex: Int = 0,
        playWhenReady: Boolean = true,
        positionMs: Long = 0L,
    ) = sendCommand(
        SimpConnectMessage.PlayTrack(
            videoId = videoId,
            playlistId = playlistId,
            playlistName = playlistName,
            queue = queue,
            startIndex = startIndex,
            playWhenReady = playWhenReady,
            positionMs = positionMs,
        ),
    )

    fun requestHandoff() = sendCommand(SimpConnectMessage.HandoffRequest)

    private fun sendCommand(msg: SimpConnectMessage) {
        // Enqueue only: the ordered sender lives for the duration of an accepted session. Before
        // that the message simply waits, which is exactly what a handoff immediately after
        // connect() needs.
        if (!outgoing.trySend(msg).isSuccess) {
            Logger.w(TAG, "Dropped command ${msg::class.simpleName}: outgoing channel closed")
        }
    }

    private suspend fun runSession(session: DefaultClientWebSocketSession, target: DiscoveredDevice) {
        // 1. Handshake: send Hello
        val savedKey = authStore.getSavedAuthKey(target.deviceId)
        val hello = SimpConnectMessage.Hello(
            deviceId = localDeviceId,
            deviceName = localDeviceName(),
            authKey = savedKey,
        )
        session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), hello)))

        // 2. Await HelloAck. Bounded: a server that accepts the socket but never answers used to
        // leave the controller stuck on CONNECTING forever. The bound is longer while the target
        // is waiting for its user to approve.
        var authenticated = false
        var handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS
        while (!authenticated && session.isActive) {
            val frame =
                withTimeoutOrNull(handshakeTimeoutMs) { session.incoming.receive() }
                    ?: throw IllegalStateException("Handshake timed out after ${handshakeTimeoutMs}ms")
            if (frame !is Frame.Text) continue

            val ack = runCatching {
                simpConnectJson.decodeFromString(SimpConnectMessage.serializer(), frame.readText())
            }.getOrNull()

            if (ack is SimpConnectMessage.HelloAck) {
                when (ack.status) {
                    SimpConnectMessage.HandshakeStatus.NEEDS_APPROVAL -> {
                        handshakeTimeoutMs = APPROVAL_TIMEOUT_MS
                        _connectionStatus.value = ConnectionStatus.WAITING_FOR_APPROVAL
                        Logger.d(TAG, "Waiting for user approval on ${target.deviceName}")
                    }
                    SimpConnectMessage.HandshakeStatus.ACCEPTED -> {
                        authenticated = true
                        if (ack.authKey != null) {
                            authStore.saveApprovedDevice(target.deviceId, ack.authKey)
                        }
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        Logger.d(TAG, "Connection accepted by ${target.deviceName}")
                    }
                    SimpConnectMessage.HandshakeStatus.REJECTED -> {
                        _connectionStatus.value = ConnectionStatus.REJECTED
                        Logger.w(TAG, "Connection rejected by ${target.deviceName}")
                        session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Rejected"))
                        return
                    }
                }
            }
        }

        coroutineScope {
            // 3. Ordered command sender. Starts only after the handshake, so the Hello is always
            // the first frame; anything queued while connecting is flushed here in order.
            val senderJob =
                launch(Dispatchers.IO) {
                    try {
                        for (msg in outgoing) {
                            if (!session.isActive) break
                            session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), msg)))
                        }
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Logger.w(TAG, "Failed to send command: ${e.message}")
                            // The socket is gone; close it so the read loop ends and the state
                            // becomes DISCONNECTED instead of silently swallowing commands.
                            runCatching { session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Send failed")) }
                        }
                    }
                }

            // 4. Heartbeat
            heartbeatJob =
                launch(Dispatchers.IO) {
                    while (isActive && session.isActive) {
                        delay(10_000)
                        try {
                            session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), SimpConnectMessage.Ping)))
                        } catch (_: Exception) {
                            runCatching { session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Heartbeat failed")) }
                            break
                        }
                    }
                }

            // 5. Message loop
            try {
                for (frame in session.incoming) {
                    if (frame is Frame.Text) {
                        val msg = runCatching {
                            simpConnectJson.decodeFromString(SimpConnectMessage.serializer(), frame.readText())
                        }.getOrNull() ?: continue

                        when (msg) {
                            is SimpConnectMessage.StateSync -> {
                                _remotePlaybackState.value = RemotePlaybackState(
                                    targetDevice = target,
                                    track = msg.track,
                                    isPlaying = msg.isPlaying,
                                    positionMs = msg.positionMs,
                                    durationMs = msg.durationMs,
                                    volume = msg.volume,
                                    isShuffle = msg.isShuffle,
                                    repeatMode = msg.repeatMode,
                                    queue = msg.queue,
                                    queueIndex = msg.queueIndex,
                                    // Local receipt time, never the sender's clock: the two devices'
                                    // clocks are not synchronised and the interpolation would jump.
                                    lastSyncTimestamp = System.currentTimeMillis(),
                                )
                            }
                            is SimpConnectMessage.HandoffPayload -> {
                                // Deliver outside this loop: handling the handoff disconnects, and
                                // that cancels the very coroutine running this loop.
                                val payload = msg
                                scope.launch { onHandoffReceived(payload) }
                            }
                            SimpConnectMessage.Pong -> Unit
                            else -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException && e !is ClosedReceiveChannelException) {
                    Logger.w(TAG, "Error in client message loop: ${e.message}")
                }
            } finally {
                // coroutineScope waits for its children: leaving the heartbeat sleeping in its
                // 10 s delay kept the connection marked CONNECTED long after the socket closed.
                senderJob.cancel()
                heartbeatJob?.cancel()
                heartbeatJob = null
            }
        }
    }
}
