/*
 * Copyright (c) 2026 Shippy contributors
 * LibraryCollectionAdapters.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.home.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemHeaderBinding
import org.oxycblt.auxio.databinding.ItemLibraryCollectionBinding
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.library.LibrarySystemCollectionRow

/** A compact Auxio-styled section title that disappears when its projection is empty. */
internal class LibrarySectionHeaderAdapter(@StringRes private val titleRes: Int) :
    RecyclerView.Adapter<LibrarySectionHeaderAdapter.ViewHolder>() {
    private var shown = false

    fun setShown(shown: Boolean) {
        if (this.shown == shown) return
        this.shown = shown
        if (shown) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    override fun getItemCount() = if (shown) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(titleRes)

    internal class ViewHolder(private val binding: ItemHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(@StringRes titleRes: Int) {
            binding.title.setText(titleRes)
        }
    }
}

/** Permanent rule-driven collection rows. They intentionally have no overflow or delete action. */
internal class LibrarySystemCollectionAdapter(
    private val onClick: (LibrarySystemCollectionRow) -> Unit,
) :
    ListAdapter<LibrarySystemCollectionRow, LibrarySystemCollectionAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemLibraryCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), onClick)

    internal class ViewHolder(private val binding: ItemLibraryCollectionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: LibrarySystemCollectionRow, onClick: (LibrarySystemCollectionRow) -> Unit) {
            binding.collectionIcon.setImageResource(
                when (row.kind) {
                    SystemCollectionKind.LIKED -> R.drawable.ic_save_24
                    SystemCollectionKind.DOWNLOADS -> R.drawable.ic_down_24
                    SystemCollectionKind.LOCAL -> R.drawable.ic_library_24
                }
            )
            binding.collectionTitle.setText(
                when (row.kind) {
                    SystemCollectionKind.LIKED -> R.string.lbl_liked
                    SystemCollectionKind.DOWNLOADS -> R.string.lbl_downloads
                    SystemCollectionKind.LOCAL -> R.string.lbl_local
                }
            )
            binding.collectionSummary.text =
                when (row.kind) {
                    SystemCollectionKind.LIKED ->
                        binding.root.resources.getQuantityString(
                            R.plurals.fmt_liked_track_count,
                            row.itemCount,
                            row.itemCount,
                        )
                    SystemCollectionKind.DOWNLOADS ->
                        binding.root.resources.getQuantityString(
                            R.plurals.fmt_download_record_count,
                            row.itemCount,
                            row.itemCount,
                        )
                    SystemCollectionKind.LOCAL ->
                        if (row.isLoading) {
                            binding.root.context.getString(R.string.lng_local_indexing)
                        } else {
                            binding.root.resources.getQuantityString(
                                R.plurals.fmt_local_song_count,
                                row.itemCount,
                                row.itemCount,
                            )
                        }
                }
            binding.root.contentDescription =
                binding.root.context.getString(
                    R.string.desc_library_collection,
                    binding.collectionTitle.text,
                    binding.collectionSummary.text,
                )
            binding.root.apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick(row) }
            }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<LibrarySystemCollectionRow>() {
                override fun areItemsTheSame(
                    oldItem: LibrarySystemCollectionRow,
                    newItem: LibrarySystemCollectionRow,
                ) = oldItem.kind == newItem.kind

                override fun areContentsTheSame(
                    oldItem: LibrarySystemCollectionRow,
                    newItem: LibrarySystemCollectionRow,
                ) = oldItem == newItem
            }
    }
}

/** One actionable row used only when a new Library has no content from any source. */
internal class LibraryOnboardingAdapter(
    private val onChooseFolders: () -> Unit,
) : RecyclerView.Adapter<LibraryOnboardingAdapter.ViewHolder>() {
    private var shown = false

    fun setShown(shown: Boolean) {
        if (this.shown == shown) return
        this.shown = shown
        if (shown) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    override fun getItemCount() = if (shown) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemLibraryCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onChooseFolders,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind()

    internal class ViewHolder(
        private val binding: ItemLibraryCollectionBinding,
        private val onChooseFolders: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.collectionIcon.setImageResource(R.drawable.ic_add_24)
            binding.collectionTitle.setText(R.string.lbl_choose_music_folders)
            binding.collectionSummary.setText(R.string.lng_choose_music_folders)
            binding.root.apply {
                isClickable = true
                isFocusable = true
                contentDescription =
                    context.getString(
                        R.string.desc_library_collection,
                        binding.collectionTitle.text,
                        binding.collectionSummary.text,
                    )
                setOnClickListener { onChooseFolders() }
            }
        }
    }
}

/**
 * Shippy playlist rows open a management/detail surface. Track IDs alone are deliberately not
 * rendered as song rows until a canonical metadata store can resolve them.
 */
internal class ShippyPlaylistProjectionAdapter(
    private val onClick: (LibraryCollection.Playlist) -> Unit,
) :
    ListAdapter<LibraryCollection.Playlist, ShippyPlaylistProjectionAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemLibraryCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), onClick)

    internal class ViewHolder(private val binding: ItemLibraryCollectionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            playlist: LibraryCollection.Playlist,
            onClick: (LibraryCollection.Playlist) -> Unit,
        ) {
            binding.collectionIcon.setImageResource(R.drawable.ic_playlist_24)
            binding.collectionTitle.text = playlist.displayName
            binding.collectionSummary.setText(
                if (playlist.isPinned) {
                    R.string.lng_pinned_shippy_playlist
                } else {
                    R.string.lng_shippy_playlist
                }
            )
            binding.root.contentDescription =
                binding.root.context.getString(
                    R.string.desc_library_collection,
                    binding.collectionTitle.text,
                    binding.collectionSummary.text,
                )
            binding.root.apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick(playlist) }
            }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<LibraryCollection.Playlist>() {
                override fun areItemsTheSame(
                    oldItem: LibraryCollection.Playlist,
                    newItem: LibraryCollection.Playlist,
                ) = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: LibraryCollection.Playlist,
                    newItem: LibraryCollection.Playlist,
                ) = oldItem == newItem
            }
    }
}
