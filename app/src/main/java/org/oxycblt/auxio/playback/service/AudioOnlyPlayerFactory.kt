/*
 * Copyright (c) 2026 Auxio Project
 * AudioOnlyPlayerFactory.kt is part of Auxio.
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
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import org.oxycblt.auxio.playback.replaygain.ReplayGainAudioProcessor

data class AudioOnlyPlayer(val player: ExoPlayer, val replayGainProcessor: ReplayGainAudioProcessor)

internal val PLAYBACK_AUDIO_ATTRIBUTES: AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

@OptIn(UnstableApi::class)
class AudioOnlyPlayerFactory
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val mediaSourceFactory: MediaSource.Factory,
    private val replayGainProcessorProvider: Provider<ReplayGainAudioProcessor>,
) {
    fun create(handleAudioFocus: Boolean): AudioOnlyPlayer {
        val processor = replayGainProcessorProvider.get()
        val audioRenderer = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<BaseRenderer>(
                FfmpegAudioRenderer(handler, audioListener, processor),
                MediaCodecAudioRenderer(
                    context,
                    MediaCodecSelector.DEFAULT,
                    handler,
                    audioListener,
                    DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(processor)).build(),
                ),
            )
        }
        val player =
            ExoPlayer.Builder(context, audioRenderer)
                .setMediaSourceFactory(mediaSourceFactory)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setAudioAttributes(PLAYBACK_AUDIO_ATTRIBUTES, handleAudioFocus)
                .build()
        return AudioOnlyPlayer(player, processor)
    }

    /** Inactive R16 construction seam; the caller still owns when creation occurs. */
    fun createR16(): AudioOnlyPlayer = create(handleAudioFocus = true)
}
