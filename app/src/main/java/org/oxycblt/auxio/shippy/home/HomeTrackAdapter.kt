/*
 * Copyright (c) 2026 Auxio Project
 * HomeTrackAdapter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemHomeTrackBinding
import org.oxycblt.musikr.Song

internal data class HomeTrackRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val artwork: String?,
    val localSong: Song? = null,
)

internal class HomeTrackAdapter(private val onClick: (HomeTrackRow) -> Unit) :
    ListAdapter<HomeTrackRow, HomeTrackAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemHomeTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemHomeTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HomeTrackRow) {
            row.localSong?.let(binding.homeTrackCover::bind)
                ?: binding.homeTrackCover.bindArtwork(row.artwork, row.title)
            binding.homeTrackTitle.text = row.title
            binding.homeTrackSubtitle.text = row.subtitle
            binding.root.setOnClickListener { onClick(row) }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<HomeTrackRow>() {
                override fun areItemsTheSame(oldItem: HomeTrackRow, newItem: HomeTrackRow) =
                    oldItem.key == newItem.key

                override fun areContentsTheSame(oldItem: HomeTrackRow, newItem: HomeTrackRow) =
                    oldItem == newItem
            }
    }
}
