/*
 * Copyright (c) 2026 Auxio Project
 * QueueUpdateReconciler.kt is part of Auxio.
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
package org.oxycblt.auxio.playback.queue

import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.shippy.domain.QueueItemId

/**
 * Reconciles playback queue instructions with the adapter's actual order.
 *
 * Dragging mutates the adapter immediately for responsive feedback. The playback state later
 * acknowledges that same move, so replaying its [UpdateInstructions.Move] would move the row twice.
 * Unexpected or stale move instructions fall back to a diff instead.
 */
internal fun reconcileQueueUpdate(
    current: List<QueueItemId>,
    next: List<QueueItemId>,
    requested: UpdateInstructions?,
): UpdateInstructions? {
    if (requested !is UpdateInstructions.Move) return requested
    if (current == next) return null
    if (requested.from !in current.indices || requested.to !in current.indices) {
        return UpdateInstructions.Diff
    }
    val moved = current.toMutableList()
    moved.add(requested.to, moved.removeAt(requested.from))
    return if (moved == next) requested else UpdateInstructions.Diff
}

internal data class QueueMoveAnchors(val beforeId: QueueItemId?, val afterId: QueueItemId?)

/** Finds the final neighbours of a dragged item by identity, not a transient adapter index. */
internal fun queueMoveAnchors(order: List<QueueItemId>, movedId: QueueItemId): QueueMoveAnchors? {
    val finalIndex = order.indexOf(movedId)
    if (finalIndex < 0) return null
    return QueueMoveAnchors(
        beforeId = order.getOrNull(finalIndex + 1),
        afterId = order.getOrNull(finalIndex - 1),
    )
}
