/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryArtistPagingAdapter.kt is part of Auxio.
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
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.shippy.data.db.view.ArtistLibrarySummaryRow
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemR16ArtistBinding

/** Opens one canonical artist; identity is ArtistId rather than display name. */
internal class R16LibraryArtistPagingAdapter(
    private val onOpen: (ArtistLibrarySummaryRow) -> Unit
) : PagingDataAdapter<ArtistLibrarySummaryRow, R16LibraryArtistPagingAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemR16ArtistBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onOpen,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let(holder::bind)
    }

    internal class ViewHolder(
        private val binding: ItemR16ArtistBinding,
        private val onOpen: (ArtistLibrarySummaryRow) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: ArtistLibrarySummaryRow) {
            binding.r16ArtistName.text = summary.canonicalName
            binding.r16ArtistMeta.text =
                binding.root.context.getString(
                    R.string.r16_artist_recording_count,
                    summary.recordingCount,
                )
            binding.root.apply {
                isEnabled = true
                isClickable = true
                setOnClickListener(View.OnClickListener { onOpen(summary) })
                contentDescription =
                    binding.root.context.getString(
                        R.string.r16_artist_content_description,
                        summary.canonicalName,
                        summary.recordingCount,
                    )
            }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<ArtistLibrarySummaryRow>() {
                override fun areItemsTheSame(
                    oldItem: ArtistLibrarySummaryRow,
                    newItem: ArtistLibrarySummaryRow,
                ): Boolean = oldItem.artistId == newItem.artistId

                override fun areContentsTheSame(
                    oldItem: ArtistLibrarySummaryRow,
                    newItem: ArtistLibrarySummaryRow,
                ): Boolean = oldItem == newItem
            }
    }
}
