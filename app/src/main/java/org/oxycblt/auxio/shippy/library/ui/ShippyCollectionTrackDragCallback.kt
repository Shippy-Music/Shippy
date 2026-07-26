/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyCollectionTrackDragCallback.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.library.ui

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/** Commits the adapter's pending order only after a completed long-press drag. */
internal class ShippyCollectionTrackDragCallback(
    private val adapter: ShippyCollectionTrackAdapter,
    private val onDragFinished: (List<ShippyCollectionTrackRow>) -> Unit,
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) adapter.beginDrag()
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        adapter.finishDrag()?.let(onDragFinished)
    }

    override fun isLongPressDragEnabled() = true
}
