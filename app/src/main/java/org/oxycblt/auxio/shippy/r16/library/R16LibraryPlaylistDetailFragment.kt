/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryPlaylistDetailFragment.kt is part of Auxio.
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

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.library.PlaylistSort
import app.shippy.core.library.PlaylistSortDirection
import app.shippy.core.library.PlaylistSortMode
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.db.view.PlaylistEntryRowView
import app.shippy.data.library.R16PlaylistEntryMoveDirection
import app.shippy.data.library.R16PlaylistEntryMoveResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16LibraryPlaylistDetailBinding
import org.oxycblt.auxio.shippy.r16.browser.R16MediaBrowserContract

/** Paged playlist detail with strict canonical MediaController commands. */
@AndroidEntryPoint
internal class R16LibraryPlaylistDetailFragment :
    Fragment(R.layout.fragment_r16_library_playlist_detail) {
    private val model: R16LibraryPlaylistDetailViewModel by viewModels()
    private val playlistId: PlaylistId by lazy {
        PlaylistId(requireArguments().getString(ARG_PLAYLIST_ID).orEmpty())
    }
    private var binding: FragmentR16LibraryPlaylistDetailBinding? = null
    private val adapter =
        R16LibraryPlaylistEntryPagingAdapter(
            ::playRecording,
            ::showRemoveEntryDialog,
            ::movePlaylistEntry,
            ::toggleSelectEntry,
            ::onLongClickEntry,
        )
    private val loadStateListener: (CombinedLoadStates) -> Unit = ::renderLoadState
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private var playbackAvailable = false
    private var rowsReady = false
    private var filtering = false
    private var customOrder = false
    private var currentSort = PlaylistSort()
    private var editOrderMode = false
    private var currentPlaylistName: String? = null
    private val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.setFragmentResultListener(R16PlaylistSortSheet.RESULT, this) {
            _,
            result ->
            val mode =
                result
                    .getString(R16PlaylistSortSheet.KEY_MODE)
                    ?.let(PlaylistSortMode::fromWireOrNull) ?: return@setFragmentResultListener
            val direction =
                result
                    .getString(R16PlaylistSortSheet.KEY_DIRECTION)
                    ?.let(PlaylistSortDirection::fromWireOrNull) ?: return@setFragmentResultListener
            model.setPlaylistSort(playlistId, PlaylistSort(mode, direction))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16LibraryPlaylistDetailBinding.bind(view).also { bound ->
                bound.r16PlaylistDetailEntries.adapter = adapter
                bound.r16PlaylistDetailBack.setOnClickListener { handleBackNavigation() }
                bound.r16PlaylistDetailPlay.setOnClickListener { playPlaylist() }
                bound.r16PlaylistDetailShuffle.setOnClickListener { shufflePlaylist() }
                bound.r16PlaylistDetailSort.setOnClickListener { showSortDialog() }
                bound.r16PlaylistDetailEditOrder.setOnClickListener {
                    model.setEditOrderMode(!editOrderMode)
                }
                bound.r16PlaylistDetailRename.setOnClickListener { showRenameDialog() }
                bound.r16PlaylistDetailDelete.setOnClickListener { showDeleteDialog() }
                bound.r16PlaylistDetailDeleteSelected.setOnClickListener {
                    showDeleteSelectedDialog()
                }
                bound.r16PlaylistDetailMoveSelectedTop.setOnClickListener {
                    moveSelectedEntries(moveBefore = true)
                }
                bound.r16PlaylistDetailMoveSelectedBottom.setOnClickListener {
                    moveSelectedEntries(moveBefore = false)
                }
                bound.r16PlaylistDetailClearSelection.setOnClickListener { model.clearSelection() }
                bound.r16PlaylistDetailTitle.setText(R.string.r16_playlist_default_title)
                bound.r16PlaylistDetailInput.doAfterTextChanged(model::updateSearchQuery)
                bound.r16PlaylistDetailInput.setOnEditorActionListener(::onSearchEditorAction)
            }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        setAllActionsEnabled()
        adapter.addLoadStateListener(loadStateListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.entries(playlistId).collectLatest(adapter::submitData)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.summary(playlistId).collectLatest(::renderSummary)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.isFiltering.collectLatest {
                    filtering = it
                    setAllActionsEnabled()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.selectedEntryIds.collectLatest { selected ->
                    adapter.selectedEntryIds = selected.map(PlaylistEntryId::value).toSet()
                    binding?.apply {
                        if (selected.isNotEmpty()) {
                            r16PlaylistDetailSelectionBar.visibility = View.VISIBLE
                            r16PlaylistDetailSelectionCount.text =
                                getString(R.string.r16_playlist_selection_count, selected.size)
                        } else {
                            r16PlaylistDetailSelectionBar.visibility = View.GONE
                        }
                    }
                    setSelectionActionsEnabled()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.lifecycleInFlight.collect(::renderLifecycleInFlight)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.lifecycleCompletion.collect { completion ->
                    completion ?: return@collect
                    renderLifecycleEffect(completion.effect)
                    model.acknowledgeLifecycleCompletion(completion.operationId)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.entryRemovalsInFlight.collect { removing ->
                    adapter.entryRemovalsInFlight = removing.map(PlaylistEntryId::value).toSet()
                    setEditOrderActionEnabled()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.entryRemovalCompletions.collect { completions ->
                    completions.values.forEach(::renderEntryRemovalCompletion)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.editOrderMode.collect(::renderEditOrderMode)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.entryMoveInFlight.collect(::renderEntryMoveInFlight)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.entryMoveCompletions.collect { completions ->
                    completions.values.forEach(::renderEntryMoveCompletion)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        backCallback.isEnabled = true
        if (mediaBrowser != null) return
        mediaBrowser =
            MediaBrowserCompat(
                    requireContext(),
                    ComponentName(requireContext(), AuxioService::class.java),
                    browserConnection,
                    null,
                )
                .also(MediaBrowserCompat::connect)
    }

    override fun onPause() {
        backCallback.isEnabled = false
        adapter.playbackAvailable = false
        playbackAvailable = false
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        setAllActionsEnabled()
        super.onPause()
    }

    override fun onDestroyView() {
        adapter.removeLoadStateListener(loadStateListener)
        binding?.r16PlaylistDetailEntries?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun renderSummary(summary: app.shippy.data.browser.R16MediaBrowserPlaylistSummary?) {
        currentPlaylistName = summary?.name
        currentSort = summary?.displaySort ?: PlaylistSort()
        customOrder = currentSort.isCustom
        if (!customOrder && editOrderMode) model.setEditOrderMode(false)
        binding?.apply {
            r16PlaylistDetailTitle.text =
                summary?.name ?: getString(R.string.r16_playlist_default_title)
            r16PlaylistDetailMeta.text =
                summary?.let {
                    val tracks = getString(R.string.r16_playlist_track_count, it.entryCount)
                    if (it.pinned) {
                        getString(R.string.r16_playlist_pinned_meta, tracks)
                    } else {
                        tracks
                    }
                } ?: ""
        }
        setAllActionsEnabled()
    }

    private fun playPlaylist() {
        if (editOrderMode || !rowsReady) return
        val mediaId = R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Playlist(playlistId))
        mediaController?.transportControls?.playFromMediaId(mediaId, playlistPlaybackExtras())
    }

    private fun shufflePlaylist() {
        if (editOrderMode || !rowsReady) return
        val controls = mediaController?.transportControls ?: return
        controls.playFromMediaId(
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Playlist(playlistId)),
            playlistPlaybackExtras(shuffleSeed = System.nanoTime()),
        )
    }

    /** Entry taps retain the exact canonical occurrence, never a provider/source URL. */
    private fun playRecording(entry: PlaylistEntryRowView) {
        if (!rowsReady) return
        val mediaId =
            R16MediaBrowserIdCodec.encode(
                R16MediaBrowserId.PlaylistEntry(PlaylistEntryId(entry.playlistEntryId))
            )
        mediaController?.transportControls?.playFromMediaId(mediaId, playlistPlaybackExtras())
    }

    private fun playlistPlaybackExtras(shuffleSeed: Long? = null): Bundle {
        val context = model.playbackContext()
        return Bundle().apply {
            context.search?.let { putString(R16MediaBrowserContract.EXTRA_PLAYLIST_FILTER, it) }
            putString(R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_MODE, context.sort.mode.wireValue)
            putString(
                R16MediaBrowserContract.EXTRA_PLAYLIST_SORT_DIRECTION,
                context.sort.direction.wireValue,
            )
            shuffleSeed?.let { putLong(R16MediaBrowserContract.EXTRA_SHUFFLE_SEED, it) }
        }
    }

    private fun showSortDialog() {
        if (editOrderMode || currentPlaylistName == null) return
        R16PlaylistSortSheet.show(parentFragmentManager, currentSort)
    }

    private fun toggleSelectEntry(entry: PlaylistEntryRowView) {
        model.toggleSelect(PlaylistEntryId(entry.playlistEntryId))
    }

    private fun onLongClickEntry(entry: PlaylistEntryRowView): Boolean {
        model.toggleSelect(PlaylistEntryId(entry.playlistEntryId))
        return true
    }

    private fun showDeleteSelectedDialog() {
        val count = model.selectedEntryIds.value.size
        if (count == 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_remove_from_playlist)
            .setMessage(getString(R.string.r16_playlist_delete_selected_confirm, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_remove_from_playlist) { _, _ ->
                model.removeSelectedEntries(playlistId)
            }
            .show()
    }

    /** Paging owns the visible canonical order; selection itself is occurrence identity. */
    private fun moveSelectedEntries(moveBefore: Boolean) {
        model.moveSelectedEntries(
            playlistId = playlistId,
            targetAnchorEntryId = null,
            moveBefore = moveBefore,
            selectedIdsInOrder =
                adapter.snapshot().items.map { entry -> PlaylistEntryId(entry.playlistEntryId) },
        )
    }

    private fun showRemoveEntryDialog(entry: PlaylistEntryRowView) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_remove_from_playlist)
            .setMessage(getString(R.string.r16_playlist_remove_entry_confirmation, entry.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_remove_from_playlist) { _, _ ->
                model.removePlaylistEntry(playlistId, PlaylistEntryId(entry.playlistEntryId))
            }
            .show()
    }

    private fun movePlaylistEntry(
        entry: PlaylistEntryRowView,
        direction: R16PlaylistEntryMoveDirection,
    ) {
        model.movePlaylistEntry(playlistId, PlaylistEntryId(entry.playlistEntryId), direction)
    }

    private fun renderEntryRemovalCompletion(completion: R16PlaylistEntryRemovalCompletion) {
        if (completion.removed) {
            model.refreshSummary()
        } else {
            binding?.apply {
                r16PlaylistDetailStatus.isGone = false
                r16PlaylistDetailStatus.text = getString(R.string.r16_playlist_remove_entry_failed)
            }
        }
        model.acknowledgeEntryRemovalCompletion(completion.operationId)
    }

    private fun renderEditOrderMode(enabled: Boolean) {
        editOrderMode = enabled
        adapter.editOrderMode = enabled
        binding
            ?.r16PlaylistDetailEditOrder
            ?.setText(
                if (enabled) R.string.r16_playlist_edit_order_done
                else R.string.r16_playlist_edit_order
            )
        setAllActionsEnabled()
    }

    private fun renderEntryMoveInFlight(entryId: PlaylistEntryId?) {
        adapter.entryMoveInFlight = entryId?.value
        if (entryId != null) {
            binding?.apply {
                r16PlaylistDetailStatus.isGone = false
                r16PlaylistDetailStatus.text = getString(R.string.r16_playlist_reordering)
            }
        }
        setAllActionsEnabled()
    }

    private fun renderEntryMoveCompletion(completion: R16PlaylistEntryMoveCompletion) {
        val message =
            when (completion.result) {
                R16PlaylistEntryMoveResult.Moved -> R.string.r16_playlist_order_updated
                R16PlaylistEntryMoveResult.AtBoundary ->
                    when (completion.direction) {
                        R16PlaylistEntryMoveDirection.TOWARD_START ->
                            R.string.r16_playlist_already_first
                        R16PlaylistEntryMoveDirection.TOWARD_END ->
                            R.string.r16_playlist_already_last
                    }
                R16PlaylistEntryMoveResult.NotFound -> R.string.r16_playlist_reorder_entry_missing
                R16PlaylistEntryMoveResult.NotCustomOrder ->
                    R.string.r16_playlist_reorder_custom_only
                R16PlaylistEntryMoveResult.Failed -> R.string.r16_playlist_reorder_failed
            }
        binding?.apply {
            r16PlaylistDetailStatus.isGone = false
            r16PlaylistDetailStatus.text = getString(message)
        }
        model.acknowledgeEntryMoveCompletion(completion.operationId)
    }

    private fun renderLoadState(states: CombinedLoadStates) {
        val refresh = states.refresh
        rowsReady = refresh is LoadState.NotLoading
        setActionsEnabled()
        val empty = refresh is LoadState.NotLoading && adapter.itemCount == 0
        binding?.apply {
            r16PlaylistDetailEntries.isGone = empty
            r16PlaylistDetailEmpty.isGone = !empty
        }
    }

    private fun setActionsEnabled() {
        binding?.apply {
            val enabled = playbackAvailable && rowsReady && adapter.itemCount > 0 && !editOrderMode
            r16PlaylistDetailPlay.isEnabled = enabled
            r16PlaylistDetailShuffle.isEnabled = enabled
        }
    }

    private fun setLifecycleActionsEnabled() {
        binding?.apply {
            val enabled =
                currentPlaylistName != null &&
                    !model.lifecycleInFlight.value &&
                    model.entryMoveInFlight.value == null &&
                    !editOrderMode
            r16PlaylistDetailRename.isEnabled = enabled
            r16PlaylistDetailDelete.isEnabled = enabled
        }
    }

    private fun setEditOrderActionEnabled() {
        binding?.apply {
            val moveIdle = model.entryMoveInFlight.value == null
            val removalIdle = model.entryRemovalsInFlight.value.isEmpty()
            r16PlaylistDetailEditOrder.isEnabled =
                if (editOrderMode) {
                    moveIdle
                } else {
                    currentPlaylistName != null &&
                        customOrder &&
                        !filtering &&
                        !model.lifecycleInFlight.value &&
                        moveIdle &&
                        removalIdle
                }
            r16PlaylistDetailInput.isEnabled = !editOrderMode && moveIdle
            r16PlaylistDetailSort.isEnabled = currentPlaylistName != null && !editOrderMode
        }
    }

    private fun handleBackNavigation() {
        if (model.selectedEntryIds.value.isNotEmpty()) {
            model.clearSelection()
            return
        }
        if (editOrderMode) {
            model.setEditOrderMode(false)
            return
        }
        val input = binding?.r16PlaylistDetailInput
        if (input != null && input.text?.isNotBlank() == true) {
            input.text?.clear()
            input.requestFocus()
            return
        }
        backCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun setAllActionsEnabled() {
        setActionsEnabled()
        setLifecycleActionsEnabled()
        setEditOrderActionEnabled()
        setSelectionActionsEnabled()
    }

    private fun setSelectionActionsEnabled() {
        binding?.apply {
            val hasSelection = model.selectedEntryIds.value.isNotEmpty()
            val moveEnabled =
                hasSelection &&
                    editOrderMode &&
                    !filtering &&
                    model.entryMoveInFlight.value == null &&
                    model.entryRemovalsInFlight.value.isEmpty()
            r16PlaylistDetailMoveSelectedTop.isEnabled = moveEnabled
            r16PlaylistDetailMoveSelectedBottom.isEnabled = moveEnabled
        }
    }

    private fun showRenameDialog() {
        val name = currentPlaylistName ?: return
        val input =
            EditText(requireContext()).apply {
                setText(name)
                selectAll()
                hint = getString(R.string.r16_playlist_name_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine()
            }
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.r16_playlist_rename)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.r16_playlist_rename, null)
                .create()
        dialog.setOnShowListener {
            dialog
                .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    if (input.text.toString().trim().isEmpty()) {
                        input.error = getString(R.string.r16_playlist_name_required)
                        input.requestFocus()
                    } else {
                        model.renamePlaylist(playlistId, input.text.toString())
                        dialog.dismiss()
                    }
                }
        }
        dialog.show()
    }

    private fun showDeleteDialog() {
        val name = currentPlaylistName ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.r16_playlist_delete)
            .setMessage(getString(R.string.r16_playlist_delete_confirmation, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.r16_playlist_delete) { _, _ ->
                model.deletePlaylist(playlistId)
            }
            .show()
    }

    private fun renderLifecycleInFlight(inFlight: Boolean) {
        binding?.apply {
            if (inFlight) {
                r16PlaylistDetailStatus.isGone = false
                r16PlaylistDetailStatus.text = getString(R.string.r16_playlist_saving)
            }
        }
        setAllActionsEnabled()
    }

    private fun renderLifecycleEffect(effect: R16PlaylistLifecycleEffect) {
        when (effect) {
            is R16PlaylistLifecycleEffect.Created -> Unit
            R16PlaylistLifecycleEffect.Updated -> {
                binding?.apply {
                    r16PlaylistDetailStatus.isGone = false
                    r16PlaylistDetailStatus.text = getString(R.string.r16_playlist_saved)
                }
                model.refreshSummary()
            }
            R16PlaylistLifecycleEffect.Deleted -> parentFragmentManager.popBackStack()
            is R16PlaylistLifecycleEffect.Failure -> {
                binding?.apply {
                    r16PlaylistDetailStatus.isGone = false
                    r16PlaylistDetailStatus.text = getString(effect.reason.messageRes)
                }
            }
        }
    }

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                mediaController = MediaControllerCompat(requireContext(), browser.sessionToken)
                playbackAvailable = true
                adapter.playbackAvailable = true
                setAllActionsEnabled()
            }

            override fun onConnectionSuspended() = onConnectionFailed()

            override fun onConnectionFailed() {
                mediaController = null
                playbackAvailable = false
                adapter.playbackAvailable = false
                setAllActionsEnabled()
            }
        }

    private fun onSearchEditorAction(view: TextView, actionId: Int, event: KeyEvent?): Boolean {
        if (
            actionId != EditorInfo.IME_ACTION_SEARCH &&
                (event?.keyCode != KeyEvent.KEYCODE_ENTER || event.action != KeyEvent.ACTION_DOWN)
        ) {
            return false
        }
        ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
        view.clearFocus()
        return true
    }

    companion object {
        private const val ARG_PLAYLIST_ID = "r16_playlist_id"

        fun newInstance(playlistId: String): R16LibraryPlaylistDetailFragment =
            R16LibraryPlaylistDetailFragment().apply {
                arguments = bundleOf(ARG_PLAYLIST_ID to playlistId)
            }
    }
}
