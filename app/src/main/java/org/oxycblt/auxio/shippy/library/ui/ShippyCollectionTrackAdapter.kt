/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyCollectionTrackAdapter.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.library.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.shippy.library.CollectionRowDownloadPresentation
import org.oxycblt.auxio.shippy.library.ShippyCollectionTrackRow

/** Reuses Auxio's normal song-row component for canonical, non-local collection items. */
internal class ShippyCollectionTrackAdapter(
    private val onClick: (ShippyCollectionTrackRow) -> Unit,
    private val onDownloadAction: (ShippyCollectionTrackRow) -> Unit,
) : ListAdapter<ShippyCollectionTrackRow, ShippyCollectionTrackAdapter.ViewHolder>(DIFF) {
    private var dragRows: MutableList<ShippyCollectionTrackRow>? = null
    private var dragStartRows: List<ShippyCollectionTrackRow>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), onClick, onDownloadAction)

    fun beginDrag() {
        if (dragRows == null) {
            dragStartRows = currentList
            dragRows = currentList.toMutableList()
        }
    }

    fun move(fromPosition: Int, toPosition: Int): Boolean {
        val rows = dragRows ?: return false
        if (fromPosition !in rows.indices || toPosition !in rows.indices) return false
        rows.add(toPosition, rows.removeAt(fromPosition))
        submitList(rows.toList())
        return true
    }

    fun finishDrag(): List<ShippyCollectionTrackRow>? {
        val rows = dragRows?.toList()
        val changed = rows != null && rows != dragStartRows
        dragRows = null
        dragStartRows = null
        return rows?.takeIf { changed }
    }

    internal class ViewHolder(private val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            row: ShippyCollectionTrackRow,
            onClick: (ShippyCollectionTrackRow) -> Unit,
            onDownloadAction: (ShippyCollectionTrackRow) -> Unit,
        ) {
            binding.songName.text = row.track.title
            binding.songInfo.text = row.track.artists.joinToString()
            binding.songAlbumCover.bindArtwork(
                row.track.artwork,
                row.track.album?.let { "${row.track.title}, $it" } ?: row.track.title,
            )
            binding.songMenu.apply {
                val presentation = row.download
                isVisible = presentation !is CollectionRowDownloadPresentation.Hidden
                isEnabled = presentation !is CollectionRowDownloadPresentation.Working
                when (presentation) {
                    CollectionRowDownloadPresentation.Hidden -> Unit
                    is CollectionRowDownloadPresentation.Ready -> {
                        setIconResource(R.drawable.ic_down_24)
                        contentDescription = context.getString(R.string.desc_download)
                    }
                    is CollectionRowDownloadPresentation.Working -> {
                        setIconResource(R.drawable.ic_down_24)
                        contentDescription = context.getString(R.string.desc_downloading)
                    }
                    is CollectionRowDownloadPresentation.Paused -> {
                        setIconResource(R.drawable.ic_play_24)
                        contentDescription = context.getString(R.string.desc_resume_download)
                    }
                    is CollectionRowDownloadPresentation.Retry -> {
                        setIconResource(R.drawable.ic_feature_request_24)
                        contentDescription = context.getString(R.string.desc_retry_download)
                    }
                    is CollectionRowDownloadPresentation.Available -> {
                        setIconResource(R.drawable.ic_check_24)
                        contentDescription = context.getString(R.string.desc_remove_download)
                    }
                }
                setOnClickListener { onDownloadAction(row) }
            }
            binding.root.setOnClickListener { onClick(row) }
            binding.root.contentDescription = "${row.track.title}, ${binding.songInfo.text}"
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ShippyCollectionTrackRow>() {
            override fun areItemsTheSame(old: ShippyCollectionTrackRow, new: ShippyCollectionTrackRow) =
                old.track.id == new.track.id
            override fun areContentsTheSame(old: ShippyCollectionTrackRow, new: ShippyCollectionTrackRow) = old == new
        }
    }
}
