/*
 * Copyright (c) 2026 Auxio Project
 * EngineWindowPlanner.kt is part of Auxio.
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
import app.shippy.core.queue.QueueState

data class EngineWindowPlan(
    val previousEntryIds: List<QueueEntryId>,
    val currentEntryId: QueueEntryId,
    val nextEntryIds: List<QueueEntryId>,
) {
    init {
        require(orderedEntryIds.size == orderedEntryIds.toSet().size) {
            "Engine window plan cannot repeat a queue occurrence"
        }
    }

    val orderedEntryIds: List<QueueEntryId>
        get() = previousEntryIds + currentEntryId + nextEntryIds
}

class EngineWindowPlanner(private val previousCount: Int = 1, private val nextCount: Int = 2) {
    init {
        require(previousCount >= 0 && nextCount >= 0) { "Engine window bounds cannot be negative" }
    }

    fun plan(
        queue: QueueState,
        currentEntryId: QueueEntryId,
        repeatMode: RepeatMode,
    ): EngineWindowPlan {
        val order = queue.traversalOrder
        val currentIndex = order.indexOf(currentEntryId)
        require(currentIndex >= 0) { "Engine window current entry must exist in traversal" }
        val next = collect(order, currentIndex, 1, nextCount, repeatMode)
        val previous =
            collect(order, currentIndex, -1, previousCount, repeatMode)
                .filterNot { it in next }
                .asReversed()
        return EngineWindowPlan(previous, currentEntryId, next)
    }

    private fun collect(
        order: List<QueueEntryId>,
        currentIndex: Int,
        direction: Int,
        count: Int,
        repeatMode: RepeatMode,
    ): List<QueueEntryId> {
        if (count == 0 || order.size <= 1) return emptyList()
        val result = mutableListOf<QueueEntryId>()
        for (distance in 1..count) {
            val rawIndex = currentIndex + direction * distance
            val index =
                if (repeatMode == RepeatMode.ALL) {
                    Math.floorMod(rawIndex, order.size)
                } else {
                    rawIndex.takeIf { it in order.indices } ?: break
                }
            val candidate = order[index]
            if (candidate == order[currentIndex] || candidate in result) break
            result += candidate
        }
        return result
    }
}
