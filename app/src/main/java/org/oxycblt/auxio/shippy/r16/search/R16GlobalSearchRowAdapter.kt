/*
 * Copyright (c) 2026 Auxio Project
 * R16GlobalSearchRowAdapter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemSongBinding

internal data class R16GlobalSearchRow(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val artwork: String?,
    val recordingId: String? = null,
)

internal class R16GlobalSearchRowAdapter(private val onPlay: (R16GlobalSearchRow) -> Unit) :
    ListAdapter<R16GlobalSearchRow, R16GlobalSearchRowAdapter.Holder>(DIFF) {
    internal var playbackAvailable = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false), onPlay)

    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(getItem(position), playbackAvailable)

    internal class Holder(
        private val binding: ItemSongBinding,
        private val onPlay: (R16GlobalSearchRow) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: R16GlobalSearchRow, playbackAvailable: Boolean) {
            binding.songAlbumCover.bindArtwork(row.artwork, row.title)
            binding.songName.text = row.title
            binding.songInfo.text = row.subtitle
            binding.songMenu.isVisible = false
            binding.root.apply {
                isEnabled = playbackAvailable
                isClickable = playbackAvailable
                setOnClickListener(
                    if (playbackAvailable) View.OnClickListener { onPlay(row) } else null
                )
                contentDescription =
                    listOf(row.title, row.subtitle).filter(String::isNotBlank).joinToString(", ")
            }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<R16GlobalSearchRow>() {
                override fun areItemsTheSame(
                    oldItem: R16GlobalSearchRow,
                    newItem: R16GlobalSearchRow,
                ) = oldItem.stableId == newItem.stableId

                override fun areContentsTheSame(
                    oldItem: R16GlobalSearchRow,
                    newItem: R16GlobalSearchRow,
                ) = oldItem == newItem
            }
    }
}
