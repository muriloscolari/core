package com.maxrave.media3.exoplayer

import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

/**
 * A read-only [Timeline] backed by the app's real queue.
 *
 * Each window/period is populated with the corresponding track's duration so that controllers
 * (both Android Auto and phone SystemUI/HyperOS) always read valid, non-unset playback durations.
 * The active track's duration is sourced dynamically from [activeDurationProvider] (the active ExoPlayer)
 * to guarantee that elapsed and total time match playback with microsecond accuracy.
 */
@UnstableApi
internal class MediaItemQueueTimeline(
    private val items: List<MediaItem>,
    private val activeIndexProvider: () -> Int = { -1 },
    private val activeDurationProvider: () -> Long = { C.TIME_UNSET },
) : Timeline() {
    private val uids: List<String> = items.mapIndexed { index, item -> "${item.mediaId}@$index" }

    override fun getWindowCount(): Int = items.size

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long,
    ): Window {
        val item = items[windowIndex]
        val activeIndex = activeIndexProvider()
        val activeDurMs = activeDurationProvider()
        val durationUs =
            if (windowIndex == activeIndex && activeDurMs > 0) {
                activeDurMs * 1000L
            } else {
                val itemDurMs = item.mediaMetadata.durationMs
                if (itemDurMs != null && itemDurMs > 0) itemDurMs * 1000L else C.TIME_UNSET
            }

        window.set(
            /* uid= */ uids[windowIndex],
            /* mediaItem= */ item,
            /* manifest= */ null,
            /* presentationStartTimeMs= */ C.TIME_UNSET,
            /* windowStartTimeMs= */ C.TIME_UNSET,
            /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET,
            /* isSeekable= */ true,
            /* isDynamic= */ false,
            /* liveConfiguration= */ null,
            /* defaultPositionUs= */ 0,
            /* durationUs= */ durationUs,
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
        val item = items[periodIndex]
        val activeIndex = activeIndexProvider()
        val activeDurMs = activeDurationProvider()
        val durationUs =
            if (periodIndex == activeIndex && activeDurMs > 0) {
                activeDurMs * 1000L
            } else {
                val itemDurMs = item.mediaMetadata.durationMs
                if (itemDurMs != null && itemDurMs > 0) itemDurMs * 1000L else C.TIME_UNSET
            }

        period.set(
            /* id= */ if (setIds) uids[periodIndex] else null,
            /* uid= */ if (setIds) uids[periodIndex] else null,
            /* windowIndex= */ periodIndex,
            /* durationUs= */ durationUs,
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
