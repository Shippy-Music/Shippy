/*
 * Copyright (c) 2026 Auxio Project
 * R16NowPlayingFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.ui

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16NowPlayingBinding
import org.oxycblt.auxio.playback.service.PlaybackActions
import org.oxycblt.auxio.pushR16Destination
import org.oxycblt.auxio.shippy.lyrics.R16LyricsState
import org.oxycblt.auxio.shippy.r16.playback.system.R16MediaSessionProjection
import org.oxycblt.auxio.shippy.r16.playback.system.R16RetryCurrentMediaCommands

/** ACTIVE-only current-item screen. The MediaSession remains its sole state and command source. */
@AndroidEntryPoint
class R16NowPlayingFragment : Fragment(R.layout.fragment_r16_now_playing) {
    private val likeModel: R16NowPlayingLikeViewModel by viewModels()
    private val lyricsModel: R16LyricsViewModel by activityViewModels()
    private var binding: FragmentR16NowPlayingBinding? = null
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private var state = R16NowPlayingUiState.Hidden
    private var artworkIdentity: Pair<String?, String?>? = null
    private val progressTicker = Runnable {
        renderProgress()
        scheduleProgressTick()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentR16NowPlayingBinding.bind(view).also(::bindControls)
        parentFragmentManager.setFragmentResultListener(
            R16PlaylistDestinationPickerFragment.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            result.getString(R16PlaylistDestinationPickerFragment.RESULT_MESSAGE)?.let { message ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { likeModel.uiState.collect(::renderLike) }
                launch {
                    lyricsModel.lyricsState.collect { lyricsState ->
                        renderLyricsPreview(lyricsState, lyricsModel.activeLineIndex.value)
                    }
                }
                launch {
                    lyricsModel.activeLineIndex.collect { activeIndex ->
                        renderLyricsPreview(lyricsModel.lyricsState.value, activeIndex)
                    }
                }
            }
        }
        render(R16NowPlayingUiState.Hidden)
    }

    override fun onResume() {
        super.onResume()
        if (mediaBrowser == null) {
            mediaBrowser =
                MediaBrowserCompat(
                        requireContext(),
                        ComponentName(requireContext(), AuxioService::class.java),
                        browserConnection,
                        null,
                    )
                    .also(MediaBrowserCompat::connect)
        }
    }

    override fun onPause() {
        clearProgressTick()
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onPause()
    }

    override fun onDestroyView() {
        clearProgressTick()
        artworkIdentity = null
        binding = null
        super.onDestroyView()
    }

    private fun bindControls(bound: FragmentR16NowPlayingBinding) {
        bound.r16NowPlayingBack.setOnClickListener { parentFragmentManager.popBackStack() }
        bound.r16NowPlayingQueue.setOnClickListener { openQueue() }
        bound.r16NowPlayingAddToPlaylist.setOnClickListener { openAddToPlaylist() }
        bound.r16NowPlayingLike.setOnClickListener { likeModel.saveCurrentToLiked() }
        bound.r16NowPlayingRetry.setOnClickListener { retryCurrent() }
        bound.r16NowPlayingPrevious.setOnClickListener {
            mediaController?.transportControls?.skipToPrevious()
        }
        bound.r16NowPlayingPlayPause.setOnClickListener { togglePlayback() }
        bound.r16NowPlayingNext.setOnClickListener {
            mediaController?.transportControls?.skipToNext()
        }
        bound.r16NowPlayingRepeat.setOnClickListener { cycleRepeat() }
        bound.r16NowPlayingShuffle.setOnClickListener { toggleShuffle() }
        bound.r16NowPlayingLyricsCard.setOnClickListener { openLyrics() }
        bound.r16NowPlayingProgress.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) =
                    Unit

