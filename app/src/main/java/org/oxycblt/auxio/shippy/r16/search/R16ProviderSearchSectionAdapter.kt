/*
 * Copyright (c) 2026 Auxio Project
 * R16ProviderSearchSectionAdapter.kt is part of Auxio.
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
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.shippy.sources.observation.SourceTrackObservation
import app.shippy.sources.provider.SourceDiscoveryFailure
import com.google.android.material.button.MaterialButton
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemSongBinding

/** Flat, bounded rendering of ordered provider sections; no provider is hard-coded in the UI. */
internal class R16ProviderSearchSectionAdapter(
    private val onPlay: (SourceTrackObservation) -> Unit,
    private val onRetry: (R16SearchProvider) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    internal var playbackAvailable = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private var rows: List<Row> = emptyList()

    fun submitSections(sections: List<R16ProviderSearchSection>) {
        rows = buildList {
            sections.forEach { section ->
                add(
                    Row.Header(
                        section.provider,
                        section.content is R16ProviderSearchContent.Loading,
                    )
                )
                when (val content = section.content) {
                    is R16ProviderSearchContent.Results ->
                        content.observations.forEach { add(Row.Track(section.provider, it)) }
                    is R16ProviderSearchContent.Failure ->
                        add(Row.Failure(section.provider, content.failure))
                    R16ProviderSearchContent.Idle,
                    R16ProviderSearchContent.Loading -> Unit
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) =
        when (rows[position]) {
            is Row.Header -> HEADER
            is Row.Track -> TRACK
            is Row.Failure -> FAILURE
        }

    override fun getItemId(position: Int) = rows[position].identity.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder =
        when (type) {
            HEADER -> HeaderHolder(TextView(parent.context).apply { setPadding(0, 20, 0, 8) })
            TRACK ->
                TrackHolder(
                    ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                )
            else -> FailureHolder(MaterialButton(parent.context))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Track -> (holder as TrackHolder).bind(row, playbackAvailable, onPlay)
            is Row.Failure -> (holder as FailureHolder).bind(row, onRetry)
        }
    }

    private sealed interface Row {
        val identity: String

        data class Header(val provider: R16SearchProvider, val loading: Boolean) : Row {
            override val identity = "header:${provider.id.value}"
        }

        data class Track(val provider: R16SearchProvider, val observation: SourceTrackObservation) :
            Row {
            override val identity =
                "track:${provider.id.value}:${observation.sourceKey.providerId.value}:" +
                    "${observation.sourceKey.itemType}:${observation.sourceKey.sourceItemId}"
        }

        data class Failure(val provider: R16SearchProvider, val failure: SourceDiscoveryFailure) :
            Row {
            override val identity = "failure:${provider.id.value}"
        }
    }

    private class HeaderHolder(private val view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(row: Row.Header) {
            view.text =
                if (row.loading) "${row.provider.displayName}…" else row.provider.displayName
        }
    }

    private class TrackHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            row: Row.Track,
            playbackAvailable: Boolean,
            onPlay: (SourceTrackObservation) -> Unit,
        ) {
            val observation = row.observation
            val title =
                observation.title?.takeIf(String::isNotBlank)
                    ?: binding.root.context.getString(R.string.cdc_unknown)
            binding.songAlbumCover.bindArtwork(observation.artwork.firstOrNull()?.value, title)
            binding.songName.text = title
            binding.songInfo.text =
                observation.artistNames.joinToString().ifBlank { row.provider.displayName }
            binding.songMenu.visibility = View.GONE
            binding.root.apply {
                isEnabled = playbackAvailable
                isClickable = playbackAvailable
                setOnClickListener(
                    if (playbackAvailable) View.OnClickListener { onPlay(observation) } else null
                )
                contentDescription =
                    listOf(title, observation.artistNames.joinToString(), row.provider.displayName)
                        .filterNotNull()
                        .filter(String::isNotBlank)
                        .joinToString(", ")
            }
        }
    }

    private class FailureHolder(private val button: MaterialButton) :
        RecyclerView.ViewHolder(button) {
        fun bind(row: Row.Failure, onRetry: (R16SearchProvider) -> Unit) {
            button.text = button.context.getString(R.string.lbl_retry)
            button.contentDescription =
                listOf(row.provider.displayName, row.failure.message)
                    .filterNotNull()
                    .joinToString(", ")
            button.isEnabled = row.failure.retryable
            button.setOnClickListener(
                if (row.failure.retryable) View.OnClickListener { onRetry(row.provider) } else null
            )
        }
    }

    init {
        setHasStableIds(true)
    }

    private companion object {
        const val HEADER = 0
        const val TRACK = 1
        const val FAILURE = 2
    }
}
