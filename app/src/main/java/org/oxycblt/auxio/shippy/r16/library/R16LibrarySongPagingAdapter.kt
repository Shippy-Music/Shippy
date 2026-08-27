/*
 * Copyright (c) 2026 Auxio Project
 * R16LibrarySongPagingAdapter.kt is part of Auxio.
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
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.shippy.data.db.view.LibrarySongRowView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemSongBinding

/**
 * Canonical Library row renderer. It intentionally has no legacy Song/Track action binding; click
 * and menu actions join only after the recording-ID playback command boundary is available.
 */
internal class R16LibrarySongPagingAdapter(
    private val onPlay: (LibrarySongRowView) -> Unit,
    private val onIdentify: ((LibrarySongRowView) -> Unit)? = null,
    private val onEditMetadata: ((LibrarySongRowView) -> Unit)? = null,
) :
    PagingDataAdapter<LibrarySongRowView, R16LibrarySongPagingAdapter.ViewHolder>(
        R16_LIBRARY_SONG_DIFF
    ) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onPlay,
            onIdentify,
            onEditMetadata,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let { holder.bind(it, playbackAvailable) }
    }

    internal var playbackAvailable: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    internal class ViewHolder(
        private val binding: ItemSongBinding,
        private val onPlay: (LibrarySongRowView) -> Unit,
        private val onIdentify: ((LibrarySongRowView) -> Unit)?,
        private val onEditMetadata: ((LibrarySongRowView) -> Unit)?,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: LibrarySongRowView, playbackAvailable: Boolean = true) {
            binding.songAlbumCover.bindArtwork(row.artworkLocation, row.title)
            binding.songName.text = row.title
            binding.songInfo.text =
                listOfNotNull(row.artistDisplay.takeIf(String::isNotBlank), row.releaseTitle)
                    .joinToString(" • ")
            val hasActions = onIdentify != null || onEditMetadata != null
            binding.songMenu.apply {
                isVisible = hasActions
                isEnabled = hasActions
                contentDescription = context.getString(R.string.lbl_more)
                setOnClickListener {
                    PopupMenu(context, this).apply {
                        if (onIdentify != null) {
                            menu.add(0, IDENTIFY, 0, R.string.r16_song_menu_identify)
                        }
                        if (onEditMetadata != null) {
                            menu.add(0, EDIT_METADATA, 1, R.string.r16_song_menu_edit_metadata)
                        }
                        setOnMenuItemClickListener { item ->
                            when (item.itemId) {
                                IDENTIFY -> onIdentify?.invoke(row)
                                EDIT_METADATA -> onEditMetadata?.invoke(row)
                                else -> return@setOnMenuItemClickListener false
                            }
                            true
                        }
                        show()
                    }
                }
            }
            binding.root.apply {
                isSelected = false
                isActivated = false
                isEnabled = playbackAvailable
                isClickable = playbackAvailable
                setOnClickListener(
                    if (playbackAvailable) View.OnClickListener { onPlay(row) } else null
                )
                contentDescription =
                    listOf(row.title, row.artistDisplay, row.releaseTitle)
                        .filterNotNull()
                        .filter(String::isNotBlank)
                        .joinToString(", ")
            }
        }

        private companion object {
            const val IDENTIFY = 1
            const val EDIT_METADATA = 2
        }
    }
}

internal val R16_LIBRARY_SONG_DIFF =
    object : DiffUtil.ItemCallback<LibrarySongRowView>() {
        override fun areItemsTheSame(oldItem: LibrarySongRowView, newItem: LibrarySongRowView) =
            oldItem.recordingId == newItem.recordingId

        override fun areContentsTheSame(oldItem: LibrarySongRowView, newItem: LibrarySongRowView) =
            oldItem == newItem
    }
