package org.oxycblt.auxio.shippy.lastfm

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.persistence.lastfm.LastFmScrobbleDao

@Singleton
class LastFmScrobbleTracker @Inject constructor(
    private val dao: LastFmScrobbleDao,
    private val credentials: LastFmCredentialRepository,
    private val client: LastFmClient,
) : PlaybackStateManager.Listener {
    private val clock = Clock.systemUTC()
    private var attached = false
    private var item: QueueItem? = null
    private var track: LastFmTrack? = null
    private var startedAtSeconds: Long? = null
    private var scrobbled = false
    private var listenPolicy: LastFmListenPolicy? = null
    private var queue: List<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem> = emptyList()
    private var scope: CoroutineScope? = null
    private val deliveryMutex = Mutex()

    fun attach(manager: PlaybackStateManager) {
        if (attached) return
        attached = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager.addListener(this)
        scope?.launch {
            deliveryMutex.withLock { flush() }
        }
    }

    fun release(manager: PlaybackStateManager) {
        if (attached) manager.removeListener(this)
        attached = false
        scope?.cancel()
        scope = null
        item = null
        track = null
        queue = emptyList()
        listenPolicy = null
        startedAtSeconds = null
        scrobbled = false
    }

    override fun onCanonicalNewPlayback(parent: org.oxycblt.musikr.MusicParent?, queue: List<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem>, index: Int, isShuffled: Boolean) { this.queue = queue; begin(queue.getOrNull(index)?.item) }
    override fun onIndexMoved(index: Int) { begin(queue.getOrNull(index)?.item) }
    override fun onCanonicalQueueChanged(queue: List<org.oxycblt.auxio.shippy.domain.ResolvedQueueItem>, index: Int, change: org.oxycblt.auxio.playback.state.QueueChange) { this.queue = queue; begin(queue.getOrNull(index)?.item) }

    override fun onProgressionChanged(progression: Progression) {
        item ?: return
        val position = progression.calculateElapsedPositionMs()
        if (!progression.isPlaying) {
            listenPolicy?.pause()
            return
        }
        if (startedAtSeconds == null) {
            startedAtSeconds = clock.instant().epochSecond
            val captured = track
            if (captured != null) {
                scope?.launch { credentials.load()?.let { client.nowPlaying(captured, it) } }
            }
        }
        if (!scrobbled && (listenPolicy?.update(position) == true)) {
            scrobbled = true
            val captured = track ?: return
            val capturedStartedAt = startedAtSeconds ?: return
            scope?.launch { flushThenQueue(captured, capturedStartedAt) }
        }
    }

    private fun begin(next: QueueItem?) {
        if (next?.id == item?.id) return
        item = next
        track = next?.let(LastFmTrack::from)
        startedAtSeconds = null
        scrobbled = false
        listenPolicy = track?.let { LastFmListenPolicy(it.durationMs) }
    }

    private suspend fun flushThenQueue(entry: LastFmTrack, startedAt: Long) {
        deliveryMutex.withLock {
            flush()
            dao.insert(entry.outbox(startedAt, clock.millis()))
            flush()
        }
    }
    private suspend fun flush() {
        val auth = credentials.load() ?: return
        val batch = dao.oldest(MAX_BATCH); if (batch.isEmpty()) return
        when (client.scrobble(batch, auth)) { LastFmDelivery.DELIVERED -> dao.delete(batch.map { it.id }); LastFmDelivery.DROP -> dao.delete(batch.map { it.id }); LastFmDelivery.REAUTH, LastFmDelivery.RETRY -> Unit }
    }
    private companion object { const val MAX_BATCH = 50 }
}
