/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryAlbumsFragment.kt is part of Auxio.
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
import androidx.recyclerview.widget.LinearLayoutManager
import app.shippy.data.db.view.AlbumLibrarySummaryRow
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16LibraryAlbumsBinding
import org.oxycblt.auxio.pushR16Destination

@AndroidEntryPoint
internal class R16LibraryAlbumsFragment : Fragment(R.layout.fragment_r16_library_albums) {
    private val model: R16LibraryAlbumsViewModel by viewModels()
    private var binding: FragmentR16LibraryAlbumsBinding? = null
    private val adapter = R16LibraryAlbumPagingAdapter(::openAlbum)
    private val loadStateListener: (CombinedLoadStates) -> Unit = ::renderLoadState

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16LibraryAlbumsBinding.bind(view).also { bound ->
                bound.r16AlbumsList.layoutManager = LinearLayoutManager(requireContext())
                bound.r16AlbumsList.adapter = adapter
            }
        adapter.addLoadStateListener(loadStateListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.albums.collectLatest(adapter::submitData)
            }
        }
    }

    override fun onDestroyView() {
        adapter.removeLoadStateListener(loadStateListener)
        binding?.r16AlbumsList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun openAlbum(summary: AlbumLibrarySummaryRow) {
        pushR16Destination(
            R16LibraryAlbumDetailFragment.newInstance(
                releaseId = summary.releaseId,
                albumTitle = summary.canonicalTitle,
            ),
            "r16-album-detail",
        )
    }

    private fun renderLoadState(states: CombinedLoadStates) {
        val refresh = states.refresh
        val empty = refresh is LoadState.NotLoading && adapter.itemCount == 0
        binding?.apply {
            r16AlbumsList.isGone = empty
            r16AlbumsEmpty.isGone = !empty
        }
    }
}
