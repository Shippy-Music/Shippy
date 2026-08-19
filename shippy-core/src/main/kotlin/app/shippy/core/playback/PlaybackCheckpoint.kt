/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCheckpoint.kt is part of Auxio.
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
package app.shippy.core.playback

import app.shippy.core.queue.QueueState

/** Source-neutral process-death intent. Expiring playback handles are never included. */
data class PlaybackCheckpoint(
    val version: Int = CURRENT_VERSION,
    val queue: QueueState,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val repeatMode: RepeatMode,
) {
    init {
        require(version == CURRENT_VERSION) { "Unsupported playback checkpoint version" }
        require(positionMs >= 0) { "Checkpoint position cannot be negative" }
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun capture(snapshot: PlaybackSnapshot): PlaybackCheckpoint =
            PlaybackCheckpoint(
                queue = snapshot.queue,
                positionMs = snapshot.position.positionMs,
                playWhenReady = snapshot.playWhenReady,
                repeatMode = snapshot.repeatMode,
            )
    }
}
