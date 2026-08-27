/*
 * Copyright (c) 2026 Auxio Project
 * R16HomeHistoryAdapters.kt is part of Auxio.
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
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.shippy.data.home.R16HomeHistoryItem
import com.google.android.material.R as MR
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemR16HistoryBinding
import org.oxycblt.auxio.util.getAttrColorCompat

internal class R16HomeRecentAdapter(private val onPlay: (R16HomeHistoryItem) -> Unit) :
    ListAdapter<R16HomeHistoryItem, R16HomeHistoryViewHolder>(R16_HOME_HISTORY_DIFF) {
    var playbackAvailable = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): R16HomeHistoryViewHolder =
        R16HomeHistoryViewHolder.inflate(parent)

    override fun onBindViewHolder(holder: R16HomeHistoryViewHolder, position: Int) =
        holder.bind(getItem(position), playbackAvailable, onPlay)
}

internal class R16HomeHistoryPagingAdapter(private val onPlay: (R16HomeHistoryItem) -> Unit) :
    PagingDataAdapter<R16HomeHistoryItem, R16HomeHistoryViewHolder>(R16_HOME_HISTORY_DIFF) {
    var playbackAvailable = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): R16HomeHistoryViewHolder =
        R16HomeHistoryViewHolder.inflate(parent)

    override fun onBindViewHolder(holder: R16HomeHistoryViewHolder, position: Int) {
        getItem(position)?.let { item -> holder.bind(item, playbackAvailable, onPlay) }
    }
}

internal class R16HomeHistoryViewHolder(private val binding: ItemR16HistoryBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(
        item: R16HomeHistoryItem,
        playbackAvailable: Boolean,
        onPlay: (R16HomeHistoryItem) -> Unit,
    ) {
        binding.r16HistoryCover.bindArtwork(item.artworkLocation, item.title)
        binding.r16HistoryTitle.text = item.title
        binding.r16HistorySubtitle.text =
            listOf(item.artist, item.releaseTitle)
                .filterNotNull()
                .filter(String::isNotBlank)
                .joinToString(" - ")
        val playable = playbackAvailable && item.recordingId != null
        binding.r16HistoryRow.isEnabled = playable
        binding.r16HistoryRow.setOnClickListener { if (playable) onPlay(item) }

        val status = item.scrobbleStatus
        if (status != null) {
            val context = binding.root.context
            val (text, colorAttr) =
                when (status) {
                    "SENT" ->
                        Pair(
                            context.getString(R.string.r16_scrobble_status_sent),
                            androidx.appcompat.R.attr.colorPrimary,
                        )
                    "PENDING" ->
                        Pair(
                            context.getString(R.string.r16_scrobble_status_pending),
                            MR.attr.colorTertiary,
                        )
                    "RETRYABLE_FAILURE" ->
                        Pair(
                            context.getString(R.string.r16_scrobble_status_retryable_failure),
                            MR.attr.colorOnErrorContainer,
                        )
                    "NOT_AUTHORIZED" ->
                        Pair(
                            context.getString(R.string.r16_scrobble_status_not_authorized),
                            android.R.attr.textColorSecondary,
                        )
                    else -> Pair(status, android.R.attr.textColorSecondary)
                }
            binding.r16HistoryScrobbleBadge.text = text
            binding.r16HistoryScrobbleBadge.setTextColor(context.getAttrColorCompat(colorAttr))
            binding.r16HistoryScrobbleBadge.isVisible = true
        } else {
            binding.r16HistoryScrobbleBadge.isVisible = false
        }
    }

    companion object {
        fun inflate(parent: ViewGroup): R16HomeHistoryViewHolder =
            R16HomeHistoryViewHolder(
                ItemR16HistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
    }
}

private val R16_HOME_HISTORY_DIFF =
    object : DiffUtil.ItemCallback<R16HomeHistoryItem>() {
        override fun areItemsTheSame(oldItem: R16HomeHistoryItem, newItem: R16HomeHistoryItem) =
            oldItem.listeningSessionId == newItem.listeningSessionId

        override fun areContentsTheSame(oldItem: R16HomeHistoryItem, newItem: R16HomeHistoryItem) =
            oldItem == newItem
    }
