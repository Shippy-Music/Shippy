/*
 * Copyright (c) 2026 Auxio Project
 * R16LibrarySongsFragment.kt is part of Auxio.
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
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.widget.doAfterTextChanged
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
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16LibrarySongsBinding
import org.oxycblt.auxio.shippy.r16.identity.R16IdentifyTrackBottomSheetFragment
import org.oxycblt.auxio.shippy.r16.identity.R16MetadataEditorBottomSheetFragment

/**
 * R16's canonical, local-library-only Songs page. It deliberately does not invoke Global Search.
 */
@AndroidEntryPoint
class R16LibrarySongsFragment : Fragment(R.layout.fragment_r16_library_songs) {
    private val model: R16LibrarySongsViewModel by viewModels()
    private var binding: FragmentR16LibrarySongsBinding? = null
    private val adapter =
        R16LibrarySongPagingAdapter(
            onPlay = ::play,
            onIdentify = ::identifySong,
            onEditMetadata = ::editSongMetadata,
        )
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private val loadStateListener: (CombinedLoadStates) -> Unit = ::renderLoadState
    private val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val input = binding?.r16LibrarySongsInput
                if (input != null && input.text?.isNotBlank() == true) {
                    input.text?.clear()
                    input.requestFocus()
                    return
                }
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentR16LibrarySongsBinding.bind(view)
        binding?.apply {
            r16LibrarySongsRecycler.adapter = adapter
            r16LibrarySongsPlaceholder.setImageResource(R.drawable.ic_song_48)
            r16LibrarySongsInput.doAfterTextChanged(model::updateSearchQuery)
            r16LibrarySongsInput.setOnEditorActionListener(::onSearchEditorAction)
        }
        parentFragmentManager.setFragmentResultListener(
            R16IdentifyTrackBottomSheetFragment.RESULT_IDENTIFICATION_CONFIRMED,
            viewLifecycleOwner,
        ) { _, result ->
            val sourceReferenceId =
                result.getString(R16IdentifyTrackBottomSheetFragment.RESULT_SOURCE_REFERENCE_ID)
                    ?: return@setFragmentResultListener
            Snackbar.make(view, R.string.r16_identification_saved, Snackbar.LENGTH_LONG)
                .setAction(R.string.r16_undo) { model.undoIdentification(sourceReferenceId) }
                .show()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        adapter.addLoadStateListener(loadStateListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.songs.collectLatest(adapter::submitData)
            }
        }
    }

    override fun onDestroyView() {
        adapter.removeLoadStateListener(loadStateListener)
        binding?.r16LibrarySongsRecycler?.adapter = null
        binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        backCallback.isEnabled = true
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
        backCallback.isEnabled = false
        adapter.playbackAvailable = false
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onPause()
    }

    /** Host-facing update path; only the canonical Library query is changed. */
    internal fun updateSearchQuery(query: CharSequence?) {
        model.updateSearchQuery(query)
        binding
            ?.r16LibrarySongsInput
            ?.takeIf { it.text?.toString() != query?.toString() }
            ?.setText(query)
    }

    private fun play(row: LibrarySongRowView) {
        val mediaId =
            R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(RecordingId(row.recordingId)))
        val extras =
            Bundle().apply {
                putString(
                    org.oxycblt.auxio.shippy.r16.browser.R16MediaBrowserContract.EXTRA_SONGS_QUERY,
                    model.currentSearchQuery ?: "",
                )
            }
        mediaController?.transportControls?.playFromMediaId(mediaId, extras)
    }

    private fun identifySong(song: LibrarySongRowView) {
        R16IdentifyTrackBottomSheetFragment.newInstance(
                sourceReferenceId = null,
                subjectRecordingId = song.recordingId,
                initialQuery =
                    listOfNotNull(song.title, song.artistDisplay.takeIf(String::isNotBlank))
                        .joinToString(" "),
            )
            .show(parentFragmentManager, R16IdentifyTrackBottomSheetFragment.TAG)
    }

    private fun editSongMetadata(song: LibrarySongRowView) {
        R16MetadataEditorBottomSheetFragment.newInstance(recordingId = song.recordingId)
            .show(parentFragmentManager, R16MetadataEditorBottomSheetFragment.TAG)
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
        binding?.r16LibrarySongsRecycler?.isInvisible = empty
        binding?.r16LibrarySongsEmpty?.isInvisible = !empty
    }

    private fun onSearchEditorAction(view: TextView, actionId: Int, event: KeyEvent?): Boolean {
        if (
            actionId != EditorInfo.IME_ACTION_SEARCH &&
                (event?.keyCode != KeyEvent.KEYCODE_ENTER || event.action != KeyEvent.ACTION_DOWN)
        ) {
            return false
        }
        ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
        view.clearFocus()
        return true
    }
}
