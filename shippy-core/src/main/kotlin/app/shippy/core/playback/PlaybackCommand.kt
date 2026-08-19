/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCommand.kt is part of Auxio.
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

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.queue.QueueAnchor
import app.shippy.core.queue.QueueEntry

sealed interface PlaybackCommand {
    data class PlayContext(
        val entries: List<QueueEntry>,
        val selectedEntryId: QueueEntryId,
        val shuffleSeed: Long? = null,
    ) : PlaybackCommand

    data object Play : PlaybackCommand

    data object Pause : PlaybackCommand

    data object Next : PlaybackCommand

    data object Previous : PlaybackCommand

    data class GoTo(val queueEntryId: QueueEntryId) : PlaybackCommand

    data class SeekTo(val positionMs: Long) : PlaybackCommand {
        init {
            require(positionMs >= 0) { "Playback seek position cannot be negative" }
        }
    }

    data class SetShuffle(val enabled: Boolean, val seed: Long) : PlaybackCommand

    data class SetRepeat(val mode: RepeatMode) : PlaybackCommand

    data class AddNext(val entries: List<QueueEntry>) : PlaybackCommand

    data class AddToEnd(val entries: List<QueueEntry>) : PlaybackCommand

    data class Move(val entryId: QueueEntryId, val anchor: QueueAnchor) : PlaybackCommand

    data class Remove(val entryIds: Set<QueueEntryId>) : PlaybackCommand

    data object Clear : PlaybackCommand
}

sealed interface PlaybackCommandResult {
    data class Accepted(val generation: Long, val queueRevision: Long) : PlaybackCommandResult

    data class Rejected(val reason: PlaybackCommandRejection) : PlaybackCommandResult
}

enum class PlaybackCommandRejection {
    EMPTY_QUEUE,
    ENTRY_NOT_FOUND,
    INVALID_QUEUE_MUTATION,
    SERVICE_NOT_ATTACHED,
    COORDINATOR_RELEASED,
}

interface PlaybackCommandRouter {
    suspend fun dispatch(command: PlaybackCommand): PlaybackCommandResult
}
