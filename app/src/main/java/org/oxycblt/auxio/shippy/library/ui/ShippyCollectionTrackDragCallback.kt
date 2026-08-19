/*
 * Copyright (c) 2026 Auxio Project
 * ShippyCollectionTrackDragCallback.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.library.ui

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.shippy.library.ShippyCollectionTrackRow

/** Commits the adapter's pending order only after a completed long-press drag. */
internal class ShippyCollectionTrackDragCallback(
    private val adapter: ShippyCollectionTrackAdapter,
    private val onDragFinished: (List<ShippyCollectionTrackRow>) -> Unit,
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int =
        if (viewHolder is ShippyCollectionTrackAdapter.ViewHolder) {
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        } else {
            makeMovementFlags(0, 0)
        }

    override fun canDropOver(
        recyclerView: RecyclerView,
        current: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean =
        current is ShippyCollectionTrackAdapter.ViewHolder &&
            target is ShippyCollectionTrackAdapter.ViewHolder

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        if (
            viewHolder !is ShippyCollectionTrackAdapter.ViewHolder ||
                target !is ShippyCollectionTrackAdapter.ViewHolder
        ) {
            return false
        }
        return adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) adapter.beginDrag()
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        adapter.finishDrag()?.let(onDragFinished)
    }

    override fun isLongPressDragEnabled() = false
}
