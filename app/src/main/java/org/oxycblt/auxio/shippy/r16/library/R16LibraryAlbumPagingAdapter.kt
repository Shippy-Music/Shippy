/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryAlbumPagingAdapter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.shippy.data.db.view.AlbumLibrarySummaryRow
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemR16AlbumBinding

internal class R16LibraryAlbumPagingAdapter(private val onOpen: (AlbumLibrarySummaryRow) -> Unit) :
    PagingDataAdapter<AlbumLibrarySummaryRow, R16LibraryAlbumPagingAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemR16AlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onOpen,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item)
    }

    internal class ViewHolder(
        private val binding: ItemR16AlbumBinding,
        private val onOpen: (AlbumLibrarySummaryRow) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: AlbumLibrarySummaryRow) {
            binding.albumTitle.text = summary.canonicalTitle
            binding.albumArtist.text = summary.artistDisplay
            binding.albumTrackCount.text =
                binding.root.context.resources.getQuantityString(
                    R.plurals.fmt_local_song_count,
                    summary.recordingCount,
                    summary.recordingCount,
                )
            binding.albumArtwork.bindArtwork(summary.artworkLocation, summary.canonicalTitle)
            binding.root.setOnClickListener { onOpen(summary) }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<AlbumLibrarySummaryRow>() {
                override fun areItemsTheSame(
                    oldItem: AlbumLibrarySummaryRow,
                    newItem: AlbumLibrarySummaryRow,
                ): Boolean = oldItem.releaseId == newItem.releaseId

                override fun areContentsTheSame(
                    oldItem: AlbumLibrarySummaryRow,
                    newItem: AlbumLibrarySummaryRow,
                ): Boolean = oldItem == newItem
            }
    }
}
