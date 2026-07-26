/*
 * Copyright (c) 2026 Shippy contributors
 * LyricsDialog.kt is part of Shippy.
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

package org.oxycblt.auxio.playback

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogLyricsBinding
import org.oxycblt.auxio.shippy.lyrics.PlainLyrics
import org.oxycblt.auxio.shippy.lyrics.SyncedLyrics
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.collectImmediately

/** A compact, lifecycle-aware full lyrics surface for constrained playback layouts. */
@AndroidEntryPoint
class LyricsDialog : ViewBindingMaterialDialogFragment<DialogLyricsBinding>() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private var renderedState: PlaybackLyricsState = PlaybackLyricsState.None
    private var renderedLineIndex = Int.MIN_VALUE

    override fun onCreateBinding(inflater: LayoutInflater) = DialogLyricsBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder.setTitle(R.string.lbl_lyrics).setPositiveButton(R.string.lbl_ok, null)
    }

    override fun onBindingCreated(binding: DialogLyricsBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        collectImmediately(playbackModel.lyrics, playbackModel.positionDs, ::updateLyrics)
    }

    override fun onDestroyBinding(binding: DialogLyricsBinding) {
        super.onDestroyBinding(binding)
        renderedState = PlaybackLyricsState.None
        renderedLineIndex = Int.MIN_VALUE
    }

    private fun updateLyrics(state: PlaybackLyricsState, positionDs: Long) {
        val binding = requireBinding()
        val stateChanged = state != renderedState
        if (stateChanged) {
            renderedState = state
            renderedLineIndex = Int.MIN_VALUE
            binding.lyricsCurrent.text = ""
            binding.lyricsCurrent.contentDescription = null
            binding.lyricsRetry.visibility = android.view.View.GONE
            binding.lyricsRetry.setOnClickListener(null)
        }

        when (state) {
            PlaybackLyricsState.None,
            is PlaybackLyricsState.Unavailable -> {
                if (stateChanged) {
                    binding.lyricsBody.setText(R.string.lng_lyrics_unavailable)
                    binding.lyricsBody.contentDescription = binding.lyricsBody.text
                }
            }
            is PlaybackLyricsState.Loading -> {
                if (stateChanged) {
                    binding.lyricsBody.setText(R.string.lng_lyrics_loading)
                    binding.lyricsBody.contentDescription = binding.lyricsBody.text
                }
            }
            is PlaybackLyricsState.Ready -> {
                when (val lyrics = state.lyrics) {
                    is SyncedLyrics -> {
                        if (stateChanged) {
                            binding.lyricsBody.text = lyrics.plainText
                            binding.lyricsBody.contentDescription = lyrics.plainText
                        }
                        val activeIndex = activeLyricIndex(lyrics, positionDs * 100)
                        if (activeIndex != renderedLineIndex) {
                            renderedLineIndex = activeIndex
                            val activeLine = lyrics.lines.getOrNull(activeIndex)?.text.orEmpty()
                            binding.lyricsCurrent.text = activeLine
                            binding.lyricsCurrent.contentDescription =
                                activeLine.takeIf(String::isNotEmpty)?.let {
                                    getString(R.string.desc_current_lyric, it)
                                }
                        }
                    }
                    is PlainLyrics -> {
                        if (stateChanged) {
                            binding.lyricsBody.text =
                                lyrics.plainText.takeIf(String::isNotBlank)
                                    ?: getString(R.string.lng_lyrics_instrumental)
                            binding.lyricsBody.contentDescription = binding.lyricsBody.text
                        }
                    }
                }
            }
            is PlaybackLyricsState.Error -> {
                if (stateChanged) {
                    binding.lyricsBody.setText(R.string.lng_lyrics_error)
                    binding.lyricsBody.contentDescription = binding.lyricsBody.text
                    if (state.retryable) {
                        binding.lyricsRetry.visibility = android.view.View.VISIBLE
                        binding.lyricsRetry.setOnClickListener { playbackModel.retryLyrics() }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "lyrics_dialog"

        fun show(fragmentManager: FragmentManager) {
            if (!fragmentManager.isStateSaved && fragmentManager.findFragmentByTag(TAG) == null) {
                LyricsDialog().show(fragmentManager, TAG)
            }
        }
    }
}
