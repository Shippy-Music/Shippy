/*
 * Copyright (c) 2026 Auxio Project
 * R16MiniPlayerFragment.kt is part of Auxio.
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
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16MiniPlayerBinding
import org.oxycblt.auxio.pushR16Destination

/** ACTIVE-only playback feedback bound to the sole MediaSession authority. */
class R16MiniPlayerFragment : Fragment(R.layout.fragment_r16_mini_player) {
    private var binding: FragmentR16MiniPlayerBinding? = null
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16MiniPlayerBinding.bind(view).also { bound ->
                bound.r16MiniPlayer.setOnClickListener { openNowPlaying() }
                bound.r16MiniPlayPause.setOnClickListener(::togglePlayback)
                bound.r16MiniNext.setOnClickListener {
                    mediaController?.transportControls?.skipToNext()
                }
            }
        render(R16MiniPlayerUiState.Hidden)
    }

    override fun onStart() {
        super.onStart()
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

    override fun onStop() {
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun togglePlayback(view: View) {
        when (mediaController?.playbackState?.state) {
            PlaybackStateCompat.STATE_PLAYING,
            PlaybackStateCompat.STATE_BUFFERING,
            PlaybackStateCompat.STATE_CONNECTING -> mediaController?.transportControls?.pause()
            else -> mediaController?.transportControls?.play()
        }
    }

    private fun render(state: R16MiniPlayerUiState) {
        binding?.apply {
            r16MiniPlayer.isVisible = state.visible
            if (!state.visible) return
            r16MiniTitle.text = state.title
            r16MiniArtist.text = state.artist
            r16MiniState.setText(
                when (state.phase) {
                    R16MiniPlayerPhase.Buffering -> R.string.r16_mini_player_buffering
                    R16MiniPlayerPhase.Paused -> R.string.r16_mini_player_paused
                    R16MiniPlayerPhase.Playing -> R.string.r16_mini_player_playing
                    R16MiniPlayerPhase.Hidden -> error("Hidden mini-player cannot render")
                }
            )
            val playing =
                state.phase == R16MiniPlayerPhase.Playing ||
                    state.phase == R16MiniPlayerPhase.Buffering
            r16MiniPlayPause.setText(
                if (playing) R.string.r16_mini_player_pause else R.string.r16_mini_player_play
            )
        }
    }

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                mediaController =
                    MediaControllerCompat(requireContext(), browser.sessionToken).also {
                        it.registerCallback(controllerCallback)
                        render(R16MiniPlayerUiStateMapper.map(it.metadata, it.playbackState))
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
            render(R16MiniPlayerUiStateMapper.map(it.metadata, it.playbackState))
        }
    }

    private fun disconnectController() {
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        render(R16MiniPlayerUiState.Hidden)
    }

    private fun openNowPlaying() {
        if (
            mediaController == null ||
                parentFragmentManager.findFragmentById(R.id.r16_active_content) is
                    R16NowPlayingFragment
        ) {
            return
        }
        pushR16Destination(R16NowPlayingFragment(), "r16-now-playing")
    }
}

internal enum class R16MiniPlayerPhase {
    Hidden,
    Buffering,
    Paused,
    Playing,
}

internal data class R16MiniPlayerUiState(
    val phase: R16MiniPlayerPhase,
    val title: CharSequence = "",
    val artist: CharSequence = "",
) {
    val visible: Boolean
        get() = phase != R16MiniPlayerPhase.Hidden

    internal companion object {
        val Hidden = R16MiniPlayerUiState(R16MiniPlayerPhase.Hidden)
    }
}

/** Maps only the MediaSession snapshot consumed by all external playback surfaces. */
internal object R16MiniPlayerUiStateMapper {
    fun map(
        metadata: MediaMetadataCompat?,
        playbackState: PlaybackStateCompat?,
    ): R16MiniPlayerUiState {
        val title =
            metadata
                ?.getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)
                ?.takeIf(CharSequence::isNotBlank)
                ?: metadata
                    ?.getText(MediaMetadataCompat.METADATA_KEY_TITLE)
                    ?.takeIf(CharSequence::isNotBlank)
                ?: return R16MiniPlayerUiState.Hidden
        val artist =
            metadata
                ?.getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)
                ?.takeIf(CharSequence::isNotBlank)
                ?: metadata
                    ?.getText(MediaMetadataCompat.METADATA_KEY_ARTIST)
                    ?.takeIf(CharSequence::isNotBlank)
                ?: ""
        return R16MiniPlayerUiState(
            phase =
                when (playbackState?.state) {
                    PlaybackStateCompat.STATE_BUFFERING,
                    PlaybackStateCompat.STATE_CONNECTING -> R16MiniPlayerPhase.Buffering
                    PlaybackStateCompat.STATE_PLAYING -> R16MiniPlayerPhase.Playing
                    else -> R16MiniPlayerPhase.Paused
                },
            title = title,
            artist = artist,
        )
    }
}
