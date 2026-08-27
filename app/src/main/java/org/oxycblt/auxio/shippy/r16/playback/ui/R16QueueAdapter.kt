/*
 * Copyright (c) 2026 Auxio Project
 * R16QueueAdapter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemSongBinding

/** Presentation-only row for the page returned by the R16 queue endpoint. */
internal data class R16QueueRow(
    val queueEntryId: String,
    val title: String,
    val subtitle: String,
    val artworkLocation: String?,
    val current: Boolean,
)

/**
 * Renders one bounded queue page. The occurrence ID remains on the row so clicks can dispatch the
 * exact queue entry; position is never used as playback identity.
 */
internal class R16QueueAdapter(
    private val onGoTo: (String) -> Unit,
    private val onMoveUp: ((R16QueueRow, Int) -> Unit)? = null,
    private val onMoveDown: ((R16QueueRow, Int) -> Unit)? = null,
    private val onMoveTop: ((R16QueueRow) -> Unit)? = null,
    private val onMoveBottom: ((R16QueueRow) -> Unit)? = null,
    private val onRemove: ((R16QueueRow) -> Unit)? = null,
    private val onToggleSelect: ((R16QueueRow) -> Unit)? = null,
    private val onLongClick: ((R16QueueRow) -> Boolean)? = null,
) : ListAdapter<R16QueueRow, R16QueueAdapter.ViewHolder>(DIFF) {

    var selectedEntryIds: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onGoTo,
            onMoveUp,
            onMoveDown,
            onMoveTop,
            onMoveBottom,
            onRemove,
            onToggleSelect,
            onLongClick,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(
            row = getItem(position),
            position = position,
            isSelected = getItem(position).queueEntryId in selectedEntryIds,
            inSelectionMode = selectedEntryIds.isNotEmpty(),
        )
    }

    internal class ViewHolder(
        private val binding: ItemSongBinding,
        private val onGoTo: (String) -> Unit,
        private val onMoveUp: ((R16QueueRow, Int) -> Unit)?,
        private val onMoveDown: ((R16QueueRow, Int) -> Unit)?,
        private val onMoveTop: ((R16QueueRow) -> Unit)?,
        private val onMoveBottom: ((R16QueueRow) -> Unit)?,
        private val onRemove: ((R16QueueRow) -> Unit)?,
        private val onToggleSelect: ((R16QueueRow) -> Unit)?,
        private val onLongClick: ((R16QueueRow) -> Boolean)?,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: R16QueueRow, position: Int, isSelected: Boolean, inSelectionMode: Boolean) {
            binding.songAlbumCover.bindArtwork(row.artworkLocation, row.title)
            binding.songName.text = row.title
            binding.songInfo.text = row.subtitle
            binding.root.isActivated = if (inSelectionMode) isSelected else row.current
            binding.root.isSelected = if (inSelectionMode) isSelected else row.current

            binding.songMenu.apply {
                isVisible = !inSelectionMode
                isEnabled = !inSelectionMode
                contentDescription =
                    context.getString(R.string.r16_playlist_reorder_entry, row.title)
                setOnClickListener {
                    PopupMenu(context, this).apply {
                        menu.add(0, MOVE_UP, 0, R.string.r16_playlist_move_up)
                        menu.add(0, MOVE_DOWN, 1, R.string.r16_playlist_move_down)
                        menu.add(0, MOVE_TOP, 2, R.string.r16_queue_move_top)
                        menu.add(0, MOVE_BOTTOM, 3, R.string.r16_queue_move_bottom)
                        menu.add(0, REMOVE, 4, R.string.lbl_remove)
                        setOnMenuItemClickListener { item ->
                            when (item.itemId) {
                                MOVE_UP -> onMoveUp?.invoke(row, position)
                                MOVE_DOWN -> onMoveDown?.invoke(row, position)
                                MOVE_TOP -> onMoveTop?.invoke(row)
                                MOVE_BOTTOM -> onMoveBottom?.invoke(row)
                                REMOVE -> onRemove?.invoke(row)
                                else -> return@setOnMenuItemClickListener false
                            }
                            true
                        }
                        show()
                    }
                }
            }

            binding.root.apply {
                isEnabled = true
                isClickable = true
                contentDescription =
                    row.subtitle.takeIf(String::isNotBlank)?.let { "${row.title}, $it" }
                        ?: row.title
                if (inSelectionMode) {
                    setOnClickListener { onToggleSelect?.invoke(row) }
                    setOnLongClickListener {
                        onToggleSelect?.invoke(row)
                        true
                    }
                } else {
                    setOnClickListener { onGoTo(row.queueEntryId) }
                    setOnLongClickListener(
                        if (onLongClick != null) {
                            View.OnLongClickListener { onLongClick.invoke(row) }
                        } else null
                    )
                }
            }
        }
    }

    private companion object {
        const val MOVE_UP = 1
        const val MOVE_DOWN = 2
        const val MOVE_TOP = 3
        const val MOVE_BOTTOM = 4
        const val REMOVE = 5

        val DIFF =
            object : DiffUtil.ItemCallback<R16QueueRow>() {
                override fun areItemsTheSame(oldItem: R16QueueRow, newItem: R16QueueRow) =
                    oldItem.queueEntryId == newItem.queueEntryId

                override fun areContentsTheSame(oldItem: R16QueueRow, newItem: R16QueueRow) =
                    oldItem == newItem
            }
    }
}
