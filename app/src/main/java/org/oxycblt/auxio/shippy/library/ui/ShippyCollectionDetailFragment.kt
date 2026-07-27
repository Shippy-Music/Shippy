/*
 * Copyright (c) 2026 Auxio Project
 * ShippyCollectionDetailFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.library.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.library.CollectionDetailMessage
import org.oxycblt.auxio.shippy.library.CollectionRowDownloadPresentation
import org.oxycblt.auxio.shippy.library.ShippyCollectionDetailState
import org.oxycblt.auxio.shippy.library.ShippyCollectionDetailViewModel
import org.oxycblt.auxio.shippy.library.ShippyCollectionTrackRow
import org.oxycblt.auxio.shippy.library.messageKind
import org.oxycblt.auxio.shippy.provider.ui.ProviderTrackActionsSheet
import org.oxycblt.auxio.ui.AuxioToolbar
import org.oxycblt.auxio.util.applyBottomContentInset
import org.oxycblt.auxio.util.showToast

/** Artwork-led playlist detail shared by Liked, Downloads, Local, and user playlists. */
@AndroidEntryPoint
class ShippyCollectionDetailFragment : Fragment(R.layout.fragment_shippy_collection_detail) {
    private val model: ShippyCollectionDetailViewModel by viewModels()
    private val musicModel: MusicViewModel by activityViewModels()

