package com.maxrave.media3.exoplayer

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.maxrave.logger.Logger

private const val TAG = "DelegatingForwardingPlayer"

/**
 * A [ForwardingPlayer] that allows runtime swapping of its underlying delegate.
 *
 * Used for MediaSession integration - provides a stable [Player] reference while
 * [CrossfadeExoPlayerAdapter] cycles through different ExoPlayer instances.
 *
 * Pattern: Track all externally added listeners, then during swap:
 * 1. Remove them via [ForwardingPlayer.removeListener] (cleans up ForwardingListener wrappers)
 * 2. Swap the internal `player` field via reflection
 * 3. Re-add them via [ForwardingPlayer.addListener] (creates new ForwardingListener wrappers for new delegate)
 *
 * Additionally, since each underlying ExoPlayer only has a single MediaItem,
 * playlist navigation methods (hasNext/hasPrevious, seek, mediaItemCount, etc.)
 * are overridden to delegate to [PlaylistNavigationProvider] — which is backed by
 * [CrossfadeExoPlayerAdapter]'s internal playlist. This ensures MediaSession reports
 * correct available commands (SEEK_TO_NEXT, SEEK_TO_PREVIOUS) and shows
 * next/previous buttons in the system notification.
 */
@UnstableApi
internal class DelegatingForwardingPlayer(
    initialDelegate: Player,
) : ForwardingPlayer(initialDelegate) {
    companion object {
        private val PLAYER_FIELD: java.lang.reflect.Field? =
            try {
                ForwardingPlayer::class.java.getDeclaredField("player").apply {
                    isAccessible = true
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to access ForwardingPlayer.player field", e)
                null
            }
    }

    // ========== Playlist Navigation Provider ==========

    /**
     * Provides playlist-level navigation information to the ForwardingPlayer.
     *
     * Each underlying ExoPlayer only has 1 MediaItem, so ExoPlayer's own
     * hasNextMediaItem()/hasPreviousMediaItem() always return false.
     * This provider bridges the gap, letting MediaSession see the full playlist state.
     */
    interface PlaylistNavigationProvider {
        fun hasNextMediaItem(): Boolean

        fun hasPreviousMediaItem(): Boolean

        fun seekToNext()

        fun seekToPrevious()

        /**
         * Always advances to the previous media item — bypasses the 3-second
         * "seek to start" rule used by [seekToPrevious]. Implementations may
         * default to [seekToPrevious] if the distinction is irrelevant.
         */
        fun seekToPreviousMediaItem() = seekToPrevious()

        /**
         * Transport intent must round-trip through the adapter, not the delegate:
         * [ForwardingPlayer]'s own play/pause/setPlayWhenReady act on the raw
         * ExoPlayer, which resumes audio while the adapter's internalPlayWhenReady
         * stays stale — the next track change then captures shouldPlay=false and
         * loads paused.
         */
        fun play()

        fun pause()

        fun setPlayWhenReady(playWhenReady: Boolean)

        /** Current position in the queue published to MediaSession, or -1 when there is none. */
        fun currentQueueIndex(): Int = -1

        /** Seek to an index in the queue published to MediaSession. */
        fun seekToQueueIndex(
            index: Int,
            positionMs: Long,
        ) = Unit
    }

    /**
     * Set by [CrossfadeExoPlayerAdapter] to provide playlist navigation.
     * When null, all navigation methods fall back to the underlying ExoPlayer (single-item behavior).
     */
    var playlistNavigationProvider: PlaylistNavigationProvider? = null

    // The real queue, in playback order, published to MediaSession. The delegate ExoPlayer only
    // ever holds the current item; this is what lets Android Auto show its native queue button.
    private var queueItems: List<MediaItem> = emptyList()
    private var queueTimeline: Timeline = Timeline.EMPTY

    // ========== Playback-Ended Suppression ==========

    /**
     * When true, [getPlaybackState] remaps [Player.STATE_ENDED] to [Player.STATE_BUFFERING].
     *
     * This prevents [androidx.media3.session.MediaLibraryService] from dropping the
     * foreground-service notification during the gap between one ExoPlayer finishing
     * and the next ExoPlayer being swapped in via [swapDelegate].
     *
     * Set by [CrossfadeExoPlayerAdapter] before a track-end transition starts,
     * cleared after the next player is swapped in and playing.
     */
    @Volatile
    var suppressPlaybackEnded = false

    override fun getPlaybackState(): Int {
        val state = super.getPlaybackState()
        if (state == Player.STATE_ENDED && suppressPlaybackEnded) {
            return Player.STATE_BUFFERING
        }
        return state
    }

    // ========== Listener Tracking ==========

    // Track all externally registered listeners so we can re-register them after delegate swap
    private val trackedListeners = mutableListOf<Player.Listener>()

    // The delegate's timeline only ever has one window. Wrap every listener so MediaSession sees
    // the real queue timeline even when ExoPlayer emits a source update for the single item.
    private val listenerWrappers = mutableMapOf<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        if (listener !in trackedListeners) {
            trackedListeners.add(listener)
        }
        listenerWrappers.remove(listener)?.let { oldWrapper ->
            super.removeListener(oldWrapper)
        }

        val wrapper =
            object : Player.Listener by listener {
                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    listener.onTimelineChanged(queueTimeline, reason)
                }
            }
        listenerWrappers[listener] = wrapper
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        trackedListeners.remove(listener)
        val wrapper = listenerWrappers.remove(listener)
        if (wrapper != null) {
            super.removeListener(wrapper)
        } else {
            super.removeListener(listener)
        }
    }

    // ========== Video Surface Tracking ==========
    // Track the current video output so it can be re-attached when the delegate is swapped.
    // Without this, video stops rendering after a delegate swap because the new ExoPlayer
    // instance never receives setVideoSurfaceView/setVideoSurface/etc.

    private sealed class VideoOutput {
        data class SurfaceViewOutput(val surfaceView: SurfaceView) : VideoOutput()

        data class TextureViewOutput(val textureView: TextureView) : VideoOutput()

        data class SurfaceOutput(val surface: Surface) : VideoOutput()

        data class SurfaceHolderOutput(val surfaceHolder: SurfaceHolder) : VideoOutput()
    }

    private var currentVideoOutput: VideoOutput? = null

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        currentVideoOutput = surfaceView?.let { VideoOutput.SurfaceViewOutput(it) }
        super.setVideoSurfaceView(surfaceView)
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        currentVideoOutput = textureView?.let { VideoOutput.TextureViewOutput(it) }
        super.setVideoTextureView(textureView)
    }

    override fun setVideoSurface(surface: Surface?) {
        currentVideoOutput = surface?.let { VideoOutput.SurfaceOutput(it) }
        super.setVideoSurface(surface)
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        currentVideoOutput = surfaceHolder?.let { VideoOutput.SurfaceHolderOutput(it) }
        super.setVideoSurfaceHolder(surfaceHolder)
    }

    override fun clearVideoSurface() {
        currentVideoOutput = null
        super.clearVideoSurface()
    }

    override fun clearVideoSurface(surface: Surface?) {
        if (currentVideoOutput is VideoOutput.SurfaceOutput &&
            (currentVideoOutput as VideoOutput.SurfaceOutput).surface === surface
        ) {
            currentVideoOutput = null
        }
        super.clearVideoSurface(surface)
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        if (currentVideoOutput is VideoOutput.SurfaceViewOutput &&
            (currentVideoOutput as VideoOutput.SurfaceViewOutput).surfaceView === surfaceView
        ) {
            currentVideoOutput = null
        }
        super.clearVideoSurfaceView(surfaceView)
    }

    override fun clearVideoTextureView(textureView: TextureView?) {
        if (currentVideoOutput is VideoOutput.TextureViewOutput &&
            (currentVideoOutput as VideoOutput.TextureViewOutput).textureView === textureView
        ) {
            currentVideoOutput = null
        }
        super.clearVideoTextureView(textureView)
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (currentVideoOutput is VideoOutput.SurfaceHolderOutput &&
            (currentVideoOutput as VideoOutput.SurfaceHolderOutput).surfaceHolder === surfaceHolder
        ) {
            currentVideoOutput = null
        }
        super.clearVideoSurfaceHolder(surfaceHolder)
    }

    /**
     * Clear video output from a specific player instance.
     * Must be called on the OLD delegate before swapping, so the native surface
     * is disconnected from the old MediaCodec before the new player tries to connect.
     */
    private fun clearVideoOutputFromPlayer(player: Player) {
        if (currentVideoOutput != null) {
            Logger.d(TAG, "Clearing video surface from old delegate before swap")
            try {
                player.clearVideoSurface()
            } catch (e: Exception) {
                Logger.w(TAG, "Error clearing video surface from old delegate: ${e.message}")
            }
        }
    }

    /**
     * Re-attach the tracked video output to the current delegate.
     * Called after [swapDelegate] to ensure video continues rendering on the new ExoPlayer.
     */
    private fun reAttachVideoOutput() {
        when (val output = currentVideoOutput) {
            is VideoOutput.SurfaceViewOutput -> {
                Logger.d(TAG, "Re-attaching SurfaceView to new delegate")
                wrappedPlayer.setVideoSurfaceView(output.surfaceView)
            }
            is VideoOutput.TextureViewOutput -> {
                Logger.d(TAG, "Re-attaching TextureView to new delegate")
                wrappedPlayer.setVideoTextureView(output.textureView)
            }
            is VideoOutput.SurfaceOutput -> {
                Logger.d(TAG, "Re-attaching Surface to new delegate")
                wrappedPlayer.setVideoSurface(output.surface)
            }
            is VideoOutput.SurfaceHolderOutput -> {
                Logger.d(TAG, "Re-attaching SurfaceHolder to new delegate")
                wrappedPlayer.setVideoSurfaceHolder(output.surfaceHolder)
            }
            null -> {
                // No video output to re-attach
            }
        }
    }

    // ========== Queue Timeline ==========

    /**
     * Replaces the queue published to MediaSession.
     *
     * Called by [CrossfadeExoPlayerAdapter] whenever its internal playlist or shuffle order
     * changes. Dispatching the timeline here, rather than relying on the delegate ExoPlayer's
     * one-item timeline, is what makes Media3 populate the legacy session queue used by Android
     * Auto.
     */
    fun setQueueItems(items: List<MediaItem>) {
        if (items == queueItems) return
        queueItems = items.toList()
        queueTimeline =
            if (queueItems.isEmpty()) {
                Timeline.EMPTY
            } else {
                MediaItemQueueTimeline(
                    items = queueItems,
                    activeIndexProvider = { currentQueueIndex() },
                    activeDurationProvider = { wrappedPlayer.duration },
                )
            }

        trackedListeners.forEach { listener ->
            try {
                listener.onTimelineChanged(queueTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                listener.onAvailableCommandsChanged(getAvailableCommands())
            } catch (e: Exception) {
                Logger.e(TAG, "Error notifying listener about queue change: ${e.message}", e)
            }
        }
    }

    // ========== Playlist Navigation Overrides ==========

    override fun getAvailableCommands(): Player.Commands {
        val baseCommands = super.getAvailableCommands()
        val nav = playlistNavigationProvider ?: return baseCommands

        val builder = baseCommands.buildUpon()

        // Always add seek-to-previous (allows seek to start of track even if no previous item)
        builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)

        if (nav.hasNextMediaItem()) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT)
            builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (nav.hasPreviousMediaItem()) {
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        if (queueItems.isNotEmpty()) {
            builder.add(Player.COMMAND_GET_TIMELINE)
            builder.add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        } else {
            builder.remove(Player.COMMAND_GET_TIMELINE)
            builder.remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        }

        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        val nav = playlistNavigationProvider
        if (nav != null) {
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                    return nav.hasNextMediaItem()
                Player.COMMAND_SEEK_TO_PREVIOUS ->
                    return true // Always allow seeking to start of current track
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                    return nav.hasPreviousMediaItem()
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_SEEK_TO_MEDIA_ITEM ->
                    return queueItems.isNotEmpty()
            }
        }
        return super.isCommandAvailable(command)
    }

    override fun getCurrentTimeline(): Timeline = queueTimeline

    override fun getDuration(): Long {
        val d = wrappedPlayer.duration
        if (d > 0) return d
        val index = currentQueueIndex()
        val itemDuration = queueItems.getOrNull(index)?.mediaMetadata?.durationMs
        return if (itemDuration != null && itemDuration > 0) itemDuration else d
    }

    override fun getContentDuration(): Long = getDuration()

    override fun getCurrentMediaItem(): MediaItem? {
        val index = currentQueueIndex()
        if (index in queueItems.indices) {
            return queueItems[index]
        }
        return wrappedPlayer.currentMediaItem
    }

    override fun getMediaItemCount(): Int =
        if (playlistNavigationProvider != null && queueItems.isNotEmpty()) {
            queueItems.size
        } else {
            super.getMediaItemCount()
        }

    override fun getMediaItemAt(index: Int): MediaItem =
        if (playlistNavigationProvider != null && index in queueItems.indices) {
            queueItems[index]
        } else {
            super.getMediaItemAt(index)
        }

    private fun currentQueueIndex(): Int {
        val nav = playlistNavigationProvider ?: return super.getCurrentMediaItemIndex()
        if (queueItems.isEmpty()) return 0
        return nav.currentQueueIndex().coerceIn(0, queueItems.lastIndex)
    }

    override fun getCurrentMediaItemIndex(): Int = currentQueueIndex()

    override fun getCurrentPeriodIndex(): Int = currentQueueIndex()

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        seekToQueueItem(mediaItemIndex, 0)
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        seekToQueueItem(mediaItemIndex, positionMs)
    }

    private fun seekToQueueItem(
        index: Int,
        positionMs: Long,
    ) {
        val nav = playlistNavigationProvider
        if (nav != null && queueItems.isNotEmpty() && index in queueItems.indices) {
            nav.seekToQueueIndex(index, positionMs)
        } else {
            super.seekTo(index, positionMs)
        }
    }

    override fun hasNextMediaItem(): Boolean =
        playlistNavigationProvider?.hasNextMediaItem() ?: super.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean =
        playlistNavigationProvider?.hasPreviousMediaItem() ?: super.hasPreviousMediaItem()

    override fun seekToNext() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            nav.seekToNext()
        } else {
            super.seekToNext()
        }
    }

    override fun seekToPrevious() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            nav.seekToPrevious()
        } else {
            super.seekToPrevious()
        }
    }

    override fun seekToNextMediaItem() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            nav.seekToNext()
        } else {
            super.seekToNextMediaItem()
        }
    }

    override fun seekToPreviousMediaItem() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            // Bypass the 3-second "seek to start" rule used by seekToPrevious().
            nav.seekToPreviousMediaItem()
        } else {
            super.seekToPreviousMediaItem()
        }
    }

    // ========== Transport Intent Overrides ==========
    // MediaController surfaces (notification, Bluetooth, headset, Android Auto) land here.
    // Without these overrides the calls fall through to the raw delegate ExoPlayer: audio
    // resumes but the adapter's internalPlayWhenReady stays false, so the next track
    // change captures shouldPlay=false and the incoming track loads paused.

    override fun play() {
        val nav = playlistNavigationProvider
        if (nav != null) nav.play() else super.play()
    }

    override fun pause() {
        val nav = playlistNavigationProvider
        if (nav != null) nav.pause() else super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        val nav = playlistNavigationProvider
        if (nav != null) nav.setPlayWhenReady(playWhenReady) else super.setPlayWhenReady(playWhenReady)
    }

    // ========== Playlist / Item Loading Overrides ==========
    // MediaSession.Callback.onSetMediaItems asynchronously orchestrates loading through
    // MediaPlayerHandler / CrossfadeExoPlayerAdapter and returns emptyList() as a default result.
    // Media3's MediaSessionStub takes that result and invokes player.setMediaItems(emptyList()),
    // which would clear the delegate ExoPlayer that was just loaded. Ignore empty setMediaItems calls.

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        if (mediaItems.isEmpty()) {
            Logger.d(TAG, "Ignoring setMediaItems with empty list to preserve active playback")
            return
        }
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (mediaItems.isEmpty()) {
            Logger.d(TAG, "Ignoring setMediaItems with empty list to preserve active playback")
            return
        }
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (mediaItems.isEmpty()) {
            Logger.d(TAG, "Ignoring setMediaItems with empty list to preserve active playback")
            return
        }
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    // NOTE: Do NOT override getMediaItemCount() or getCurrentMediaItemIndex() here.
    // These must remain consistent with the underlying ExoPlayer's Timeline (1 item, index 0).
    // Media3's PlayerWrapper.createPositionInfo() validates that currentMediaItemIndex < timeline.windowCount.
    // If we return the adapter's playlist index (e.g. 5) but Timeline only has 1 window, it crashes.

    // ========== Delegate Swap ==========

    /**
     * Swap the underlying delegate player.
     *
     * This properly migrates all registered listeners (e.g., MediaSession's listener)
     * from the old delegate to the new one.
     */
    fun swapDelegate(newDelegate: Player) {
        if (wrappedPlayer === newDelegate) return

        val field = PLAYER_FIELD
            ?: throw IllegalStateException("Cannot swap delegate - reflection on ForwardingPlayer.player field failed")

        // 1. Snapshot current listeners
        val listenersToReAdd = trackedListeners.toList()

        // 2. Clear video surface from OLD delegate BEFORE swapping.
        //    The native surface can only be connected to one MediaCodec at a time.
        //    If we don't clear it here, the new player's MediaCodec.setSurface() will fail
        //    with "already connected" → IllegalArgumentException crash.
        clearVideoOutputFromPlayer(wrappedPlayer)

        // 3. Remove all listeners from old delegate (ForwardingPlayer removes ForwardingListener wrappers)
        listenersToReAdd.forEach { listener ->
            try {
                super.removeListener(listenerWrappers[listener] ?: listener)
            } catch (e: Exception) {
                Logger.w(TAG, "Error removing listener during swap: ${e.message}")
            }
        }

        // 4. Swap the private final `player` field
        field.set(this, newDelegate)

        // 5. Re-add all listeners (ForwardingPlayer creates new ForwardingListener wrappers for new delegate)
        listenersToReAdd.forEach { listener ->
            super.addListener(listenerWrappers[listener] ?: listener)
        }

        // 6. Re-attach video surface to the new delegate
        //    Without this, video stops rendering because the new ExoPlayer
        //    never received setVideoSurfaceView/setVideoSurface/etc.
        reAttachVideoOutput()

        // 6. Verify
        if (wrappedPlayer !== newDelegate) {
            Logger.e(TAG, "Delegate swap verification FAILED - wrappedPlayer is not the new delegate!")
        } else {
            Logger.d(TAG, "Delegate swapped successfully")
        }
    }

    // ========== Manual Event Dispatch ==========

    /**
     * Manually notify all tracked listeners about a media item change.
     *
     * This is needed after [swapDelegate] because the new delegate may already have
     * a MediaItem set and be playing — meaning listeners missed the initial
     * [Player.Listener.onMediaItemTransition] and [Player.Listener.onMediaMetadataChanged] events.
     *
     * Also dispatches [Player.Listener.onAvailableCommandsChanged] so MediaSession
     * re-evaluates which notification buttons to show (next/previous).
     *
     * Primary use case: crossfade transitions, where the secondary player is prepared
     * (with MediaItem + prepare()) before the ForwardingPlayer is swapped to it.
     * MediaSession uses these events to update the system notification metadata.
     */
    fun notifyMediaItemChanged() {
        val player = wrappedPlayer
        val mediaItem = player.currentMediaItem ?: MediaItem.EMPTY
        val metadata = player.mediaMetadata
        val commands = getAvailableCommands()

        Logger.d(TAG, "Manually notifying ${trackedListeners.size} listeners about media item change: ${metadata.title}")

        trackedListeners.forEach { listener ->
            try {
                // Re-publish the real queue first so a delegate swap/transition keeps the queue in sync
                listener.onTimelineChanged(queueTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                listener.onMediaItemTransition(mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                listener.onMediaMetadataChanged(metadata)
                listener.onAvailableCommandsChanged(commands)
            } catch (e: Exception) {
                Logger.w(TAG, "Error notifying listener about media item change: ${e.message}")
            }
        }
    }
}
