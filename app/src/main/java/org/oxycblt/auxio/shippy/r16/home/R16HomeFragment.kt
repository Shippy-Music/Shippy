/*
 * Copyright (c) 2026 Auxio Project
 * R16HomeFragment.kt is part of Auxio.
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
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.RecordingId
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.home.R16HomeHistoryItem
import app.shippy.data.home.R16HomePinnedPlaylistShortcut
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16HomeBinding
import org.oxycblt.auxio.pushR16Destination
import org.oxycblt.auxio.shippy.home.LastFmHomeState
import org.oxycblt.auxio.shippy.home.LastFmHomeViewModel
import org.oxycblt.auxio.shippy.lastfm.LastFmOverviewTrack
import org.oxycblt.auxio.shippy.r16.library.R16LibraryPlaylistDetailFragment
import org.oxycblt.auxio.shippy.r16.playback.system.R16ResumeCurrentMediaCommands
import org.oxycblt.auxio.shippy.r16.playback.ui.R16NowPlayingFragment
import org.oxycblt.auxio.shippy.r16.search.R16GlobalSearchFragment

/** ACTIVE Home root: current MediaSession feedback plus bounded canonical listening history. */
@AndroidEntryPoint
internal class R16HomeFragment : Fragment(R.layout.fragment_r16_home) {
    private val model: R16HomeHistoryViewModel by viewModels()
    private val lastFmModel: LastFmHomeViewModel by viewModels()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recentAdapter = R16HomeRecentAdapter(::playRecording)
    private val pinnedShortcutsAdapter = R16HomePinnedShortcutsAdapter(::openPinnedPlaylist)
    private val lastFmAdapter = R16HomeLastFmAdapter(::onLastFmTrackClick)
    private var binding: FragmentR16HomeBinding? = null
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private var continueState: R16ContinueUiState? = null
    private var resumeInFlight = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding =
            FragmentR16HomeBinding.bind(view).also { bound ->
                bound.r16HomeRecent.layoutManager =
                    LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                bound.r16HomeRecent.adapter = recentAdapter
                bound.r16HomePinnedShortcuts.layoutManager =
                    LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                bound.r16HomePinnedShortcuts.adapter = pinnedShortcutsAdapter
                bound.r16HomeLastfmTracks.layoutManager =
                    LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                bound.r16HomeLastfmTracks.adapter = lastFmAdapter
                bound.r16HomeContinue.setOnClickListener { resumeCurrent() }
                bound.r16HomeSeeAll.setOnClickListener { openHistory() }
            }
        renderContinue(null)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.recentlyPlayed.collect(::renderRecentlyPlayed)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.pinnedPlaylistShortcuts.collect(::renderPinnedPlaylistShortcuts)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                lastFmModel.state.collect(::renderLastFm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lastFmModel.refresh()
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
        resumeInFlight = false
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        recentAdapter.playbackAvailable = false
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onPause()
    }

    override fun onDestroyView() {
        binding?.r16HomeRecent?.adapter = null
        binding?.r16HomePinnedShortcuts?.adapter = null
        binding?.r16HomeLastfmTracks?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private val browserConnection =
        object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val browser = mediaBrowser ?: return
                mediaController =
                    MediaControllerCompat(requireContext(), browser.sessionToken).also {
                        it.registerCallback(controllerCallback)
                        recentAdapter.playbackAvailable = true
                        renderContinue(R16ContinueUiStateMapper.map(it.metadata))
                    }
            }

            override fun onConnectionSuspended() = disconnectController()

