package com.maxrave.domain.data.model.simpconnect

data class DiscoveredDeviceDomain(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val hostAddress: String,
    val tcpPort: Int,
    val isLocal: Boolean = false,
)

data class TrackSummaryDomain(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUri: String? = null,
    val durationMs: Long = 0L,
)

data class RemotePlaybackStateDomain(
    val targetDevice: DiscoveredDeviceDomain,
    val track: TrackSummaryDomain?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Float,
    val isShuffle: Boolean,
    val repeatMode: Int,
    val queue: List<TrackSummaryDomain> = emptyList(),
    val queueIndex: Int = 0,
)

enum class SimpConnectStatusDomain {
    IDLE,
    CONNECTING,
    WAITING_FOR_APPROVAL,
    CONNECTED,
    DISCONNECTED,
    REJECTED,
    FAILED,
}

data class SimpConnectAuthRequestDomain(
    val deviceId: String,
    val deviceName: String,
    val onResponse: (Boolean) -> Unit,
)
