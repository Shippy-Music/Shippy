/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryPlaylistPagingAdapter.kt is part of Auxio.
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
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.shippy.core.identity.PlaylistId
import app.shippy.data.browser.R16MediaBrowserPlaylistSummary
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemR16PlaylistBinding

/** Opens a canonical playlist; identity is the stable PlaylistId, not its display name. */
internal class R16LibraryPlaylistPagingAdapter(
    private val onOpen: (R16MediaBrowserPlaylistSummary) -> Unit,
    private val onTogglePinned: (R16MediaBrowserPlaylistSummary) -> Unit,
) :
    PagingDataAdapter<R16MediaBrowserPlaylistSummary, R16LibraryPlaylistPagingAdapter.ViewHolder>(
        DIFF
    ) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemR16PlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onOpen,
            onTogglePinned,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let { summary ->
            holder.bind(summary, summary.playlistId in pinTogglesInFlight)
        }
    }

    private var pinTogglesInFlight: Set<PlaylistId> = emptySet()

    fun setPinTogglesInFlight(playlistIds: Set<PlaylistId>) {
        if (pinTogglesInFlight == playlistIds) return
        pinTogglesInFlight = playlistIds
        notifyDataSetChanged()
    }

    internal class ViewHolder(
        private val binding: ItemR16PlaylistBinding,
        private val onOpen: (R16MediaBrowserPlaylistSummary) -> Unit,
        private val onTogglePinned: (R16MediaBrowserPlaylistSummary) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: R16MediaBrowserPlaylistSummary, pinToggleInFlight: Boolean) {
            binding.r16PlaylistName.text = summary.name
            binding.r16PlaylistMeta.text =
                binding.root.context.getString(
                    R.string.r16_playlist_track_count,
                    summary.entryCount,
                )
            binding.r16PlaylistPinned.isVisible = summary.pinned
            binding.r16PlaylistPinToggle.apply {
                isEnabled = !pinToggleInFlight
                contentDescription =
                    binding.root.context.getString(
                        if (summary.pinned) R.string.r16_playlist_unpin_content_description
                        else R.string.r16_playlist_pin_content_description,
                        summary.name,
                    )
                setOnClickListener { onTogglePinned(summary) }
            }
            binding.root.apply {
                isEnabled = true
                isClickable = true
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
