/*
 * Copyright (c) 2026 Auxio Project
 * R16LibrarySearchPlaylistPagingAdapter.kt is part of Auxio.
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
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.shippy.data.db.view.PlaylistSummaryRowView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemR16PlaylistBinding

/**
 * Search-only playlist rows navigate to the existing playlist route; they do not expose pinning.
 */
internal class R16LibrarySearchPlaylistPagingAdapter(
    private val onOpen: (PlaylistSummaryRowView) -> Unit
) :
    PagingDataAdapter<PlaylistSummaryRowView, R16LibrarySearchPlaylistPagingAdapter.ViewHolder>(
        DIFF
    ) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemR16PlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onOpen,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let(holder::bind)
    }

    internal class ViewHolder(
        private val binding: ItemR16PlaylistBinding,
        private val onOpen: (PlaylistSummaryRowView) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: PlaylistSummaryRowView) {
            binding.r16PlaylistName.text = summary.name
            binding.r16PlaylistMeta.text =
                binding.root.context.getString(
                    R.string.r16_playlist_track_count,
                    summary.entryCount,
                )
            binding.r16PlaylistPinned.isGone = !summary.pinned
            binding.r16PlaylistPinToggle.isGone = true
            binding.root.apply {
                setOnClickListener(View.OnClickListener { onOpen(summary) })
                contentDescription =
                    binding.root.context.getString(
                        R.string.r16_playlist_content_description,
                        summary.name,
                        summary.entryCount,
                    )
            }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<PlaylistSummaryRowView>() {
                override fun areItemsTheSame(
                    oldItem: PlaylistSummaryRowView,
                    newItem: PlaylistSummaryRowView,
                ) = oldItem.playlistId == newItem.playlistId

                override fun areContentsTheSame(
                    oldItem: PlaylistSummaryRowView,
                    newItem: PlaylistSummaryRowView,
                ) = oldItem == newItem
            }
    }
}