    private val collectionId: LibraryCollectionId by lazy {
        LibraryCollectionId(requireArguments().getString(ARG_COLLECTION_ID).orEmpty())
    }
    private lateinit var toolbar: AuxioToolbar
    private lateinit var tracks: RecyclerView
    private val headerAdapter =
        ShippyCollectionHeaderAdapter(
            onPlay = {
                currentState?.let { model.playAll(collectionId, it.rows, shuffled = false) }
            },
            onShuffle = {
                currentState?.let { model.playAll(collectionId, it.rows, shuffled = true) }
            },
            onDownload = { currentState?.let { model.downloadAvailable(it.rows) } },
        )
    private val tracksAdapter =
        ShippyCollectionTrackAdapter(
            onClick = { row -> model.play(collectionId, visibleRows, row) },
            onMenu = { row -> ProviderTrackActionsSheet.show(parentFragmentManager, row.track) },
        )
    private var currentState: ShippyCollectionDetailState? = null
    private var visibleRows = emptyList<ShippyCollectionTrackRow>()
    private var currentQuery = ""
    private var currentSort = CollectionSort.COLLECTION_ORDER
    private var trackDragHelper: ItemTouchHelper? = null
    private var trackDragAttached = false
    private val artworkPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null || collectionId.isSystem) return@registerForActivityResult
            runCatching {
                requireContext()
                    .contentResolver
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            model.setArtwork(collectionId, uri.toString())
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                musicModel.refresh()
            } else if (isAdded) {
                requireContext().showToast(R.string.lng_grant_storage_required)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentSort = readSortPreference()
        toolbar = view.findViewById(R.id.shippy_collection_toolbar)
        tracks =
            view.findViewById<RecyclerView>(R.id.shippy_collection_scroll).also {
                it.applyBottomContentInset()
                it.adapter = ConcatAdapter(headerAdapter, tracksAdapter)
                it.itemAnimator = null
            }

        toolbar.apply {
            inflateMenu(R.menu.shippy_collection_detail)
            setNavigationOnClickListener { findNavController().navigateUp() }
            setOnMenuItemClickListener(::onToolbarItemSelected)
        }
        configureSearch()
        requestLocalPermissionIfNeeded(savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.observe(collectionId).collect(::render)
            }
        }
    }

    override fun onDestroyView() {
        trackDragHelper?.attachToRecyclerView(null)
        tracks.adapter = null
        toolbar.setOnMenuItemClickListener(null)
        super.onDestroyView()
    }

    private fun onToolbarItemSelected(item: android.view.MenuItem): Boolean {
        val playlist = (currentState as? ShippyCollectionDetailState.Playlist)?.playlist
        return when (item.itemId) {
            R.id.action_search -> {
                item.expandActionView()
                true
            }
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            R.id.action_rename -> {
                playlist?.let { showRenameDialog(it.id, it.displayName) }
                playlist != null
            }
            R.id.action_shippy_pin -> {
                val state = currentState ?: return false
                if (state is ShippyCollectionDetailState.Missing) return false
                model.setPinned(collectionId, !state.isPinned)
                true
            }
            R.id.action_change_artwork -> {
                if (playlist == null) return false
                artworkPicker.launch(arrayOf("image/*"))
                true
            }
            R.id.action_delete -> {
                playlist?.let { showDeleteDialog(it.id, it.displayName) }
                playlist != null
            }
            else -> false
        }
    }

    private fun configureSearch() {
        val searchView =
            toolbar.menu.findItem(R.id.action_search).actionView as? SearchView ?: return
        searchView.queryHint = getString(R.string.lbl_search)
        searchView.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true

                override fun onQueryTextChange(query: String?): Boolean {
                    currentQuery = query.orEmpty()
                    updateVisibleRows()
                    return true
                }
            }
        )
    }

    private fun render(state: ShippyCollectionDetailState) {
        currentState = state
        toolbar.title = ""
        updateToolbarMenu(state)
        updateVisibleRows()

        val totalTrackCount = state.rows.size + state.unresolvedTrackCount
        val totalDuration = state.rows.mapNotNull { it.track.durationMs }.sum()
        val metadata =
            if (totalDuration > 0L) {
                getString(
                    R.string.fmt_collection_metadata,
                    totalTrackCount,
                    totalDuration.formatDurationMs(false),
                )
            } else {
                getString(R.string.fmt_collection_metadata_without_duration, totalTrackCount)
            }
        val message =
            if (state.rows.isNotEmpty() && state.unresolvedTrackCount == 0) {
                null
            } else {
                getString(
                    when (state.messageKind()) {
                        CollectionDetailMessage.DELETED -> R.string.lng_collection_deleted
                        CollectionDetailMessage.EMPTY -> R.string.lng_collection_empty
                        CollectionDetailMessage.METADATA_PENDING ->
                            R.string.lng_collection_metadata_pending
                    }
                )
            }
        val playlistArtwork = (state as? ShippyCollectionDetailState.Playlist)?.playlist?.artworkUri
        val artwork =
            if (playlistArtwork == null) {
                state.rows.mapNotNull { it.track.artwork }.distinct().take(4)
            } else {
                emptyList()
            }
        headerAdapter.update(
            ShippyCollectionHeaderModel(
                title = state.title,
                subtitle = collectionSubtitle(state),
                metadata = metadata,
                primaryArtwork = collectionArtwork(state),
                collageArtwork =
                    if (state is ShippyCollectionDetailState.Playlist) artwork else emptyList(),
                message = message,
                canPlay = state.rows.isNotEmpty(),
                playbackStarting = state.playbackStarting,
                download = state.rows.headerDownloadPresentation(),
            )
        )
    }

    private fun updateToolbarMenu(state: ShippyCollectionDetailState) {
        val playlist = state as? ShippyCollectionDetailState.Playlist
        toolbar.menu.findItem(R.id.action_rename)?.isVisible = playlist != null
        toolbar.menu.findItem(R.id.action_shippy_pin)?.apply {
            isVisible = state !is ShippyCollectionDetailState.Missing
            title = getString(if (state.isPinned) R.string.lbl_unpin else R.string.lbl_pin)
        }
        toolbar.menu.findItem(R.id.action_change_artwork)?.isVisible = playlist != null
        toolbar.menu.findItem(R.id.action_delete)?.isVisible = playlist != null
    }

    private fun updateVisibleRows() {
        val state = currentState ?: return
        val query = currentQuery.trim().lowercase(Locale.getDefault())
        val filtered =
            if (query.isEmpty()) {
                state.rows
            } else {
                state.rows.filter { row ->
                    row.track.title.lowercase(Locale.getDefault()).contains(query) ||
                        row.track.artists.any {
                            it.lowercase(Locale.getDefault()).contains(query)
                        } ||
                        row.track.album?.lowercase(Locale.getDefault())?.contains(query) == true
                }
            }
        visibleRows =
            when (currentSort) {
                CollectionSort.COLLECTION_ORDER -> filtered
                CollectionSort.TITLE -> filtered.sortedBy { it.track.title.lowercase() }
                CollectionSort.ARTIST ->
                    filtered.sortedBy { it.track.artists.joinToString().lowercase() }
                CollectionSort.DURATION ->
                    filtered.sortedByDescending { it.track.durationMs ?: Long.MIN_VALUE }
            }
        tracksAdapter.submitList(visibleRows)
        updateTrackReordering(state)
    }

    private fun updateTrackReordering(state: ShippyCollectionDetailState) {
        val shouldAttach =
            state is ShippyCollectionDetailState.Playlist &&
                currentQuery.isBlank() &&
                currentSort == CollectionSort.COLLECTION_ORDER
        if (trackDragHelper == null) {
            trackDragHelper =
                ItemTouchHelper(
                    ShippyCollectionTrackDragCallback(
                        tracksAdapter,
                        onDragFinished@{ rows ->
                            val playlist =
                                currentState as? ShippyCollectionDetailState.Playlist
                                    ?: return@onDragFinished
                            model.reorderPlaylistTracks(
                                playlist.playlist.id,
                                playlist.trackIds,
                                rows,
                            )
                        },
                    )
                )
        }
        if (shouldAttach != trackDragAttached) {
            trackDragHelper?.attachToRecyclerView(if (shouldAttach) tracks else null)
            trackDragAttached = shouldAttach
        }
    }

    private fun showSortDialog() {
        val options =
            arrayOf(
                getString(R.string.lbl_collection_order),
                getString(R.string.lbl_collection_by_title),
                getString(R.string.lbl_collection_by_artist),
                getString(R.string.lbl_collection_by_duration),
            )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_sort_mode)
            .setSingleChoiceItems(options, currentSort.ordinal) { dialog, index ->
                currentSort = CollectionSort.entries[index]
                writeSortPreference(currentSort)
                updateVisibleRows()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun collectionSubtitle(state: ShippyCollectionDetailState) =
        when {
            state is ShippyCollectionDetailState.Playlist ->
                getString(R.string.lbl_playlist_by_author)
            collectionId.value == systemCollectionId(SystemCollectionKind.LIKED) ->
                getString(R.string.lbl_liked_collection_subtitle)
            collectionId.value == systemCollectionId(SystemCollectionKind.DOWNLOADS) ->
                getString(R.string.lbl_downloads_collection_subtitle)
            else -> getString(R.string.lbl_local_collection_subtitle)
        }

    private fun collectionArtwork(state: ShippyCollectionDetailState): Any =
        (state as? ShippyCollectionDetailState.Playlist)?.playlist?.artworkUri
            ?: when (collectionId.value) {
                systemCollectionId(SystemCollectionKind.LIKED) -> R.drawable.shippy_library_liked
                systemCollectionId(SystemCollectionKind.DOWNLOADS) ->
                    R.drawable.shippy_library_downloads
                systemCollectionId(SystemCollectionKind.LOCAL) -> R.drawable.shippy_library_local
                else -> R.drawable.ic_playlist_48
            }

    private fun readSortPreference(): CollectionSort {
        val value =
            requireContext()
                .getSharedPreferences(
                    COLLECTION_VIEW_PREFERENCES,
                    android.content.Context.MODE_PRIVATE,
                )
                .getString("${COLLECTION_SORT_PREFIX}${collectionId.value}", null)
        return runCatching { CollectionSort.valueOf(value.orEmpty()) }
            .getOrDefault(CollectionSort.COLLECTION_ORDER)
    }

    private fun writeSortPreference(sort: CollectionSort) {
        requireContext()
            .getSharedPreferences(COLLECTION_VIEW_PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("${COLLECTION_SORT_PREFIX}${collectionId.value}", sort.name)
            .apply()
    }

    private fun requestLocalPermissionIfNeeded(savedInstanceState: Bundle?) {
        if (
            savedInstanceState == null &&
                collectionId.value == systemCollectionId(SystemCollectionKind.LOCAL) &&
                ContextCompat.checkSelfPermission(requireContext(), mediaPermission()) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(mediaPermission())
        }
    }

    private fun mediaPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun showRenameDialog(playlistId: LibraryCollectionId, currentName: String) {
        val input =
            EditText(requireContext()).apply {
                setText(currentName)
                selectAll()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine()
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_rename_playlist)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_rename) { _, _ ->
                model.rename(playlistId, input.text.toString())
            }
            .show()
    }

    private fun showDeleteDialog(playlistId: LibraryCollectionId, name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.lbl_delete))
            .setMessage(getString(R.string.lng_delete_shippy_playlist, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_delete) { _, _ ->
                model.delete(playlistId)
                findNavController().navigateUp()
            }
            .show()
    }

    private enum class CollectionSort {
        COLLECTION_ORDER,
        TITLE,
        ARTIST,
        DURATION,
    }

    companion object {
        const val ARG_COLLECTION_ID = "collectionId"
        private const val COLLECTION_VIEW_PREFERENCES = "shippy_collection_view"
        private const val COLLECTION_SORT_PREFIX = "sort:"

        private fun systemCollectionId(kind: SystemCollectionKind) = "system:${kind.id}"
    }
}

private fun List<ShippyCollectionTrackRow>.headerDownloadPresentation():
    CollectionHeaderDownloadPresentation {
    val visible =
        map(ShippyCollectionTrackRow::download).filterNot {
            it is CollectionRowDownloadPresentation.Hidden
        }
    return when {
        visible.isEmpty() -> CollectionHeaderDownloadPresentation.Hidden
        visible.any {
            it is CollectionRowDownloadPresentation.Ready ||
                it is CollectionRowDownloadPresentation.Paused ||
                it is CollectionRowDownloadPresentation.Retry
        } -> CollectionHeaderDownloadPresentation.Ready
        visible.any { it is CollectionRowDownloadPresentation.Working } ->
            CollectionHeaderDownloadPresentation.Working
        visible.all { it is CollectionRowDownloadPresentation.Available } ->
            CollectionHeaderDownloadPresentation.Hidden
        else -> CollectionHeaderDownloadPresentation.Hidden
    }
}
