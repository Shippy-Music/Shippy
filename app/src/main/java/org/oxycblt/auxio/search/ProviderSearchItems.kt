/*
 * Copyright (c) 2026 Auxio Project
 * ProviderSearchItems.kt is part of Auxio.
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
package org.oxycblt.auxio.search

import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemHeaderBinding
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntityType
import org.oxycblt.auxio.util.inflater

data class SearchTextHeader(val text: String)

data class RecentSearchItem(val recent: RecentSearch)

data class ProviderTrackItem(val providerName: String, val track: Track)

data class ProviderEntityItem(val providerName: String, val entity: ProviderEntity)

data class ProviderSearchFailureItem(val providerName: String, val retryable: Boolean)

class SearchTextHeaderViewHolder private constructor(private val binding: ItemHeaderBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(item: SearchTextHeader) {
        binding.title.text = item.text
    }

    companion object {
        const val VIEW_TYPE = 10_101

        fun from(parent: View) =
            SearchTextHeaderViewHolder(ItemHeaderBinding.inflate(parent.context.inflater))
    }
}

class RecentSearchViewHolder private constructor(private val binding: ItemSongBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(item: RecentSearchItem, onClick: (RecentSearchItem) -> Unit) {
        val recent = item.recent
        binding.songName.text = recent.title
        binding.songInfo.text = recent.subtitle ?: recent.query
        binding.songAlbumCover.bindArtwork(recent.artwork, recent.title)
        binding.songMenu.isVisible = false
        binding.root.apply {
            contentDescription = "${recent.title}, ${recent.query}"
            setOnClickListener { onClick(item) }
        }
    }

    companion object {
        const val VIEW_TYPE = 10_105

        fun from(parent: View) =
            RecentSearchViewHolder(ItemSongBinding.inflate(parent.context.inflater))
    }
}

class ProviderTrackViewHolder private constructor(private val binding: ItemSongBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(
        item: ProviderTrackItem,
        onClick: (ProviderTrackItem) -> Unit,
        onMenu: (ProviderTrackItem) -> Unit,
    ) {
        val track = item.track
        binding.songName.text = track.title
        binding.songInfo.text =
            buildList {
                    if (track.artists.isNotEmpty()) add(track.artists.joinToString(", "))
                    track.album?.takeIf(String::isNotBlank)?.let(::add)
                }
                .joinToString(" • ")
                .ifBlank { item.providerName }
        binding.songAlbumCover.bindArtwork(
            track.artwork,
            track.album?.let { "${track.title}, $it" } ?: track.title,
        )
        binding.root.contentDescription = buildString {
            append(track.title)
            if (track.artists.isNotEmpty()) {
                append(", ")
                append(track.artists.joinToString(", "))
            }
            append(", ")
            append(item.providerName)
        }
        binding.root.setOnClickListener { onClick(item) }
        binding.songMenu.apply {
            isVisible = true
            setOnClickListener { onMenu(item) }
        }
    }

    companion object {
        const val VIEW_TYPE = 10_102

        fun from(parent: View) =
            ProviderTrackViewHolder(ItemSongBinding.inflate(parent.context.inflater))
    }
}

class ProviderEntityViewHolder private constructor(private val binding: ItemSongBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(item: ProviderEntityItem, onClick: (ProviderEntityItem) -> Unit) {
        val entity = item.entity
        val type =
            binding.root.context.getString(
                when (entity.type) {
                    ProviderEntityType.ALBUM -> R.string.lbl_album
                    ProviderEntityType.ARTIST -> R.string.lbl_artist
                    ProviderEntityType.PLAYLIST -> R.string.lbl_playlist
                }
            )
        binding.songName.text = entity.title
        binding.songInfo.text =
            buildList {
                    add(type)
                    entity.subtitle?.takeIf(String::isNotBlank)?.let(::add)
                }
                .joinToString(" • ")
        binding.songAlbumCover.bindArtwork(entity.artwork, entity.title)
        binding.root.contentDescription = buildString {
            append(entity.title)
            append(", ")
            append(type)
            entity.subtitle?.takeIf(String::isNotBlank)?.let {
                append(", ")
                append(it)
            }
            append(", ")
            append(item.providerName)
        }
        binding.root.setOnClickListener { onClick(item) }
        binding.songMenu.isVisible = false
    }

    companion object {
        const val VIEW_TYPE = 10_104

        fun from(parent: View) =
            ProviderEntityViewHolder(ItemSongBinding.inflate(parent.context.inflater))
    }
}

class ProviderSearchFailureViewHolder private constructor(private val binding: ItemHeaderBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(item: ProviderSearchFailureItem) {
        binding.title.text =
            binding.root.context.getString(
                if (item.retryable) {
                    R.string.fmt_provider_temporarily_unavailable
                } else {
                    R.string.fmt_provider_unavailable
                },
                item.providerName,
            )
    }

    companion object {
        const val VIEW_TYPE = 10_103

        fun from(parent: View) =
            ProviderSearchFailureViewHolder(ItemHeaderBinding.inflate(parent.context.inflater))
    }
}
