/*
 * Copyright (c) 2026 Auxio Project
 * R16IdentifyTrackBottomSheetFragment.kt is part of Auxio.
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
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.databinding.FragmentR16IdentifyTrackBinding
import org.oxycblt.auxio.databinding.ItemR16IdentifyCandidateBinding

@AndroidEntryPoint
class R16IdentifyTrackBottomSheetFragment : BottomSheetDialogFragment() {
    private val model: R16IdentifyTrackViewModel by viewModels()
    private var binding: FragmentR16IdentifyTrackBinding? = null
    private var sourceReferenceId: String? = null
    private var subjectRecordingId: String? = null
    private var initialQuery: String? = null
    private var confirmationDispatched = false

    private val adapter = CandidatesAdapter { candidate ->
        model.confirmCandidate(sourceReferenceId, subjectRecordingId, candidate)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceReferenceId = arguments?.getString(ARG_SOURCE_REFERENCE_ID)
        subjectRecordingId = arguments?.getString(ARG_SUBJECT_RECORDING_ID)
        initialQuery = arguments?.getString(ARG_INITIAL_QUERY)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bound = FragmentR16IdentifyTrackBinding.inflate(inflater, container, false)
        binding = bound
        return bound.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val bound = binding ?: return

        bound.r16IdentifyCandidatesList.layoutManager = LinearLayoutManager(requireContext())
        bound.r16IdentifyCandidatesList.adapter = adapter

        bound.r16IdentifySearchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                model.searchCandidates(bound.r16IdentifySearchEdit.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        bound.r16IdentifySearchEdit.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            if (query.length >= 2) {
                model.searchCandidates(query)
            }
        }

        initialQuery?.let {
            bound.r16IdentifySearchEdit.setText(it)
            model.searchCandidates(it)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    bound.r16IdentifyProgress.isVisible = state.isLoading
                    bound.r16IdentifyEmpty.isVisible =
                        !state.isLoading && state.candidates.isEmpty() && state.query.isNotBlank()
                    adapter.submitList(state.candidates)
                    if (state.confirmedRecordingId != null && !confirmationDispatched) {
                        confirmationDispatched = true
                        state.undo?.let { undo ->
                            parentFragmentManager.setFragmentResult(
                                RESULT_IDENTIFICATION_CONFIRMED,
                                Bundle().apply {
                                    putString(RESULT_SOURCE_REFERENCE_ID, undo.sourceReferenceId)
                                },
                            )
                        }
                        dismiss()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding?.r16IdentifyCandidatesList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private class CandidatesAdapter(private val onSelected: (IdentifyCandidate) -> Unit) :
        ListAdapter<IdentifyCandidate, CandidateViewHolder>(DiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemR16IdentifyCandidateBinding.inflate(inflater, parent, false)
            return CandidateViewHolder(binding, onSelected)
        }

        override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        object DiffCallback : DiffUtil.ItemCallback<IdentifyCandidate>() {
            override fun areItemsTheSame(
                oldItem: IdentifyCandidate,
                newItem: IdentifyCandidate,
            ): Boolean = oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(
                oldItem: IdentifyCandidate,
                newItem: IdentifyCandidate,
            ): Boolean = oldItem == newItem
        }
    }

    private class CandidateViewHolder(
        private val binding: ItemR16IdentifyCandidateBinding,
        private val onSelected: (IdentifyCandidate) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(candidate: IdentifyCandidate) {
            binding.candidateTitle.text = candidate.title
            binding.candidateArtistAndAlbum.text =
                listOfNotNull(
                        candidate.artist.takeIf(String::isNotBlank),
                        candidate.album?.takeIf(String::isNotBlank),
                        candidate.provenance,
                    )
                    .joinToString(" • ")

            binding.candidateConfidenceChip.text =
                when (candidate.confidence) {
                    IdentifyConfidenceKind.AUTHORITATIVE -> "Exact"
                    IdentifyConfidenceKind.PROBABLE -> "Probable"
                    IdentifyConfidenceKind.DURATION_MATCH -> "Duration"
                }

            binding.candidateArtwork.bindArtwork(candidate.artworkLocation, candidate.title)

            binding.root.setOnClickListener { onSelected(candidate) }
        }
    }

    companion object {
        const val TAG = "R16IdentifyTrackBottomSheetFragment"
        const val RESULT_IDENTIFICATION_CONFIRMED = "r16_identification_confirmed"
        const val RESULT_SOURCE_REFERENCE_ID = "r16_identification_source_reference_id"
        private const val ARG_SOURCE_REFERENCE_ID = "arg_source_reference_id"
        private const val ARG_SUBJECT_RECORDING_ID = "arg_subject_recording_id"
        private const val ARG_INITIAL_QUERY = "arg_initial_query"

        fun newInstance(
            sourceReferenceId: String?,
            subjectRecordingId: String?,
            initialQuery: String?,
        ): R16IdentifyTrackBottomSheetFragment =
            R16IdentifyTrackBottomSheetFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_SOURCE_REFERENCE_ID, sourceReferenceId)
                        putString(ARG_SUBJECT_RECORDING_ID, subjectRecordingId)
                        putString(ARG_INITIAL_QUERY, initialQuery)
                    }
            }
    }
}
