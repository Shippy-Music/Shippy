/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryGenreDetailFragment.kt is part of Auxio.
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
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import app.shippy.core.identity.RecordingId
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.db.view.LibrarySongRowView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16LibraryGenreDetailBinding

@AndroidEntryPoint
internal class R16LibraryGenreDetailFragment :
    Fragment(R.layout.fragment_r16_library_genre_detail) {
    private val model: R16LibraryGenreDetailViewModel by viewModels()
    private val genre: String by lazy { requireArguments().getString(ARG_GENRE).orEmpty() }
    private var binding: FragmentR16LibraryGenreDetailBinding? = null
    private val adapter = R16LibrarySongPagingAdapter(::playSong)
    private val loadStateListener: (CombinedLoadStates) -> Unit = ::renderLoadState
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16LibraryGenreDetailBinding.bind(view).also { bound ->
                bound.r16GenreDetailTitle.text = genre
                bound.r16GenreDetailSongs.adapter = adapter
                bound.r16GenreDetailBack.setOnClickListener {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        adapter.addLoadStateListener(loadStateListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.songs(genre).collectLatest(adapter::submitData)
            }
        }
    }

    override fun onStart() {
        super.onStart()
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

    override fun onStop() {
        adapter.playbackAvailable = false
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onStop()
    }

    override fun onDestroyView() {
        adapter.removeLoadStateListener(loadStateListener)
        binding?.r16GenreDetailSongs?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun playSong(song: LibrarySongRowView) {
        val mediaId =
            R16MediaBrowserIdCodec.encode(
                R16MediaBrowserId.GenreRecording(
                    genre = genre,
                    recordingId = RecordingId(song.recordingId),
                )
            )
        mediaController?.transportControls?.playFromMediaId(mediaId, null)
    }

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                mediaController = MediaControllerCompat(requireContext(), browser.sessionToken)
                adapter.playbackAvailable = true
            }

            override fun onConnectionSuspended() {
                mediaController = null
                adapter.playbackAvailable = false
            }

            override fun onConnectionFailed() {
                mediaController = null
                adapter.playbackAvailable = false
            }
        }

    private fun renderLoadState(states: CombinedLoadStates) {
        val refresh = states.refresh
        val empty = refresh is LoadState.NotLoading && adapter.itemCount == 0
        binding?.apply {
            r16GenreDetailSongs.isGone = empty
            r16GenreDetailEmpty.isGone = !empty
        }
    }

    companion object {
        private const val ARG_GENRE = "arg_genre"

        fun newInstance(genre: String): R16LibraryGenreDetailFragment =
            R16LibraryGenreDetailFragment().apply {
                arguments = Bundle().apply { putString(ARG_GENRE, genre) }
            }
    }
}