            override fun onConnectionFailed() = disconnectController()
        }

    private val controllerCallback =
        object : MediaControllerCompat.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
                renderContinue(R16ContinueUiStateMapper.map(metadata))
            }
        }

    private fun renderContinue(next: R16ContinueUiState?) {
        continueState = next
        binding?.apply {
            r16HomeContinue.text = next?.label ?: getString(R.string.r16_home_continue_empty)
            r16HomeContinue.isEnabled = next != null && mediaController != null && !resumeInFlight
        }
    }

    private fun renderRecentlyPlayed(items: List<R16HomeHistoryItem>) {
        recentAdapter.submitList(items)
        binding?.r16HomeRecentEmpty?.isVisible = items.isEmpty()
        binding?.r16HomeRecent?.isVisible = items.isNotEmpty()
    }

    private fun renderPinnedPlaylistShortcuts(items: List<R16HomePinnedPlaylistShortcut>) {
        pinnedShortcutsAdapter.submitList(items)
        binding?.r16HomePinnedSection?.isVisible = items.isNotEmpty()
    }

    private fun renderLastFm(state: LastFmHomeState) {
        val bound = binding ?: return
        when (state) {
            is LastFmHomeState.Content -> {
                val tracks =
                    state.overview.recommendations.ifEmpty { state.overview.topTracks }.take(8)
                bound.r16HomeLastfmSection.isVisible = tracks.isNotEmpty()
                bound.r16HomeLastfmSubtitle.text =
                    getString(R.string.r16_home_lastfm_profile_summary, state.overview.username)
                lastFmAdapter.submitList(tracks)
            }
            else -> {
                bound.r16HomeLastfmSection.isVisible = false
                lastFmAdapter.submitList(emptyList())
            }
        }
    }

    private fun onLastFmTrackClick(track: LastFmOverviewTrack) {
        pushR16Destination(
            R16GlobalSearchFragment.forRecommendation(track.title, track.artist),
            "r16-global-search",
        )
    }

    private fun resumeCurrent() {
        val controller = mediaController ?: return
        val expected = continueState ?: return
        if (resumeInFlight) return
        resumeInFlight = true
        renderContinue(expected)
        controller.sendCommand(
            R16ResumeCurrentMediaCommands.RESUME_CURRENT,
            bundleOf(
                R16ResumeCurrentMediaCommands.EXTRA_QUEUE_ENTRY_ID to expected.queueEntryId,
                R16ResumeCurrentMediaCommands.EXTRA_RECORDING_ID to expected.recordingId,
            ),
            object : ResultReceiver(mainHandler) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    resumeInFlight = false
                    val current = R16ContinueUiStateMapper.map(mediaController?.metadata)
                    val responseMatches =
                        resultData?.let { result ->
                            result.getString(R16ResumeCurrentMediaCommands.KEY_QUEUE_ENTRY_ID) ==
                                expected.queueEntryId &&
                                result.getString(R16ResumeCurrentMediaCommands.KEY_RECORDING_ID) ==
                                    expected.recordingId
                        } == true
                    if (
                        resultCode == R16ResumeCurrentMediaCommands.RESULT_ACCEPTED &&
                            responseMatches &&
                            current?.queueEntryId == expected.queueEntryId &&
                            current.recordingId == expected.recordingId
                    ) {
                        openNowPlaying()
                    }
                    renderContinue(current)
                }
            },
        )
    }

    private fun openHistory() {
        pushR16Destination(R16HistoryFragment(), "r16-history")
    }

    private fun playRecording(item: R16HomeHistoryItem) {
        val recordingId = item.recordingId ?: return
        val mediaId =
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(RecordingId(recordingId)))
        mediaController?.transportControls?.playFromMediaId(mediaId, null)
    }

    private fun openPinnedPlaylist(shortcut: R16HomePinnedPlaylistShortcut) {
        val playlistId = PlaylistId(shortcut.playlistId)
        pushR16Destination(
            R16LibraryPlaylistDetailFragment.newInstance(playlistId.value),
            "r16-home-pinned-playlist-${playlistId.value}",
        )
    }

    private fun openNowPlaying() {
        if (
            parentFragmentManager.findFragmentById(R.id.r16_active_content) is R16NowPlayingFragment
        ) {
            return
        }
        pushR16Destination(R16NowPlayingFragment(), "r16-now-playing")
    }

    private fun disconnectController() {
        resumeInFlight = false
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        recentAdapter.playbackAvailable = false
        renderContinue(null)
    }
}
