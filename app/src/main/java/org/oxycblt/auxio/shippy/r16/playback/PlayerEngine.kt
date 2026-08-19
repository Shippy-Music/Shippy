/*
 * Copyright (c) 2026 Auxio Project
 * PlayerEngine.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.playback.CommittedEnginePhase
import app.shippy.core.playback.PlaybackError
import app.shippy.core.playback.PlaybackRequestTag
import app.shippy.core.playback.PlaybackSourceHandle
import app.shippy.core.playback.PositionAnchor
import app.shippy.core.playback.RepeatMode
import kotlinx.coroutines.flow.Flow

data class PreparedEngineItem(val queueEntryId: QueueEntryId, val source: PlaybackSourceHandle)

data class PlayerTransaction(
    val tag: PlaybackRequestTag,
    val expectedCurrentEntryId: QueueEntryId,
    val window: List<PreparedEngineItem>,
    val startPositionMs: Long,
    val playWhenReady: Boolean,
) {
    init {
        require(startPositionMs >= 0) { "Player transaction position cannot be negative" }
        require(window.isNotEmpty()) { "Player transaction window cannot be empty" }
        require(window.map(PreparedEngineItem::queueEntryId).toSet().size == window.size) {
            "Player transaction window cannot repeat a QueueEntryId"
        }
        require(expectedCurrentEntryId in window.map(PreparedEngineItem::queueEntryId)) {
            "Expected current entry must exist in the player transaction window"
        }
        require(tag.queueEntryId == expectedCurrentEntryId) {
            "Player transaction tag must identify its expected current entry"
        }
    }
}

sealed interface PlayerObservation {
    data class CurrentItemCommitted(
        val tag: PlaybackRequestTag,
        val automaticTransition: Boolean = false,
    ) : PlayerObservation

    data class PhaseChanged(
        val generation: Long,
        val queueEntryId: QueueEntryId,
        val phase: CommittedEnginePhase,
    ) : PlayerObservation

    data class PositionChanged(
        val generation: Long,
        val queueEntryId: QueueEntryId,
        val position: PositionAnchor,
        val discontinuity: Boolean = false,
    ) : PlayerObservation

    data class Failed(
        val generation: Long,
        val queueEntryId: QueueEntryId,
        val error: PlaybackError,
    ) : PlayerObservation
}

interface PlayerEngine {
    val observations: Flow<PlayerObservation>

    suspend fun apply(transaction: PlayerTransaction)

    suspend fun setPlayWhenReady(value: Boolean)

    suspend fun seek(queueEntryId: QueueEntryId, positionMs: Long)

    suspend fun setRepeat(mode: RepeatMode)

    suspend fun release()
}