                override fun onStartTrackingTouch(seekBar: SeekBar) = clearProgressTick()

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    mediaController?.transportControls?.seekTo(seekBar.progress.toLong())
                    scheduleProgressTick()
                }
            }
        )
    }

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                mediaController =
                    MediaControllerCompat(requireContext(), browser.sessionToken).also {
                        it.registerCallback(controllerCallback)
                        renderCurrentState()
                    }
            }

            override fun onConnectionSuspended() = disconnectController()

            override fun onConnectionFailed() = disconnectController()
        }

    private val controllerCallback =
        object : MediaControllerCompat.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadataCompat?) = renderCurrentState()

            override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = renderCurrentState()
        }

    private fun renderCurrentState() {
        mediaController?.let {
            render(
                R16NowPlayingUiStateMapper.map(
                    it.metadata,
                    it.playbackState,
                    SystemClock.elapsedRealtime(),
                )
            )
        }
    }

    private fun render(next: R16NowPlayingUiState) {
        clearProgressTick()
        val previousRecordingId = state.recordingId
        state = next
        if (previousRecordingId != state.recordingId) resetLike()
        likeModel.setCurrentRecordingId(state.recordingId)
        binding?.apply {
            r16NowPlaying.isVisible = true
            r16NowPlayingCover.isVisible = state.visible
            r16NowPlayingTitle.isVisible = state.visible
            r16NowPlayingArtist.isVisible = state.visible
            r16NowPlayingRelease.isVisible = state.visible
            r16NowPlayingState.isVisible = true
            r16NowPlayingRetry.isVisible = state.visible && state.phase == R16NowPlayingPhase.Failed
            r16NowPlayingRetry.isEnabled =
                state.phase == R16NowPlayingPhase.Failed &&
                    state.queueEntryId != null &&
                    state.recordingId != null
            r16NowPlayingQueue.isVisible = true
            r16NowPlayingAddToPlaylist.isVisible = state.visible
            r16NowPlayingAddToPlaylist.isEnabled =
                state.visible && state.recordingId != null && state.queueEntryId != null
            r16NowPlayingProgress.isVisible = state.visible
            r16NowPlayingTime.isVisible = state.visible
            r16NowPlayingPrevious.isVisible = state.visible
            r16NowPlayingPlayPause.isVisible = state.visible
            r16NowPlayingNext.isVisible = state.visible
            r16NowPlayingRepeat.isVisible = state.visible
            r16NowPlayingShuffle.isVisible = state.visible
            if (!state.visible) {
                r16NowPlayingState.setText(R.string.r16_now_playing_empty)
                return
            }
            val nextArtwork = state.queueEntryId to state.artworkLocation
            if (artworkIdentity != nextArtwork) {
                artworkIdentity = nextArtwork
                r16NowPlayingCover.bindArtwork(state.artworkLocation, state.title.toString())
            } else {
                r16NowPlayingCover.contentDescription = state.title
            }
            r16NowPlayingTitle.text = state.title
            r16NowPlayingArtist.text = state.artist
            r16NowPlayingRelease.text = state.release
            r16NowPlayingState.text =
                when (state.phase) {
                    R16NowPlayingPhase.Loading -> getString(R.string.r16_now_playing_loading)
                    R16NowPlayingPhase.Buffering -> getString(R.string.r16_now_playing_buffering)
                    R16NowPlayingPhase.Paused -> getString(R.string.r16_now_playing_paused)
                    R16NowPlayingPhase.Playing -> getString(R.string.r16_now_playing_playing)
                    R16NowPlayingPhase.Failed -> getString(R.string.r16_now_playing_failed)
                    R16NowPlayingPhase.Hidden -> error("Hidden now-playing screen cannot render")
                }
            r16NowPlayingProgress.max =
                state.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            r16NowPlayingProgress.progress =
                state.progressMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            r16NowPlayingProgress.isEnabled = state.durationMs > 0
            r16NowPlayingTime.text =
                "${formatDuration(state.progressMs)} / ${formatDuration(state.durationMs)}"
            r16NowPlayingPlayPause.setText(
                if (
                    state.phase == R16NowPlayingPhase.Playing ||
                        state.phase == R16NowPlayingPhase.Buffering ||
                        state.phase == R16NowPlayingPhase.Loading
                ) {
                    R.string.r16_now_playing_pause
                } else {
                    R.string.r16_now_playing_play
                }
            )
        }
        if (state.visible && state.recordingId != null && state.queueEntryId != null) {
            lyricsModel.loadTrack(
                recordingId = app.shippy.core.identity.RecordingId(state.recordingId!!),
                queueEntryId = app.shippy.core.identity.QueueEntryId(state.queueEntryId!!),
                generation = state.generation,
                title = state.title?.toString(),
                artist = state.artist?.toString(),
                album = state.release?.toString(),
                durationMs = state.durationMs,
            )
            lyricsModel.updateProgress(state.progressMs)
        }
        scheduleProgressTick()
    }

    private fun renderLyricsPreview(lyricsState: R16LyricsState, activeIndex: Int) {
        val preview = binding?.r16NowPlayingLyricsPreview ?: return
        when (lyricsState) {
            is R16LyricsState.Synced -> {
                val lines = lyricsState.lyrics.lines
                val lineText =
                    if (activeIndex in lines.indices) {
                        lines[activeIndex].text
                    } else if (lines.isNotEmpty()) {
                        lines.take(2).joinToString("\n") { it.text }
                    } else {
                        getString(R.string.lbl_lyrics_tap_to_view)
                    }
                preview.text = lineText.ifBlank { getString(R.string.lbl_lyrics_tap_to_view) }
            }
            is R16LyricsState.Unsynced -> {
                val firstLine = lyricsState.lyrics.plainText.lines().firstOrNull(String::isNotBlank)
                preview.text = firstLine ?: getString(R.string.lbl_lyrics_tap_to_view)
            }
            is R16LyricsState.Loading -> {
                preview.setText(R.string.lbl_lyrics_loading)
            }
            is R16LyricsState.Unavailable,
            is R16LyricsState.Failed -> {
                preview.setText(R.string.lbl_lyrics_unavailable)
            }
            is R16LyricsState.None -> {
                preview.setText(R.string.lbl_lyrics_tap_to_view)
            }
        }
    }

    private fun renderLike(like: R16NowPlayingLikeUiState) {
        binding?.r16NowPlayingLike?.apply {
            isVisible = state.visible
            if (like.recordingId != state.recordingId || like.recordingId == null) {
                setImageResource(R.drawable.ic_add_24)
                contentDescription = getString(R.string.desc_save_to_liked)
                isEnabled = false
                return
            }
            setImageResource(if (like.liked) R.drawable.ic_favorite_24 else R.drawable.ic_add_24)
            contentDescription =
                getString(
                    if (like.liked) R.string.desc_saved_to_liked else R.string.desc_save_to_liked
                )
            isEnabled = state.visible && like.canSave
        }
    }

    private fun resetLike() {
        binding?.r16NowPlayingLike?.apply {
            isVisible = state.visible
            setImageResource(R.drawable.ic_add_24)
            contentDescription = getString(R.string.desc_save_to_liked)
            isEnabled = false
        }
    }

    private fun scheduleProgressTick() {
        clearProgressTick()
        if (state.phase == R16NowPlayingPhase.Playing && binding != null && isResumed) {
            binding?.r16NowPlaying?.postDelayed(progressTicker, PROGRESS_TICK_MS)
        }
    }

    /** Position ticks must not rebind metadata, restart artwork, or reload lyrics. */
    private fun renderProgress() {
        val controller = mediaController ?: return
        val next =
            R16NowPlayingUiStateMapper.map(
                controller.metadata,
                controller.playbackState,
                SystemClock.elapsedRealtime(),
            )
        if (next.queueEntryId != state.queueEntryId || next.phase != state.phase) {
            render(next)
            return
        }
        state = next
        binding?.apply {
            r16NowPlayingProgress.progress =
                next.progressMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            r16NowPlayingTime.text =
                "${formatDuration(next.progressMs)} / ${formatDuration(next.durationMs)}"
        }
        lyricsModel.updateProgress(next.progressMs)
    }

    private fun clearProgressTick() {
        binding?.r16NowPlaying?.removeCallbacks(progressTicker)
    }

    private fun togglePlayback() {
        when (mediaController?.playbackState?.state) {
            PlaybackStateCompat.STATE_PLAYING,
            PlaybackStateCompat.STATE_BUFFERING,
            PlaybackStateCompat.STATE_CONNECTING -> mediaController?.transportControls?.pause()
            else -> mediaController?.transportControls?.play()
        }
    }

    private fun cycleRepeat() {
        mediaController
            ?.transportControls
            ?.sendCustomAction(PlaybackActions.ACTION_INC_REPEAT_MODE, null)
    }

    private fun toggleShuffle() {
        mediaController
            ?.transportControls
            ?.sendCustomAction(PlaybackActions.ACTION_INVERT_SHUFFLE, null)
    }

    private fun retryCurrent() {
        val queueEntryId = state.queueEntryId ?: return
        val recordingId = state.recordingId ?: return
        mediaController
            ?.transportControls
            ?.sendCustomAction(
                R16RetryCurrentMediaCommands.RETRY_CURRENT,
                R16RetryCurrentMediaCommands.extras(
                    app.shippy.core.identity.QueueEntryId(queueEntryId),
                    app.shippy.core.identity.RecordingId(recordingId),
                ),
            )
    }

    private fun openQueue() {
        if (parentFragmentManager.findFragmentById(R.id.r16_active_content) is R16QueueFragment) {
            return
        }
        pushR16Destination(R16QueueFragment(), "r16-queue")
    }

    private fun openAddToPlaylist() {
        val recordingId = state.recordingId ?: return
        val queueEntryId = state.queueEntryId ?: return
        pushR16Destination(
            R16PlaylistDestinationPickerFragment.newInstance(
                recordingId,
                queueEntryId,
                state.title,
                state.artist,
            ),
            "r16-playlist-destination",
        )
    }

    private fun openLyrics() {
        val recordingId = state.recordingId ?: return
        val queueEntryId = state.queueEntryId ?: return
        R16LyricsBottomSheetFragment.newInstance(
                recordingId = recordingId,
                queueEntryId = queueEntryId,
                generation = state.generation,
                title = state.title?.toString(),
                artist = state.artist?.toString(),
                album = state.release?.toString(),
                durationMs = state.durationMs,
            )
            .show(parentFragmentManager, R16LyricsBottomSheetFragment.TAG)
    }

    private fun disconnectController() {
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        render(R16NowPlayingUiState.Hidden)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0) / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private companion object {
        const val PROGRESS_TICK_MS = 500L
    }
}

