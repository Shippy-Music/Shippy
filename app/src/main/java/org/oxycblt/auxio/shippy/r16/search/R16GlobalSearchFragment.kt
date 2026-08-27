/*
 * Copyright (c) 2026 Auxio Project
 * R16GlobalSearchFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.search

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16GlobalSearchBinding

/**
 * Visible global search entry in the ACTIVE host; canonical local results remain above providers.
 */
@AndroidEntryPoint
class R16GlobalSearchFragment : Fragment(R.layout.fragment_r16_global_search) {
    private val model: R16GlobalSearchViewModel by viewModels()
    private var binding: FragmentR16GlobalSearchBinding? = null
    private val localAdapter = R16GlobalSearchRowAdapter(::playCanonical)
    private val providerAdapter by
        lazy(LazyThreadSafetyMode.NONE) {
            R16ProviderSearchSectionAdapter(model::selectProvider) { model.retryProvider(it.id) }
        }
    private val resultsAdapter by
        lazy(LazyThreadSafetyMode.NONE) { ConcatAdapter(localAdapter, providerAdapter) }
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16GlobalSearchBinding.bind(view).also { bound ->
                bound.r16SearchLocalResults.adapter = resultsAdapter
                bound.r16SearchInput.doAfterTextChanged(model::submitQuery)
                arguments?.getString(ARG_INITIAL_QUERY)?.let { query ->
                    bound.r16SearchInput.setText(query)
                    bound.r16SearchInput.setSelection(query.length)
                }
                bound.r16SearchClose.setOnClickListener { parentFragmentManager.popBackStack() }
            }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.playRequests.collect(::playMediaId)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (mediaBrowser == null) {
            mediaBrowser =
                MediaBrowserCompat(
                        requireContext(),
                        ComponentName(requireContext(), AuxioService::class.java),
                        browserConnection,
                        null,
                    )
                    .also(MediaBrowserCompat::connect)
        }
    }

    override fun onStop() {
        localAdapter.playbackAvailable = false
        providerAdapter.playbackAvailable = false
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onStop()
    }

    override fun onDestroyView() {
        binding?.r16SearchLocalResults?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun render(state: R16GlobalSearchState) {
        localAdapter.submitList(
            state.localResults.map {
                R16GlobalSearchRow(
                    stableId = "recording:${it.recordingId.value}",
                    title = it.title,
                    subtitle = it.artistDisplay,
                    artwork = it.artworkLocation,
                    recordingId = it.recordingId.value,
                )
            }
        )
        providerAdapter.submitSections(state.providers)
    }

    private fun playCanonical(row: R16GlobalSearchRow) {
        row.recordingId?.let { recordingId -> playMediaId("r16:recording:$recordingId") }
    }

    private fun playMediaId(mediaId: String) {
        mediaController?.transportControls?.playFromMediaId(mediaId, null)
    }

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                mediaController = MediaControllerCompat(requireContext(), browser.sessionToken)
                localAdapter.playbackAvailable = true
                providerAdapter.playbackAvailable = true
            }

            override fun onConnectionSuspended() {
                mediaController = null
                localAdapter.playbackAvailable = false
                providerAdapter.playbackAvailable = false
            }

            override fun onConnectionFailed() = onConnectionSuspended()
        }

    companion object {
        private const val ARG_INITIAL_QUERY = "r16_global_search_initial_query"

        fun newInstance(query: String): R16GlobalSearchFragment =
            R16GlobalSearchFragment().apply { arguments = bundleOf(ARG_INITIAL_QUERY to query) }

        fun forRecommendation(title: String, artist: String): R16GlobalSearchFragment =
            newInstance(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "))
    }
}
