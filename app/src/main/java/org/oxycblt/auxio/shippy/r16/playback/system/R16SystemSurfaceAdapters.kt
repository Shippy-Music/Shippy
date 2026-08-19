/*
 * Copyright (c) 2026 Auxio Project
 * R16SystemSurfaceAdapters.kt is part of Auxio.
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

import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.RepeatMode
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.service.PlaybackActions

/** Source-neutral content consumed by notification, widget, and lock-screen renderers. */
data class R16SystemNowPlaying(
    val queueEntryId: QueueEntryId,
    val recordingId: RecordingId,
    val title: String,
    val artist: String,
    val releaseTitle: String?,
    val artworkLocation: String?,
    val durationMs: Long?,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val repeatMode: RepeatMode,
    val isShuffled: Boolean,
)

object R16SystemNowPlayingProjection {
    fun project(state: R16SystemPlaybackState): R16SystemNowPlaying? {
        val item = state.displayItem ?: return null
        val presentation = item.presentation
        return R16SystemNowPlaying(
            queueEntryId = item.queueEntryId,
            recordingId = item.recordingId,
            title = presentation?.title.orEmpty(),
            artist = presentation?.artist.orEmpty(),
            releaseTitle = presentation?.releaseTitle,
            artworkLocation = presentation?.artworkLocation,
            durationMs = presentation?.durationMs,
            isPlaying = state.isPlaying,
            playWhenReady = state.playback.playWhenReady,
            repeatMode = state.playback.repeatMode,
            isShuffled = state.isShuffled,
        )
    }
}

enum class R16QuickSettingsState {
    UNAVAILABLE,
    ACTIVE,
    INACTIVE,
}

object R16QuickSettingsProjection {
    fun project(state: R16SystemPlaybackState): R16QuickSettingsState =
        when {
            !state.hasQueue -> R16QuickSettingsState.UNAVAILABLE
            state.isPlaying -> R16QuickSettingsState.ACTIVE
            else -> R16QuickSettingsState.INACTIVE
        }
}

/** Thin renderer/click adapter for the existing Quick Settings service lifecycle. */
class R16QuickSettingsAdapter(
    private val states: StateFlow<R16SystemPlaybackState>,
    private val commands: R16SystemPlaybackCommands,
) {
    fun render(context: Context, tile: Tile) {
        val state = R16QuickSettingsProjection.project(states.value)
        tile.state =
            when (state) {
                R16QuickSettingsState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
                R16QuickSettingsState.ACTIVE -> Tile.STATE_ACTIVE
                R16QuickSettingsState.INACTIVE -> Tile.STATE_INACTIVE
            }
        tile.icon =
            Icon.createWithResource(
                context,
                if (state == R16QuickSettingsState.ACTIVE) {
                    R.drawable.ic_pause_24
                } else {
                    R.drawable.ic_play_24
                },
            )
        tile.setLabel(context.getString(R.string.lbl_playback))
        tile.updateTile()
    }

    suspend fun click() {
        commands.playPause()
    }
}

/**
 * Shared notification/widget/headset action translator. Android receivers retain only their own
 * lifecycle bookkeeping; every playback mutation still passes through the canonical router.
 */
class R16SystemActionRouter(
    private val commands: R16SystemPlaybackCommands,
    private val onExitRequested: () -> Unit,
) {
    suspend fun handle(action: String?): Boolean =
        when (action) {
            PlaybackActions.ACTION_PLAY_PAUSE -> {
                commands.playPause()
                true
            }
            PlaybackActions.ACTION_INC_REPEAT_MODE -> {
                commands.cycleRepeat()
                true
            }
            PlaybackActions.ACTION_INVERT_SHUFFLE -> {
                commands.toggleShuffle()
                true
            }
            PlaybackActions.ACTION_SKIP_PREV -> {
                commands.previous()
                true
            }
            PlaybackActions.ACTION_SKIP_NEXT -> {
                commands.next()
                true
            }
            PlaybackActions.ACTION_EXIT -> {
                onExitRequested()
                true
            }
            else -> false
        }

    suspend fun onAudioBecomingNoisy() {
        commands.pause()
    }

    suspend fun onHeadsetConnected(autoplay: Boolean, initialPlugEventHandled: Boolean) {
        if (autoplay && initialPlugEventHandled) commands.play()
    }

    suspend fun onHeadsetDisconnected() {
        commands.pause()
    }
}
