/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackMutation.kt is part of Auxio.
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
package org.oxycblt.auxio.playback.state

import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem

/** An explicit user/system request made at the playback-manager boundary. */
sealed interface PlaybackMutation {
    data class Start(val command: PlaybackCommand) : PlaybackMutation

    data object Next : PlaybackMutation

    data object Previous : PlaybackMutation

    data class GoTo(val itemId: QueueItemId) : PlaybackMutation

    data class PlayNext(val items: List<ResolvedQueueItem>) : PlaybackMutation

    data class AddToQueue(val items: List<ResolvedQueueItem>) : PlaybackMutation

    data class MoveQueueItem(
        val itemId: QueueItemId,
        /** Item that should immediately follow the moved item, when present. */
        val beforeId: QueueItemId?,
        /** Item that should immediately precede the moved item, when present. */
        val afterId: QueueItemId?,
    ) : PlaybackMutation

    data class RemoveQueueItem(val itemId: QueueItemId) : PlaybackMutation

    data class SetShuffled(val enabled: Boolean) : PlaybackMutation

    data class SetPlaying(val playing: Boolean) : PlaybackMutation

    data class SetRepeatMode(val repeatMode: RepeatMode) : PlaybackMutation

    data class SeekTo(val positionMs: Long) : PlaybackMutation
}

fun interface PlaybackMutationInterceptor {
    /** Returns true when the mutation has been consumed and must not touch the local player. */
    fun intercept(mutation: PlaybackMutation): Boolean
}
