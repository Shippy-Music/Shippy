/*
 * Copyright (c) 2023 Auxio Project
 * PlaylistListFragment.kt is part of Auxio.
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

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaylistListBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.recycler.FastScrollRecyclerView
import org.oxycblt.auxio.list.recycler.PlaylistViewHolder
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMsPopup
import org.oxycblt.auxio.shippy.library.LibraryCollectionListRow
import org.oxycblt.auxio.shippy.library.LibraryCollectionsState
import org.oxycblt.auxio.shippy.library.LibraryCollectionsViewModel
import org.oxycblt.auxio.shippy.library.collectionRows
import org.oxycblt.auxio.shippy.library.shouldShowOnboarding
import org.oxycblt.auxio.shippy.library.ui.LibraryCollectionActionsSheet
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song

/**
 * A [ListFragment] that shows a list of [Playlist]s.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class PlaylistListFragment :
    ListFragment<Playlist, FragmentPlaylistListBinding>(),
    FastScrollRecyclerView.PopupProvider,
    FastScrollRecyclerView.Listener {
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    private val collectionsModel: LibraryCollectionsViewModel by viewModels()
    private val collectionAdapter =
        UnifiedLibraryCollectionAdapter(
            onClick = { row -> homeModel.openShippyCollection(row.id) },
            onLongClick = { row ->
                LibraryCollectionActionsSheet.show(
                    parentFragmentManager,
                    collectionId = row.id.value,
                    title =
                        when (row) {
                            is LibraryCollectionListRow.System ->
                                getString(
                                    when (row.collection.kind) {
                                        org.oxycblt.auxio.shippy.domain.SystemCollectionKind
                                            .LIKED -> R.string.lbl_liked
                                        org.oxycblt.auxio.shippy.domain.SystemCollectionKind
                                            .DOWNLOADS -> R.string.lbl_downloads
                                        org.oxycblt.auxio.shippy.domain.SystemCollectionKind
                                            .LOCAL -> R.string.lbl_local
                                    }
                                )
                            is LibraryCollectionListRow.Playlist -> row.playlist.displayName
                        },
                    isSystem = row is LibraryCollectionListRow.System,
                    isPinned = row.isPinned,
                    artwork = (row as? LibraryCollectionListRow.Playlist)?.artwork,
                )
            },
        )
    private val onboardingAdapter = LibraryOnboardingAdapter {
        homeModel.startChooseMusicLocations()
    }
    private val savedProvidersHeaderAdapter =
        LibrarySectionHeaderAdapter(R.string.lbl_saved_from_providers)
    private val savedProviderAdapter = SavedProviderEntityAdapter { saved ->
        homeModel.openProviderEntity(saved.entity)
    }
    private val devicePlaylistsHeaderAdapter =
        LibrarySectionHeaderAdapter(R.string.lbl_on_this_device)
    private val playlistAdapter = PlaylistAdapter(this)
    private val libraryAdapter =
        ConcatAdapter(
            collectionAdapter,
            onboardingAdapter,
            savedProvidersHeaderAdapter,
            savedProviderAdapter,
            devicePlaylistsHeaderAdapter,
            playlistAdapter,
        )
    private var playlistDragHelper: ItemTouchHelper? = null
    private var localSongCount = 0
    private var devicePlaylistCount = 0
    private var isLocalIndexing = false
    private var collectionState = LibraryCollectionsState()
    private var orderEditing = false
    private var pendingArtworkCollectionId: String? = null
    private var orderBackCallback: OnBackPressedCallback? = null

    private val artworkPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val collectionId = pendingArtworkCollectionId
            pendingArtworkCollectionId = null
            if (uri == null || collectionId == null) return@registerForActivityResult
            runCatching {
                requireContext()
                    .contentResolver
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            collectionsModel.setPlaylistArtwork(
                org.oxycblt.auxio.shippy.domain.LibraryCollectionId(collectionId),
                uri.toString(),
            )
        }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaylistListBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaylistListBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeRecycler.apply {
            id = R.id.home_playlist_recycler
            adapter = libraryAdapter
            popupProvider = this@PlaylistListFragment
            listener = this@PlaylistListFragment
        }
        binding.homeCollectionEditStart.setOnClickListener { startOrderEditing() }
        binding.homeCollectionEditCancel.setOnClickListener { cancelOrderEditing() }
        binding.homeCollectionEditDone.setOnClickListener { finishOrderEditing() }
        parentFragmentManager.setFragmentResultListener(
            LibraryCollectionActionsSheet.RESULT,
            viewLifecycleOwner,
        ) { _, result ->
            val collectionId =
                result.getString(LibraryCollectionActionsSheet.KEY_COLLECTION_ID)
                    ?: return@setFragmentResultListener
            val row =
                currentCollectionRows().firstOrNull { it.id.value == collectionId }
                    ?: return@setFragmentResultListener
            when (result.getInt(LibraryCollectionActionsSheet.KEY_ACTION)) {
                R.id.action_library_edit_order -> startOrderEditing()
                R.id.action_library_pin -> collectionsModel.setPinned(row.id, !row.isPinned)
                R.id.action_library_rename ->
                    (row as? LibraryCollectionListRow.Playlist)?.let {
                        showRenameDialog(it.id.value, it.playlist.displayName)
                    }
                R.id.action_library_artwork -> {
                    if (row is LibraryCollectionListRow.Playlist) {
                        pendingArtworkCollectionId = row.id.value
                        artworkPicker.launch(arrayOf("image/*"))
                    }
                }
                R.id.action_library_delete ->
                    (row as? LibraryCollectionListRow.Playlist)?.let {
                        showDeleteDialog(it.id.value, it.playlist.displayName)
                    }
            }
        }
        orderBackCallback =
            object : OnBackPressedCallback(false) {
                    override fun handleOnBackPressed() = cancelOrderEditing()
                }
                .also {
                    requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it)
                }
        playlistDragHelper = ItemTouchHelper(ShippyPlaylistDragCallback(collectionAdapter) {})

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_playlist_48)
            contentDescription = getString(R.string.lbl_playlists)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_playlists)
        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.playlistList, ::updatePlaylists)
        collectImmediately(collectionsModel.state, ::updateCollections)
        collectImmediately(homeModel.songList, musicModel.indexingState, ::updateLocalCollection)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
    }

    override fun onDestroyBinding(binding: FragmentPlaylistListBinding) {
        super.onDestroyBinding(binding)
        playlistDragHelper?.attachToRecyclerView(null)
        playlistDragHelper = null
        orderEditing = false
        binding.homeRecycler.apply {
            adapter = null
            popupProvider = null
            listener = null
        }
    }

    override fun getPopupData(pos: Int): FastScrollRecyclerView.PopupProvider.PopupData? {
        val playlistPosition = pos - (libraryAdapter.itemCount - playlistAdapter.itemCount)
        val playlist = homeModel.playlistList.value.getOrNull(playlistPosition) ?: return null
        // Change how we display the popup depending on the current sort mode.
        return when (homeModel.playlistSort.mode) {
            // By Name -> Use Name
            is Sort.Mode.ByName ->
                FastScrollRecyclerView.PopupProvider.PopupData(playlist.name.thumb() ?: "?")

            // Duration -> Use compact bucket duration
            is Sort.Mode.ByDuration ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    playlist.durationMs.formatDurationMsPopup()
                )

            // Count -> Use song count
            is Sort.Mode.ByCount ->
                FastScrollRecyclerView.PopupProvider.PopupData(playlist.songs.size.toString())

            // Unsupported sort, error gracefully
            else -> null
        }
    }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    override fun onRealClick(item: Playlist) {
        detailModel.showPlaylist(item)
    }

    override fun onOpenMenu(item: Playlist) {
        listModel.openMenu(R.menu.playlist, item)
    }

    private fun updatePlaylists(playlists: List<Playlist>) {
        devicePlaylistCount = playlists.size
        playlistAdapter.update(playlists, homeModel.playlistInstructions.consume())
        devicePlaylistsHeaderAdapter.setShown(playlists.isNotEmpty())
        renderSystemCollections()
    }

    private fun updateCollections(state: LibraryCollectionsState) {
        collectionState = state
        savedProvidersHeaderAdapter.setShown(state.savedProviderEntities.isNotEmpty())
        savedProviderAdapter.submitList(state.savedProviderEntities)
        renderSystemCollections()
    }

    private fun updateLocalCollection(songs: List<Song>, indexingState: IndexingState?) {
        localSongCount = songs.size
        isLocalIndexing = indexingState is IndexingState.Indexing
        renderSystemCollections()
    }

    private fun renderSystemCollections() {
        val rows = currentCollectionRows()
        collectionAdapter.update(rows)
        onboardingAdapter.setShown(
            collectionState.shouldShowOnboarding(
                localSongCount = localSongCount,
                devicePlaylistCount = devicePlaylistCount,
                isLocalIndexing = isLocalIndexing,
            )
        )
        val binding = requireBinding()
        binding.homeCollectionEditStart.isVisible = false
        binding.homeRecycler.isInvisible = false
        binding.homeNoMusic.isInvisible = true
    }

    private fun currentCollectionRows(): List<LibraryCollectionListRow> =
        collectionState.collectionRows(localSongCount, isLocalIndexing)

    private fun startOrderEditing() {
        if (orderEditing) return
        orderEditing = true
        collectionAdapter.setEditMode(true)
        requireBinding().homeCollectionEditStart.isVisible = false
        requireBinding().homeCollectionEditActions.isVisible = true
        orderBackCallback?.isEnabled = true
        playlistDragHelper?.attachToRecyclerView(requireBinding().homeRecycler)
    }

    private fun cancelOrderEditing() {
        if (!orderEditing) return
        collectionAdapter.cancelEdit()
        orderEditing = false
        orderBackCallback?.isEnabled = false
        playlistDragHelper?.attachToRecyclerView(null)
        requireBinding().homeCollectionEditStart.isVisible = false
        requireBinding().homeCollectionEditActions.isVisible = false
    }

    private fun finishOrderEditing() {
        if (!orderEditing) return
        val reorderedRows = collectionAdapter.finishEdit()
        orderEditing = false
        orderBackCallback?.isEnabled = false
        playlistDragHelper?.attachToRecyclerView(null)
        requireBinding().homeCollectionEditStart.isVisible = false
        requireBinding().homeCollectionEditActions.isVisible = false
        if (reorderedRows != null && !collectionsModel.reorderCollections(reorderedRows)) {
            collectionAdapter.rejectPending(currentCollectionRows())
        }
    }

    private fun showRenameDialog(collectionId: String, currentName: String) {
        val input =
            android.widget.EditText(requireContext()).apply {
                setText(currentName)
                selectAll()
                setSingleLine()
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_rename_playlist)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_rename) { _, _ ->
                collectionsModel.renamePlaylist(
                    org.oxycblt.auxio.shippy.domain.LibraryCollectionId(collectionId),
                    input.text.toString(),
                )
            }
            .show()
    }

    private fun showDeleteDialog(collectionId: String, name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_delete)
            .setMessage(getString(R.string.lng_delete_shippy_playlist, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_delete) { _, _ ->
                collectionsModel.deletePlaylist(
                    org.oxycblt.auxio.shippy.domain.LibraryCollectionId(collectionId)
                )
            }
            .show()
    }

    private fun updateSelection(selection: List<Music>) {
        playlistAdapter.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        // Only highlight the playlist if it is currently playing, and if the currently
        // playing song is also contained within.
        val playlist = (parent as? Playlist)?.takeIf { it.songs.contains(song) }
        playlistAdapter.setPlaying(playlist, isPlaying)
    }

    /**
     * A [SelectionIndicatorAdapter] that shows a list of [Playlist]s using [PlaylistViewHolder].
     *
     * @param listener An [SelectableListListener] to bind interactions to.
     */
    private class PlaylistAdapter(private val listener: SelectableListListener<Playlist>) :
        SelectionIndicatorAdapter<Playlist, PlaylistViewHolder>(PlaylistViewHolder.DIFF_CALLBACK) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PlaylistViewHolder.from(parent)

        override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
            holder.bind(getItem(position), listener)
        }
    }
}
