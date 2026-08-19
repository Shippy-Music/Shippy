/*
 * Copyright (c) 2026 Auxio Project
 * LastFmScrobbleTracker.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.shippy.lastfm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.auxio.shippy.persistence.lastfm.LastFmScrobbleDao

enum class LastFmScrobbleStatusKind {
    Disabled,
    NowPlaying,
    Eligible,
    Queued,
    Scrobbled,
    Retrying,
    Ignored,
    ReauthRequired,
}

data class LastFmScrobbleStatus(
    val queueItemId: QueueItemId?,
    val kind: LastFmScrobbleStatusKind,
    val message: String? = null,
)

@Singleton
class LastFmScrobbleTracker
@Inject
constructor(
    @ApplicationContext context: Context,
    private val dao: LastFmScrobbleDao,
    private val credentials: LastFmCredentialRepository,
    private val client: LastFmClient,
    private val reauth: LastFmReauthState,
) : PlaybackStateManager.Listener {
    private val clock = Clock.systemUTC()
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var attached = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var item: QueueItem? = null
    private var track: LastFmTrack? = null
    private var startedAtSeconds: Long? = null
    private var scrobbled = false
    private var listenPolicy: LastFmListenPolicy? = null
    private var queue: List<ResolvedQueueItem> = emptyList()
    private var scope: CoroutineScope? = null
    private val deliveryMutex = Mutex()
    private val mutableStatus =
        MutableStateFlow(LastFmScrobbleStatus(null, LastFmScrobbleStatusKind.Disabled))

    val status: StateFlow<LastFmScrobbleStatus> = mutableStatus.asStateFlow()

    fun attach(manager: PlaybackStateManager) {
        if (attached) return
        attached = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager.addListener(this)
        registerNetworkCallback()
        scope?.launch { deliveryMutex.withLock { flush() } }
    }

    fun release(manager: PlaybackStateManager) {
        if (attached) manager.removeListener(this)
        attached = false
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        scope?.cancel()
        scope = null
        item = null
        track = null
        queue = emptyList()
        listenPolicy = null
        startedAtSeconds = null
        scrobbled = false
        mutableStatus.value = LastFmScrobbleStatus(null, LastFmScrobbleStatusKind.Disabled)
    }

    override fun onCanonicalNewPlayback(
        parent: org.oxycblt.musikr.MusicParent?,
        queue: List<ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) {
        this.queue = queue
        begin(queue.getOrNull(index)?.item)
    }

    override fun onIndexMoved(index: Int) {
        begin(queue.getOrNull(index)?.item)
    }

    override fun onCanonicalQueueChanged(
        queue: List<ResolvedQueueItem>,
        index: Int,
        change: org.oxycblt.auxio.playback.state.QueueChange,
    ) {
        this.queue = queue
        begin(queue.getOrNull(index)?.item)
    }

    override fun onProgressionChanged(progression: Progression) {
        val currentItem = item ?: return
        val position = progression.calculateElapsedPositionMs()
        if (!progression.isPlaying) {
            listenPolicy?.pause()
            return
        }
        if (startedAtSeconds == null) {
            startedAtSeconds = clock.instant().epochSecond
            val captured = track
            if (captured != null) {
                publish(currentItem.id, LastFmScrobbleStatusKind.NowPlaying)
                scope?.launch { sendNowPlaying(currentItem.id, captured) }
            }
        }
        if (!scrobbled && (listenPolicy?.update(position) == true)) {
            scrobbled = true
            val captured = track ?: return
            val capturedStartedAt = startedAtSeconds ?: return
            scope?.launch { flushThenQueue(currentItem.id, captured, capturedStartedAt) }
        }
    }

    private fun begin(next: QueueItem?) {
        if (next?.id == item?.id) return
        item = next
        track = next?.let(LastFmTrack::from)
        startedAtSeconds = null
        scrobbled = false
        listenPolicy = track?.let { LastFmListenPolicy(it.durationMs) }

        val queueItemId = next?.id
        if (queueItemId == null || track == null) {
            mutableStatus.value =
                LastFmScrobbleStatus(queueItemId, LastFmScrobbleStatusKind.Disabled)
            return
        }
        mutableStatus.value = LastFmScrobbleStatus(queueItemId, LastFmScrobbleStatusKind.Disabled)
        scope?.launch {
            val configured = credentials.load() != null
            if (item?.id != queueItemId) return@launch
            mutableStatus.value =
                when {
                    !configured ->
                        LastFmScrobbleStatus(queueItemId, LastFmScrobbleStatusKind.Disabled)
                    reauth.required.value ->
                        LastFmScrobbleStatus(queueItemId, LastFmScrobbleStatusKind.ReauthRequired)
                    else -> LastFmScrobbleStatus(queueItemId, LastFmScrobbleStatusKind.Eligible)
                }
        }
    }

    private suspend fun sendNowPlaying(queueItemId: QueueItemId, entry: LastFmTrack) {
        val auth = credentials.load()
        if (auth == null) {
            publish(queueItemId, LastFmScrobbleStatusKind.Disabled)
            return
        }
        if (reauth.required.value) {
            publish(queueItemId, LastFmScrobbleStatusKind.ReauthRequired)
            return
        }
        when (client.nowPlaying(entry, auth)) {
            LastFmDelivery.DELIVERED -> publish(queueItemId, LastFmScrobbleStatusKind.NowPlaying)
            LastFmDelivery.RETRY -> publish(queueItemId, LastFmScrobbleStatusKind.Retrying)
            LastFmDelivery.REAUTH -> {
                reauth.markRequired()
                publish(queueItemId, LastFmScrobbleStatusKind.ReauthRequired)
            }
            LastFmDelivery.DROP -> publish(queueItemId, LastFmScrobbleStatusKind.NowPlaying)
        }
    }

    private suspend fun flushThenQueue(
        queueItemId: QueueItemId,
        entry: LastFmTrack,
        startedAt: Long,
    ) {
        deliveryMutex.withLock {
            flush()
            dao.insert(entry.outbox(queueItemId, startedAt, clock.millis()))
            publish(queueItemId, LastFmScrobbleStatusKind.Queued)
            flush()
        }
    }

    private suspend fun flush() {
        val auth = credentials.load() ?: return
        if (reauth.required.value) return
        repeat(MAX_BATCHES_PER_FLUSH) {
            val batch = dao.oldest(MAX_BATCH)
            if (batch.isEmpty()) return
            when (val result = client.scrobble(batch, auth)) {
                is LastFmScrobbleResult.Delivered -> {
                    val terminalIds =
                        result.acceptedIds + result.ignored.mapTo(linkedSetOf()) { it.id }
                    dao.delete(terminalIds.toList())
                    result.acceptedIds.forEach {
                        publishOutbox(it, LastFmScrobbleStatusKind.Scrobbled)
                    }
                    result.ignored.forEach {
                        publishOutbox(
                            it.id,
                            LastFmScrobbleStatusKind.Ignored,
                            it.message.ifBlank { "Last.fm ignored this scrobble" },
                        )
                    }
                    reauth.clear()
                }
                is LastFmScrobbleResult.Dropped -> {
                    dao.delete(result.ids.toList())
                    result.ids.forEach {
                        publishOutbox(
                            it,
                            LastFmScrobbleStatusKind.Ignored,
                            result.reason ?: "Last.fm rejected this scrobble",
                        )
                    }
                }
                LastFmScrobbleResult.Reauth -> {
                    reauth.markRequired()
                    batch.forEach { publishOutbox(it.id, LastFmScrobbleStatusKind.ReauthRequired) }
                    return
                }
                is LastFmScrobbleResult.Retry -> {
                    batch.forEach {
                        publishOutbox(it.id, LastFmScrobbleStatusKind.Retrying, result.reason)
                    }
                    return
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = connectivityManager ?: return
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope?.launch { deliveryMutex.withLock { flush() } }
                }
            }
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) {
            networkCallback = callback
        }
    }

    private fun publish(
        queueItemId: QueueItemId,
        kind: LastFmScrobbleStatusKind,
        message: String? = null,
    ) {
        if (item?.id == queueItemId) {
            mutableStatus.value = LastFmScrobbleStatus(queueItemId, kind, message)
        }
    }

    private fun publishOutbox(id: String, kind: LastFmScrobbleStatusKind, message: String? = null) {
        val queueItemId = id.removePrefix(QUEUE_ITEM_PREFIX).takeIf { it != id } ?: return
        publish(QueueItemId(queueItemId), kind, message)
    }

    private companion object {
        const val MAX_BATCH = 50
        const val MAX_BATCHES_PER_FLUSH = 4
        const val QUEUE_ITEM_PREFIX = "queue-item:"
    }
}
