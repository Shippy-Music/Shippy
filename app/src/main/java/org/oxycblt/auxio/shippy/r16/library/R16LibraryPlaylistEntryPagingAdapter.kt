/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryPlaylistEntryPagingAdapter.kt is part of Auxio.
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
import app.shippy.data.db.view.PlaylistEntryRowView
import app.shippy.data.library.R16PlaylistEntryMoveDirection
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemSongBinding

/** Renders playlist occurrences; duplicate recordings remain distinct by PlaylistEntryId. */
internal class R16LibraryPlaylistEntryPagingAdapter(
    private val onPlay: (PlaylistEntryRowView) -> Unit,
    private val onRemove: (PlaylistEntryRowView) -> Unit,
    private val onMove: (PlaylistEntryRowView, R16PlaylistEntryMoveDirection) -> Unit,
    private val onToggleSelect: ((PlaylistEntryRowView) -> Unit)? = null,
    private val onLongClick: ((PlaylistEntryRowView) -> Boolean)? = null,
) : PagingDataAdapter<PlaylistEntryRowView, R16LibraryPlaylistEntryPagingAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onPlay,
            onRemove,
            onMove,
            onToggleSelect,
            onLongClick,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let {
            holder.bind(
                entry = it,
                playbackAvailable = playbackAvailable,
                removalInFlight = it.playlistEntryId in entryRemovalsInFlight,
                editOrderMode = editOrderMode,
                moveInFlight = entryMoveInFlight != null,
                isSelected = it.playlistEntryId in selectedEntryIds,
                inSelectionMode = selectedEntryIds.isNotEmpty(),
            )
        }
    }

    internal var playbackAvailable: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    internal var entryRemovalsInFlight: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    internal var selectedEntryIds: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    internal var editOrderMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    internal var entryMoveInFlight: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    internal class ViewHolder(
        private val binding: ItemSongBinding,
        private val onPlay: (PlaylistEntryRowView) -> Unit,
        private val onRemove: (PlaylistEntryRowView) -> Unit,
        private val onMove: (PlaylistEntryRowView, R16PlaylistEntryMoveDirection) -> Unit,
        private val onToggleSelect: ((PlaylistEntryRowView) -> Unit)?,
        private val onLongClick: ((PlaylistEntryRowView) -> Boolean)?,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            entry: PlaylistEntryRowView,
            playbackAvailable: Boolean,
            removalInFlight: Boolean,
            editOrderMode: Boolean,
            moveInFlight: Boolean,
            isSelected: Boolean,
            inSelectionMode: Boolean,
        ) {
            binding.songAlbumCover.bindArtwork(entry.artworkLocation, entry.title)
            binding.songName.text = entry.title
            binding.songInfo.text =
                listOfNotNull(entry.artistDisplay.takeIf(String::isNotBlank), entry.releaseTitle)
                    .joinToString(" • ")
            binding.root.isActivated = isSelected
            binding.songMenu.apply {
                isVisible = !inSelectionMode
                val actionInFlight = if (editOrderMode) moveInFlight else removalInFlight
                isEnabled = !actionInFlight
                contentDescription =
                    if (editOrderMode) {
                        context.getString(R.string.r16_playlist_reorder_entry, entry.title)
                    } else {
                        context.getString(R.string.lbl_remove_from_playlist)
                    }
                setOnClickListener(
                    if (actionInFlight) {
                        null
                    } else {
                        View.OnClickListener {
                            PopupMenu(context, this).apply {
                                if (editOrderMode) {
                                    menu.add(0, MOVE_TOWARD_START, 0, R.string.r16_playlist_move_up)
                                    menu.add(0, MOVE_TOWARD_END, 1, R.string.r16_playlist_move_down)
                                } else {
                                    menu.add(0, REMOVE, 0, R.string.lbl_remove_from_playlist)
                                }
                                setOnMenuItemClickListener { item ->
                                    when (item.itemId) {
                                        MOVE_TOWARD_START ->
                                            onMove(
                                                entry,
                                                R16PlaylistEntryMoveDirection.TOWARD_START,
                                            )
                                        MOVE_TOWARD_END ->
                                            onMove(entry, R16PlaylistEntryMoveDirection.TOWARD_END)
                                        REMOVE -> onRemove(entry)
                                        else -> return@setOnMenuItemClickListener false
                                    }
                                    true
                                }
                                show()
                            }
                        }
                    }
                )
            }
            binding.root.apply {
                if (inSelectionMode) {
                    isEnabled = true
                    isClickable = true
                    setOnClickListener { onToggleSelect?.invoke(entry) }
                    setOnLongClickListener {
                        onToggleSelect?.invoke(entry)
                        true
                    }
                } else {
                    val canPlay = playbackAvailable && !editOrderMode
                    isEnabled = canPlay || onLongClick != null
                    isClickable = canPlay || onLongClick != null
                    setOnClickListener(
                        if (canPlay) View.OnClickListener { onPlay(entry) } else null
                    )
                    setOnLongClickListener(
                        if (onLongClick != null && !editOrderMode) {
                            View.OnLongClickListener { onLongClick.invoke(entry) }
                        } else {
                            null
                        }
                    )
                }
                contentDescription =
                    listOfNotNull(entry.title, entry.artistDisplay, entry.releaseTitle)
                        .filter(String::isNotBlank)
                        .joinToString(", ")
            }
        }
    }

    private companion object {
        const val MOVE_TOWARD_START = 1
        const val MOVE_TOWARD_END = 2
        const val REMOVE = 3

        val DIFF =
            object : DiffUtil.ItemCallback<PlaylistEntryRowView>() {
                override fun areItemsTheSame(
                    oldItem: PlaylistEntryRowView,
                    newItem: PlaylistEntryRowView,
                ): Boolean = oldItem.playlistEntryId == newItem.playlistEntryId

                override fun areContentsTheSame(
                    oldItem: PlaylistEntryRowView,
                    newItem: PlaylistEntryRowView,
                ): Boolean = oldItem == newItem
            }
    }
}
