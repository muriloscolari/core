package com.maxrave.data.simpconnect

import android.os.Build
import com.maxrave.common.Config
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.model.simpconnect.DiscoveredDeviceDomain
import com.maxrave.domain.data.model.simpconnect.RemotePlaybackStateDomain
import com.maxrave.domain.data.model.simpconnect.SimpConnectAuthRequestDomain
import com.maxrave.domain.data.model.simpconnect.SimpConnectStatusDomain
import com.maxrave.domain.data.model.simpconnect.TrackSummaryDomain
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.repository.SimpConnectRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.simpmusic.simpconnect.AuthRequest
import org.simpmusic.simpconnect.ConnectionStatus
import org.simpmusic.simpconnect.DiscoveredDevice
import org.simpmusic.simpconnect.InMemorySimpConnectAuthStore
import org.simpmusic.simpconnect.SimpConnectAuthStore
import org.simpmusic.simpconnect.SimpConnectClient
import org.simpmusic.simpconnect.SimpConnectDiscovery
import org.simpmusic.simpconnect.SimpConnectMessage
import org.simpmusic.simpconnect.SimpConnectServer
import org.simpmusic.simpconnect.SimpConnectServerListener
import org.simpmusic.simpconnect.TrackSummary
import java.util.UUID

private const val TAG = "SimpConnectRepoAndroid"
private const val KEY_DEVICE_ID = "simpconnect_local_device_id"

