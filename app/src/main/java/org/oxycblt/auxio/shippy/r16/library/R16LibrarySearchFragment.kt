/*
 * Copyright (c) 2026 Auxio Project
 * R16LibrarySearchFragment.kt is part of Auxio.
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
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import app.shippy.core.identity.ArtistId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.RecordingId
import app.shippy.core.library.R16SystemCollection
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.db.view.ArtistLibrarySummaryRow
import app.shippy.data.db.view.LibrarySongRowView
import app.shippy.data.db.view.PlaylistSummaryRowView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16LibrarySearchBinding
import org.oxycblt.auxio.pushR16Destination

/** Dedicated local-only search across durable R16 Library targets. It never invokes providers. */
@AndroidEntryPoint
internal class R16LibrarySearchFragment : Fragment(R.layout.fragment_r16_library_search) {
    private val model: R16LibrarySearchViewModel by viewModels()
    private var binding: FragmentR16LibrarySearchBinding? = null
    private val songHeader = R16LibrarySearchSectionHeaderAdapter(R.string.r16_library_songs)
    private val playlistHeader =
        R16LibrarySearchSectionHeaderAdapter(R.string.r16_library_playlists)
    private val artistHeader = R16LibrarySearchSectionHeaderAdapter(R.string.r16_library_artists)
    private val songAdapter = R16LibrarySongPagingAdapter(::playSong)
    private val playlistAdapter = R16LibrarySearchPlaylistPagingAdapter(::openPlaylist)
    private val artistAdapter = R16LibraryArtistPagingAdapter(::openArtist)
    private val resultAdapter =
        ConcatAdapter(
            songHeader,
            songAdapter,
            playlistHeader,
            playlistAdapter,
            artistHeader,
            artistAdapter,
        )
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private var songRefreshComplete = false
    private var playlistRefreshComplete = false
    private var artistRefreshComplete = false
    private var songRefreshError = false
    private var playlistRefreshError = false
    private var artistRefreshError = false
    private val songLoadStateListener: (CombinedLoadStates) -> Unit = {
        songRefreshComplete = it.refresh is LoadState.NotLoading
        songRefreshError = it.hasError()
        renderResultState()
    }
    private val playlistLoadStateListener: (CombinedLoadStates) -> Unit = {
        playlistRefreshComplete = it.refresh is LoadState.NotLoading
        playlistRefreshError = it.hasError()
        renderResultState()
    }
    private val artistLoadStateListener: (CombinedLoadStates) -> Unit = {
        artistRefreshComplete = it.refresh is LoadState.NotLoading
        artistRefreshError = it.hasError()
        renderResultState()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16LibrarySearchBinding.bind(view).also { bound ->
                bound.r16LibrarySearchResults.adapter = resultAdapter
                bound.r16LibrarySearchBack.setOnClickListener {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                bound.r16LibrarySearchRetry.setOnClickListener {
                    songAdapter.retry()
                    playlistAdapter.retry()
                    artistAdapter.retry()
                }
                bound.r16LibrarySearchInput.setText(model.query.value)
                bound.r16LibrarySearchInput.setSelection(model.query.value.length)
                bound.r16LibrarySearchInput.doAfterTextChanged(model::updateQuery)
                bound.r16LibrarySearchLiked.setOnClickListener {
                    openSystemCollection(R16SystemCollection.LIKED)
                }
                bound.r16LibrarySearchLocal.setOnClickListener {
                    openSystemCollection(R16SystemCollection.LOCAL)
                }
                bound.r16LibrarySearchDownloads.setOnClickListener {
                    openSystemCollection(R16SystemCollection.DOWNLOADS)
                }
            }
        songAdapter.addLoadStateListener(songLoadStateListener)
        playlistAdapter.addLoadStateListener(playlistLoadStateListener)
        artistAdapter.addLoadStateListener(artistLoadStateListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.query.collect { query ->
                    val hasQuery = query.isNotBlank()
                    songRefreshComplete = false
                    playlistRefreshComplete = false
                    artistRefreshComplete = false
                    songRefreshError = false
                    playlistRefreshError = false
                    artistRefreshError = false
                    binding?.apply {
                        r16LibrarySearchResults.isVisible = hasQuery
                        r16LibrarySearchPrompt.isGone = hasQuery
                        r16LibrarySearchLiked.isVisible =
                            collectionMatches(query, getString(R.string.lbl_liked))
                        r16LibrarySearchLocal.isVisible =
                            collectionMatches(query, getString(R.string.lbl_local))
                        r16LibrarySearchDownloads.isVisible =
                            collectionMatches(query, getString(R.string.lbl_downloads))
                        r16LibrarySearchCollectionsTitle.isVisible =
                            r16LibrarySearchLiked.isVisible ||
                                r16LibrarySearchLocal.isVisible ||
                                r16LibrarySearchDownloads.isVisible
                    }
                    renderResultState()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.songs.collectLatest(songAdapter::submitData)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.playlists.collectLatest(playlistAdapter::submitData)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.artists.collectLatest(artistAdapter::submitData)
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
        songAdapter.playbackAvailable = false
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onStop()
    }

    override fun onDestroyView() {
        songAdapter.removeLoadStateListener(songLoadStateListener)
        playlistAdapter.removeLoadStateListener(playlistLoadStateListener)
        artistAdapter.removeLoadStateListener(artistLoadStateListener)
        binding?.r16LibrarySearchResults?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun playSong(row: LibrarySongRowView) {
        mediaController
            ?.transportControls
            ?.playFromMediaId(
                R16MediaBrowserIdCodec.encode(
                    R16MediaBrowserId.Recording(RecordingId(row.recordingId))
                ),
                null,
            )
    }

    private fun openPlaylist(summary: PlaylistSummaryRowView) {
        pushR16Destination(
            R16LibraryPlaylistDetailFragment.newInstance(PlaylistId(summary.playlistId).value),
            "r16-library-search-playlist-${summary.playlistId}",
        )
    }

    private fun openArtist(summary: ArtistLibrarySummaryRow) {
        pushR16Destination(
            R16LibraryArtistDetailFragment.newInstance(
                artistId = ArtistId(summary.artistId).value,
                artistName = summary.canonicalName,
            ),
            "r16-library-search-artist-${summary.artistId}",
        )
    }

    private fun openSystemCollection(collection: R16SystemCollection) {
        pushR16Destination(
            R16SystemCollectionDetailFragment.newInstance(collection),
            "r16-library-search-${collection.id}",
        )
    }

    private fun renderResultState() {
        val hasQuery = model.query.value.isNotBlank()
        val hasCollectionMatch = hasQuery && hasVisibleCollectionMatch(model.query.value)
        val anyResult =
            songAdapter.itemCount > 0 ||
                playlistAdapter.itemCount > 0 ||
                artistAdapter.itemCount > 0
        val hasRefreshError = songRefreshError || playlistRefreshError || artistRefreshError
        songHeader.setVisible(hasQuery && songAdapter.itemCount > 0)
        playlistHeader.setVisible(hasQuery && playlistAdapter.itemCount > 0)
        artistHeader.setVisible(hasQuery && artistAdapter.itemCount > 0)
        binding?.r16LibrarySearchError?.isVisible = hasQuery && hasRefreshError
        binding?.r16LibrarySearchRetry?.isVisible = hasQuery && hasRefreshError
        binding?.r16LibrarySearchEmpty?.isVisible =
            hasQuery &&
                songRefreshComplete &&
                playlistRefreshComplete &&
                artistRefreshComplete &&
                !hasRefreshError &&
                !anyResult &&
                !hasCollectionMatch
    }

    private fun collectionMatches(query: String, label: String): Boolean =
        query.isNotBlank() && label.contains(query, ignoreCase = true)

    private fun hasVisibleCollectionMatch(query: String): Boolean =
        collectionMatches(query, getString(R.string.lbl_liked)) ||
            collectionMatches(query, getString(R.string.lbl_local)) ||
            collectionMatches(query, getString(R.string.lbl_downloads))

    private fun CombinedLoadStates.hasError(): Boolean =
        refresh is LoadState.Error || prepend is LoadState.Error || append is LoadState.Error

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                mediaController = MediaControllerCompat(requireContext(), browser.sessionToken)
                songAdapter.playbackAvailable = true
            }

            override fun onConnectionSuspended() = onConnectionFailed()

            override fun onConnectionFailed() {
                mediaController = null
                songAdapter.playbackAvailable = false
            }
        }
}
