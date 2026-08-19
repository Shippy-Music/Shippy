/*
 * Copyright (c) 2026 Auxio Project
 * R16MediaSessionAdapter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.system

import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import app.shippy.core.playback.PlaybackPhase
import app.shippy.core.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.playback.service.PlaybackActions

/** Pure Android MediaSession projection of the shared R16 system state. */
object R16MediaSessionProjection {
    fun metadata(
        state: R16SystemPlaybackState,
        unknownTitle: CharSequence,
        unknownArtist: CharSequence,
        defaultParent: CharSequence,
    ): MediaMetadataCompat {
        val item = state.displayItem ?: return MediaMetadataCompat.Builder().build()
        val presentation = item.presentation
        val title = presentation?.title?.ifBlank { null } ?: unknownTitle
        val artist = presentation?.artist?.ifBlank { null } ?: unknownArtist
        val release = presentation?.releaseTitle.orEmpty()
        return MediaMetadataCompat.Builder()
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, release)
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
            .putText(MediaMetadataCompat.METADATA_KEY_AUTHOR, artist)
            .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, release)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, presentation?.durationMs ?: 0L)
            .putText(KEY_PARENT, presentation?.releaseTitle ?: defaultParent)
            .putString(KEY_QUEUE_ENTRY_ID, item.queueEntryId.value)
            .putString(KEY_RECORDING_ID, item.recordingId.value)
            .apply {
                presentation?.artworkLocation?.takeIf(String::isNotBlank)?.let { artwork ->
                    putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork)
                    putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artwork)
                }
            }
            .build()
    }

    fun queue(state: R16SystemPlaybackState): List<MediaSessionCompat.QueueItem> =
        state.traversal.mapIndexed { index, item ->
            val presentation = item.presentation
            val description =
                MediaDescriptionCompat.Builder()
                    .setMediaId(item.queueEntryId.value)
                    .setTitle(presentation?.title.orEmpty())
                    .setSubtitle(presentation?.artist.orEmpty())
                    .setDescription(presentation?.releaseTitle)
                    .setIconUri(
                        presentation?.artworkLocation?.takeIf(String::isNotBlank)?.let(Uri::parse)
                    )
                    .setExtras(
                        Bundle().apply {
                            putInt(KEY_QUEUE_POSITION, index)
                            putString(KEY_QUEUE_ENTRY_ID, item.queueEntryId.value)
                            putString(KEY_RECORDING_ID, item.recordingId.value)
                        }
                    )
                    .build()
            MediaSessionCompat.QueueItem(description, index.toLong())
        }

    fun playbackState(state: R16SystemPlaybackState): PlaybackStateCompat {
        val phase = state.playback.phase
        val playbackState =
            when (phase) {
                PlaybackPhase.Idle -> PlaybackStateCompat.STATE_NONE
                is PlaybackPhase.Preparing,
                is PlaybackPhase.Buffering,
                is PlaybackPhase.Recovering -> PlaybackStateCompat.STATE_BUFFERING
                is PlaybackPhase.Ready -> PlaybackStateCompat.STATE_PAUSED
                is PlaybackPhase.Playing -> PlaybackStateCompat.STATE_PLAYING
                is PlaybackPhase.Paused -> PlaybackStateCompat.STATE_PAUSED
                is PlaybackPhase.Failed -> PlaybackStateCompat.STATE_ERROR
                PlaybackPhase.Ended -> PlaybackStateCompat.STATE_STOPPED
            }
        return PlaybackStateCompat.Builder()
            .setActions(ACTIONS)
            .setState(
                playbackState,
                state.playback.position.positionMs,
                if (playbackState == PlaybackStateCompat.STATE_PLAYING) {
                    state.playback.position.playbackSpeed.toFloat()
                } else {
                    0f
                },
                state.playback.position.sampledElapsedRealtimeMs,
            )
            .setActiveQueueItemId(state.activeQueueIndex.toLong())
            .apply {
                (phase as? PlaybackPhase.Failed)?.let {
                    setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it.error.code)
                }
            }
            .build()
    }

    const val KEY_PARENT = BuildConfig.APPLICATION_ID + ".r16.metadata.PARENT"
    const val KEY_QUEUE_POSITION = BuildConfig.APPLICATION_ID + ".r16.metadata.QUEUE_POSITION"
    const val KEY_QUEUE_ENTRY_ID = BuildConfig.APPLICATION_ID + ".r16.metadata.QUEUE_ENTRY_ID"
    const val KEY_RECORDING_ID = BuildConfig.APPLICATION_ID + ".r16.metadata.RECORDING_ID"

    const val ACTIONS =
        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SET_REPEAT_MODE or
            PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_STOP
}

/** MediaSession callback that submits only canonical R16 commands. */
class R16MediaSessionCommandCallback(
    private val scope: CoroutineScope,
    private val commands: R16SystemPlaybackCommands,
    private val onPlayFromMediaIdRequested: (String?, Bundle?) -> Unit,
    private val onPlayFromSearchRequested: (String?, Bundle?) -> Unit,
    private val onExitRequested: () -> Unit,
) : MediaSessionCompat.Callback() {
    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) =
        onPlayFromMediaIdRequested(mediaId, extras)

    override fun onPlayFromSearch(query: String?, extras: Bundle?) =
        onPlayFromSearchRequested(query, extras)

    override fun onPlay() = submit { commands.play() }

    override fun onPause() = submit { commands.pause() }

    override fun onSkipToNext() = submit { commands.next() }

    override fun onSkipToPrevious() = submit { commands.previous() }

    override fun onSkipToQueueItem(id: Long) = submit { commands.goToQueueIndex(id) }

    override fun onSeekTo(position: Long) = submit { commands.seekTo(position) }

    override fun onFastForward() = submit { commands.next() }

    override fun onRewind() = submit {
        commands.seekTo(0)
        commands.play()
    }

    override fun onSetRepeatMode(repeatMode: Int) = submit {
        commands.setRepeat(
            when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ALL,
                PlaybackStateCompat.REPEAT_MODE_GROUP -> RepeatMode.ALL
                PlaybackStateCompat.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            }
        )
    }

    override fun onSetShuffleMode(shuffleMode: Int) = submit {
        commands.setShuffle(
            shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL ||
                shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_GROUP
        )
    }

    override fun onStop() = onExitRequested()

    override fun onCustomAction(action: String, extras: Bundle?) {
        when (action) {
            PlaybackActions.ACTION_INC_REPEAT_MODE -> submit { commands.cycleRepeat() }
            PlaybackActions.ACTION_INVERT_SHUFFLE -> submit { commands.toggleShuffle() }
        }
    }

    private fun submit(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
