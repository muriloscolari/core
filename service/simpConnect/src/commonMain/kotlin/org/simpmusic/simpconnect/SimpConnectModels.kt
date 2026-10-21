package org.simpmusic.simpconnect

import kotlinx.serialization.Serializable

@Serializable
data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val hostAddress: String,
    val tcpPort: Int,
    val lastSeenMs: Long,
)

data class RemotePlaybackState(
    val targetDevice: DiscoveredDevice,
    val track: TrackSummary?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Float,
    val isShuffle: Boolean,
    val repeatMode: Int,
    val queue: List<TrackSummary> = emptyList(),
    val queueIndex: Int = 0,
    val lastSyncTimestamp: Long = 0L,
)

enum class ConnectionStatus {
    IDLE,
    CONNECTING,
    WAITING_FOR_APPROVAL,
    CONNECTED,
    DISCONNECTED,
    REJECTED,
    FAILED,
}

data class AuthRequest(
    val deviceId: String,
    val deviceName: String,
    val onResponse: (Boolean) -> Unit,
)
