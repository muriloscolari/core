package org.simpmusic.simpconnect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SIMP_CONNECT_DISCOVERY_PORT = 8764
const val SIMP_CONNECT_DEFAULT_SERVER_PORT = 8765
const val SIMP_CONNECT_MAGIC = "SIMPCONNECT_V1"

val simpConnectJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

@Serializable
sealed class DiscoveryMessage {
    @Serializable
    @SerialName("ping")
    data class Ping(
        val magic: String = SIMP_CONNECT_MAGIC,
        val deviceId: String,
        val deviceName: String,
        val platform: String,
        val tcpPort: Int = SIMP_CONNECT_DEFAULT_SERVER_PORT,
    ) : DiscoveryMessage()

    @Serializable
    @SerialName("pong")
    data class Pong(
        val magic: String = SIMP_CONNECT_MAGIC,
        val deviceId: String,
        val deviceName: String,
        val platform: String,
        val tcpPort: Int,
    ) : DiscoveryMessage()
}

@Serializable
data class TrackSummary(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUri: String? = null,
    val durationMs: Long = 0L,
)

@Serializable
sealed class SimpConnectMessage {
    // ========== Handshake ==========

    @Serializable
    @SerialName("hello")
    data class Hello(
        val deviceId: String,
        val deviceName: String,
        val authKey: String? = null,
    ) : SimpConnectMessage()

    @Serializable
    @SerialName("hello_ack")
    data class HelloAck(
        val status: HandshakeStatus,
        val deviceName: String,
        val authKey: String? = null,
    ) : SimpConnectMessage()

    @Serializable
    enum class HandshakeStatus {
        ACCEPTED,
        NEEDS_APPROVAL,
        REJECTED,
    }

    // ========== Playback State Sync ==========

    @Serializable
    @SerialName("state_sync")
    data class StateSync(
        val track: TrackSummary?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val volume: Float,
        val isShuffle: Boolean,
        val repeatMode: Int,
        val queue: List<TrackSummary> = emptyList(),
        val queueIndex: Int = 0,
        val timestampMs: Long = 0L,
    ) : SimpConnectMessage()

    // ========== Commands ==========

    @Serializable
    @SerialName("cmd_play_pause")
    data object PlayPause : SimpConnectMessage()

    @Serializable
    @SerialName("cmd_play")
    data object Play : SimpConnectMessage()

    @Serializable
    @SerialName("cmd_pause")
    data object Pause : SimpConnectMessage()

    @Serializable
    @SerialName("cmd_next")
    data object Next : SimpConnectMessage()

    @Serializable
    @SerialName("cmd_prev")
    data object Previous : SimpConnectMessage()

    @Serializable
    @SerialName("cmd_seek")
    data class SeekTo(val positionMs: Long) : SimpConnectMessage()

    @Serializable
    @SerialName("cmd_volume")
    data class SetVolume(val volume: Float) : SimpConnectMessage()

    @Serializable
    @SerialName("cmd_shuffle")
    data class SetShuffle(val enabled: Boolean) : SimpConnectMessage()

    @Serializable
    @SerialName("cmd_repeat")
    data class SetRepeat(val mode: Int) : SimpConnectMessage()

    @Serializable
    @SerialName("cmd_play_track")
    data class PlayTrack(
        val videoId: String,
        val playlistId: String? = null,
        val playlistName: String? = null,
        val queue: List<TrackSummary> = emptyList(),
        val startIndex: Int = 0,
        val playWhenReady: Boolean = true,
        /** Where to start inside the selected track. Without it a handoff restarted mid-song. */
        val positionMs: Long = 0L,
    ) : SimpConnectMessage()

    // ========== Playback Handoff ==========

    @Serializable
    @SerialName("handoff_request")
    data object HandoffRequest : SimpConnectMessage()

    @Serializable
    @SerialName("handoff_payload")
    data class HandoffPayload(
        val track: TrackSummary?,
        val positionMs: Long,
        val queue: List<TrackSummary>,
        val queueIndex: Int,
        val isPlaying: Boolean,
    ) : SimpConnectMessage()

    // ========== Heartbeat ==========

    @Serializable
    @SerialName("ping")
    data object Ping : SimpConnectMessage()

    @Serializable
    @SerialName("pong")
    data object Pong : SimpConnectMessage()
}
