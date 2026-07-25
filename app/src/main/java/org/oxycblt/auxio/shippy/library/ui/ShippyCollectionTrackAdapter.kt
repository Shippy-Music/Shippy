/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyCollectionTrackAdapter.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.library.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.library.ShippyCollectionTrackRow

/** Reuses Auxio's normal song-row component for canonical, non-local collection items. */
internal class ShippyCollectionTrackAdapter(
    private val onClick: (ShippyCollectionTrackRow) -> Unit,
) : ListAdapter<ShippyCollectionTrackRow, ShippyCollectionTrackAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position), onClick)

    internal class ViewHolder(private val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: ShippyCollectionTrackRow, onClick: (ShippyCollectionTrackRow) -> Unit) {
            binding.songName.text = row.track.title
            binding.songInfo.text = buildString {
                append(row.track.artists.joinToString())
                row.downloadState?.let { append(" - ").append(it.rowLabel()) }
            }
            binding.songAlbumCover.bindArtwork(
                row.track.artwork,
                row.track.album?.let { "${row.track.title}, $it" } ?: row.track.title,
            )
            binding.songMenu.visibility = android.view.View.GONE
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

private fun DownloadState.rowLabel(): String = when (this) {
    DownloadState.AVAILABLE -> "Downloaded"
    DownloadState.REQUESTED, DownloadState.RESOLVING, DownloadState.QUEUED,
    DownloadState.TRANSFERRING, DownloadState.VERIFYING, DownloadState.FINALIZING -> "Downloading"
    DownloadState.PAUSED -> "Download paused"
    DownloadState.FAILED_RETRYABLE, DownloadState.FAILED_FINAL -> "Download failed"
    DownloadState.CANCELLED, DownloadState.REMOVED -> "Not downloaded"
}
