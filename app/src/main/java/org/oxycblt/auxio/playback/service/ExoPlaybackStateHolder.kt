/*
 * Copyright (c) 2024 Auxio Project
 * ExoPlaybackStateHolder.kt is part of Auxio.
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
package org.oxycblt.auxio.playback.service

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.R
import org.oxycblt.auxio.image.ImageSettings
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.CrossfadeEligibility
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.TransitionMode
import org.oxycblt.auxio.playback.equalPowerCrossfade
import org.oxycblt.auxio.playback.persist.PersistenceRepository
import org.oxycblt.auxio.playback.replaygain.ReplayGainAudioProcessor
import org.oxycblt.auxio.playback.state.DeferredPlayback
import org.oxycblt.auxio.playback.state.PlaybackCommand
import org.oxycblt.auxio.playback.state.PlaybackStateHolder
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.RawQueue
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.state.ShuffleMode
import org.oxycblt.auxio.playback.state.StateAck
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.auxio.shippy.persistence.playback.CanonicalPlaybackCheckpoint
import org.oxycblt.auxio.shippy.persistence.playback.PlaybackCheckpointRepository
import org.oxycblt.auxio.shippy.playback.CanonicalPlaybackRestoreCoordinator
import org.oxycblt.auxio.shippy.playback.ProviderPlaybackLifecycle
import org.oxycblt.musikr.MusicParent
import timber.log.Timber as L

@OptIn(UnstableApi::class)
class ExoPlaybackStateHolder(
    private val context: Context,
    private var player: ExoPlayer,
    private var standbyPlayer: ExoPlayer,
    private val playbackManager: PlaybackStateManager,
    private val persistenceRepository: PersistenceRepository,
    private val canonicalCheckpoints: PlaybackCheckpointRepository,
    private val canonicalRestore: CanonicalPlaybackRestoreCoordinator,
    private val playbackSettings: PlaybackSettings,
    private val commandFactory: PlaybackCommand.Factory,
    private var replayGainProcessor: ReplayGainAudioProcessor,
    private var standbyReplayGainProcessor: ReplayGainAudioProcessor,
    private val musicRepository: MusicRepository,
    private val imageSettings: ImageSettings,
    private val playbackRequestHeaders: PlaybackRequestHeaders,
    private val transitionGuard: PlaybackTransitionGuard,
    private val providerPlaybackLifecycle: ProviderPlaybackLifecycle,
) :
    PlaybackStateHolder,
    Player.Listener,
    MusicRepository.UpdateListener,
    PlaybackSettings.Listener,
    ImageSettings.Listener {
    private val saveJob = Job()
    private val saveScope = CoroutineScope(Dispatchers.IO + saveJob)
    private val restoreScope = CoroutineScope(Dispatchers.IO + saveJob)
    private val locatorLifecycleJob = Job()
    private val locatorLifecycleScope = CoroutineScope(Dispatchers.IO + locatorLifecycleJob)
    private var currentSaveJob: Job? = null
    private var openAudioEffectSession = false
    private var crossfadeJob: Job? = null
    private var crossfadeArmJob: Job? = null
    private var preparedStandbyIndex = C.INDEX_UNSET
    private var crossfadePromoting = false
    /** Hold queue publication until Media3 has committed the replacement media item. */
    private var pendingNewPlaybackAck: StateAck.NewPlayback? = null
    private var pendingNewPlaybackMediaId: String? = null
    private var lastErrorMediaId: String? = null
    private var retriedCurrentError = false
    private val failedMediaIds = mutableSetOf<String>()

    var sessionOngoing = false
        private set

    fun attach() {
        playbackManager.registerStateHolder(this)
        musicRepository.addUpdateListener(this)
        player.addListener(this)
        replayGainProcessor.attach()
        standbyPlayer.volume = 0f
        playbackSettings.registerListener(this)
        imageSettings.registerListener(this)
    }

    fun release() {
        cancelCrossfade()
        pendingNewPlaybackAck = null
        pendingNewPlaybackMediaId = null
        saveJob.cancel()
        locatorLifecycleJob.cancel()
        playbackRequestHeaders.replace(emptyList())
        playbackManager.unregisterStateHolder(this)
        musicRepository.removeUpdateListener(this)
        player.removeListener(this)
        replayGainProcessor.release()
        imageSettings.unregisterListener(this)
        playbackSettings.unregisterListener(this)
        player.release()
        standbyPlayer.release()
    }

    override var parent: MusicParent? = null
        private set

    override val progression: Progression
        get() {
            val mediaItem = player.currentMediaItem ?: return Progression.nil()
            val duration = mediaItem.mediaMetadata.durationMs ?: Long.MAX_VALUE
            val clampedPosition = player.currentPosition.coerceAtLeast(0).coerceAtMost(duration)
            return Progression.from(player.playWhenReady, player.isPlaying, clampedPosition)
        }

    override val repeatMode
        get() =
            when (val repeatMode = player.repeatMode) {
                Player.REPEAT_MODE_OFF -> RepeatMode.NONE
                Player.REPEAT_MODE_ONE -> RepeatMode.TRACK
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> throw IllegalStateException("Unknown repeat mode: $repeatMode")
            }

    override val audioSessionId: Int
        get() = player.audioSessionId

    override fun resolveQueue(): RawQueue {
        val canonicalById = playbackManager.resolvedQueue.associateBy { it.item.id.value }
        val heap =
            (0 until player.mediaItemCount).map { index ->
                val mediaItem = player.getMediaItemAt(index)
                checkNotNull(mediaItem.resolvedQueueItem ?: canonicalById[mediaItem.mediaId]) {
                    "Playback item ${mediaItem.mediaId} at $index lost its canonical queue identity"
                }
            }
        val shuffledMapping =
            if (player.shuffleModeEnabled) {
                player.unscrambleQueueIndices()
            } else {
                emptyList()
            }
        return RawQueue(heap, shuffledMapping, player.currentMediaItemIndex)
    }

    override fun handleDeferred(action: DeferredPlayback): Boolean {
        when (action) {
            // Restore state -> Start a new restoreState job
            is DeferredPlayback.RestoreState -> {
                L.d("Restoring playback state")
                restoreScope.launch {
                    val canonical =
                        try {
                            canonicalRestore.restore()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            L.e(error, "Unable to restore canonical playback checkpoint")
                            null
                        }
                    val state = if (canonical == null) persistenceRepository.readState() else null
                    withContext(Dispatchers.Main) {
                        if (canonical != null) {
                            playbackManager.applyCanonicalCheckpoint(canonical, false)
                            if (action.play) playbackManager.playing(true)
                        } else if (state != null) {
                            // Apply the saved state on the main thread to prevent code expecting
                            // state updates on the main thread from crashing.
                            playbackManager.applySavedState(state, false)
                            if (action.play) {
                                playbackManager.playing(true)
                            }
                        } else if (action.fallback != null) {
                            playbackManager.playDeferred(action.fallback)
                        }
                    }
                }
            }
            // Shuffle all -> Start new playback from all songs
            is DeferredPlayback.ShuffleAll -> {
                val library = musicRepository.library?.takeIf { !it.empty() } ?: return false
                L.d("Shuffling all tracks")
                playbackManager.play(
                    requireNotNull(commandFactory.all(ShuffleMode.ON)) {
                        "Invalid playback parameters"
                    }
                )
            }
            // Open -> Try to find the Song for the given file and then play it from all songs
            is DeferredPlayback.Open -> {
                val library = musicRepository.library?.takeIf { !it.empty() } ?: return false
                L.d("Opening specified file")
                context.applicationContext.contentResolver
                    .query(
                        action.uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )
                    ?.use { cursor ->
                        val displayNameIndex =
                            cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            val displayName = cursor.getString(displayNameIndex)
                            val size = cursor.getLong(sizeIndex)
                            val song =
                                library.songs.find {
                                    it.path.name == displayName && it.size == size
                                }
                            if (song != null) {
                                val command =
                                    requireNotNull(
                                        commandFactory.songFromAll(song, ShuffleMode.IMPLICIT)
                                    ) {
                                        "Invalid playback command"
                                    }
                                playbackManager.play(command)
                            }
                        }
                    }
            }
        }

        return true
    }

    override fun playing(playing: Boolean) {
        if (!playing) cancelCrossfade()
        player.playWhenReady = playing
    }

    override fun playbackSpeed(speed: Float) {
        require(speed in 0.95f..1.05f) { "Playback synchronization speed is outside bounds" }
        player.setPlaybackSpeed(speed)
    }

    override fun seekTo(positionMs: Long) {
        cancelCrossfade()
        player.seekTo(positionMs)
        deferSave()
        // Ack handled w/ExoPlayer events
    }

    override fun repeatMode(repeatMode: RepeatMode) {
        cancelCrossfade()
        player.repeatMode =
            when (repeatMode) {
                RepeatMode.NONE -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
            }
        updatePauseOnRepeat()
        playbackManager.ack(this, StateAck.RepeatModeChanged)
        deferSave()
    }

    override fun newPlayback(command: PlaybackCommand) {
        cancelCrossfade()
        lastErrorMediaId = null
        retriedCurrentError = false
        failedMediaIds.clear()
        parent = command.parent
        pendingNewPlaybackAck = StateAck.NewPlayback
        playbackRequestHeaders.replace(command.queue)
        player.shuffleModeEnabled = command.shuffled
        player.setMediaItems(command.queue.map { it.buildMediaItem() })
        val startIndex =
            command.selectedItemId
                ?.let { selectedId -> command.queue.indexOfFirst { it.item.id == selectedId } }
                .also { check(it != -1) { "Start song not in queue" } }
        if (command.shuffled) {
            player.setShuffleOrder(BetterShuffleOrder(command.queue.size, startIndex ?: -1))
        }
        val target = startIndex ?: player.currentTimeline.getFirstWindowIndex(command.shuffled)
        check(target in command.queue.indices) { "Playback queue has no selected item" }
        pendingNewPlaybackMediaId = command.queue[target].item.id.value
        player.seekTo(target, C.TIME_UNSET)
        player.prepare()
        player.play()
        refreshProviderLocatorsNearPlayback()
        deferSave()
    }

    override fun shuffled(shuffled: Boolean) {
        cancelCrossfade()
        player.setShuffleModeEnabled(shuffled)
        if (player.shuffleModeEnabled) {
            // Have to manually refresh the shuffle seed and anchor it to the new current songs
            player.setShuffleOrder(
                BetterShuffleOrder(player.mediaItemCount, player.currentMediaItemIndex)
            )
        }
        playbackManager.ack(this, StateAck.QueueReordered)
        refreshProviderLocatorsNearPlayback()
        deferSave()
    }

    override fun next() {
        cancelCrossfade()
        // Replicate the old pseudo-circular queue behavior when no repeat option is implemented.
        // Basically, you can't skip back and wrap around the queue, but you can skip forward and
        // wrap around the queue, albeit playback will be paused.
        if (player.repeatMode == Player.REPEAT_MODE_ALL || player.hasNextMediaItem()) {
            player.seekToNext()
            if (!playbackSettings.rememberPause) {
                player.play()
            }
        } else {
            player.seekTo(
                player.currentTimeline.getFirstWindowIndex(player.shuffleModeEnabled),
                C.TIME_UNSET,
            )
            // TODO: Dislike the UX implications of this, I feel should I bite the bullet
            //  and switch to dynamic skip enable/disable?
            if (!playbackSettings.rememberPause) {
                player.pause()
            }
        }
        deferSave()
    }

    override fun prev() {
        cancelCrossfade()
        if (playbackSettings.rewindWithPrev) {
            player.seekToPrevious()
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0)
        }
        if (!playbackSettings.rememberPause) {
            player.play()
        }
        deferSave()
    }

    override fun goto(index: Int) {
        cancelCrossfade()
        val indices = player.unscrambleQueueIndices()
        if (indices.isEmpty()) {
            return
        }

        val trueIndex = indices[index]
        player.seekTo(trueIndex, C.TIME_UNSET) // Handles remaining custom logic
        if (!playbackSettings.rememberPause) {
            player.play()
        }
        deferSave()
    }

    override fun playNext(items: List<ResolvedQueueItem>, ack: StateAck.PlayNext) {
        cancelCrossfade()
        playbackRequestHeaders.replace(resolveQueue().heap + items)
        val currTimeline = player.currentTimeline
        val nextIndex =
            if (currTimeline.isEmpty) {
                C.INDEX_UNSET
            } else {
                currTimeline.getNextWindowIndex(
                    player.currentMediaItemIndex,
                    Player.REPEAT_MODE_OFF,
                    player.shuffleModeEnabled,
                )
            }

        if (nextIndex == C.INDEX_UNSET) {
            player.addMediaItems(items.map { it.buildMediaItem() })
        } else {
            player.addMediaItems(nextIndex, items.map { it.buildMediaItem() })
        }
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun addToQueue(items: List<ResolvedQueueItem>, ack: StateAck.AddToQueue) {
        cancelCrossfade()
        playbackRequestHeaders.replace(resolveQueue().heap + items)
        player.addMediaItems(items.map { it.buildMediaItem() })
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun move(from: Int, to: Int, ack: StateAck.Move) {
        cancelCrossfade()
        val indices = player.unscrambleQueueIndices()
        when (val plan = planQueueMove(indices, from, to, player.shuffleModeEnabled)) {
            is QueueMovePlan.MediaItem -> player.moveMediaItem(plan.from, plan.to)
            is QueueMovePlan.ShuffleOrder ->
                player.setShuffleOrder(BetterShuffleOrder(plan.indices.toIntArray()))
            null -> return
        }
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun remove(at: Int, ack: StateAck.Remove) {
        cancelCrossfade()
        val indices = player.unscrambleQueueIndices()
        if (indices.isEmpty()) {
            return
        }

        val trueIndex = indices[at]
        val songWillChange = player.currentMediaItemIndex == trueIndex
        player.removeMediaItem(trueIndex)
        playbackRequestHeaders.replace(resolveQueue().heap)
        if (songWillChange && !playbackSettings.rememberPause) {
            player.play()
        }
        playbackManager.ack(this, ack)
        deferSave()
    }

    override fun applySavedState(
        parent: MusicParent?,
        rawQueue: RawQueue,
        positionMs: Long,
        repeatMode: RepeatMode,
        ack: StateAck.NewPlayback?,
    ) {
        cancelCrossfade()
        var sendNewPlaybackEvent = false
        var shouldSeek = false
        val queueChanged = rawQueue != resolveQueue()
        if (this.parent != parent) {
            this.parent = parent
            sendNewPlaybackEvent = true
        }
        if (queueChanged) {
            pendingNewPlaybackAck = ack
            pendingNewPlaybackMediaId = ack?.let { rawQueue.heap[rawQueue.heapIndex].item.id.value }
            playbackRequestHeaders.replace(rawQueue.heap)
            player.setMediaItems(rawQueue.heap.map { it.buildMediaItem() })
            if (rawQueue.isShuffled) {
                player.shuffleModeEnabled = true
                player.setShuffleOrder(BetterShuffleOrder(rawQueue.shuffledMapping.toIntArray()))
            } else {
                player.shuffleModeEnabled = false
            }
            player.seekTo(rawQueue.heapIndex, C.TIME_UNSET)
            player.prepare()
            player.pause()
            sendNewPlaybackEvent = true
            shouldSeek = true
        }

        repeatMode(repeatMode)
        // See if we differ by more than a second. This allows us to avoid a meaningless seek
        // in the case of a "tight restore" (i.e music was reloaded).
        // In the case that this is a false positive, it's not very percievable (at least compared
        // to skipping when updating the library).
        // TODO: Introduce a better state management system rather than do something finicky like
        // this.
        if (shouldSeek || abs(player.currentPosition - positionMs) > 1000L) {
            player.seekTo(positionMs)
        }

        if (sendNewPlaybackEvent) {
            if (!queueChanged) {
                pendingNewPlaybackAck = null
                pendingNewPlaybackMediaId = null
                ack?.let { playbackManager.ack(this, it) }
            }
        }
    }

    override fun endSession() {
        cancelCrossfade()
        // This session has ended, so we need to reset this flag for when the next
        // session starts.
        playbackManager.playing(false)
        save {
            // User could feasibly start playing again if they were fast enough, so
            // we need to avoid stopping the foreground state if that's the case.
            if (!playbackManager.progression.isPlaying) {
                sessionOngoing = false
                playbackManager.ack(this, StateAck.SessionEnded)
            }
        }
    }

    override fun reset(ack: StateAck.NewPlayback) {
        cancelCrossfade()
        pendingNewPlaybackAck = null
        pendingNewPlaybackMediaId = null
        player.setMediaItems(listOf())
        playbackRequestHeaders.replace(emptyList())
        playbackManager.ack(this, ack)
        deferSave()
    }

    // --- PLAYER OVERRIDES ---

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        super.onPlayWhenReadyChanged(playWhenReady, reason)

        if (player.playWhenReady) {
            // Mark that we have started playing so that the notification can now be posted.
            L.d("Player has started playing")
            sessionOngoing = true
            if (!openAudioEffectSession) {
                // Convention to start an audioeffect session on play/pause rather than
                // start/stop
                L.d("Opening audio effect session")
                broadcastAudioEffectAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                openAudioEffectSession = true
            }
        } else if (openAudioEffectSession) {
            // Make sure to close the audio session when we stop playback.
            L.d("Closing audio effect session")
            broadcastAudioEffectAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            openAudioEffectSession = false
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)

        if (playbackState == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF) {
            if (transitionGuard.crewActive) {
                // End-of-media is device telemetry in Crew. The canonical coordinator decides
                // the shared wrap/pause policy; this device must not independently rewrite state.
                playbackManager.ack(this, StateAck.PlaybackEnded)
                return
            }
            goto(0)
            player.pause()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        if (mediaItem?.mediaId != lastErrorMediaId) {
            lastErrorMediaId = mediaItem?.mediaId
            retriedCurrentError = false
        }

        // Media3 is the final authority for which media item is actually producing audio. A seek
        // initiated by Next/Previous/GoTo can be applied asynchronously, so acknowledging from the
        // command method can publish the old numeric index. Always synchronize from this callback,
        // after Media3 has committed the transition. This also covers automatic transitions and
        // playlist replacements without pairing new audio with stale title/artwork/lyrics.
        if (mediaItem != null) {
            val pendingAck = pendingNewPlaybackAck
            if (pendingAck != null) {
                if (mediaItem.mediaId != pendingNewPlaybackMediaId) {
                    refreshProviderLocatorsNearPlayback()
                    return
                }
                pendingNewPlaybackAck = null
                pendingNewPlaybackMediaId = null
                playbackManager.ack(this, pendingAck)
            } else {
                playbackManager.ack(this, StateAck.IndexMoved)
            }
            if (BuildConfig.DEBUG) {
                val rawQueue = resolveQueue()
                val current = rawQueue.resolveItems().getOrNull(rawQueue.resolveIndex())
                L.d(
                    "Media3 transition mediaId=${mediaItem.mediaId}, " +
                        "queueItemId=${current?.item?.id?.value}, " +
                        "trackId=${current?.item?.track?.id?.value}, " +
                        "index=${rawQueue.resolveIndex()}, shuffled=${rawQueue.isShuffled}"
                )
            }
            deferSave()
        }
        refreshProviderLocatorsNearPlayback()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        super.onEvents(player, events)

        // So many actions trigger progression changes that it becomes easier just to handle it
        // in an ExoPlayer callback anyway. This doesn't really cause issues anywhere.
        if (
            events.containsAny(
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
            )
        ) {
            L.d("Player state changed, must synchronize state")
            playbackManager.ack(this, StateAck.ProgressionChanged)
        }
        if (
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED)
        ) {
            maybePrepareOrStartCrossfade()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        cancelCrossfade()
        val mediaId = player.currentMediaItem?.mediaId.orEmpty()
        if (mediaId != lastErrorMediaId) {
            lastErrorMediaId = mediaId
            retriedCurrentError = false
        }
        L.e(error, "Player error on $mediaId")
        if (
            !transitionGuard.crewActive &&
                providerPlaybackLifecycle.requiresProviderRefresh(mediaId)
        ) {
            refreshFailedProviderLocator(mediaId, error)
            return
        }
        if (error.errorCode in 2000..2999 && !retriedCurrentError) {
            retriedCurrentError = true
            player.prepare()
            player.play()
            return
        }
        failedMediaIds += mediaId
        Toast.makeText(context, R.string.err_track_playback, Toast.LENGTH_SHORT).show()
        if (failedMediaIds.size >= player.mediaItemCount.coerceAtLeast(1)) {
            player.pause()
            playbackManager.ack(this, StateAck.ProgressionChanged)
            return
        }
        if (transitionGuard.crewActive) {
            // A device-local resolver/decoder failure is not collaborative intent. Keep the
            // canonical Crew item selected and wait for provider retry or Push & Pull completion;
            // never turn this failure into a group-wide Next command.
            player.pause()
            playbackManager.ack(this, StateAck.ProgressionChanged)
            return
        }
        player.prepare()
        playbackManager.next()
    }

    /**
     * Explicit ordinary-playback hook: resolved provider URLs are device-private and may expire, so
     * refresh them near the cursor instead of rebuilding or shrinking the logical queue.
     */
    private fun refreshProviderLocatorsNearPlayback() {
        if (transitionGuard.crewActive) return
        val current = player.currentMediaItem?.resolvedQueueItem ?: return
        // Resolve the actual playback traversal, not physical Media3 storage order. In shuffle
        // mode these differ, and preparing heap neighbours leaves the real next item unresolved.
        val order = resolveQueue().resolveItems().map { it.item.id }
        locatorLifecycleScope.launch {
            val updates = providerPlaybackLifecycle.resolveNearPlayback(current.item.id, order)
            applyProviderLocatorUpdates(updates)
        }
    }

    private fun refreshFailedProviderLocator(mediaId: String, error: PlaybackException) {
        locatorLifecycleScope.launch {
            val refreshed = providerPlaybackLifecycle.refreshCurrent(mediaId)
            if (refreshed != null) {
                withContext(Dispatchers.Main.immediate) {
                    replaceResolvedMediaItem(refreshed)
                    player.prepare()
                    player.play()
                }
            } else {
                withContext(Dispatchers.Main.immediate) {
                    failCurrentAfterLocatorRefresh(error, mediaId)
                }
            }
        }
    }

    private suspend fun applyProviderLocatorUpdates(updates: List<ResolvedQueueItem>) {
        if (updates.isEmpty()) return
        withContext(Dispatchers.Main.immediate) { updates.forEach(::replaceResolvedMediaItem) }
    }

    private fun replaceResolvedMediaItem(update: ResolvedQueueItem) {
        val index =
            (0 until player.mediaItemCount).firstOrNull {
                player.getMediaItemAt(it).mediaId == update.item.id.value
            } ?: return
        player.replaceMediaItem(index, update.buildMediaItem())
        playbackRequestHeaders.replace(resolveQueue().heap)
    }

    private fun failCurrentAfterLocatorRefresh(error: PlaybackException, mediaId: String) {
        failedMediaIds += mediaId
        Toast.makeText(context, R.string.err_track_playback, Toast.LENGTH_SHORT).show()
        if (failedMediaIds.size >= player.mediaItemCount.coerceAtLeast(1)) {
            player.pause()
            playbackManager.ack(this, StateAck.ProgressionChanged)
            return
        }
        L.w(error, "Unable to refresh provider locator for $mediaId")
        player.prepare()
        playbackManager.next()
    }

    /**
     * Standby remains private: no state-manager listener, media session, or audio focus. It gets
     * the exact raw queue only so a later promotion preserves shuffle and index semantics.
     */
    private fun maybePrepareOrStartCrossfade() {
        if (
            crossfadeJob?.isActive == true ||
                playbackSettings.transitionMode != TransitionMode.CROSSFADE ||
                transitionGuard.crewActive ||
                !player.isPlaying ||
                player.repeatMode == Player.REPEAT_MODE_ONE
        )
            return
        val nextIndex = nextMediaItemIndex() ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val fadeDuration = playbackSettings.crossfadeDurationMs
        if (duration <= fadeDuration) return
        val next = player.getMediaItemAt(nextIndex).resolvedQueueItem ?: return
        if (!next.playback.isStillValid()) return

        if (
            preparedStandbyIndex != nextIndex ||
                standbyPlayer.mediaItemCount != player.mediaItemCount
        ) {
            prepareStandby(nextIndex)
            return
        }
        val eligibility =
            CrossfadeEligibility(
                playbackSettings.transitionMode,
                transitionGuard.crewActive,
                player.isPlaying,
                player.repeatMode == Player.REPEAT_MODE_ONE,
                duration,
                fadeDuration,
                nextItemExists = true,
                nextLocatorValid = next.playback.isStillValid(),
                standbyReady = standbyPlayer.playbackState == Player.STATE_READY,
            )
        if (!eligibility.allowed || player.currentPosition < duration - fadeDuration) return
        startCrossfade(nextIndex, duration, fadeDuration)
    }

    private fun prepareStandby(nextIndex: Int) {
        cancelCrossfade(clearStandby = false)
        val raw = resolveQueue()
        if (raw.heap.isEmpty() || nextIndex !in raw.heap.indices) return
        standbyPlayer.stop()
        standbyPlayer.clearMediaItems()
        standbyPlayer.volume = 0f
        standbyReplayGainProcessor.prepareNeutral()
        standbyPlayer.shuffleModeEnabled = player.shuffleModeEnabled
        standbyPlayer.setMediaItems(raw.heap.map { it.buildMediaItem() })
        if (raw.isShuffled)
            standbyPlayer.setShuffleOrder(BetterShuffleOrder(raw.shuffledMapping.toIntArray()))
        standbyPlayer.seekTo(nextIndex, 0)
        standbyPlayer.prepare()
        preparedStandbyIndex = nextIndex
        crossfadeArmJob?.cancel()
        crossfadeArmJob =
            saveScope.launch(Dispatchers.Main.immediate) {
                while (
                    player.isPlaying &&
                        preparedStandbyIndex == nextIndex &&
                        playbackSettings.transitionMode == TransitionMode.CROSSFADE &&
                        !transitionGuard.crewActive &&
                        crossfadeJob?.isActive != true
                ) {
                    maybePrepareOrStartCrossfade()
                    delay(CROSSFADE_ARM_TICK_MS)
                }
            }
    }

    private fun startCrossfade(nextIndex: Int, sourceDurationMs: Long, fadeDurationMs: Long) {
        if (crossfadeJob?.isActive == true || standbyPlayer.playbackState != Player.STATE_READY)
            return
        val source = player
        val sourceIndex = source.currentMediaItemIndex
        // Prevent native auto-advance from racing the promoted player; always restored below.
        source.pauseAtEndOfMediaItems = true
        standbyPlayer.volume = 0f
        standbyPlayer.play()
        val startedAtPosition = source.currentPosition
        crossfadeJob =
            saveScope.launch(Dispatchers.Main.immediate) {
                try {
                    while (true) {
                        if (
                            player !== source ||
                                transitionGuard.crewActive ||
                                playbackSettings.transitionMode != TransitionMode.CROSSFADE ||
                                !source.isPlaying ||
                                source.currentMediaItemIndex != sourceIndex ||
                                !standbyPlayer.isPlaying
                        ) {
                            cancelCrossfade()
                            return@launch
                        }
                        val progress =
                            ((source.currentPosition - startedAtPosition).coerceAtLeast(0))
                                .toFloat() / fadeDurationMs
                        val envelope = equalPowerCrossfade(progress)
                        source.volume = envelope.outgoing
                        standbyPlayer.volume = envelope.incoming
                        if (
                            progress >= 1f ||
                                source.currentPosition >=
                                    sourceDurationMs - CROSSFADE_PROMOTION_SAFETY_MS
                        ) {
                            promoteStandby(nextIndex)
                            return@launch
                        }
                        delay(CROSSFADE_TICK_MS)
                    }
                } catch (_: CancellationException) {
                    // Queue actions race overlap by design; cancellation restores primary state.
                }
            }
    }

    /** Swaps renderer ownership while [PlaybackStateManager] remains the sole logical authority. */
    private fun promoteStandby(expectedIndex: Int) {
        if (
            crossfadePromoting ||
                preparedStandbyIndex != expectedIndex ||
                standbyPlayer.playbackState != Player.STATE_READY
        ) {
            cancelCrossfade()
            return
        }
        crossfadePromoting = true
        val oldPlayer = player
        val oldProcessor = replayGainProcessor
        val promotedPlayer = standbyPlayer
        val promotedProcessor = standbyReplayGainProcessor
        try {
            crossfadeJob?.cancel()
            crossfadeJob = null
            oldPlayer.removeListener(this)
            oldProcessor.release()
            oldPlayer.volume = 0f
            oldPlayer.pause()
            oldPlayer.setAudioAttributes(PLAYBACK_AUDIO_ATTRIBUTES, false)

            player = promotedPlayer
            replayGainProcessor = promotedProcessor
            player.setAudioAttributes(PLAYBACK_AUDIO_ATTRIBUTES, true)
            player.volume = 1f
            player.addListener(this)
            replayGainProcessor.attach()
            player.play()

            standbyPlayer = oldPlayer
            standbyReplayGainProcessor = oldProcessor
            standbyPlayer.stop()
            standbyPlayer.clearMediaItems()
            standbyPlayer.volume = 0f
            preparedStandbyIndex = C.INDEX_UNSET
            updatePauseOnRepeat()
            reopenAudioEffectSession()
            playbackManager.ack(this, StateAck.IndexMoved)
            deferSave()
        } finally {
            crossfadePromoting = false
        }
    }

    private fun cancelCrossfade(clearStandby: Boolean = true) {
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadeArmJob?.cancel()
        crossfadeArmJob = null
        player.volume = 1f
        updatePauseOnRepeat()
        standbyPlayer.pause()
        standbyPlayer.volume = 0f
        if (clearStandby) {
            standbyPlayer.stop()
            standbyPlayer.clearMediaItems()
            preparedStandbyIndex = C.INDEX_UNSET
        }
    }

    private fun nextMediaItemIndex(): Int? =
        player.currentTimeline
            .getNextWindowIndex(
                player.currentMediaItemIndex,
                Player.REPEAT_MODE_OFF,
                player.shuffleModeEnabled,
            )
            .takeIf { it != C.INDEX_UNSET }

    private fun org.oxycblt.auxio.shippy.domain.ResolvedPlayback.isStillValid() =
        uri.isNotBlank() &&
            (expiresAtEpochMs == null || expiresAtEpochMs > System.currentTimeMillis())

    private fun reopenAudioEffectSession() {
        if (openAudioEffectSession) {
            broadcastAudioEffectAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            openAudioEffectSession = false
        }
        if (player.playWhenReady) {
            broadcastAudioEffectAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            openAudioEffectSession = true
        }
    }

    private fun broadcastAudioEffectAction(event: String) {
        L.d("Broadcasting AudioEffect event: $event")
        context.sendBroadcast(
            Intent(event)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        )
    }

    // --- MUSICREPOSITORY METHODS ---

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary && musicRepository.library?.takeIf { !it.empty() } != null) {
            // We now have a library, see if we have anything we need to do.
            L.d("Library obtained, requesting action")
            playbackManager.requestAction(this)
        }
    }

    // --- PLAYBACKSETTINGS OVERRIDES ---

    override fun onPauseOnRepeatChanged() {
        super.onPauseOnRepeatChanged()
        updatePauseOnRepeat()
    }

    override fun onTransitionSettingsChanged() {
        cancelCrossfade()
    }

    private fun updatePauseOnRepeat() {
        player.pauseAtEndOfMediaItems =
            player.repeatMode == Player.REPEAT_MODE_ONE && playbackSettings.pauseOnRepeat
    }

    private fun save(cb: () -> Unit) {
        saveJob {
            if (sessionOngoing) {
                persistenceRepository.saveState(playbackManager.toSavedState())
                saveCanonicalCheckpoint()
            }
            withContext(Dispatchers.Main) { cb() }
        }
    }

    private fun deferSave() {
        saveJob {
            L.d("Waiting for save buffer")
            delay(SAVE_BUFFER)
            yield()
            L.d("Committing saved state")
            if (sessionOngoing) {
                persistenceRepository.saveState(playbackManager.toSavedState())
                saveCanonicalCheckpoint()
            }
        }
    }

    private fun saveJob(block: suspend () -> Unit) {
        currentSaveJob?.let {
            L.d("Discarding prior save job")
            it.cancel()
        }
        currentSaveJob = saveScope.launch { block() }
    }

    private suspend fun saveCanonicalCheckpoint() {
        try {
            playbackManager.toCanonicalCheckpoint()?.let { checkpoint ->
                canonicalCheckpoints.replace(
                    CanonicalPlaybackCheckpoint(
                        checkpoint.heap.map(ResolvedQueueItem::item),
                        checkpoint.shuffledMapping,
                        checkpoint.heapIndex,
                        checkpoint.positionMs.coerceAtLeast(0),
                        checkpoint.repeatMode,
                    )
                )
            } ?: canonicalCheckpoints.clear()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            L.e(error, "Unable to save canonical playback checkpoint")
        }
    }

    private fun ResolvedQueueItem.buildMediaItem(): MediaItem {
        val track = item.track
        val metadata =
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artists.joinToString())
                .setAlbumTitle(track.album)
                .setDurationMs(track.durationMs)
                .setArtworkUri(track.artwork?.takeIf(String::isNotBlank)?.let(Uri::parse))
                .setIsPlayable(true)
                .build()
        return MediaItem.Builder()
            .setMediaId(item.id.value)
            .setUri(playback.uri)
            .setMimeType(playback.mimeType)
            .setCustomCacheKey(playback.mediaObjectKey.value.takeIf { playback.cacheEligible })
            .setMediaMetadata(metadata)
            .setTag(this)
            .build()
    }

    private val MediaItem.resolvedQueueItem: ResolvedQueueItem?
        get() = this.localConfiguration?.tag as? ResolvedQueueItem

    private fun Player.unscrambleQueueIndices(): List<Int> {
        val timeline = currentTimeline
        if (timeline.isEmpty) {
            return emptyList()
        }
        val queue = mutableListOf<Int>()

        // Add the active queue item.
        val currentMediaItemIndex = currentMediaItemIndex
        queue.add(currentMediaItemIndex)

        // Fill queue alternating with next and/or previous queue items.
        var firstMediaItemIndex = currentMediaItemIndex
        var lastMediaItemIndex = currentMediaItemIndex
        val shuffleModeEnabled = shuffleModeEnabled
        while ((firstMediaItemIndex != C.INDEX_UNSET || lastMediaItemIndex != C.INDEX_UNSET)) {
            // Begin with next to have a longer tail than head if an even sized queue needs to be
            // trimmed.
            if (lastMediaItemIndex != C.INDEX_UNSET) {
                lastMediaItemIndex =
                    timeline.getNextWindowIndex(
                        lastMediaItemIndex,
                        Player.REPEAT_MODE_OFF,
                        shuffleModeEnabled,
                    )
                if (lastMediaItemIndex != C.INDEX_UNSET) {
                    queue.add(lastMediaItemIndex)
                }
            }
            if (firstMediaItemIndex != C.INDEX_UNSET) {
                firstMediaItemIndex =
                    timeline.getPreviousWindowIndex(
                        firstMediaItemIndex,
                        Player.REPEAT_MODE_OFF,
                        shuffleModeEnabled,
                    )
                if (firstMediaItemIndex != C.INDEX_UNSET) {
                    queue.add(0, firstMediaItemIndex)
                }
            }
        }

        return queue
    }

    class Factory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val playbackManager: PlaybackStateManager,
        private val persistenceRepository: PersistenceRepository,
        private val canonicalCheckpoints: PlaybackCheckpointRepository,
        private val canonicalRestore: CanonicalPlaybackRestoreCoordinator,
        private val playbackSettings: PlaybackSettings,
        private val commandFactory: PlaybackCommand.Factory,
        private val mediaSourceFactory: MediaSource.Factory,
        private val replayGainProcessorProvider: Provider<ReplayGainAudioProcessor>,
        private val musicRepository: MusicRepository,
        private val imageSettings: ImageSettings,
        private val playbackRequestHeaders: PlaybackRequestHeaders,
        private val transitionGuard: PlaybackTransitionGuard,
        private val providerPlaybackLifecycle: ProviderPlaybackLifecycle,
    ) {
        fun create(): ExoPlaybackStateHolder {
            val activeProcessor = replayGainProcessorProvider.get()
            val standbyProcessor = replayGainProcessorProvider.get()
            val exoPlayer = createAudioOnlyPlayer(activeProcessor, handleAudioFocus = true)
            val standbyPlayer = createAudioOnlyPlayer(standbyProcessor, handleAudioFocus = false)

            return ExoPlaybackStateHolder(
                context,
                exoPlayer,
                standbyPlayer,
                playbackManager,
                persistenceRepository,
                canonicalCheckpoints,
                canonicalRestore,
                playbackSettings,
                commandFactory,
                activeProcessor,
                standbyProcessor,
                musicRepository,
                imageSettings,
                playbackRequestHeaders,
                transitionGuard,
                providerPlaybackLifecycle,
            )
        }

        private fun createAudioOnlyPlayer(
            processor: ReplayGainAudioProcessor,
            handleAudioFocus: Boolean,
        ): ExoPlayer {
            val audioRenderer = RenderersFactory { handler, _, audioListener, _, _ ->
                arrayOf<BaseRenderer>(
                    FfmpegAudioRenderer(handler, audioListener, processor),
                    MediaCodecAudioRenderer(
                        context,
                        MediaCodecSelector.DEFAULT,
                        handler,
                        audioListener,
                        DefaultAudioSink.Builder(context)
                            .setAudioProcessors(arrayOf(processor))
                            .build(),
                    ),
                )
            }
            return ExoPlayer.Builder(context, audioRenderer)
                .setMediaSourceFactory(mediaSourceFactory)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setAudioAttributes(PLAYBACK_AUDIO_ATTRIBUTES, handleAudioFocus)
                .build()
        }
    }

    private companion object {
        const val SAVE_BUFFER = 5000L
        const val CROSSFADE_TICK_MS = 50L
        const val CROSSFADE_ARM_TICK_MS = 250L
        const val CROSSFADE_PROMOTION_SAFETY_MS = 150L
        val PLAYBACK_AUDIO_ATTRIBUTES: AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
    }
}
