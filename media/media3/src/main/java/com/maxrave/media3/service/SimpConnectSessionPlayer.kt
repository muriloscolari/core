package com.maxrave.media3.service

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.model.simpconnect.RemotePlaybackStateDomain
import com.maxrave.domain.repository.SimpConnectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [Player] wrapper used **only by the MediaSession** so the system notification, Bluetooth
 * buttons, Android Auto and the media resumption chip operate the device that is actually playing
 * while SimpConnect is in remote-control mode.
 *
 * The local player keeps owning the local queue. While a remote target is connected this wrapper
 * reports the remote track/state and routes transport calls to [SimpConnectRepository]. On
 * disconnect it falls back to the wrapped player, which is the normal Cast-aware session player.
 *
 * MediaSession builds the notification from its session player, so merely forwarding commands was
 * not enough: without the metadata/state overrides the notification still showed the local track
 * (or nothing) while the remote device was playing something else.
 */
@UnstableApi
internal class SimpConnectSessionPlayer(
    private val delegate: Player,
    private val repository: SimpConnectRepository,
    private val scope: CoroutineScope,
) : ForwardingPlayer(delegate) {
    private val listeners = CopyOnWriteArrayList<Player.Listener>()

    @Volatile
    private var remoteDeviceActive: Boolean = false

    @Volatile
    private var remote: RemotePlaybackStateDomain? = null

    private var lastVideoId: String? = null
    private var lastIsPlaying: Boolean? = null
    private var lastRepeatMode: Int? = null
    private var lastShuffle: Boolean? = null
    private var lastRemoteActive: Boolean? = null

    init {
        scope.launch {
            combine(repository.activeRemoteDevice, repository.remotePlaybackState) { device, state ->
                device to state
            }.collect { (device, state) ->
                val active = device != null
                remoteDeviceActive = active
                remote = if (active) state else null
                dispatchStateChanged()
            }
        }
    }

    private fun isRemoteActive(): Boolean = remoteDeviceActive

    // ========== Listener tracking ==========

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    private fun dispatchStateChanged() {
        val state = remote
        val active = remoteDeviceActive
        val track = state?.track

        // On disconnect restore the local notification with the delegate's own item/state rather
        // than clearing it: the local player may not emit anything until the next user action.
        val mediaItem = if (active) remoteMediaItem() else delegate.currentMediaItem
        val metadata = if (active) mediaItem?.mediaMetadata ?: MediaMetadata.EMPTY else delegate.mediaMetadata
        val isPlaying = if (active) state?.isPlaying == true else delegate.isPlaying
        val repeatMode = if (active) remoteRepeatMode() else delegate.repeatMode
        val shuffle = if (active) state?.isShuffle == true else delegate.shuffleModeEnabled

        val activeChanged = lastRemoteActive != active
        lastRemoteActive = active

        listeners.forEach { listener ->
            try {
                if (activeChanged) {
                    listener.onAvailableCommandsChanged(availableCommands)
                }
                if (lastVideoId != track?.videoId) {
                    lastVideoId = track?.videoId
                    listener.onMediaItemTransition(
                        mediaItem,
                        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                    )
                    listener.onMediaMetadataChanged(metadata)
                }
                if (lastIsPlaying != isPlaying) {
                    lastIsPlaying = isPlaying
                    listener.onIsPlayingChanged(isPlaying)
                    listener.onPlayWhenReadyChanged(
                        isPlaying,
                        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                    )
                }
                listener.onPlaybackStateChanged(playbackState)
                if (lastRepeatMode != repeatMode) {
                    lastRepeatMode = repeatMode
                    listener.onRepeatModeChanged(repeatMode)
                }
                if (lastShuffle != shuffle) {
                    lastShuffle = shuffle
                    listener.onShuffleModeEnabledChanged(shuffle)
                }
            } catch (_: Exception) {
                // A controller disconnecting mid-dispatch must not break the others.
            }
        }
    }

    private fun remoteMediaItem(): MediaItem? {
        val track = remote?.track ?: return null
        val metadata =
            MediaMetadata
                .Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .apply {
                    track.album?.takeIf { it.isNotBlank() }?.let { setAlbumTitle(it) }
                    track.artworkUri?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) }
                    if (track.durationMs > 0L) setDurationMs(track.durationMs)
                }.build()

        return MediaItem
            .Builder()
            .setMediaId(track.videoId)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun remoteRepeatMode(): Int =
        when (remote?.repeatMode) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }

    private fun remotePositionMs(): Long {
        val state = remote ?: return 0L
        val total = state.durationMs.takeIf { it > 0L } ?: state.track?.durationMs ?: 0L
        val elapsed =
            if (state.isPlaying && state.lastSyncTimestamp > 0L) {
                (System.currentTimeMillis() - state.lastSyncTimestamp).coerceAtLeast(0L)
            } else {
                0L
            }
        val position = state.positionMs.coerceAtLeast(0L) + elapsed
        return if (total > 0L) position.coerceAtMost(total) else position
    }

    private fun remoteDurationMs(): Long {
        val state = remote ?: return C.TIME_UNSET
        return state.durationMs.takeIf { it > 0L } ?: state.track?.durationMs?.takeIf { it > 0L } ?: C.TIME_UNSET
    }

    // ========== State reported to MediaSession ==========

    override fun getPlaybackState(): Int =
        if (isRemoteActive()) {
            if (remote?.track != null) Player.STATE_READY else Player.STATE_IDLE
        } else {
            super.getPlaybackState()
        }

    override fun isPlaying(): Boolean = if (isRemoteActive()) remote?.isPlaying == true else super.isPlaying()

    override fun getPlayWhenReady(): Boolean = if (isRemoteActive()) remote?.isPlaying == true else super.playWhenReady

    override fun getPlaybackSuppressionReason(): Int =
        if (isRemoteActive()) Player.PLAYBACK_SUPPRESSION_REASON_NONE else super.playbackSuppressionReason

    override fun getPlayerError(): androidx.media3.common.PlaybackException? =
        if (isRemoteActive()) null else super.playerError

    override fun getCurrentMediaItem(): MediaItem? = if (isRemoteActive()) remoteMediaItem() else super.currentMediaItem

    override fun getMediaMetadata(): MediaMetadata =
        if (isRemoteActive()) remoteMediaItem()?.mediaMetadata ?: MediaMetadata.EMPTY else super.mediaMetadata

    override fun getMediaItemCount(): Int = if (isRemoteActive()) if (remote?.track != null) 1 else 0 else super.mediaItemCount

    override fun getMediaItemAt(index: Int): MediaItem =
        if (isRemoteActive()) remoteMediaItem() ?: MediaItem.EMPTY else super.getMediaItemAt(index)

    override fun getCurrentMediaItemIndex(): Int = if (isRemoteActive()) 0 else super.currentMediaItemIndex

    override fun getCurrentPeriodIndex(): Int = if (isRemoteActive()) 0 else super.currentPeriodIndex

    override fun getDuration(): Long = if (isRemoteActive()) remoteDurationMs() else super.duration

    override fun getContentDuration(): Long = if (isRemoteActive()) remoteDurationMs() else super.contentDuration

    override fun getCurrentPosition(): Long = if (isRemoteActive()) remotePositionMs() else super.currentPosition

    override fun getContentPosition(): Long = if (isRemoteActive()) remotePositionMs() else super.contentPosition

    override fun getBufferedPosition(): Long = if (isRemoteActive()) remotePositionMs() else super.bufferedPosition

    override fun isCurrentMediaItemLive(): Boolean = if (isRemoteActive()) false else super.isCurrentMediaItemLive()

    override fun isCurrentMediaItemDynamic(): Boolean = if (isRemoteActive()) false else super.isCurrentMediaItemDynamic()

    override fun isCurrentMediaItemSeekable(): Boolean = if (isRemoteActive()) remote?.track != null else super.isCurrentMediaItemSeekable()

    override fun getRepeatMode(): Int = if (isRemoteActive()) remoteRepeatMode() else super.repeatMode

    override fun getShuffleModeEnabled(): Boolean = if (isRemoteActive()) remote?.isShuffle == true else super.shuffleModeEnabled

    override fun getVolume(): Float = if (isRemoteActive()) remote?.volume ?: 1f else super.volume

    override fun hasNextMediaItem(): Boolean = if (isRemoteActive()) remote?.track != null else super.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean = if (isRemoteActive()) remote?.track != null else super.hasPreviousMediaItem()

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (!isRemoteActive()) return base
        return Player.Commands
            .Builder()
            .addAll(REMOTE_COMMANDS)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        if (isRemoteActive()) REMOTE_COMMANDS.contains(command) else super.isCommandAvailable(command)

    // ========== Transport routed to the remote target ==========

    override fun play() {
        if (isRemoteActive()) repository.sendPlay() else super.play()
    }

    override fun pause() {
        if (isRemoteActive()) repository.sendPause() else super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (isRemoteActive()) {
            if (playWhenReady) repository.sendPlay() else repository.sendPause()
        } else {
            super.setPlayWhenReady(playWhenReady)
        }
    }

    override fun stop() {
        if (isRemoteActive()) repository.sendPause() else super.stop()
    }

    override fun seekTo(positionMs: Long) {
        if (isRemoteActive()) {
            repository.sendSeekTo(positionMs.coerceAtLeast(0L))
        } else {
            super.seekTo(positionMs)
        }
    }

    override fun seekBack() {
        if (isRemoteActive()) {
            repository.sendSeekTo((remotePositionMs() - 5_000L).coerceAtLeast(0L))
        } else {
            super.seekBack()
        }
    }

    override fun seekForward() {
        if (isRemoteActive()) {
            val total = remoteDurationMs()
            val target = remotePositionMs() + 5_000L
            repository.sendSeekTo(if (total != C.TIME_UNSET) target.coerceAtMost(total) else target)
        } else {
            super.seekForward()
        }
    }

    override fun seekToNext() {
        if (isRemoteActive()) repository.sendNext() else super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (isRemoteActive()) repository.sendNext() else super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        if (isRemoteActive()) repository.sendPrevious() else super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (isRemoteActive()) repository.sendPrevious() else super.seekToPreviousMediaItem()
    }

    override fun setRepeatMode(repeatMode: Int) {
        if (isRemoteActive()) {
            repository.sendSetRepeat(
                when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> 1
                    Player.REPEAT_MODE_ONE -> 2
                    else -> 0
                },
            )
        } else {
            super.setRepeatMode(repeatMode)
        }
    }

    override fun setShuffleModeEnabled(enabled: Boolean) {
        if (isRemoteActive()) repository.sendSetShuffle(enabled) else super.setShuffleModeEnabled(enabled)
    }

    override fun setVolume(volume: Float) {
        if (isRemoteActive()) repository.sendSetVolume(volume.coerceIn(0f, 1f)) else super.setVolume(volume)
    }

    private companion object {
        /**
         * The command set exposed while remote. Playlist editing, media item loading and timeline
         * reads stay out: the local timeline does not describe the remote queue and letting
         * MediaSession apply those to the wrapped player would clear or replace local playback.
         */
        val REMOTE_COMMANDS: Player.Commands =
            Player.Commands
                .Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SET_REPEAT_MODE)
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .add(Player.COMMAND_GET_VOLUME)
                .add(Player.COMMAND_SET_VOLUME)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_STOP)
                .build()
    }
}
