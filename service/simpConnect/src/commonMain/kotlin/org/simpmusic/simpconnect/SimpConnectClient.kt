package org.simpmusic.simpconnect

import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
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
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "SimpConnectClient"

class SimpConnectClient(
    private val localDeviceId: String,
    private val localDeviceName: () -> String,
    private val authStore: SimpConnectAuthStore,
    private val onHandoffReceived: (SimpConnectMessage.HandoffPayload) -> Unit,
    private val scope: CoroutineScope,
) {
    private val httpClient = HttpClient(getEngine()) {
        install(WebSockets)
    }

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _activeTargetDevice = MutableStateFlow<DiscoveredDevice?>(null)
    val activeTargetDevice: StateFlow<DiscoveredDevice?> = _activeTargetDevice.asStateFlow()

    private val _remotePlaybackState = MutableStateFlow<RemotePlaybackState?>(null)
    val remotePlaybackState: StateFlow<RemotePlaybackState?> = _remotePlaybackState.asStateFlow()

    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var currentSession: DefaultClientWebSocketSession? = null

    fun connect(target: DiscoveredDevice) {
        disconnect()
        Logger.d(TAG, "Connecting to remote device: ${target.deviceName} (${target.hostAddress}:${target.tcpPort})")
        _activeTargetDevice.value = target
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
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Logger.e(TAG, "Connection to ${target.deviceName} failed: ${e.message}")
                    _connectionStatus.value = ConnectionStatus.FAILED
                }
            } finally {
                currentSession = null
                heartbeatJob?.cancel()
                heartbeatJob = null
                if (_connectionStatus.value == ConnectionStatus.CONNECTING ||
                    _connectionStatus.value == ConnectionStatus.CONNECTED ||
                    _connectionStatus.value == ConnectionStatus.WAITING_FOR_APPROVAL
                ) {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
                _remotePlaybackState.value = null
            }
        }
    }

    fun disconnect() {
        if (_connectionStatus.value == ConnectionStatus.IDLE) return
        Logger.d(TAG, "Disconnecting from remote session")
        connectionJob?.cancel()
        connectionJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null

        scope.launch(Dispatchers.IO) {
            runCatching {
                currentSession?.close(CloseReason(CloseReason.Codes.NORMAL, "User disconnected"))
            }
            currentSession = null
        }

        _activeTargetDevice.value = null
        _connectionStatus.value = ConnectionStatus.IDLE
        _remotePlaybackState.value = null
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
    ) = sendCommand(SimpConnectMessage.PlayTrack(videoId, playlistId, playlistName, queue, startIndex))

    fun requestHandoff() = sendCommand(SimpConnectMessage.HandoffRequest)

    private fun sendCommand(msg: SimpConnectMessage) {
        val session = currentSession ?: return
        val json = simpConnectJson.encodeToString(SimpConnectMessage.serializer(), msg)
        scope.launch(Dispatchers.IO) {
            try {
                if (session.isActive) {
                    session.send(Frame.Text(json))
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to send command ${msg::class.simpleName}: ${e.message}")
            }
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

        // 2. Await HelloAck
        var authenticated = false
        while (!authenticated && session.isActive) {
            val frame = session.incoming.receive()
            if (frame is Frame.Text) {
                val ack = runCatching {
                    simpConnectJson.decodeFromString(SimpConnectMessage.serializer(), frame.readText())
                }.getOrNull()

                if (ack is SimpConnectMessage.HelloAck) {
                    when (ack.status) {
                        SimpConnectMessage.HandshakeStatus.NEEDS_APPROVAL -> {
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
        }

        // 3. Start Heartbeat
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && session.isActive) {
                delay(10_000)
                try {
                    session.send(Frame.Text(simpConnectJson.encodeToString(SimpConnectMessage.serializer(), SimpConnectMessage.Ping)))
                } catch (_: Exception) {}
            }
        }

        // 4. Message loop
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
                                lastSyncTimestamp = if (msg.timestampMs > 0) msg.timestampMs else System.currentTimeMillis(),
                            )
                        }
                        is SimpConnectMessage.HandoffPayload -> {
                            onHandoffReceived(msg)
                        }
                        is SimpConnectMessage.Pong -> {
                            // Pong received
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException && e !is ClosedReceiveChannelException) {
                Logger.w(TAG, "Error in client message loop: ${e.message}")
            }
        }
    }
}
