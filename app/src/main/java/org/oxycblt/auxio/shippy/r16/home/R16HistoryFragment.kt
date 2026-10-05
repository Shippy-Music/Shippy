/*
 * Copyright (c) 2026 Auxio Project
 * R16HistoryFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.home

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import app.shippy.core.identity.RecordingId
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16HistoryBinding

/** Full raw listening-session history. Duplicate recordings intentionally remain separate rows. */
@AndroidEntryPoint
internal class R16HistoryFragment : Fragment(R.layout.fragment_r16_history) {
    private val model: R16HomeHistoryViewModel by viewModels()
    private val adapter = R16HomeHistoryPagingAdapter(::playRecording)
    private var binding: FragmentR16HistoryBinding? = null
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16HistoryBinding.bind(view).also { bound ->
                bound.r16HistoryEntries.layoutManager = LinearLayoutManager(requireContext())
                bound.r16HistoryEntries.adapter = adapter
                bound.r16HistoryBack.setOnClickListener { parentFragmentManager.popBackStack() }
            }
        adapter.addLoadStateListener { states ->
            val empty = states.refresh is LoadState.NotLoading && adapter.itemCount == 0
            binding?.r16HistoryEmpty?.isVisible = empty
            binding?.r16HistoryEntries?.isVisible = !empty
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.history.collectLatest(adapter::submitData)
            }
        }
    }

    override fun onDestroyView() {
        binding?.r16HistoryEntries?.adapter = null
        binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
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
        adapter.playbackAvailable = false
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onPause()
    }

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                mediaController = MediaControllerCompat(requireContext(), browser.sessionToken)
                adapter.playbackAvailable = true
            }

            override fun onConnectionSuspended() = disconnectController()

            override fun onConnectionFailed() = disconnectController()
        }

    private fun playRecording(item: app.shippy.data.home.R16HomeHistoryItem) {
        val recordingId = item.recordingId ?: return
        val mediaId =
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(RecordingId(recordingId)))
        mediaController?.transportControls?.playFromMediaId(mediaId, null)
    }

    private fun disconnectController() {
        mediaController = null
        adapter.playbackAvailable = false
    }
}
