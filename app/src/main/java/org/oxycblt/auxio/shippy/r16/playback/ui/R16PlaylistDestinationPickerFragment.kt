/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaylistDestinationPickerFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import app.shippy.core.identity.PlaylistId
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16PlaylistDestinationPickerBinding

/** Picks one canonical playlist for the RecordingId captured before this screen opened. */
@AndroidEntryPoint
internal class R16PlaylistDestinationPickerFragment :
    Fragment(R.layout.fragment_r16_playlist_destination_picker) {
    private val model: R16PlaylistDestinationPickerViewModel by viewModels()
    private var binding: FragmentR16PlaylistDestinationPickerBinding? = null
    private val adapter = R16PlaylistDestinationPagingAdapter(model::togglePlaylistMembership)
    private val loadStateListener: (CombinedLoadStates) -> Unit = ::renderLoadState
    private var showingLoadStatus = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!model.hasCapturedIdentity()) {
            parentFragmentManager.popBackStack()
            return
        }
        binding =
            FragmentR16PlaylistDestinationPickerBinding.bind(view).also { bound ->
                bound.r16PlaylistDestinationBack.setOnClickListener {
                    parentFragmentManager.popBackStack()
                }
                bound.r16PlaylistDestinationRetry.setOnClickListener { adapter.retry() }
                bound.r16PlaylistDestinationTarget.text =
                    getString(
                        R.string.r16_playlist_destination_target,
                        model.capturedTitle,
                        model.capturedArtist,
                    )
                bound.r16PlaylistDestinationLikedRow.setOnClickListener { model.toggleLiked() }
                bound.r16PlaylistDestinationList.adapter = adapter
            }
        adapter.addLoadStateListener(loadStateListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.playlists.collectLatest(adapter::submitData)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.liked.collectLatest { isLiked ->
                    binding?.apply {
                        r16PlaylistDestinationLikedIcon.setImageResource(
                            if (isLiked) R.drawable.ic_favorite_24
                            else R.drawable.ic_favorite_border_24
                        )
                        r16PlaylistDestinationLikedStatus.setText(
                            if (isLiked) R.string.r16_playlist_destination_liked_saved
                            else R.string.r16_playlist_destination_liked_unsaved
                        )
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.memberships.collectLatest { memberships -> adapter.memberships = memberships }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.inFlight.collect(::renderInFlight)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.completion.collect { completion ->
                    completion ?: return@collect
                    renderCompletion(completion)
                    model.acknowledgeCompletion(completion.operationId)
                }
            }
        }
    }

    override fun onDestroyView() {
        adapter.removeLoadStateListener(loadStateListener)
        binding?.r16PlaylistDestinationList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun renderLoadState(states: CombinedLoadStates) {
        val refresh = states.refresh
        binding?.apply {
            r16PlaylistDestinationEmpty.isGone =
                refresh !is LoadState.NotLoading || adapter.itemCount != 0
            if (model.inFlight.value.isNotEmpty()) return@apply
            when (refresh) {
                is LoadState.Loading -> {
                    showingLoadStatus = true
                    r16PlaylistDestinationStatus.isGone = false
                    r16PlaylistDestinationStatus.setText(R.string.r16_playlist_destination_loading)
                }
                is LoadState.Error -> {
                    showingLoadStatus = true
                    r16PlaylistDestinationStatus.isGone = false
                    r16PlaylistDestinationStatus.setText(
                        R.string.r16_playlist_destination_load_failed
                    )
                    r16PlaylistDestinationRetry.isGone = false
                }
                is LoadState.NotLoading -> {
                    if (showingLoadStatus) {
                        showingLoadStatus = false
                        r16PlaylistDestinationStatus.isGone = true
                        r16PlaylistDestinationRetry.isGone = true
                    }
                }
            }
        }
    }

    private fun renderInFlight(inFlight: Set<PlaylistId>) {
        adapter.setInFlight(inFlight)
        if (inFlight.isEmpty()) return
        binding?.apply {
            r16PlaylistDestinationStatus.isGone = false
            r16PlaylistDestinationStatus.setText(R.string.r16_playlist_destination_adding)
            r16PlaylistDestinationRetry.isGone = true
        }
    }

    private fun renderCompletion(completion: R16PlaylistDestinationCompletion) {
        if (completion.isRemoval) {
            if (completion.success) {
                binding?.apply {
                    r16PlaylistDestinationStatus.isGone = false
                    r16PlaylistDestinationStatus.setText(R.string.r16_playlist_destination_removed)
                }
            } else {
                showFailure(R.string.r16_playlist_action_failed)
            }
            return
        }
        when (completion.result) {
            is app.shippy.data.library.R16PlaylistEntryAddResult.Added -> {
                binding?.apply {
                    r16PlaylistDestinationStatus.isGone = false
                    r16PlaylistDestinationStatus.setText(R.string.r16_playlist_destination_added)
                }
            }
            app.shippy.data.library.R16PlaylistEntryAddResult.PlaylistNotFound ->
                showFailure(R.string.r16_playlist_not_found)
            app.shippy.data.library.R16PlaylistEntryAddResult.RecordingNotFound ->
                showFailure(R.string.r16_playlist_destination_recording_missing)
            app.shippy.data.library.R16PlaylistEntryAddResult.Failed ->
                showFailure(R.string.r16_playlist_action_failed)
            null -> Unit
        }
    }

    private fun showFailure(message: Int) {
        binding?.apply {
            r16PlaylistDestinationStatus.isGone = false
            r16PlaylistDestinationStatus.setText(message)
        }
    }

    internal companion object {
        const val RESULT_KEY = "r16_playlist_destination_result"
        const val RESULT_MESSAGE = "message"

        fun newInstance(
            recordingId: String,
            queueEntryId: String,
            title: CharSequence,
            artist: CharSequence,
        ) =
            R16PlaylistDestinationPickerFragment().apply {
                arguments =
                    bundleOf(
                        R16PlaylistDestinationPickerViewModel.ARG_RECORDING_ID to recordingId,
                        R16PlaylistDestinationPickerViewModel.ARG_QUEUE_ENTRY_ID to queueEntryId,
                        R16PlaylistDestinationPickerViewModel.ARG_TITLE to title.toString(),
                        R16PlaylistDestinationPickerViewModel.ARG_ARTIST to artist.toString(),
                    )
            }
    }
}
