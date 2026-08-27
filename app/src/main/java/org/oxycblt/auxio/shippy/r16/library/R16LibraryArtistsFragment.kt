/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryArtistsFragment.kt is part of Auxio.
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
import android.view.View
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import app.shippy.data.db.view.ArtistLibrarySummaryRow
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16LibraryArtistsBinding
import org.oxycblt.auxio.pushR16Destination

/** Canonical Library > Artists page, scoped to artists with current Library recordings. */
@AndroidEntryPoint
internal class R16LibraryArtistsFragment : Fragment(R.layout.fragment_r16_library_artists) {
    private val model: R16LibraryArtistsViewModel by viewModels()
    private var binding: FragmentR16LibraryArtistsBinding? = null
    private val adapter = R16LibraryArtistPagingAdapter(::openArtist)
    private val loadStateListener: (CombinedLoadStates) -> Unit = ::renderLoadState

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16LibraryArtistsBinding.bind(view).also { bound ->
                bound.r16LibraryArtists.adapter = adapter
                bound.r16LibraryArtistsBack.setOnClickListener {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        adapter.addLoadStateListener(loadStateListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.artists.collectLatest(adapter::submitData)
            }
        }
    }

    override fun onDestroyView() {
        adapter.removeLoadStateListener(loadStateListener)
        binding?.r16LibraryArtists?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun openArtist(summary: ArtistLibrarySummaryRow) {
        pushR16Destination(
            R16LibraryArtistDetailFragment.newInstance(
                artistId = summary.artistId,
                artistName = summary.canonicalName,
            ),
            "r16-artist-detail",
        )
    }

    private fun renderLoadState(states: CombinedLoadStates) {
        val refresh = states.refresh
        val empty = refresh is LoadState.NotLoading && adapter.itemCount == 0
        binding?.apply {
            r16LibraryArtists.isGone = empty
            r16LibraryArtistsEmpty.isGone = !empty
        }
    }
}
