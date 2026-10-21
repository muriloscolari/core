package com.maxrave.media3.exoplayer

import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

/**
 * A read-only [Timeline] backed by the app's real queue.
 *
 * The playback adapter deliberately keeps one [MediaItem] per ExoPlayer instance while the queue
 * lives in [CrossfadeExoPlayerAdapter.playlist]. MediaSession builds the Android Auto queue from
 * the session player's timeline, so the forwarding player reports this timeline instead of the
 * single-item delegate. Each window is one queue entry and has exactly one period; that keeps
 * [androidx.media3.session.PlayerWrapper]'s position validation consistent with
 * [DelegatingForwardingPlayer.getCurrentMediaItemIndex].
 */
@UnstableApi
internal class MediaItemQueueTimeline(
    private val items: List<MediaItem>,
) : Timeline() {
    private val uids: List<String> = items.mapIndexed { index, item -> "${item.mediaId}@$index" }

    override fun getWindowCount(): Int = items.size

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long,
    ): Window {
        window.set(
            /* uid= */ uids[windowIndex],
            /* mediaItem= */ items[windowIndex],
            /* manifest= */ null,
            /* presentationStartTimeMs= */ C.TIME_UNSET,
            /* windowStartTimeMs= */ C.TIME_UNSET,
            /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET,
            /* isSeekable= */ true,
            /* isDynamic= */ false,
            /* liveConfiguration= */ null,
            /* defaultPositionUs= */ 0,
            /* durationUs= */ C.TIME_UNSET,
            /* firstPeriodIndex= */ windowIndex,
            /* lastPeriodIndex= */ windowIndex,
            /* positionInFirstPeriodUs= */ 0,
        )
        return window
    }

    override fun getPeriodCount(): Int = items.size

    override fun getPeriod(
        periodIndex: Int,
        period: Period,
        setIds: Boolean,
    ): Period {
        period.set(
            /* id= */ if (setIds) uids[periodIndex] else null,
            /* uid= */ if (setIds) uids[periodIndex] else null,
            /* windowIndex= */ periodIndex,
            /* durationUs= */ C.TIME_UNSET,
            /* positionInWindowUs= */ 0,
            /* adPlaybackState= */ AdPlaybackState.NONE,
            /* isPlaceholder= */ false,
        )
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int = uids.indexOf(uid)

    override fun getUidOfPeriod(periodIndex: Int): Any = uids[periodIndex]

    override fun equals(other: Any?): Boolean =
        this === other || (other is MediaItemQueueTimeline && items == other.items)

    override fun hashCode(): Int = items.hashCode()
}
