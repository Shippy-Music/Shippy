/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryPlaylistsFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import app.shippy.data.browser.R16MediaBrowserPlaylistSummary
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16LibraryPlaylistsBinding
import org.oxycblt.auxio.pushR16Destination

/** Canonical Library > Playlists browse page. */
@AndroidEntryPoint
internal class R16LibraryPlaylistsFragment : Fragment(R.layout.fragment_r16_library_playlists) {
    private val model: R16LibraryPlaylistsViewModel by viewModels()
    private var binding: FragmentR16LibraryPlaylistsBinding? = null
    private val adapter = R16LibraryPlaylistPagingAdapter(::openPlaylist, model::togglePinned)
    private val loadStateListener: (CombinedLoadStates) -> Unit = ::renderLoadState

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16LibraryPlaylistsBinding.bind(view).also { bound ->
                bound.r16LibraryPlaylists.adapter = adapter
                bound.r16LibraryPlaylistsBack.setOnClickListener {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                bound.r16LibraryPlaylistsCreate.setOnClickListener(::showCreateDialog)
            }
        adapter.addLoadStateListener(loadStateListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.playlists.collectLatest(adapter::submitData)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.pinTogglesInFlight.collect(adapter::setPinTogglesInFlight)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.pinToggleCompletions.collect { adapter.refresh() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.playlistLifecycleInFlight.collect(::renderLifecycleInFlight)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.playlistLifecycleCompletion.collect { completion ->
                    completion ?: return@collect
                    renderLifecycleEffect(completion.effect)
                    model.acknowledgePlaylistLifecycleCompletion(completion.operationId)
                }
            }
        }
    }

    override fun onDestroyView() {
        adapter.removeLoadStateListener(loadStateListener)
        binding?.r16LibraryPlaylists?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun openPlaylist(summary: R16MediaBrowserPlaylistSummary) {
        openPlaylistId(summary.playlistId.value)
    }

    private fun openPlaylistId(playlistId: String) {
        pushR16Destination(
            R16LibraryPlaylistDetailFragment.newInstance(playlistId),
            "r16-playlist-detail",
        )
    }

    private fun renderLoadState(states: CombinedLoadStates) {
        val refresh = states.refresh
        val empty = refresh is LoadState.NotLoading && adapter.itemCount == 0
        binding?.apply {
            r16LibraryPlaylists.isGone = empty
            r16LibraryPlaylistsEmpty.isGone = !empty
        }
    }

    private fun showCreateDialog(@Suppress("UNUSED_PARAMETER") ignored: View) {
        val input =
            EditText(requireContext()).apply {
                hint = getString(R.string.r16_playlist_name_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine()
            }
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.r16_playlist_create)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.r16_playlist_create, null)
                .create()
        dialog.setOnShowListener {
            dialog
                .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    if (input.text.toString().trim().isEmpty()) {
                        input.error = getString(R.string.r16_playlist_name_required)
                        input.requestFocus()
                    } else {
                        model.createPlaylist(input.text.toString())
                        dialog.dismiss()
                    }
                }
        }
        dialog.show()
    }

    private fun renderLifecycleInFlight(inFlight: Boolean) {
        binding?.apply {
            r16LibraryPlaylistsCreate.isEnabled = !inFlight
            if (inFlight) {
                r16LibraryPlaylistsStatus.isGone = false
                r16LibraryPlaylistsStatus.text = getString(R.string.r16_playlist_creating)
            }
        }
    }

    private fun renderLifecycleEffect(effect: R16PlaylistLifecycleEffect) {
        when (effect) {
            is R16PlaylistLifecycleEffect.Created -> {
                adapter.refresh()
                openPlaylistId(effect.playlistId.value)
            }
            R16PlaylistLifecycleEffect.Updated,
            R16PlaylistLifecycleEffect.Deleted -> Unit
            is R16PlaylistLifecycleEffect.Failure -> renderLifecycleFailure(effect.reason)
        }
    }

    private fun renderLifecycleFailure(reason: R16PlaylistLifecycleFailure) {
        binding?.apply {
            r16LibraryPlaylistsStatus.isGone = false
            r16LibraryPlaylistsStatus.text = getString(reason.messageRes)
        }
    }
}

internal val R16PlaylistLifecycleFailure.messageRes: Int
    get() =
        when (this) {
            R16PlaylistLifecycleFailure.INVALID_NAME -> R.string.r16_playlist_name_required
            R16PlaylistLifecycleFailure.NOT_FOUND -> R.string.r16_playlist_not_found
            R16PlaylistLifecycleFailure.FAILED -> R.string.r16_playlist_action_failed
        }
