/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyPlaylistDragCallback.kt is part of Shippy.
 */

package org.oxycblt.auxio.home.list

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.shippy.domain.LibraryCollection

/** Restricts long-press reordering to Shippy playlist rows in the ConcatAdapter. */
internal class ShippyPlaylistDragCallback(
    private val adapter: ShippyPlaylistProjectionAdapter,
    private val onDragFinished: (List<LibraryCollection.Playlist>) -> Unit,
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int =
        if (viewHolder.bindingAdapter === adapter) {
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        } else {
            0
        }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        if (viewHolder.bindingAdapter !== adapter || target.bindingAdapter !== adapter) return false
        return adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder?.bindingAdapter === adapter) {
            adapter.beginDrag()
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (viewHolder.bindingAdapter === adapter) adapter.finishDrag()?.let(onDragFinished)
    }

    override fun isLongPressDragEnabled() = true
}
