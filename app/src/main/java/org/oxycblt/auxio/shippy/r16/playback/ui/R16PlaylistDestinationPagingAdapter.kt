/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaylistDestinationPagingAdapter.kt is part of Auxio.
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
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.shippy.core.identity.PlaylistId
import app.shippy.data.browser.R16MediaBrowserPlaylistSummary
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemR16PlaylistDestinationBinding

/** Bounded canonical playlist targets; a row always selects exactly one PlaylistId. */
internal class R16PlaylistDestinationPagingAdapter(
    private val onSelect: (PlaylistId, Boolean) -> Unit
) :
    PagingDataAdapter<
        R16MediaBrowserPlaylistSummary,
        R16PlaylistDestinationPagingAdapter.ViewHolder,
    >(DIFF) {
    private var inFlight: Set<PlaylistId> = emptySet()
    var memberships: Map<PlaylistId, Int> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemR16PlaylistDestinationBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
            onSelect,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        val count = memberships[item.playlistId] ?: 0
        holder.bind(item, inFlight.isNotEmpty(), count)
    }

    fun setInFlight(playlistIds: Set<PlaylistId>) {
        if (inFlight == playlistIds) return
        inFlight = playlistIds
        notifyDataSetChanged()
    }

    internal class ViewHolder(
        private val binding: ItemR16PlaylistDestinationBinding,
        private val onSelect: (PlaylistId, Boolean) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: R16MediaBrowserPlaylistSummary, busy: Boolean, occurrenceCount: Int) {
            binding.r16PlaylistDestinationName.text = summary.name
            val baseMeta =
                binding.root.context.getString(
                    R.string.r16_playlist_track_count,
                    summary.entryCount,
                )
            binding.r16PlaylistDestinationMeta.text =
                if (occurrenceCount > 0) {
                    "$baseMeta • Added ($occurrenceCount)"
                } else {
                    baseMeta
                }
            binding.root.isEnabled = !busy
            binding.root.setOnClickListener { onSelect(summary.playlistId, occurrenceCount > 0) }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<R16MediaBrowserPlaylistSummary>() {
                override fun areItemsTheSame(
                    oldItem: R16MediaBrowserPlaylistSummary,
                    newItem: R16MediaBrowserPlaylistSummary,
                ): Boolean = oldItem.playlistId == newItem.playlistId

                override fun areContentsTheSame(
                    oldItem: R16MediaBrowserPlaylistSummary,
                    newItem: R16MediaBrowserPlaylistSummary,
                ): Boolean = oldItem == newItem
            }
    }
}
