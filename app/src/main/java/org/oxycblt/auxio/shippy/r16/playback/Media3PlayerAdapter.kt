/*
 * Copyright (c) 2026 Auxio Project
 * Media3PlayerAdapter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.shippy.core.playback.CommittedEnginePhase
import app.shippy.core.playback.PlaybackError
import app.shippy.core.playback.PlaybackRequestTag
import app.shippy.core.playback.PositionAnchor
import app.shippy.core.playback.RepeatMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.oxycblt.auxio.shippy.media.MediaObjectKey
import org.oxycblt.auxio.shippy.media.cache.PlaybackCacheManager

fun interface Media3ItemFactory {
    fun create(item: PreparedEngineItem): MediaItem

    fun release() = Unit
}

internal data class R16MediaItemTag(val requestTag: PlaybackRequestTag)

internal data class Media3ProjectedItem(
    val preparedItem: PreparedEngineItem,
    val requestTag: PlaybackRequestTag,
)

internal data class Media3TransactionProjection(
    val requestTag: PlaybackRequestTag,
    val items: List<Media3ProjectedItem>,
    val currentIndex: Int,
    val startPositionMs: Long,
    val playWhenReady: Boolean,
)

@OptIn(UnstableApi::class)
internal object Media3TransactionProjector {
    fun project(transaction: PlayerTransaction): Media3TransactionProjection {
        val items =
            transaction.window.map { item ->
                Media3ProjectedItem(item, transaction.tag.copy(queueEntryId = item.queueEntryId))
            }
        return Media3TransactionProjection(
            requestTag = transaction.tag,
            items = items,
            currentIndex =
                transaction.window.indexOfFirst {
                    it.queueEntryId == transaction.expectedCurrentEntryId
                },
            startPositionMs = transaction.startPositionMs,
            playWhenReady = transaction.playWhenReady,
        )
    }

    fun materialize(
        projection: Media3TransactionProjection,
        itemFactory: Media3ItemFactory,
    ): List<MediaItem> =
        projection.items.map { projected ->
            itemFactory
                .create(projected.preparedItem)
                .buildUpon()
                .setMediaId(projected.preparedItem.queueEntryId.value)
                .setTag(R16MediaItemTag(projected.requestTag))
                .build()
                .also {
                    require(it.localConfiguration != null) {
                        "Media3 item factory must provide playable local configuration"
                    }
                }
        }

    fun committedObservation(
        requestTag: PlaybackRequestTag,
        reason: Int,
    ): PlayerObservation.CurrentItemCommitted {
        return PlayerObservation.CurrentItemCommitted(
            requestTag,
            automaticTransition =
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        )
    }
}

/**
 * Inactive R16 Media3 projection. Production playback remains on the R15.3 holder until cutover
 * gates pass.
 */