internal enum class R16NowPlayingPhase {
    Hidden,
    Loading,
    Buffering,
    Paused,
    Playing,
    Failed,
}

internal data class R16NowPlayingUiState(
    val phase: R16NowPlayingPhase,
    val title: CharSequence = "",
    val artist: CharSequence = "",
    val release: CharSequence = "",
    val queueEntryId: String? = null,
    val recordingId: String? = null,
    val generation: Long = 0L,
    val artworkLocation: String? = null,
    val durationMs: Long = 0,
    val progressMs: Long = 0,
    val failureMessage: CharSequence? = null,
) {
    val visible: Boolean
        get() = phase != R16NowPlayingPhase.Hidden

    internal companion object {
        val Hidden = R16NowPlayingUiState(R16NowPlayingPhase.Hidden)
    }
}

/** Pure current-item projection from the shared MediaSession snapshot. */
internal object R16NowPlayingUiStateMapper {
    fun map(
        metadata: MediaMetadataCompat?,
        playbackState: PlaybackStateCompat?,
        nowElapsedRealtimeMs: Long,
    ): R16NowPlayingUiState {
        val resolvedMetadata = metadata ?: return R16NowPlayingUiState.Hidden
        val title =
            resolvedMetadata
                .getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)
                ?.takeIf(CharSequence::isNotBlank)
                ?: resolvedMetadata
                    .getText(MediaMetadataCompat.METADATA_KEY_TITLE)
                    ?.takeIf(CharSequence::isNotBlank)
                ?: return R16NowPlayingUiState.Hidden
        val durationMs = max(0, resolvedMetadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
        val positionMs = playbackState?.position ?: 0
        val advancedPositionMs =
            if (playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
                positionMs +
                    max(0, nowElapsedRealtimeMs - playbackState.lastPositionUpdateTime) *
                        playbackState.playbackSpeed
            } else {
                positionMs
            }
        return R16NowPlayingUiState(
            phase =
                when (playbackState?.state) {
                    PlaybackStateCompat.STATE_ERROR -> R16NowPlayingPhase.Failed
                    PlaybackStateCompat.STATE_BUFFERING,
                    PlaybackStateCompat.STATE_CONNECTING -> R16NowPlayingPhase.Buffering
                    PlaybackStateCompat.STATE_PLAYING -> R16NowPlayingPhase.Playing
                    PlaybackStateCompat.STATE_NONE -> R16NowPlayingPhase.Loading
                    else -> R16NowPlayingPhase.Paused
                },
            title = title,
            artist =
                resolvedMetadata
                    .getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)
                    ?.takeIf(CharSequence::isNotBlank)
                    ?: resolvedMetadata.getText(MediaMetadataCompat.METADATA_KEY_ARTIST)
                    ?: "",
            release =
                resolvedMetadata
                    .getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION)
                    ?.takeIf(CharSequence::isNotBlank)
                    ?: resolvedMetadata.getText(MediaMetadataCompat.METADATA_KEY_ALBUM)
                    ?: "",
            queueEntryId = resolvedMetadata.getString(R16MediaSessionProjection.KEY_QUEUE_ENTRY_ID),
            recordingId = resolvedMetadata.getString(R16MediaSessionProjection.KEY_RECORDING_ID),
            generation = resolvedMetadata.getLong(R16MediaSessionProjection.KEY_GENERATION),
            artworkLocation =
                resolvedMetadata.getString(MediaMetadataCompat.METADATA_KEY_ART_URI)
                    ?: resolvedMetadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI),
            durationMs = durationMs,
            progressMs = min(durationMs, max(0, advancedPositionMs.toLong())),
            failureMessage = playbackState?.errorMessage,
        )
    }
}
