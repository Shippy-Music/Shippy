/*
 * Copyright (c) 2026 Auxio Project
 * QueueMovePlan.kt is part of Auxio.
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

internal sealed interface QueueMovePlan {
    data class MediaItem(val from: Int, val to: Int) : QueueMovePlan

    data class ShuffleOrder(val indices: List<Int>) : QueueMovePlan
}

internal fun planQueueMove(
    queueIndices: List<Int>,
    from: Int,
    to: Int,
    shuffled: Boolean,
): QueueMovePlan? {
    if (from !in queueIndices.indices || to !in queueIndices.indices || from == to) return null
    if (!shuffled) {
        return QueueMovePlan.MediaItem(queueIndices[from], queueIndices[to])
    }

    val reordered = queueIndices.toMutableList()
    reordered.add(to, reordered.removeAt(from))
    return QueueMovePlan.ShuffleOrder(reordered)
}
