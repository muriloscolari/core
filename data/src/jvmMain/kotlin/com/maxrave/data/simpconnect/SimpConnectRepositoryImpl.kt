package com.maxrave.data.simpconnect

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
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.repository.SimpConnectRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.simpmusic.loginsync.localDeviceName
import org.simpmusic.simpconnect.AuthRequest
import org.simpmusic.simpconnect.ConnectionStatus
import org.simpmusic.simpconnect.DiscoveredDevice
import org.simpmusic.simpconnect.SimpConnectAuthStore
import org.simpmusic.simpconnect.SimpConnectClient
import org.simpmusic.simpconnect.SimpConnectDiscovery
import org.simpmusic.simpconnect.SimpConnectMessage
import org.simpmusic.simpconnect.SimpConnectServer
import org.simpmusic.simpconnect.SimpConnectServerListener
import org.simpmusic.simpconnect.TrackSummary
import java.util.UUID

private const val TAG = "SimpConnectRepoJvm"
private const val KEY_DEVICE_ID = "simpconnect_local_device_id"
private const val KEY_APPROVED_DEVICES = "simpconnect_approved_devices"

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

    private val localDeviceNameString: String by lazy {
        runCatching { localDeviceName().name }.getOrDefault("SimpMusic Desktop")
    }

    private val authStore: SimpConnectAuthStore = PersistentSimpConnectAuthStore(dataStoreManager, scope)

    private val _localDevice = MutableStateFlow(
        DiscoveredDeviceDomain(
            deviceId = localDeviceId,
            deviceName = localDeviceNameString,
            platform = "desktop",
            hostAddress = "127.0.0.1",
            tcpPort = 8765,
            isLocal = true,
        )
    )
    override val localDevice: StateFlow<DiscoveredDeviceDomain> = _localDevice.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDeviceDomain>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDeviceDomain>> = _discoveredDevices.asStateFlow()

    private val _pendingDevice = MutableStateFlow<DiscoveredDeviceDomain?>(null)
    override val pendingDevice: StateFlow<DiscoveredDeviceDomain?> = _pendingDevice.asStateFlow()

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
            // Only the newest request is shown: a second controller that asks while the dialog is
            // open replaces the first, which then simply times out on the server side.
            var answered = false
            var domainRequest: SimpConnectAuthRequestDomain? = null
            val created = SimpConnectAuthRequestDomain(
                deviceId = req.deviceId,
                deviceName = req.deviceName,
                onResponse = { approved ->
                    if (!answered) {
                        answered = true
                        req.onResponse(approved)
                        // Clear only if this is still the request on screen: a timeout for an old
                        // request must not dismiss the dialog of a newer one.
                        _incomingAuthRequest.compareAndSet(domainRequest, null)
                    }
                }
            )
            domainRequest = created
            _incomingAuthRequest.value = created
        },
        scope = scope,
    )

    private val discovery: SimpConnectDiscovery = SimpConnectDiscovery(
        localDeviceId = localDeviceId,
        localDeviceName = { localDeviceNameString },
        localPlatform = "desktop",
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

    /** Serialises remote PlayTrack handling so two quick requests cannot apply out of order. */
    private val playTrackMutex = Mutex()

    override fun start() {
        if (started) return
        started = true
        Logger.d(TAG, "Starting SimpConnectRepository for $localDeviceNameString")

        scope.launch(Dispatchers.IO) {
            val port =
                runCatching { server.start() }
                    .onFailure { Logger.e(TAG, "SimpConnect server failed to start: ${it.message}") }
                    .getOrDefault(0)
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
                when (status) {
                    ConnectionStatus.CONNECTED -> {
                        _activeRemoteDevice.value = _pendingDevice.value
                        _pendingDevice.value = null
                        suspendLocalPlayer()
                    }
                    ConnectionStatus.CONNECTING, ConnectionStatus.WAITING_FOR_APPROVAL -> Unit
                    ConnectionStatus.IDLE,
                    ConnectionStatus.DISCONNECTED,
                    ConnectionStatus.REJECTED,
                    ConnectionStatus.FAILED,
                    -> {
                        _activeRemoteDevice.value = null
                        _pendingDevice.value = null
                        resumeLocalPlayer()
                    }
                }
            }
        }

        // Bridge remote playback state
        scope.launch {
            client.remotePlaybackState.collect { remote ->
                _remotePlaybackState.value = remote?.toDomain()
            }
        }

        // Watch local player and broadcast state to connected controllers.
        // Deliberately NOT simpleMediaState: its progress tick fired several broadcasts per second,
        // flooding the LAN and making the controller's interpolated timeline jump around.
        scope.launch {
            mediaPlayerHandler.nowPlaying.collect { broadcastCurrentState() }
        }
        scope.launch {
            mediaPlayerHandler.controlState.collect { broadcastCurrentState() }
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

    /**
     * True while the local player was stopped because this device became a controller.
     * Pausing was not enough: a paused player keeps buffering and retrying chunk loads, and every
     * retry went through the stream resolver, flooding the log with "Stream: No URL found" and
     * starving the controller's CPU/network while it should only be mirroring the target.
     */
    @Volatile
    private var localPlayerSuspended = false

    private fun suspendLocalPlayer() {
        if (localPlayerSuspended) return
        if (mediaPlayerHandler.player.mediaItemCount == 0 &&
            mediaPlayerHandler.nowPlaying.value == null
        ) {
            return
        }
        localPlayerSuspended = true
        scope.launch {
            try {
                val player = mediaPlayerHandler.player
                player.playWhenReady = false
                player.stop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.w(TAG, "Failed to suspend local player: ${error.message}")
            }
        }
    }

    private fun resumeLocalPlayer() {
        if (!localPlayerSuspended) return
        localPlayerSuspended = false
        scope.launch {
            try {
                val player = mediaPlayerHandler.player
                // The stopped player retains its queue and position. Keep it paused when control
                // returns instead of restoring an unrelated persisted queue.
                player.playWhenReady = false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.w(TAG, "Failed to resume local player: ${error.message}")
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
        _pendingDevice.value = device
        _activeRemoteDevice.value = null
        client.connect(wireDevice)
    }

    override fun disconnect() {
        _pendingDevice.value = null
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
        playWhenReady: Boolean,
        positionMs: Long,
    ) {
        val wireQueue = queue.map { it.toWire() }
        client.sendPlayTrack(
            videoId = videoId,
            playlistId = playlistId,
            playlistName = playlistName,
            queue = wireQueue,
            startIndex = startIndex,
            playWhenReady = playWhenReady,
            positionMs = positionMs,
        )
    }

    override fun requestHandoff() {
        client.requestHandoff()
    }

    override fun handoffToRemote(device: DiscoveredDeviceDomain) {
        val nowPlaying = mediaPlayerHandler.nowPlaying.value ?: return
        val queue = mediaPlayerHandler.queueData.value?.data?.listTracks.orEmpty()
        val trackSummary = nowPlaying.toTrackSummary()
        val rawQueue = queue.map { it.toTrackSummary() }
        // queueData can lag behind the player (radio/restored queue). If it does not contain what is
        // actually playing, send only that track instead of starting the wrong song on the target.
        val foundIndex = rawQueue.indexOfFirst { it.videoId == trackSummary.videoId }
        val wireQueue = if (foundIndex >= 0 || rawQueue.isEmpty()) rawQueue else listOf(trackSummary)
        val startIndex = if (foundIndex >= 0) foundIndex else 0
        val wasPlaying = mediaPlayerHandler.controlState.value.isPlaying
        // Captured before connecting: the connection callback pauses the local player, which would
        // otherwise make the target restart the song from zero.
        val startPosition = mediaPlayerHandler.getProgress().coerceAtLeast(0L)

        connectToDevice(device)
        scope.launch {
            val result =
                kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                    connectionStatus.first {
                        it == SimpConnectStatusDomain.CONNECTED ||
                            it == SimpConnectStatusDomain.FAILED ||
                            it == SimpConnectStatusDomain.REJECTED ||
                            it == SimpConnectStatusDomain.DISCONNECTED
                    }
                }
            if (result != SimpConnectStatusDomain.CONNECTED) {
                Logger.w(TAG, "Handoff cancelled: could not connect to ${device.deviceName}")
                return@launch
            }
            if (mediaPlayerHandler.controlState.value.isPlaying) {
                mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
            }
            client.sendPlayTrack(
                videoId = trackSummary.videoId,
                playlistId = null,
                playlistName = null,
                queue = wireQueue,
                startIndex = startIndex,
                playWhenReady = wasPlaying,
                positionMs = startPosition,
            )
        }
    }

    override fun transferToDevice(device: DiscoveredDeviceDomain) {
        val remote = _remotePlaybackState.value
        if (remote == null) {
            // Not actually controlling anything (yet). Normal local handoff is the right move.
            handoffToRemote(device)
            return
        }

        val track = remote.track
        val queue = remote.queue
        val startIndex = remote.queueIndex
        val wasPlaying = remote.isPlaying
        // The state arrived up to 500 ms ago; carry the elapsed time so the new target resumes at
        // the same point instead of at the position of the last sync.
        val elapsed =
            if (remote.isPlaying) {
                (System.currentTimeMillis() - remote.lastSyncTimestamp).coerceAtLeast(0L)
            } else {
                0L
            }
        val positionMs =
            (remote.positionMs.coerceAtLeast(0L) + elapsed).let { position ->
                val total = remote.durationMs.takeIf { it > 0L } ?: track?.durationMs ?: 0L
                if (total > 0L) position.coerceAtMost(total) else position
            }
        // Same guard as the local handoff: never start a track that is not the one being played.
        var sendQueue = queue
        var sendIndex = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        if (track != null) {
            val found = queue.indexOfFirst { it.videoId == track.videoId }
            if (found >= 0) {
                sendIndex = found
            } else {
                sendQueue = listOf(track)
                sendIndex = 0
            }
        }

        scope.launch {
            // Silence the old target first: disconnect alone leaves it playing, so both devices
            // would be audible at the same time.
            client.sendPause()
            kotlinx.coroutines.delay(150)
            client.disconnect()
            _activeRemoteDevice.value = null
            _remotePlaybackState.value = null

            connectToDevice(device)
            val result =
                kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                    connectionStatus.first {
                        it == SimpConnectStatusDomain.CONNECTED ||
                            it == SimpConnectStatusDomain.FAILED ||
                            it == SimpConnectStatusDomain.REJECTED ||
                            it == SimpConnectStatusDomain.DISCONNECTED
                    }
                }
            if (result != SimpConnectStatusDomain.CONNECTED) {
                Logger.w(TAG, "Transfer cancelled: could not connect to ${device.deviceName}")
                return@launch
            }
            val videoId = track?.videoId ?: sendQueue.getOrNull(sendIndex)?.videoId ?: return@launch
            client.sendPlayTrack(
                videoId = videoId,
                playlistId = null,
                playlistName = null,
                queue = sendQueue.map { it.toWire() },
                startIndex = sendIndex,
                playWhenReady = wasPlaying,
                positionMs = positionMs,
            )
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
        val target =
            if (duration > 0L) {
                positionMs.coerceIn(0L, duration)
            } else {
                positionMs.coerceAtLeast(0L)
            }
        mediaPlayerHandler.player.seekTo(target)
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
        scope.launch {
            val target = when (mode) {
                1 -> RepeatState.All
                2 -> RepeatState.One
                else -> RepeatState.None
            }
            var attempts = 0
            while (mediaPlayerHandler.controlState.value.repeatState != target && attempts < 3) {
                mediaPlayerHandler.onPlayerEvent(PlayerEvent.Repeat)
                attempts++
                kotlinx.coroutines.delay(60)
            }
        }
    }

    override fun onPlayTrack(message: SimpConnectMessage.PlayTrack) {
        scope.launch {
            playTrackMutex.withLock {
                if (message.queue.isNotEmpty()) {
                    playRemoteQueue(
                        tracks = message.queue,
                        startIndex = message.startIndex,
                        positionMs = message.positionMs,
                        playWhenReady = message.playWhenReady,
                    )
                    return@launch
                }
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
                mediaPlayerHandler.loadMediaItem(track, type = MERGING_DATA_TYPE.SONG, index = null)
                if (message.positionMs > 0L) {
                    kotlinx.coroutines.delay(400)
                    if (mediaPlayerHandler.getPlayerDuration() > 0L) {
                        mediaPlayerHandler.player.seekTo(message.positionMs)
                    }
                }
                if (!message.playWhenReady && mediaPlayerHandler.controlState.value.isPlaying) {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                }
            }
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
            positionMs = progress.coerceAtLeast(0L),
            durationMs = duration.coerceAtLeast(0L),
            volume = controlState.volume,
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
        // The target pauses itself before sending the payload, so just stop mirroring and resume.
        disconnect()

        val trackSummary = payload.track ?: return
        scope.launch {
            if (payload.queue.isNotEmpty()) {
                playRemoteQueue(
                    tracks = payload.queue,
                    startIndex = payload.queueIndex,
                    positionMs = payload.positionMs,
                    playWhenReady = payload.isPlaying,
                )
            } else {
                val track = trackSummary.toTrack()
                mediaPlayerHandler.loadMediaItem(track, type = MERGING_DATA_TYPE.SONG, index = null)
                if (payload.positionMs > 0) {
                    kotlinx.coroutines.delay(400)
                    if (mediaPlayerHandler.getPlayerDuration() > 0) {
                        mediaPlayerHandler.player.seekTo(payload.positionMs)
                    }
                }
                if (!payload.isPlaying && mediaPlayerHandler.controlState.value.isPlaying) {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                }
            }
        }
    }

    private suspend fun playRemoteQueue(
        tracks: List<TrackSummary>,
        startIndex: Int,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        if (tracks.isEmpty()) return
        val domainTracks = tracks.map { it.toTrack() }
        val index = startIndex.coerceIn(0, domainTracks.lastIndex)

        val player = mediaPlayerHandler.player
        player.shuffleModeEnabled = false
        player.clearMediaItems()
        for (track in domainTracks) {
            player.addMediaItem(track.toGenericMediaItem())
        }
        mediaPlayerHandler.setQueueData(
            QueueData.Data(
                listTracks = domainTracks,
                firstPlayedTrack = domainTracks[index],
                playlistId = null,
                playlistName = null,
                playlistType = null,
                continuation = null,
            ),
        )
        mediaPlayerHandler.setCurrentSongIndex(index)
        // Not playMediaItemInMediaSource(): it forces playWhenReady = true, so a paused handoff
        // started playing for a moment before being paused again.
        player.seekTo(index, 0L)
        player.prepare()
        player.playWhenReady = playWhenReady

        if (positionMs > 0L) {
            kotlinx.coroutines.delay(400)
            player.seekTo(positionMs)
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

/**
 * Survives app restarts so reconnecting to an already-approved device does not ask for approval
 * every time. Keys issued by this device and keys issued by the peer share one map, keyed by the
 * other device's id.
 */
private class PersistentSimpConnectAuthStore(
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope,
) : SimpConnectAuthStore {
    private val approvedKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        runCatching {
            runBlocking {
                dataStoreManager.getString(KEY_APPROVED_DEVICES).first()?.let { raw ->
                    raw.lineSequence().forEach { line ->
                        val separator = line.indexOf('=')
                        if (separator > 0) {
                            val deviceId = line.substring(0, separator)
                            val key = line.substring(separator + 1)
                            if (deviceId.isNotBlank() && key.isNotBlank()) {
                                approvedKeys[deviceId] = key
                            }
                        }
                    }
                }
            }
        }
    }

    override fun isDeviceApproved(deviceId: String, authKey: String?): Boolean {
        if (authKey.isNullOrBlank()) return false
        return approvedKeys[deviceId] == authKey
    }

    override fun saveApprovedDevice(deviceId: String, authKey: String) {
        approvedKeys[deviceId] = authKey
        persist()
    }

    override fun getSavedAuthKey(deviceId: String): String? = approvedKeys[deviceId]

    private val persistMutex = Mutex()

    private fun persist() {
        // Snapshot inside the lock: two approvals in quick succession would otherwise race and
        // the older snapshot could be the one left on disk, forgetting the newer device.
        scope.launch(Dispatchers.IO) {
            persistMutex.withLock {
                val snapshot = approvedKeys.entries.joinToString("\n") { "${it.key}=${it.value}" }
                runCatching { dataStoreManager.putString(KEY_APPROVED_DEVICES, snapshot) }
            }
        }
    }
}
