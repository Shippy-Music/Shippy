/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryArtistDetailFragment.kt is part of Auxio.
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
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import app.shippy.core.identity.ArtistId
import app.shippy.core.identity.RecordingId
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.db.view.LibrarySongRowView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16LibraryArtistDetailBinding
import org.oxycblt.auxio.shippy.r16.browser.R16MediaBrowserContract

/** Paged artist recordings with composite artist+recording playback identity. */
@AndroidEntryPoint
internal class R16LibraryArtistDetailFragment :
    Fragment(R.layout.fragment_r16_library_artist_detail) {
    private val model: R16LibraryArtistDetailViewModel by viewModels()
    private val artistId: ArtistId by lazy {
        ArtistId(requireArguments().getString(ARG_ARTIST_ID).orEmpty())
    }
    private val artistName: String by lazy {
        requireArguments().getString(ARG_ARTIST_NAME).orEmpty()
    }
    private var binding: FragmentR16LibraryArtistDetailBinding? = null
    private val adapter = R16LibrarySongPagingAdapter(::playRecording)
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
            FragmentR16LibraryArtistDetailBinding.bind(view).also { bound ->
                bound.r16ArtistDetailTitle.text = artistName
                bound.r16ArtistDetailSongs.adapter = adapter
                bound.r16ArtistDetailBack.setOnClickListener {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                bound.r16ArtistDetailPlay.setOnClickListener { playArtist() }
                bound.r16ArtistDetailShuffle.setOnClickListener { shuffleArtist() }
            }
        setActionsEnabled()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        adapter.addLoadStateListener(loadStateListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.songs(artistId).collectLatest(adapter::submitData)
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
        setActionsEnabled()
        super.onStop()
    }

    override fun onDestroyView() {
        adapter.removeLoadStateListener(loadStateListener)
        binding?.r16ArtistDetailSongs?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun playArtist() {
        val mediaId = R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Artist(artistId))
        mediaController?.transportControls?.playFromMediaId(mediaId, null)
    }

    private fun shuffleArtist() {
        val controls = mediaController?.transportControls ?: return
        controls.playFromMediaId(
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Artist(artistId)),
            bundleOf(R16MediaBrowserContract.EXTRA_SHUFFLE_SEED to System.nanoTime()),
        )
    }

    private fun playRecording(row: LibrarySongRowView) {
        val mediaId =
            R16MediaBrowserIdCodec.encode(
                R16MediaBrowserId.ArtistRecording(artistId, RecordingId(row.recordingId))
            )
        mediaController?.transportControls?.playFromMediaId(mediaId, null)
    }

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                mediaController = MediaControllerCompat(requireContext(), browser.sessionToken)
                adapter.playbackAvailable = true
                setActionsEnabled()
            }

            override fun onConnectionSuspended() = disablePlayback()

            override fun onConnectionFailed() = disablePlayback()
        }

    private fun disablePlayback() {
        mediaController = null
        adapter.playbackAvailable = false
        setActionsEnabled()
    }

    private fun renderLoadState(states: CombinedLoadStates) {
        val refresh = states.refresh
        val empty = refresh is LoadState.NotLoading && adapter.itemCount == 0
        binding?.apply {
            r16ArtistDetailSongs.visibility = if (empty) View.GONE else View.VISIBLE
            r16ArtistDetailEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        }
        setActionsEnabled()
    }

    private fun setActionsEnabled() {
        val enabled = mediaController != null && adapter.itemCount > 0
        binding?.apply {
            r16ArtistDetailPlay.isEnabled = enabled
            r16ArtistDetailShuffle.isEnabled = enabled
        }
    }

    companion object {
        private const val ARG_ARTIST_ID = "artist_id"
        private const val ARG_ARTIST_NAME = "artist_name"

        fun newInstance(artistId: String, artistName: String) =
            R16LibraryArtistDetailFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_ARTIST_ID, artistId)
                        putString(ARG_ARTIST_NAME, artistName)
                    }
            }
    }
}
