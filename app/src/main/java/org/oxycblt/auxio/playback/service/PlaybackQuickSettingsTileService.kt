/*
 * Copyright (c) 2026 Shippy contributors
 * PlaybackQuickSettingsTileService.kt is part of Shippy.
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

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/** A Quick Settings play/pause control backed by the canonical playback state. */
@AndroidEntryPoint
class PlaybackQuickSettingsTileService : TileService(), PlaybackStateManager.Listener {
    @Inject lateinit var playbackManager: PlaybackStateManager

    @Volatile private var isListening = false

    override fun onStartListening() {
        super.onStartListening()
        if (isListening) {
            updateTile()
            return
        }
        isListening = true
        playbackManager.addListener(this)
        updateTile()
    }

    override fun onStopListening() {
        isListening = false
        playbackManager.removeListener(this)
        super.onStopListening()
    }

    override fun onDestroy() {
        if (isListening) {
            isListening = false
            playbackManager.removeListener(this)
        }
        super.onDestroy()
    }

    override fun onClick() {
        if (playbackManager.currentQueueItem != null) {
            sendMediaButton(KeyEvent.ACTION_DOWN)
            sendMediaButton(KeyEvent.ACTION_UP)
        }
    }

    override fun onIndexMoved(index: Int) = updateWhileListening()

    override fun onQueueChanged(
        queue: List<Song>,
        index: Int,
        change: QueueChange,
    ) = updateWhileListening()

    override fun onQueueReordered(
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) = updateWhileListening()

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) = updateWhileListening()

    override fun onCanonicalQueueChanged(
        queue: List<ResolvedQueueItem>,
        index: Int,
        change: QueueChange,
    ) = updateWhileListening()

    override fun onCanonicalQueueReordered(
        queue: List<ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) = updateWhileListening()

    override fun onCanonicalNewPlayback(
        parent: MusicParent?,
        queue: List<ResolvedQueueItem>,
        index: Int,
        isShuffled: Boolean,
    ) = updateWhileListening()

    override fun onProgressionChanged(progression: Progression) = updateWhileListening()

    override fun onSessionEnded() = updateWhileListening()

    private fun updateWhileListening() {
        if (isListening) updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = quickSettingsTileState(
            hasCurrentItem = playbackManager.currentQueueItem != null,
            isPlaying = playbackManager.progression.isPlaying,
        )
        tile.state = state
        tile.icon = Icon.createWithResource(
            this,
            if (state == Tile.STATE_ACTIVE) R.drawable.ic_pause_24 else R.drawable.ic_play_24,
        )
        tile.label = getString(R.string.lbl_playback)
        tile.updateTile()
    }

    private fun sendMediaButton(action: Int) {
        sendBroadcast(
            Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(this, MediaButtonReceiver::class.java))
                .putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
                ),
        )
    }
}

private fun quickSettingsTileState(hasCurrentItem: Boolean, isPlaying: Boolean) =
    when {
        !hasCurrentItem -> Tile.STATE_UNAVAILABLE
        isPlaying -> Tile.STATE_ACTIVE
        else -> Tile.STATE_INACTIVE
    }
