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
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
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
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.download.DownloadRemovalResult
import org.oxycblt.auxio.shippy.download.DownloadWorkCoordinator
import org.oxycblt.auxio.shippy.library.CollectionDetailMessage
import org.oxycblt.auxio.shippy.library.CollectionRowDownloadPresentation
import org.oxycblt.auxio.shippy.library.ShippyCollectionDetailState
import org.oxycblt.auxio.shippy.library.ShippyCollectionDetailViewModel
import org.oxycblt.auxio.shippy.library.ShippyCollectionTrackRow
import org.oxycblt.auxio.shippy.library.messageKind
import org.oxycblt.auxio.shippy.provider.ui.ProviderTrackActionsSheet
import org.oxycblt.auxio.shippy.storage.LocalMediaDeletionCoordinator
import org.oxycblt.auxio.shippy.storage.LocalMediaDeletionResult
import org.oxycblt.auxio.ui.AuxioToolbar
import org.oxycblt.auxio.util.applyBottomContentInset
import org.oxycblt.auxio.util.showToast

/** Artwork-led playlist detail shared by Liked, Downloads, Local, and user playlists. */
@AndroidEntryPoint
class ShippyCollectionDetailFragment : Fragment(R.layout.fragment_shippy_collection_detail) {
    private val model: ShippyCollectionDetailViewModel by viewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    @Inject lateinit var downloadCoordinator: DownloadWorkCoordinator
    @Inject lateinit var localMediaDeletion: LocalMediaDeletionCoordinator

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
            onClick = { row ->
                if (selectedTrackIds.isEmpty()) model.play(collectionId, visibleRows, row)
                else model.toggleTrackSelection(row.track.id)
            },
            onMenu = { row -> ProviderTrackActionsSheet.show(parentFragmentManager, row.track) },
            onLongClick = { row -> model.toggleTrackSelection(row.track.id) },
        )
    private var currentState: ShippyCollectionDetailState? = null
    private var visibleRows = emptyList<ShippyCollectionTrackRow>()
    private var currentQuery = ""
    private var currentSort = CollectionSort.COLLECTION_ORDER
    private var trackDragHelper: ItemTouchHelper? = null
    private var trackDragAttached = false
    private var trackOrderEditing = false
    private var trackOrderStartRows = emptyList<ShippyCollectionTrackRow>()
    private var pendingTrackOrderRows: List<ShippyCollectionTrackRow>? = null
    private var selectedTrackIds = emptySet<org.oxycblt.auxio.shippy.domain.TrackId>()
    private var pendingLocalDeletes = emptyList<org.oxycblt.auxio.shippy.domain.Track>()
    private var pendingLocalDeleteIndex = 0
    private var completedLocalDeletes = 0
    private var failedLocalDeletes = 0

    private val localDeleteConsent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) completedLocalDeletes++
            else failedLocalDeletes++
            pendingLocalDeleteIndex++
            continueLocalDelete()
        }
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
            setNavigationOnClickListener {
                when {
                    selectedTrackIds.isNotEmpty() -> model.clearTrackSelection()
                    trackOrderEditing -> cancelTrackOrderEditing()
                    else -> findNavController().navigateUp()
                }
            }
            setOnMenuItemClickListener(::onToolbarItemSelected)
        }
        configureSearch()
        requestLocalPermissionIfNeeded(savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.observe(collectionId).collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.selectedTrackIds.collect { selected ->
                    selectedTrackIds = selected
                    tracksAdapter.setSelected(selected)
                    updateSelectionToolbar()
                }
            }
        }
        parentFragmentManager.setFragmentResultListener(
            LibraryBatchActionsSheet.RESULT,
            viewLifecycleOwner,
        ) { _, result ->
            handleBatchAction(result.getInt(LibraryBatchActionsSheet.KEY_ACTION))
        }
        parentFragmentManager.setFragmentResultListener(
            CollectionSortSheet.RESULT,
            viewLifecycleOwner,
        ) { _, result ->
            currentSort =
                CollectionSort.entries[
                        result
                            .getInt(CollectionSortSheet.KEY_SELECTED)
                            .coerceIn(0, CollectionSort.entries.lastIndex)]
            writeSortPreference(currentSort)
            updateVisibleRows()
        }
    }

    override fun onDestroyView() {
        trackDragHelper?.attachToRecyclerView(null)
        trackOrderEditing = false
        pendingTrackOrderRows = null
        model.clearTrackSelection()
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
            R.id.action_batch_actions -> {
                if (selectedTrackIds.isEmpty()) return false
                LibraryBatchActionsSheet.show(
                    parentFragmentManager,
                    batchKind(),
                    selectedTrackIds.size,
                )
                true
            }
            R.id.action_edit_order -> {
                if (trackOrderEditing) finishTrackOrderEditing() else startTrackOrderEditing()
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
        updateToolbarMenu(state)
        updateSelectionToolbar()
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
        toolbar.menu.findItem(R.id.action_batch_actions)?.isVisible = selectedTrackIds.isNotEmpty()
        toolbar.menu.findItem(R.id.action_edit_order)?.apply {
            isVisible =
                playlist != null &&
                    selectedTrackIds.isEmpty() &&
                    currentQuery.isBlank() &&
                    currentSort == CollectionSort.COLLECTION_ORDER
            title = getString(if (trackOrderEditing) R.string.lbl_done else R.string.lbl_edit_order)
        }
    }

    private fun updateSelectionToolbar() {
        val selected = selectedTrackIds.size
        toolbar.title =
            selected.takeIf { it > 0 }?.let { getString(R.string.fmt_selected_tracks, it) } ?: ""
        toolbar.menu.findItem(R.id.action_search)?.isVisible = selected == 0
        toolbar.menu.findItem(R.id.action_sort)?.isVisible = selected == 0
        toolbar.menu.findItem(R.id.action_batch_actions)?.isVisible = selected > 0
        toolbar.menu.findItem(R.id.action_edit_order)?.isVisible =
            selected == 0 &&
                currentState is ShippyCollectionDetailState.Playlist &&
                currentQuery.isBlank() &&
                currentSort == CollectionSort.COLLECTION_ORDER
        toolbar.menu.findItem(R.id.action_edit_order)?.title =
            getString(if (trackOrderEditing) R.string.lbl_done else R.string.lbl_edit_order)
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
                currentSort == CollectionSort.COLLECTION_ORDER &&
                trackOrderEditing
        if (trackDragHelper == null) {
            trackDragHelper =
                ItemTouchHelper(
                    ShippyCollectionTrackDragCallback(
                        tracksAdapter,
                        onDragFinished = { pendingTrackOrderRows = it },
                    )
                )
        }
        if (shouldAttach != trackDragAttached) {
            trackDragHelper?.attachToRecyclerView(if (shouldAttach) tracks else null)
            trackDragAttached = shouldAttach
        }
    }

    private fun showSortDialog() {
        CollectionSortSheet.show(parentFragmentManager, currentSort.ordinal)
    }

    private fun batchKind() =
        when (collectionId.value) {
            systemCollectionId(SystemCollectionKind.LIKED) -> LibraryBatchActionsSheet.KIND_LIKED
            systemCollectionId(SystemCollectionKind.DOWNLOADS) ->
                LibraryBatchActionsSheet.KIND_DOWNLOADS
            systemCollectionId(SystemCollectionKind.LOCAL) -> LibraryBatchActionsSheet.KIND_LOCAL
            else -> LibraryBatchActionsSheet.KIND_PLAYLIST
        }

    private fun handleBatchAction(action: Int) {
        val rows = currentState?.rows.orEmpty().filter { it.track.id in selectedTrackIds }
        if (rows.isEmpty()) return
        when (action) {
            R.id.action_batch_remove_playlist ->
                confirmBatchMutation(
                    R.string.lbl_remove_from_playlist,
                    R.string.msg_remove_selected_from_playlist,
                ) {
                    val state =
                        currentState as? ShippyCollectionDetailState.Playlist
                            ?: return@confirmBatchMutation
                    model.removeSelectedFromPlaylist(collectionId, state.trackIds)
                }
            R.id.action_batch_remove_liked ->
                confirmBatchMutation(
                    R.string.lbl_remove_from_liked,
                    R.string.msg_remove_selected_from_liked,
                ) {
                    model.removeSelectedFromLiked()
                }
            R.id.action_batch_remove_downloads ->
                confirmBatchMutation(
                    R.string.lbl_remove_downloads,
                    R.string.msg_remove_selected_downloads,
                ) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        var failures = 0
                        rows.forEach { row ->
                            val jobId =
                                (row.download as? CollectionRowDownloadPresentation.Available)
                                    ?.jobId ?: return@forEach
                            if (
                                downloadCoordinator.remove(jobId) != DownloadRemovalResult.Removed
                            ) {
                                failures++
                            }
                        }
                        model.clearTrackSelection()
                        if (failures > 0) {
                            showBatchFailure(failures)
                        }
                    }
                }
            R.id.action_batch_delete_local ->
                confirmBatchMutation(
                    R.string.lbl_delete_from_device,
                    R.string.msg_delete_selected_local,
                ) {
                    pendingLocalDeletes = rows.map(ShippyCollectionTrackRow::track)
                    pendingLocalDeleteIndex = 0
                    completedLocalDeletes = 0
                    failedLocalDeletes = 0
                    continueLocalDelete()
                }
        }
    }

    private fun startTrackOrderEditing() {
        val playlist = currentState as? ShippyCollectionDetailState.Playlist ?: return
        if (currentQuery.isNotBlank() || currentSort != CollectionSort.COLLECTION_ORDER) return
        trackOrderEditing = true
        trackOrderStartRows = visibleRows
        pendingTrackOrderRows = null
        updateTrackReordering(playlist)
        updateSelectionToolbar()
    }

    private fun cancelTrackOrderEditing() {
        trackDragHelper?.attachToRecyclerView(null)
        trackDragAttached = false
        trackOrderEditing = false
        pendingTrackOrderRows = null
        if (trackOrderStartRows.isNotEmpty()) tracksAdapter.submitList(trackOrderStartRows)
        trackOrderStartRows = emptyList()
        updateSelectionToolbar()
    }

    private fun finishTrackOrderEditing() {
        val playlist = currentState as? ShippyCollectionDetailState.Playlist ?: return
        trackDragHelper?.attachToRecyclerView(null)
        trackDragAttached = false
        trackOrderEditing = false
        val reorderedRows = pendingTrackOrderRows ?: tracksAdapter.currentList.toList()
        pendingTrackOrderRows = null
        trackOrderStartRows = emptyList()
        if (reorderedRows.map { it.track.id } != currentState?.rows?.map { it.track.id }) {
            model.reorderPlaylistTracks(playlist.playlist.id, playlist.trackIds, reorderedRows)
        }
        updateSelectionToolbar()
    }

    private fun confirmBatchMutation(titleRes: Int, messageRes: Int, mutation: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(getString(messageRes, selectedTrackIds.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_confirm) { _, _ -> mutation() }
            .show()
    }

    private fun continueLocalDelete() {
        while (true) {
            val track = pendingLocalDeletes.getOrNull(pendingLocalDeleteIndex)
            if (track == null) {
                model.clearTrackSelection()
                if (completedLocalDeletes > 0) musicModel.rescan()
                if (failedLocalDeletes > 0) {
                    showBatchFailure(failedLocalDeletes)
                }
                pendingLocalDeletes = emptyList()
                return
            }
            when (val result = localMediaDeletion.delete(track)) {
                LocalMediaDeletionResult.Deleted -> completedLocalDeletes++
                is LocalMediaDeletionResult.ConsentRequired -> {
                    localDeleteConsent.launch(result.request)
                    return
                }
                LocalMediaDeletionResult.MissingLocalObject,
                LocalMediaDeletionResult.Failed -> failedLocalDeletes++
            }
            pendingLocalDeleteIndex++
        }
    }

    private fun showBatchFailure(count: Int) {
        Toast.makeText(
                requireContext(),
                getString(R.string.msg_batch_failed, count),
                Toast.LENGTH_SHORT,
            )
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
