/*
 * Copyright (c) 2026 Auxio Project
 * ShippyCollectionTrackAdapter.kt is part of Auxio.
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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.shippy.library.ShippyCollectionTrackRow

/** Reuses Auxio's normal song-row component for canonical, non-local collection items. */
internal class ShippyCollectionTrackAdapter(
    private val onClick: (ShippyCollectionTrackRow) -> Unit,
    private val onMenu: (ShippyCollectionTrackRow) -> Unit,
    private val onLongClick: (ShippyCollectionTrackRow) -> Unit,
) : ListAdapter<ShippyCollectionTrackRow, ShippyCollectionTrackAdapter.ViewHolder>(DIFF) {
    private var dragRows: MutableList<ShippyCollectionTrackRow>? = null
    private var dragStartRows: List<ShippyCollectionTrackRow>? = null
    private var selectedIds = emptySet<org.oxycblt.auxio.shippy.domain.TrackId>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(
            getItem(position),
            selectedIds.contains(getItem(position).track.id),
            onClick,
            onMenu,
            onLongClick,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) onBindViewHolder(holder, position)
        else holder.updateSelection(selectedIds.contains(getItem(position).track.id))
    }

    fun setSelected(ids: Set<org.oxycblt.auxio.shippy.domain.TrackId>) {
        val old = selectedIds
        selectedIds = ids.toSet()
        currentList.forEachIndexed { index, row ->
            if (old.contains(row.track.id) xor selectedIds.contains(row.track.id)) {
                notifyItemChanged(index, PAYLOAD_SELECTION)
            }
        }
    }

    fun beginDrag() {
        if (dragRows == null) {
            dragStartRows = currentList
            dragRows = currentList.toMutableList()
        }
    }

    fun move(fromPosition: Int, toPosition: Int): Boolean {
        val rows = dragRows ?: return false
        if (fromPosition !in rows.indices || toPosition !in rows.indices) return false
        rows.add(toPosition, rows.removeAt(fromPosition))
        submitList(rows.toList())
        return true
    }

    fun finishDrag(): List<ShippyCollectionTrackRow>? {
        val rows = dragRows?.toList()
        val changed = rows != null && rows != dragStartRows
        dragRows = null
        dragStartRows = null
        return rows?.takeIf { changed }
    }

    internal class ViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            row: ShippyCollectionTrackRow,
            selected: Boolean,
            onClick: (ShippyCollectionTrackRow) -> Unit,
            onMenu: (ShippyCollectionTrackRow) -> Unit,
            onLongClick: (ShippyCollectionTrackRow) -> Unit,
        ) {
            binding.songName.text = row.track.title
            binding.songInfo.text = row.track.artists.joinToString()
            binding.songAlbumCover.bindArtwork(
                row.track.artwork,
                row.track.album?.let { "${row.track.title}, $it" } ?: row.track.title,
            )
            binding.songMenu.apply {
                isEnabled = true
                setIconResource(R.drawable.ic_more_24)
                contentDescription = context.getString(R.string.lbl_more)
                setOnClickListener { onMenu(row) }
            }
            binding.root.setOnClickListener { onClick(row) }
            binding.root.setOnLongClickListener {
                onLongClick(row)
                true
            }
            updateSelection(selected)
            binding.root.contentDescription = "${row.track.title}, ${binding.songInfo.text}"
        }

        fun updateSelection(selected: Boolean) {
            binding.root.isActivated = selected
        }
    }

    private companion object {
        val PAYLOAD_SELECTION = Any()

        val DIFF =
            object : DiffUtil.ItemCallback<ShippyCollectionTrackRow>() {
                override fun areItemsTheSame(
                    old: ShippyCollectionTrackRow,
                    new: ShippyCollectionTrackRow,
                ) = old.track.id == new.track.id

                override fun areContentsTheSame(
                    old: ShippyCollectionTrackRow,
                    new: ShippyCollectionTrackRow,
                ) = old == new
            }
    }
}
