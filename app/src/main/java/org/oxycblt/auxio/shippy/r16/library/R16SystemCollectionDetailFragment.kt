/*
 * Copyright (c) 2026 Auxio Project
 * R16SystemCollectionDetailFragment.kt is part of Auxio.
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
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import app.shippy.core.identity.RecordingId
import app.shippy.core.library.R16SystemCollection
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.db.view.LibrarySongRowView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16SystemCollectionDetailBinding
import org.oxycblt.auxio.shippy.r16.browser.R16MediaBrowserContract

/** R16 rule-derived collection detail: display rows page through Room; play context is ID-only. */
@AndroidEntryPoint
internal class R16SystemCollectionDetailFragment :
    Fragment(R.layout.fragment_r16_system_collection_detail) {
    private val model: R16SystemCollectionDetailViewModel by viewModels()
    private val collection: R16SystemCollection by lazy {
        requireNotNull(
            R16SystemCollection.fromId(requireArguments().getString(ARG_COLLECTION_ID).orEmpty())
        )
    }
    private var binding: FragmentR16SystemCollectionDetailBinding? = null
    private val adapter = R16LibrarySongPagingAdapter(::playRecording)
    private val loadStateListener: (CombinedLoadStates) -> Unit = ::renderLoadState
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16SystemCollectionDetailBinding.bind(view).also { bound ->
                bound.r16SystemCollectionEntries.adapter = adapter
                bound.r16SystemCollectionBack.setOnClickListener {
                    parentFragmentManager.popBackStack()
                }
                bound.r16SystemCollectionPlay.setOnClickListener { playCollection() }
                bound.r16SystemCollectionShuffle.setOnClickListener { shuffleCollection() }
                bound.r16SystemCollectionTitle.setText(collection.titleRes)
                bound.r16SystemCollectionInput.doAfterTextChanged(model::updateSearchQuery)
            }
        setActionsEnabled(false)
        adapter.addLoadStateListener(loadStateListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.songs(collection).collectLatest(adapter::submitData)
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
        setActionsEnabled(false)
        super.onStop()
    }

    override fun onDestroyView() {
        adapter.removeLoadStateListener(loadStateListener)
        binding?.r16SystemCollectionEntries?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun playCollection() {
        mediaController?.transportControls?.playFromMediaId(collectionMediaId(), null)
    }

    private fun shuffleCollection() {
        mediaController
            ?.transportControls
            ?.playFromMediaId(
                collectionMediaId(),
                bundleOf(R16MediaBrowserContract.EXTRA_SHUFFLE_SEED to System.nanoTime()),
            )
    }

    /** Row IDs retain both exact RecordingId and the derived collection context. */
    private fun playRecording(row: LibrarySongRowView) {
        val mediaId =
            R16MediaBrowserIdCodec.encode(
                R16MediaBrowserId.SystemCollectionRecording(
                    collection,
                    RecordingId(row.recordingId),
                )
            )
        mediaController?.transportControls?.playFromMediaId(mediaId, null)
    }

    private fun collectionMediaId(): String =
        R16MediaBrowserIdCodec.encode(R16MediaBrowserId.SystemCollection(collection))

    private fun renderLoadState(states: CombinedLoadStates) {
        val empty = states.refresh is LoadState.NotLoading && adapter.itemCount == 0
        binding?.apply {
            r16SystemCollectionEntries.isGone = empty
            r16SystemCollectionEmpty.isGone = !empty
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        binding?.apply {
            r16SystemCollectionPlay.isEnabled = enabled
            r16SystemCollectionShuffle.isEnabled = enabled
        }
    }

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                mediaController = MediaControllerCompat(requireContext(), browser.sessionToken)
                adapter.playbackAvailable = true
                setActionsEnabled(true)
            }

            override fun onConnectionSuspended() = onConnectionFailed()

            override fun onConnectionFailed() {
                mediaController = null
                adapter.playbackAvailable = false
                setActionsEnabled(false)
            }
        }

    private val R16SystemCollection.titleRes: Int
        get() =
            when (this) {
                R16SystemCollection.LIKED -> R.string.lbl_liked
                R16SystemCollection.DOWNLOADS -> R.string.lbl_downloads
                R16SystemCollection.LOCAL -> R.string.lbl_local
            }

    companion object {
        private const val ARG_COLLECTION_ID = "r16_system_collection_id"

        fun newInstance(collection: R16SystemCollection): R16SystemCollectionDetailFragment =
            R16SystemCollectionDetailFragment().apply {
                arguments = bundleOf(ARG_COLLECTION_ID to collection.id)
            }
    }
}