class SimpConnectRepositoryImpl(
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope,
) : SimpConnectRepository, SimpConnectServerListener {

    private val localDeviceId: String = runBlocking {
        val existing = dataStoreManager.getString(KEY_DEVICE_ID).first()
        if (!existing.isNullOrBlank()) {
            existing
        } else {
            val generated = UUID.randomUUID().toString()
            dataStoreManager.putString(KEY_DEVICE_ID, generated)
            generated
        }
    }

    private val localDeviceNameString: String = Build.MODEL ?: "Android Device"

    private val authStore: SimpConnectAuthStore = InMemorySimpConnectAuthStore()

    private val _localDevice = MutableStateFlow(
        DiscoveredDeviceDomain(
            deviceId = localDeviceId,
            deviceName = localDeviceNameString,
            platform = "android",
            hostAddress = "127.0.0.1",
            tcpPort = 8765,
            isLocal = true,
        )
    )
    override val localDevice: StateFlow<DiscoveredDeviceDomain> = _localDevice.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDeviceDomain>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDeviceDomain>> = _discoveredDevices.asStateFlow()

    private val _activeRemoteDevice = MutableStateFlow<DiscoveredDeviceDomain?>(null)
    override val activeRemoteDevice: StateFlow<DiscoveredDeviceDomain?> = _activeRemoteDevice.asStateFlow()

    private val _remotePlaybackState = MutableStateFlow<RemotePlaybackStateDomain?>(null)
    override val remotePlaybackState: StateFlow<RemotePlaybackStateDomain?> = _remotePlaybackState.asStateFlow()

    private val _connectionStatus = MutableStateFlow(SimpConnectStatusDomain.IDLE)
    override val connectionStatus: StateFlow<SimpConnectStatusDomain> = _connectionStatus.asStateFlow()

    private val _incomingAuthRequest = MutableStateFlow<SimpConnectAuthRequestDomain?>(null)
    override val incomingAuthRequest: StateFlow<SimpConnectAuthRequestDomain?> = _incomingAuthRequest.asStateFlow()

    private val server: SimpConnectServer = SimpConnectServer(
        localDeviceId = localDeviceId,
        localDeviceName = { localDeviceNameString },
        authStore = authStore,
        listener = this,
        onAuthRequest = { req: AuthRequest ->
            _incomingAuthRequest.value = SimpConnectAuthRequestDomain(
                deviceId = req.deviceId,
                deviceName = req.deviceName,
                onResponse = { approved ->
                    req.onResponse(approved)
                    _incomingAuthRequest.value = null
                }
            )
        },
        scope = scope,
    )

    private val discovery: SimpConnectDiscovery = SimpConnectDiscovery(
        localDeviceId = localDeviceId,
        localDeviceName = { localDeviceNameString },
        localPlatform = "android",
        getTcpPort = { server.getPort() },
        scope = scope,
    )

    private val client: SimpConnectClient = SimpConnectClient(
        localDeviceId = localDeviceId,
        localDeviceName = { localDeviceNameString },
        authStore = authStore,
        onHandoffReceived = { payload -> handleHandoffReceived(payload) },
        scope = scope,
    )

    private var started = false

    override fun start() {
        if (started) return
        started = true
        Logger.d(TAG, "Starting SimpConnectRepository for $localDeviceNameString")

        scope.launch(Dispatchers.IO) {
            val port = server.start()
            _localDevice.value = _localDevice.value.copy(tcpPort = port)
            discovery.start()
        }

        // Bridge discovery updates to domain flow
        scope.launch {
            discovery.discoveredDevices.collect { list ->
                _discoveredDevices.value = list.map { it.toDomain() }
            }
        }

        // Bridge client state
        scope.launch {
            client.connectionStatus.collect { status ->
                _connectionStatus.value = when (status) {
                    ConnectionStatus.IDLE -> SimpConnectStatusDomain.IDLE
                    ConnectionStatus.CONNECTING -> SimpConnectStatusDomain.CONNECTING
                    ConnectionStatus.WAITING_FOR_APPROVAL -> SimpConnectStatusDomain.WAITING_FOR_APPROVAL
                    ConnectionStatus.CONNECTED -> SimpConnectStatusDomain.CONNECTED
                    ConnectionStatus.DISCONNECTED -> SimpConnectStatusDomain.DISCONNECTED
                    ConnectionStatus.REJECTED -> SimpConnectStatusDomain.REJECTED
                    ConnectionStatus.FAILED -> SimpConnectStatusDomain.FAILED
                }
                if (status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.FAILED || status == ConnectionStatus.IDLE) {
                    _activeRemoteDevice.value = null
                }
            }
        }

        // Bridge remote playback state
        scope.launch {
            client.remotePlaybackState.collect { remote ->
                _remotePlaybackState.value = remote?.toDomain()
            }
        }

        // Watch local player and broadcast state to connected controllers
        scope.launch {
            mediaPlayerHandler.nowPlaying.collect { broadcastCurrentState() }
        }
        scope.launch {
            mediaPlayerHandler.controlState.collect { broadcastCurrentState() }
        }
        scope.launch {
            mediaPlayerHandler.simpleMediaState.collect { broadcastCurrentState() }
        }
        scope.launch {
            mediaPlayerHandler.queueData.collect { broadcastCurrentState() }
        }

        // Periodic ticker to sync playback position to connected controllers while playing
        scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(500)
                if (server.connectedControllersCount.value > 0 && mediaPlayerHandler.controlState.value.isPlaying) {
                    broadcastCurrentState()
                }
            }
        }
    }

    override fun refreshDiscovery() {
        discovery.broadcastPing()
    }

    override fun stopDiscovery() {
        discovery.stop()
    }

    override fun connectToDevice(device: DiscoveredDeviceDomain) {
        val wireDevice = DiscoveredDevice(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            platform = device.platform,
            hostAddress = device.hostAddress,
            tcpPort = device.tcpPort,
            lastSeenMs = System.currentTimeMillis(),
        )
        _activeRemoteDevice.value = device
        client.connect(wireDevice)
    }

    override fun disconnect() {
        client.disconnect()
        _activeRemoteDevice.value = null
    }

    override fun sendPlayPause() {
        client.sendPlayPause()
    }

    override fun sendPlay() {
        client.sendPlay()
    }

    override fun sendPause() {
        client.sendPause()
    }

    override fun sendNext() {
        client.sendNext()
    }

    override fun sendPrevious() {
        client.sendPrevious()
    }

    override fun sendSeekTo(positionMs: Long) {
        client.sendSeekTo(positionMs)
    }

    override fun sendSetVolume(volume: Float) {
        client.sendSetVolume(volume)
    }

    override fun sendSetShuffle(enabled: Boolean) {
        client.sendSetShuffle(enabled)
    }

    override fun sendSetRepeat(mode: Int) {
        client.sendSetRepeat(mode)
    }

    override fun sendPlayTrack(
        videoId: String,
        playlistId: String?,
        playlistName: String?,
        queue: List<TrackSummaryDomain>,
        startIndex: Int,
    ) {
        val wireQueue = queue.map { it.toWire() }
        client.sendPlayTrack(videoId, playlistId, playlistName, wireQueue, startIndex)
    }

    override fun requestHandoff() {
        client.requestHandoff()
    }

    override fun handoffToRemote(device: DiscoveredDeviceDomain) {
        connectToDevice(device)
        val nowPlaying = mediaPlayerHandler.nowPlaying.value
        val queue = mediaPlayerHandler.queueData.value?.data?.listTracks.orEmpty()
        val currentIndex = mediaPlayerHandler.currentSongIndex()

        scope.launch {
            // Pause local playback
            mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)

            nowPlaying?.let { item ->
                val trackSummary = item.toTrackSummary()
                val wireQueue = queue.map { it.toTrackSummary() }
                client.sendPlayTrack(
                    videoId = trackSummary.videoId,
                    playlistId = null,
                    playlistName = null,
                    queue = wireQueue,
                    startIndex = currentIndex.coerceAtLeast(0),
                )
            }
        }
    }

    // ==========================================
    // SimpConnectServerListener callbacks (Target mode)
    // ==========================================

    override fun onPlayPause() {
        scope.launch { mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause) }
    }

    override fun onPlay() {
        val isPlaying = mediaPlayerHandler.controlState.value.isPlaying
        if (!isPlaying) {
            scope.launch { mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause) }
        }
    }

    override fun onPause() {
        val isPlaying = mediaPlayerHandler.controlState.value.isPlaying
        if (isPlaying) {
            scope.launch { mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause) }
        }
    }

    override fun onNext() {
        scope.launch { mediaPlayerHandler.onPlayerEvent(PlayerEvent.Next) }
    }

    override fun onPrevious() {
        scope.launch { mediaPlayerHandler.onPlayerEvent(PlayerEvent.Previous) }
    }

    override fun onSeekTo(positionMs: Long) {
        val duration = mediaPlayerHandler.getPlayerDuration()
        if (duration > 0) {
            val fraction = (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            scope.launch { mediaPlayerHandler.onPlayerEvent(PlayerEvent.UpdateProgress(fraction)) }
        }
    }

    override fun onSetVolume(volume: Float) {
        scope.launch { mediaPlayerHandler.onPlayerEvent(PlayerEvent.UpdateVolume(volume)) }
    }

    override fun onSetShuffle(enabled: Boolean) {
        val currentShuffle = mediaPlayerHandler.controlState.value.isShuffle
        if (currentShuffle != enabled) {
            scope.launch { mediaPlayerHandler.onPlayerEvent(PlayerEvent.Shuffle) }
        }
    }

    override fun onSetRepeat(mode: Int) {
        scope.launch { mediaPlayerHandler.onPlayerEvent(PlayerEvent.Repeat) }
    }

    override fun onPlayTrack(message: SimpConnectMessage.PlayTrack) {
        scope.launch {
            val track = Track(
                album = null,
                artists = emptyList(),
                duration = null,
                durationSeconds = null,
                isAvailable = true,
                isExplicit = false,
                likeStatus = null,
                thumbnails = emptyList(),
                title = "",
                videoId = message.videoId,
                videoType = null,
                category = null,
                feedbackTokens = null,
                resultType = null,
            )
            mediaPlayerHandler.loadMediaItem(track, type = MERGING_DATA_TYPE.SONG, index = message.startIndex)
        }
    }

    override fun getCurrentState(): SimpConnectMessage.StateSync {
        val nowPlaying = mediaPlayerHandler.nowPlaying.value
        val controlState = mediaPlayerHandler.controlState.value
        val duration = mediaPlayerHandler.getPlayerDuration()
        val progress = mediaPlayerHandler.getProgress()
        val queue = mediaPlayerHandler.queueData.value?.data?.listTracks.orEmpty()
        val currentIndex = mediaPlayerHandler.currentSongIndex()

        val repeatModeInt = when (controlState.repeatState) {
            RepeatState.None -> 0
            RepeatState.All -> 1
            RepeatState.One -> 2
        }

        return SimpConnectMessage.StateSync(
            track = nowPlaying?.toTrackSummary(),
            isPlaying = controlState.isPlaying,
            positionMs = progress,
            durationMs = duration,
            volume = 1f,
            isShuffle = controlState.isShuffle,
            repeatMode = repeatModeInt,
            queue = queue.map { it.toTrackSummary() },
            queueIndex = currentIndex,
            timestampMs = System.currentTimeMillis(),
        )
    }

    override fun getHandoffPayload(): SimpConnectMessage.HandoffPayload {
        val state = getCurrentState()
        return SimpConnectMessage.HandoffPayload(
            track = state.track,
            positionMs = state.positionMs,
            queue = state.queue,
            queueIndex = state.queueIndex,
            isPlaying = state.isPlaying,
        )
    }

    private fun broadcastCurrentState() {
        if (server.connectedControllersCount.value > 0) {
            server.broadcastStateSync(getCurrentState())
        }
    }

    private fun handleHandoffReceived(payload: SimpConnectMessage.HandoffPayload) {
        Logger.d(TAG, "Handoff payload received: ${payload.track?.title}")
        // Pause remote playback & disconnect
        client.sendPause()
        disconnect()

        val trackSummary = payload.track ?: return
        scope.launch {
            val track = trackSummary.toTrack()
            mediaPlayerHandler.loadMediaItem(track, type = MERGING_DATA_TYPE.SONG, index = payload.queueIndex)
            if (payload.positionMs > 0) {
                // Seek to transferred position after short delay
                kotlinx.coroutines.delay(300)
                val duration = mediaPlayerHandler.getPlayerDuration()
                if (duration > 0) {
                    val fraction = (payload.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.UpdateProgress(fraction))
                }
            }
        }
    }

    // ==========================================
    // Mappers
    // ==========================================

    private fun DiscoveredDevice.toDomain(): DiscoveredDeviceDomain {
        return DiscoveredDeviceDomain(
            deviceId = deviceId,
            deviceName = deviceName,
            platform = platform,
            hostAddress = hostAddress,
            tcpPort = tcpPort,
            isLocal = false,
        )
    }

    private fun org.simpmusic.simpconnect.RemotePlaybackState.toDomain(): RemotePlaybackStateDomain {
        return RemotePlaybackStateDomain(
            targetDevice = targetDevice.toDomain(),
            track = track?.toDomain(),
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            volume = volume,
            isShuffle = isShuffle,
            repeatMode = repeatMode,
            queue = queue.map { it.toDomain() },
            queueIndex = queueIndex,
            lastSyncTimestamp = lastSyncTimestamp,
        )
    }

    private fun TrackSummary.toDomain(): TrackSummaryDomain {
        return TrackSummaryDomain(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            artworkUri = artworkUri,
            durationMs = durationMs,
        )
    }

    private fun TrackSummaryDomain.toWire(): TrackSummary {
        return TrackSummary(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            artworkUri = artworkUri,
            durationMs = durationMs,
        )
    }

    private fun GenericMediaItem.toTrackSummary(): TrackSummary {
        return TrackSummary(
            videoId = mediaId,
            title = metadata.title.orEmpty(),
            artist = metadata.artist.orEmpty(),
            album = metadata.albumTitle,
            artworkUri = metadata.artworkUri,
            durationMs = metadata.durationMs ?: 0L,
        )
    }

    private fun Track.toTrackSummary(): TrackSummary {
        return TrackSummary(
            videoId = videoId,
            title = title,
            artist = artists?.firstOrNull()?.name.orEmpty(),
            album = album?.name,
            artworkUri = thumbnails?.lastOrNull()?.url,
            durationMs = (durationSeconds ?: 0) * 1000L,
        )
    }

    private fun TrackSummary.toTrack(): Track {
        return Track(
            album = album?.let { Album(name = it, id = "") },
            artists = listOf(Artist(name = artist, id = null)),
            duration = null,
            durationSeconds = (durationMs / 1000).toInt(),
            isAvailable = true,
            isExplicit = false,
            likeStatus = null,
            thumbnails = artworkUri?.let { listOf(Thumbnail(url = it, width = 500, height = 500)) },
            title = title,
            videoId = videoId,
            videoType = null,
            category = null,
            feedbackTokens = null,
            resultType = null,
        )
    }
}
