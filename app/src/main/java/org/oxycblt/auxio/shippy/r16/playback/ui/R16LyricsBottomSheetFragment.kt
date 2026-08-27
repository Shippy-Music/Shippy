/*
 * Copyright (c) 2026 Auxio Project
 * R16LyricsBottomSheetFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.ui

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16LyricsSheetBinding
import org.oxycblt.auxio.shippy.lyrics.R16LyricsState

@AndroidEntryPoint
class R16LyricsBottomSheetFragment : BottomSheetDialogFragment() {
    private val viewModel: R16LyricsViewModel by activityViewModels()
    private var binding: FragmentR16LyricsSheetBinding? = null
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private lateinit var lyricsAdapter: R16SyncedLyricsAdapter
    private lateinit var layoutManager: LinearLayoutManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bound = FragmentR16LyricsSheetBinding.inflate(inflater, container, false)
        binding = bound
        return bound.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val bound = binding ?: return

        val title = arguments?.getString(ARG_TITLE) ?: ""
        val artist = arguments?.getString(ARG_ARTIST) ?: ""
        val recordingId = arguments?.getString(ARG_RECORDING_ID)
        val queueEntryId = arguments?.getString(ARG_QUEUE_ENTRY_ID)
        val album = arguments?.getString(ARG_ALBUM)
        val durationMs = arguments?.getLong(ARG_DURATION_MS, 0L) ?: 0L

        bound.lyricsSheetTitle.text = title
        bound.lyricsSheetArtist.text = artist
        bound.lyricsSheetClose.setOnClickListener { dismiss() }
        bound.lyricsSheetRetry.setOnClickListener { viewModel.retry() }
        bound.lyricsSheetAutoScroll.setOnClickListener {
            viewModel.setAutoFollow(true)
            scrollToActiveLine()
        }

        layoutManager = LinearLayoutManager(requireContext())
        lyricsAdapter = R16SyncedLyricsAdapter { line ->
            mediaController?.transportControls?.seekTo(line.startMs)
        }

        bound.lyricsSheetRecycler.apply {
            layoutManager = this@R16LyricsBottomSheetFragment.layoutManager
            adapter = lyricsAdapter
            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                            viewModel.setAutoFollow(false)
                        }
                    }
                }
            )
        }

        val generation = arguments?.getLong(ARG_GENERATION, 0L) ?: 0L

        if (recordingId != null && queueEntryId != null) {
            viewModel.loadTrack(
                recordingId = RecordingId(recordingId),
                queueEntryId = QueueEntryId(queueEntryId),
                generation = generation,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.lyricsState.collect(::renderState) }
                launch { viewModel.activeLineIndex.collect(::renderActiveIndex) }
                launch { viewModel.autoFollow.collect(::renderAutoFollow) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        if (mediaBrowser == null) {
            mediaBrowser =
                MediaBrowserCompat(
                        requireContext(),
                        ComponentName(requireContext(), AuxioService::class.java),
                        object : MediaBrowserCompat.ConnectionCallback() {
                            override fun onConnected() {
                                val browser = mediaBrowser ?: return
                                mediaController =
                                    MediaControllerCompat(requireContext(), browser.sessionToken)
                            }
                        },
                        null,
                    )
                    .also(MediaBrowserCompat::connect)
        }
    }

    override fun onStop() {
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun renderState(state: R16LyricsState) {
        val bound = binding ?: return
        bound.lyricsSheetLoading.isVisible = state is R16LyricsState.Loading
        bound.lyricsSheetRecycler.isVisible = state is R16LyricsState.Synced
        bound.lyricsSheetPlainScroll.isVisible = state is R16LyricsState.Unsynced
        bound.lyricsSheetUnavailable.isVisible = state is R16LyricsState.Unavailable
        bound.lyricsSheetError.isVisible = state is R16LyricsState.Failed

        when (state) {
            is R16LyricsState.Synced -> {
                bound.lyricsSheetSource.apply {
                    isVisible = true
                    text = getString(R.string.r16_lyrics_source, state.sourceId)
                }
                lyricsAdapter.submitLines(state.lyrics.lines)
                scrollToActiveLine()
            }
            is R16LyricsState.Unsynced -> {
                bound.lyricsSheetSource.apply {
                    isVisible = true
                    text = getString(R.string.r16_lyrics_source, state.sourceId)
                }
                bound.lyricsSheetPlainText.text = state.lyrics.plainText
            }
            is R16LyricsState.Failed -> {
                bound.lyricsSheetSource.isVisible = false
                bound.lyricsSheetErrorMessage.text =
                    state.message ?: getString(R.string.lbl_lyrics_unavailable)
                bound.lyricsSheetRetry.isVisible = state.retryable
            }
            is R16LyricsState.Unavailable -> {
                bound.lyricsSheetSource.isVisible = false
            }
            else -> {
                bound.lyricsSheetSource.isVisible = false
            }
        }
    }

    private fun renderActiveIndex(index: Int) {
        lyricsAdapter.setActiveIndex(index)
        if (viewModel.autoFollow.value) {
            scrollToActiveLine()
        }
    }

    private fun renderAutoFollow(autoFollow: Boolean) {
        val state = viewModel.lyricsState.value
        binding?.lyricsSheetAutoScroll?.isVisible = !autoFollow && state is R16LyricsState.Synced
    }

    private fun scrollToActiveLine() {
        val index = viewModel.activeLineIndex.value
        if (index in 0 until lyricsAdapter.itemCount) {
            val recyclerView = binding?.lyricsSheetRecycler ?: return
            val offset = recyclerView.height / 3
            layoutManager.scrollToPositionWithOffset(index, offset)
        }
    }

    companion object {
        const val TAG = "R16LyricsBottomSheet"
        private const val ARG_RECORDING_ID = "arg_recording_id"
        private const val ARG_QUEUE_ENTRY_ID = "arg_queue_entry_id"
        private const val ARG_GENERATION = "arg_generation"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_ARTIST = "arg_artist"
        private const val ARG_ALBUM = "arg_album"
        private const val ARG_DURATION_MS = "arg_duration_ms"

        fun newInstance(
            recordingId: String,
            queueEntryId: String,
            generation: Long = 0L,
            title: String?,
            artist: String?,
            album: String?,
            durationMs: Long,
        ): R16LyricsBottomSheetFragment =
            R16LyricsBottomSheetFragment().apply {
                arguments =
                    bundleOf(
                        ARG_RECORDING_ID to recordingId,
                        ARG_QUEUE_ENTRY_ID to queueEntryId,
                        ARG_GENERATION to generation,
                        ARG_TITLE to title,
                        ARG_ARTIST to artist,
                        ARG_ALBUM to album,
                        ARG_DURATION_MS to durationMs,
                    )
            }
    }
}
