/*
 * Copyright (c) 2026 Shippy contributors
 * RecentListeningTracker.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.history

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem

/** Observes canonical playback selection only; it neither owns nor alters playback state. */
@Singleton
class RecentListeningTracker @Inject constructor(
    private val repository: RecentListeningRepository,
) : PlaybackStateManager.Listener {
    private val clock = Clock.systemUTC()
    private var attached = false
    private var queue: List<ResolvedQueueItem> = emptyList()
    private var selectedQueueItemId: String? = null
    private var scope: CoroutineScope? = null

    fun attach(manager: PlaybackStateManager) {
        if (attached) return
        attached = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager.addListener(this)
    }

    fun release(manager: PlaybackStateManager) {
        if (attached) manager.removeListener(this)
        attached = false
        queue = emptyList()
        selectedQueueItemId = null
        scope?.cancel()
        scope = null
    }

    override fun onCanonicalNewPlayback(
        parent: org.oxycblt.musikr.MusicParent?,
        queue: List<ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) = select(queue, index)

    override fun onCanonicalQueueChanged(
        queue: List<ResolvedQueueItem>,
        index: Int,
        change: QueueChange,
    ) = select(queue, index)

    override fun onIndexMoved(index: Int) = recordCurrent(index)

    private fun select(nextQueue: List<ResolvedQueueItem>, index: Int) {
        queue = nextQueue
        recordCurrent(index)
    }

    private fun recordCurrent(index: Int) {
        val item = queue.getOrNull(index)?.item ?: return
        if (item.id.value == selectedQueueItemId) return
        selectedQueueItemId = item.id.value
        val entry = item.toRecentListeningEntry(clock.millis())
        scope?.launch { repository.record(entry) }
    }
}

internal fun QueueItem.toRecentListeningEntry(nowEpochMs: Long) = RecentListeningEntry(
    trackId = track.id.value,
    realm = track.realm,
    title = track.title,
    artists = track.artists,
    album = track.album,
    artwork = track.artwork?.takeIf { it.startsWith("https://") },
    lastPlayedAtEpochMs = nowEpochMs,
)
