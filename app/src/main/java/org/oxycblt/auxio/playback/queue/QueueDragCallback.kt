/*
 * Copyright (c) 2021 Auxio Project
 * QueueDragCallback.kt is part of Auxio.
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

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.list.recycler.MaterialDragCallback

/**
 * A highly customized [ItemTouchHelper.Callback] that enables some extra eye candy in the queue UI,
 * such as an animation when lifting items.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class QueueDragCallback(
    private val queueModel: QueueViewModel,
    private val queueAdapter: QueueAdapter,
) : MaterialDragCallback() {
    private var dragStart = RecyclerView.NO_POSITION
    private var dragEnd = RecyclerView.NO_POSITION

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        if (dragStart == RecyclerView.NO_POSITION) dragStart = from
        if (!queueAdapter.previewMove(from, to)) return false
        dragEnd = to
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        queueModel.removeQueueDataItem(viewHolder.bindingAdapterPosition)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        val from = dragStart
        val to = dragEnd
        dragStart = RecyclerView.NO_POSITION
        dragEnd = RecyclerView.NO_POSITION
        if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION && from != to) {
            queueModel.moveQueueDataItems(from, to)
        }
    }
}