@OptIn(UnstableApi::class)
class Media3PlayerAdapter(
    private val player: Player,
    private val itemFactory: Media3ItemFactory,
    private val clock: PlaybackClock = SystemPlaybackClock,
    private val playbackCache: PlaybackCacheManager? = null,
) : PlayerEngine, Player.Listener {
    private val observationChannel = Channel<PlayerObservation>(Channel.UNLIMITED)
    override val observations: Flow<PlayerObservation> = observationChannel.receiveAsFlow()

    private val playerLooper = player.applicationLooper
    private val playerHandler = Handler(playerLooper)
    private val released = AtomicBoolean(false)
    private var activeCacheKey: MediaObjectKey? = null

    init {
        postToPlayer { player.addListener(this) }
    }

    override suspend fun apply(transaction: PlayerTransaction) {
        val projection = Media3TransactionProjector.project(transaction)
        val mediaItems = Media3TransactionProjector.materialize(projection, itemFactory)
        onPlayer {
            check(!released.get()) { "Media3 adapter is released" }
            player.shuffleModeEnabled = false
            player.setMediaItems(mediaItems, projection.currentIndex, projection.startPositionMs)
            player.playWhenReady = projection.playWhenReady
            player.prepare()
        }
    }

    override suspend fun setPlayWhenReady(value: Boolean) {
        onPlayer { player.playWhenReady = value }
    }

    override suspend fun seek(
        queueEntryId: app.shippy.core.identity.QueueEntryId,
        positionMs: Long,
    ) {
        require(positionMs >= 0) { "Media3 seek position cannot be negative" }
        onPlayer {
            require(player.currentMediaItem?.mediaId == queueEntryId.value) {
                "Media3 seek QueueEntryId must match the committed item"
            }
            player.seekTo(positionMs)
        }
    }

    override suspend fun setRepeat(mode: RepeatMode) {
        onPlayer {
            player.repeatMode =
                if (mode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    override suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            onPlayer {
                player.removeListener(this)
                player.release()
                activeCacheKey?.let { key -> playbackCache?.unprotectKey(key) }
                activeCacheKey = null
            }
        } finally {
            itemFactory.release()
            observationChannel.close()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val newCustomKey = mediaItem?.localConfiguration?.customCacheKey
        val newKey =
            if (newCustomKey != null && newCustomKey.startsWith(MediaObjectKey.CACHE_KEY_PREFIX)) {
                MediaObjectKey.fromCustomCacheKey(newCustomKey)
            } else {
                null
            }
        if (newKey != activeCacheKey) {
            activeCacheKey?.let { key -> playbackCache?.unprotectKey(key) }
            activeCacheKey = newKey
            newKey?.let { key -> playbackCache?.protectKey(key) }
        }
        mediaItem
            ?.localConfiguration
            ?.tag
            ?.let { it as? R16MediaItemTag }
            ?.let { Media3TransactionProjector.committedObservation(it.requestTag, reason) }
            ?.let(observationChannel::trySend)
        publishPosition(discontinuity = true)
    }

    override fun onPlayerError(error: PlaybackException) {
        val tag = currentRequestTag() ?: return
        observationChannel.trySend(
            PlayerObservation.Failed(
                tag.generation,
                tag.queueEntryId,
                PlaybackError(
                    code = "MEDIA3_${error.errorCodeName}",
                    retryable = error.errorCode in 2_000 until 3_000,
                ),
            )
        )
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (
            events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
            )
        ) {
            publishPhase()
        }
        if (
            events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
        ) {
            publishPosition(discontinuity = events.contains(Player.EVENT_POSITION_DISCONTINUITY))
        }
    }

    private fun publishPhase() {
        val tag = currentRequestTag() ?: return
        val phase =
            when (player.playbackState) {
                Player.STATE_BUFFERING -> CommittedEnginePhase.BUFFERING
                Player.STATE_ENDED -> CommittedEnginePhase.ENDED
                Player.STATE_READY ->
                    when {
                        player.isPlaying -> CommittedEnginePhase.PLAYING
                        player.playWhenReady -> CommittedEnginePhase.READY
                        else -> CommittedEnginePhase.PAUSED
                    }
                else -> CommittedEnginePhase.PAUSED
            }
        observationChannel.trySend(
            PlayerObservation.PhaseChanged(tag.generation, tag.queueEntryId, phase)
        )
    }

    private fun publishPosition(discontinuity: Boolean) {
        val tag = currentRequestTag() ?: return
        observationChannel.trySend(
            PlayerObservation.PositionChanged(
                tag.generation,
                tag.queueEntryId,
                PositionAnchor(
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    sampledElapsedRealtimeMs = clock.elapsedRealtimeMs(),
                    playbackSpeed = player.playbackParameters.speed.toDouble(),
                    advancing = player.isPlaying,
                ),
                discontinuity,
            )
        )
    }

    private fun currentRequestTag(): PlaybackRequestTag? =
        (player.currentMediaItem?.localConfiguration?.tag as? R16MediaItemTag)?.requestTag

    private fun postToPlayer(block: () -> Unit) {
        if (Looper.myLooper() == playerLooper) {
            block()
        } else {
            check(playerHandler.post(block)) { "Media3 player looper rejected adapter work" }
        }
    }

    private suspend fun onPlayer(block: () -> Unit) {
        if (Looper.myLooper() == playerLooper) {
            block()
            return
        }
        suspendCancellableCoroutine { continuation ->
            val accepted =
                playerHandler.post {
                    if (!continuation.isActive) return@post
                    try {
                        block()
                        continuation.resume(Unit)
                    } catch (error: Throwable) {
                        continuation.resumeWithException(error)
                    }
                }
            if (!accepted && continuation.isActive) {
                continuation.resumeWithException(
                    IllegalStateException("Media3 player looper rejected adapter work")
                )
            }
        }
    }
}
