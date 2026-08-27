/*
 * Copyright (c) 2026 Auxio Project
 * R16HomeLastFmAdapter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemHomeTrackBinding
import org.oxycblt.auxio.shippy.lastfm.LastFmOverviewTrack

internal class R16HomeLastFmAdapter(private val onTrackClick: (LastFmOverviewTrack) -> Unit) :
    ListAdapter<LastFmOverviewTrack, R16HomeLastFmViewHolder>(R16_LASTFM_DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): R16HomeLastFmViewHolder =
        R16HomeLastFmViewHolder.inflate(parent)

    override fun onBindViewHolder(holder: R16HomeLastFmViewHolder, position: Int) =
        holder.bind(getItem(position), onTrackClick)
}

internal class R16HomeLastFmViewHolder(private val binding: ItemHomeTrackBinding) :
    RecyclerView.ViewHolder(binding.root) {

    fun bind(track: LastFmOverviewTrack, onTrackClick: (LastFmOverviewTrack) -> Unit) {
        binding.homeTrackCover.bindArtwork(track.artworkUrl, track.album ?: track.title)
        binding.homeTrackTitle.text = track.title
        binding.homeTrackSubtitle.text = track.artist
        binding.root.setOnClickListener { onTrackClick(track) }
    }

    companion object {
        fun inflate(parent: ViewGroup): R16HomeLastFmViewHolder =
            R16HomeLastFmViewHolder(
                ItemHomeTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
    }
}

private val R16_LASTFM_DIFF =
    object : DiffUtil.ItemCallback<LastFmOverviewTrack>() {
        override fun areItemsTheSame(
            oldItem: LastFmOverviewTrack,
            newItem: LastFmOverviewTrack,
        ): Boolean = oldItem.title == newItem.title && oldItem.artist == newItem.artist

        override fun areContentsTheSame(
            oldItem: LastFmOverviewTrack,
            newItem: LastFmOverviewTrack,
        ): Boolean = oldItem == newItem
    }
