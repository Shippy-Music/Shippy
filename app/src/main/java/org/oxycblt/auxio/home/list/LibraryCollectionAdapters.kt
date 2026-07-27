/*
 * Copyright (c) 2026 Auxio Project
 * LibraryCollectionAdapters.kt is part of Auxio.
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
package org.oxycblt.auxio.home.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemHeaderBinding
import org.oxycblt.auxio.databinding.ItemLibraryCollectionBinding
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.library.LibraryCollectionListRow
import org.oxycblt.auxio.shippy.library.LibrarySystemCollectionRow
import org.oxycblt.auxio.shippy.persistence.library.SavedProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntityType

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
        ViewHolder(ItemHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

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
    private val onClick: (LibrarySystemCollectionRow) -> Unit
) : ListAdapter<LibrarySystemCollectionRow, LibrarySystemCollectionAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemLibraryCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), onClick)

    internal class ViewHolder(private val binding: ItemLibraryCollectionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: LibrarySystemCollectionRow, onClick: (LibrarySystemCollectionRow) -> Unit) {
            val titleRes =
                when (row.kind) {
                    SystemCollectionKind.LIKED -> R.string.lbl_liked
                    SystemCollectionKind.DOWNLOADS -> R.string.lbl_downloads
                    SystemCollectionKind.LOCAL -> R.string.lbl_local
                }
            binding.collectionIcon.bindArtwork(
                when (row.kind) {
                    SystemCollectionKind.LIKED -> R.drawable.shippy_library_liked
                    SystemCollectionKind.DOWNLOADS -> R.drawable.shippy_library_downloads
                    SystemCollectionKind.LOCAL -> R.drawable.shippy_library_local
                },
                binding.root.context.getString(titleRes),
            )
            binding.collectionTitle.setText(titleRes)
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

/**
 * One Spotify-like list for permanent collections and Shippy playlists.
 *
 * Rows can cross the pinned boundary during a long press. Crossing that boundary changes the
 * dragged row's pin state; the rest of the list keeps its state and order.
 */
