/*
 * Copyright (c) 2026 Auxio Project
 * R16MetadataEditorBottomSheetFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.identity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.databinding.FragmentR16MetadataEditorBinding

@AndroidEntryPoint
class R16MetadataEditorBottomSheetFragment : BottomSheetDialogFragment() {
    private val model: R16MetadataEditorViewModel by viewModels()
    private var binding: FragmentR16MetadataEditorBinding? = null
    private var recordingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordingId = arguments?.getString(ARG_RECORDING_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bound = FragmentR16MetadataEditorBinding.inflate(inflater, container, false)
        binding = bound
        return bound.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val bound = binding ?: return

        recordingId?.let { model.loadRecording(it) }

        bound.editorTitleRevert.setOnClickListener { model.revertField("TITLE") }
        bound.editorArtistRevert.setOnClickListener { model.revertField("ARTIST") }
        bound.editorReleaseRevert.setOnClickListener { model.revertField("RELEASE") }

        bound.editorCancelButton.setOnClickListener { dismiss() }
        bound.editorUnmergeButton.setOnClickListener { model.unmerge() }
        bound.editorUnlinkIdentificationButton.setOnClickListener { model.unlinkIdentification() }
        bound.editorSaveButton.setOnClickListener {
            model.saveOverrides(
                newTitle = bound.editorTitleEdit.text?.toString().orEmpty(),
                newArtist = bound.editorArtistEdit.text?.toString().orEmpty(),
                newRelease = bound.editorReleaseEdit.text?.toString().orEmpty(),
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (
                        bound.editorTitleEdit.text.isNullOrBlank() ||
                            bound.editorTitleEdit.text.toString() != state.title
                    ) {
                        bound.editorTitleEdit.setText(state.title)
                    }
                    if (
                        bound.editorArtistEdit.text.isNullOrBlank() ||
                            bound.editorArtistEdit.text.toString() != state.artist
                    ) {
                        bound.editorArtistEdit.setText(state.artist)
                    }
                    if (
                        bound.editorReleaseEdit.text.isNullOrBlank() ||
                            bound.editorReleaseEdit.text.toString() != state.release
                    ) {
                        bound.editorReleaseEdit.setText(state.release)
                    }

                    bound.editorTitleRevert.isVisible = state.isTitleOverridden
                    bound.editorArtistRevert.isVisible = state.isArtistOverridden
                    bound.editorReleaseRevert.isVisible = state.isReleaseOverridden
                    bound.editorUnlinkIdentificationButton.isVisible = state.canUnlinkIdentification

                    if (state.isSaved) {
                        dismiss()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "R16MetadataEditorBottomSheetFragment"
        private const val ARG_RECORDING_ID = "arg_recording_id"

        fun newInstance(recordingId: String): R16MetadataEditorBottomSheetFragment =
            R16MetadataEditorBottomSheetFragment().apply {
                arguments = Bundle().apply { putString(ARG_RECORDING_ID, recordingId) }
            }
    }
}