internal class UnifiedLibraryCollectionAdapter(
    private val onClick: (LibraryCollectionListRow) -> Unit
) : RecyclerView.Adapter<UnifiedLibraryCollectionAdapter.ViewHolder>() {
    private var rows = mutableListOf<LibraryCollectionListRow>()
    private var dragStartRows: List<LibraryCollectionListRow>? = null
    private var pendingRows: List<LibraryCollectionListRow>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemLibraryCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(rows[position], onClick)

    override fun getItemCount() = rows.size

    fun update(newRows: List<LibraryCollectionListRow>) {
        val activeDrag = dragStartRows
        if (
            activeDrag != null && activeDrag.map { it.id }.toSet() != newRows.map { it.id }.toSet()
        ) {
            dragStartRows = null
            pendingRows = null
            rows = newRows.toMutableList()
            notifyDataSetChanged()
            return
        }
        if (activeDrag != null) return

        val pending = pendingRows
        if (pending != null && newRows != pending) {
            if (newRows.map { it.id }.toSet() == pending.map { it.id }.toSet()) return
            pendingRows = null
        } else if (pending != null) {
            pendingRows = null
        }
        rows = newRows.toMutableList()
        notifyDataSetChanged()
    }

    fun beginDrag() {
        if (dragStartRows == null) dragStartRows = rows.toList()
    }

    fun move(fromPosition: Int, toPosition: Int): Boolean {
        if (dragStartRows == null) return false
        if (fromPosition !in rows.indices || toPosition !in rows.indices) return false
        val targetPinned = rows[toPosition].isPinned
        val moving = rows.removeAt(fromPosition).withPinned(targetPinned)
        rows.add(toPosition, moving)
        notifyItemMoved(fromPosition, toPosition)
        notifyItemChanged(toPosition)
        return true
    }

    fun finishDrag(): List<LibraryCollectionListRow>? {
        val finished = rows.toList()
        val changed = dragStartRows != null && finished != dragStartRows
        dragStartRows = null
        if (changed) pendingRows = finished
        return finished.takeIf { changed }
    }

    fun rejectPending(newRows: List<LibraryCollectionListRow>) {
        dragStartRows = null
        pendingRows = null
        rows = newRows.toMutableList()
        notifyDataSetChanged()
    }

    internal class ViewHolder(private val binding: ItemLibraryCollectionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: LibraryCollectionListRow, onClick: (LibraryCollectionListRow) -> Unit) {
            when (row) {
                is LibraryCollectionListRow.System -> bindSystem(row)
                is LibraryCollectionListRow.Playlist -> bindPlaylist(row)
            }
            binding.root.apply {
                isClickable = true
                isFocusable = true
                contentDescription =
                    context.getString(
                        R.string.desc_library_collection,
                        binding.collectionTitle.text,
                        binding.collectionSummary.text,
                    )
                setOnClickListener { onClick(row) }
            }
        }

        private fun bindSystem(row: LibraryCollectionListRow.System) {
            val collection = row.collection
            val titleRes =
                when (collection.kind) {
                    SystemCollectionKind.LIKED -> R.string.lbl_liked
                    SystemCollectionKind.DOWNLOADS -> R.string.lbl_downloads
                    SystemCollectionKind.LOCAL -> R.string.lbl_local
                }
            binding.collectionIcon.bindArtwork(
                when (collection.kind) {
                    SystemCollectionKind.LIKED -> R.drawable.shippy_library_liked
                    SystemCollectionKind.DOWNLOADS -> R.drawable.shippy_library_downloads
                    SystemCollectionKind.LOCAL -> R.drawable.shippy_library_local
                },
                binding.root.context.getString(titleRes),
            )
            binding.collectionTitle.setText(titleRes)
            val count =
                when (collection.kind) {
                    SystemCollectionKind.LIKED ->
                        binding.root.resources.getQuantityString(
                            R.plurals.fmt_liked_track_count,
                            collection.itemCount,
                            collection.itemCount,
                        )
                    SystemCollectionKind.DOWNLOADS ->
                        binding.root.resources.getQuantityString(
                            R.plurals.fmt_download_record_count,
                            collection.itemCount,
                            collection.itemCount,
                        )
                    SystemCollectionKind.LOCAL ->
                        if (collection.isLoading) {
                            binding.root.context.getString(R.string.lng_local_indexing)
                        } else {
                            binding.root.resources.getQuantityString(
                                R.plurals.fmt_local_song_count,
                                collection.itemCount,
                                collection.itemCount,
                            )
                        }
                }
            binding.collectionSummary.text =
                listOfNotNull(
                        binding.root.context.getString(R.string.lbl_pinned).takeIf { row.isPinned },
                        count,
                    )
                    .joinToString(" â€¢ ")
        }

        private fun bindPlaylist(row: LibraryCollectionListRow.Playlist) {
            binding.collectionIcon.bindArtwork(
                row.playlist.artworkUri ?: row.artwork ?: R.drawable.ic_playlist_24,
                row.playlist.displayName,
            )
            binding.collectionTitle.text = row.playlist.displayName
            binding.collectionSummary.setText(
                if (row.isPinned) {
                    R.string.lng_pinned_shippy_playlist
                } else {
                    R.string.lng_shippy_playlist
                }
            )
        }
    }
}

/** One actionable row used only when a new Library has no content from any source. */
internal class LibraryOnboardingAdapter(private val onChooseFolders: () -> Unit) :
    RecyclerView.Adapter<LibraryOnboardingAdapter.ViewHolder>() {
    private var shown = false

    fun setShown(shown: Boolean) {
        if (this.shown == shown) return
        this.shown = shown
        if (shown) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    override fun getItemCount() = if (shown) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemLibraryCollectionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
            onChooseFolders,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind()

    internal class ViewHolder(
        private val binding: ItemLibraryCollectionBinding,
        private val onChooseFolders: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.collectionIcon.bindArtwork(
                R.drawable.ic_add_24,
                binding.root.context.getString(R.string.lbl_choose_music_folders),
            )
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

/** Artwork-led provider albums, artists, and playlists saved into Shippy's unified Library. */
internal class SavedProviderEntityAdapter(private val onClick: (SavedProviderEntity) -> Unit) :
    ListAdapter<SavedProviderEntity, SavedProviderEntityAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), onClick)

    internal class ViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(saved: SavedProviderEntity, onClick: (SavedProviderEntity) -> Unit) {
            val entity = saved.entity
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
                        if (saved.isPinned) add(binding.root.context.getString(R.string.lbl_pinned))
                        add(type)
                        entity.subtitle?.takeIf(String::isNotBlank)?.let(::add)
                    }
                    .joinToString(" • ")
            binding.songAlbumCover.bindArtwork(entity.artwork, entity.title)
            binding.songMenu.isVisible = false
            binding.root.apply {
                contentDescription =
                    context.getString(
                        R.string.desc_library_collection,
                        entity.title,
                        binding.songInfo.text,
                    )
                setOnClickListener { onClick(saved) }
            }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<SavedProviderEntity>() {
                override fun areItemsTheSame(
                    oldItem: SavedProviderEntity,
                    newItem: SavedProviderEntity,
                ) =
                    oldItem.entity.providerId == newItem.entity.providerId &&
                        oldItem.entity.type == newItem.entity.type &&
                        oldItem.entity.sourceItemId == newItem.entity.sourceItemId

                override fun areContentsTheSame(
                    oldItem: SavedProviderEntity,
                    newItem: SavedProviderEntity,
                ) = oldItem == newItem
            }
    }
}

/**
 * Shippy playlist rows open a management/detail surface. Track IDs alone are deliberately not
 * rendered as song rows until a canonical metadata store can resolve them.
 */
internal class ShippyPlaylistProjectionAdapter(
    private val onClick: (LibraryCollection.Playlist) -> Unit
) : RecyclerView.Adapter<ShippyPlaylistProjectionAdapter.ViewHolder>() {
    private var rows = mutableListOf<LibraryCollection.Playlist>()
    private var dragStartRows: List<LibraryCollection.Playlist>? = null
    private var pendingOrder: List<LibraryCollectionId>? = null
    private var artworkByPlaylist = emptyMap<LibraryCollectionId, String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemLibraryCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(rows[position], artworkByPlaylist[rows[position].id], onClick)

    override fun getItemCount() = rows.size

    /** Applies repository state unless an incompatible concurrent emission invalidates a drag. */
    fun update(
        playlists: List<LibraryCollection.Playlist>,
        artworkByPlaylist: Map<LibraryCollectionId, String> = emptyMap(),
    ) {
        this.artworkByPlaylist = artworkByPlaylist
        val startRows = dragStartRows
        if (startRows != null && !hasSamePlaylistGroups(startRows, playlists)) {
            dragStartRows = null
            pendingOrder = null
            rows = playlists.toMutableList()
            notifyDataSetChanged()
            return
        }
        if (startRows != null) return

        val emittedOrder = playlists.map(LibraryCollection.Playlist::id)
        val pending = pendingOrder
        if (pending != null && emittedOrder != pending) {
            // A combined-state emission may still contain the pre-transaction order.
            // Keep the optimistic complete order until Room publishes the exact write.
            if (hasSamePlaylistGroups(rows, playlists)) return
            pendingOrder = null
        } else if (pending != null) {
            pendingOrder = null
        }
        rows = playlists.toMutableList()
        notifyDataSetChanged()
    }

    fun beginDrag() {
        if (dragStartRows != null) return
        dragStartRows = rows.toList()
    }

    fun move(fromPosition: Int, toPosition: Int): Boolean {
        if (dragStartRows == null) return false
        if (fromPosition !in rows.indices || toPosition !in rows.indices) return false
        if (rows[fromPosition].isPinned != rows[toPosition].isPinned) return false
        rows.add(toPosition, rows.removeAt(fromPosition))
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    fun finishDrag(): List<LibraryCollection.Playlist>? {
        val finishedRows = rows.toList()
        val changed = dragStartRows != null && finishedRows != dragStartRows
        dragStartRows = null
        if (changed) {
            pendingOrder = finishedRows.map(LibraryCollection.Playlist::id)
        }
        return finishedRows.takeIf { changed }
    }

    fun rejectPending(playlists: List<LibraryCollection.Playlist>) {
        dragStartRows = null
        pendingOrder = null
        rows = playlists.toMutableList()
        notifyDataSetChanged()
    }

    internal class ViewHolder(private val binding: ItemLibraryCollectionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            playlist: LibraryCollection.Playlist,
            artwork: String?,
            onClick: (LibraryCollection.Playlist) -> Unit,
        ) {
            binding.collectionIcon.bindArtwork(
                artwork ?: R.drawable.ic_playlist_24,
                playlist.displayName,
            )
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
        fun hasSamePlaylistGroups(
            first: List<LibraryCollection.Playlist>,
            second: List<LibraryCollection.Playlist>,
        ): Boolean =
            first.associate { it.id to it.isPinned } == second.associate { it.id to it.isPinned } &&
                first.size == second.size
    }
}
