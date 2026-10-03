/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaylistSortSheet.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import app.shippy.core.library.PlaylistSort
import app.shippy.core.library.PlaylistSortDirection
import app.shippy.core.library.PlaylistSortMode
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogSortBinding
import org.oxycblt.auxio.databinding.ItemSortModeBinding
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.systemBarInsetsCompat

/** Shippy-styled typed display-sort sheet for one R16 playlist. */
@AndroidEntryPoint
class R16PlaylistSortSheet : ViewBindingBottomSheetDialogFragment<DialogSortBinding>() {
    private val modeAdapter = R16PlaylistSortModeAdapter(::onModeSelected)
    private var selectedMode = PlaylistSortMode.CUSTOM
    private var selectedDirection = PlaylistSortDirection.ASCENDING
    private var initialSort = PlaylistSort()

    override fun onCreateBinding(inflater: LayoutInflater) = DialogSortBinding.inflate(inflater)

    private fun onModeSelected(mode: PlaylistSortMode) {
        selectedMode = mode
        modeAdapter.selectedMode = mode
        updateDirectionVisibility()
        updateSaveEnabled()
    }

    override fun onBindingCreated(binding: DialogSortBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            view.updatePadding(bottom = insets.systemBarInsetsCompat.bottom)
            insets
        }
        initialSort =
            PlaylistSort.fromWire(
                requireArguments().getString(ARG_MODE),
                requireArguments().getString(ARG_DIRECTION),
            )
        selectedMode = initialSort.mode
        selectedDirection = initialSort.direction
        binding.sortModeRecycler.adapter = modeAdapter
        modeAdapter.selectedMode = selectedMode
        modeAdapter.submitList(
            PlaylistSortMode.entries.filterNot { it == PlaylistSortMode.OLDEST_ADDED }
        )

        binding.sortDirectionAsc.setOnClickListener {
            selectedDirection = PlaylistSortDirection.ASCENDING
            updateDirectionButtons()
            updateSaveEnabled()
        }
        binding.sortDirectionDsc.setOnClickListener {
            selectedDirection = PlaylistSortDirection.DESCENDING
            updateDirectionButtons()
            updateSaveEnabled()
        }
        binding.sortCancel.setOnClickListener { dismiss() }
        binding.sortSave.setOnClickListener {
            val direction =
                if (selectedMode == PlaylistSortMode.CUSTOM) {
                    PlaylistSortDirection.ASCENDING
                } else {
                    selectedDirection
                }
            parentFragmentManager.setFragmentResult(
                RESULT,
                bundleOf(KEY_MODE to selectedMode.wireValue, KEY_DIRECTION to direction.wireValue),
            )
            dismiss()
        }
        updateDirectionVisibility()
        updateSaveEnabled()
    }

    private fun updateDirectionVisibility() {
        val binding = requireBinding()
        val visible = selectedMode != PlaylistSortMode.CUSTOM
        binding.sortHeader.isVisible = visible
        binding.sortDirectionGroup.isVisible = visible
        if (!visible) selectedDirection = PlaylistSortDirection.ASCENDING
        updateDirectionButtons()
    }

    private fun updateDirectionButtons() {
        val binding = requireBinding()
        val dateAdded = selectedMode == PlaylistSortMode.RECENTLY_ADDED
        binding.sortDirectionAsc.setText(
            if (dateAdded) R.string.r16_playlist_sort_newest_first else R.string.lbl_sort_asc
        )
        binding.sortDirectionDsc.setText(
            if (dateAdded) R.string.r16_playlist_sort_oldest_first else R.string.lbl_sort_dsc
        )
        binding.sortDirectionAsc.isChecked = selectedDirection == PlaylistSortDirection.ASCENDING
        binding.sortDirectionDsc.isChecked = selectedDirection == PlaylistSortDirection.DESCENDING
    }

    private fun updateSaveEnabled() {
        val chosen = PlaylistSort(selectedMode, selectedDirection).normalized()
        requireBinding().sortSave.isEnabled = chosen != initialSort
    }

    companion object {
        const val RESULT = "shippy.r16.playlist_sort.result"
        const val KEY_MODE = "mode"
        const val KEY_DIRECTION = "direction"
        private const val TAG = "r16_playlist_sort"
        private const val ARG_MODE = "mode_arg"
        private const val ARG_DIRECTION = "direction_arg"

        fun show(manager: androidx.fragment.app.FragmentManager, sort: PlaylistSort) {
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            val normalized = sort.normalized()
            R16PlaylistSortSheet()
                .apply {
                    arguments =
                        bundleOf(
                            ARG_MODE to normalized.mode.wireValue,
                            ARG_DIRECTION to normalized.direction.wireValue,
                        )
                }
                .show(manager, TAG)
        }
    }
}

private class R16PlaylistSortModeAdapter(private val onSelected: (PlaylistSortMode) -> Unit) :
    RecyclerView.Adapter<R16PlaylistSortModeAdapter.ViewHolder>() {
    private var modes: List<PlaylistSortMode> = emptyList()
    var selectedMode: PlaylistSortMode = PlaylistSortMode.CUSTOM
        set(value) {
            val oldIndex = modes.indexOf(field)
            field = value
            val newIndex = modes.indexOf(value)
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (newIndex >= 0) notifyItemChanged(newIndex)
        }

    fun submitList(value: List<PlaylistSortMode>) {
        modes = value
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = modes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemSortModeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val mode = modes[position]
        holder.binding.sortRadio.setText(mode.labelRes)
        holder.binding.sortRadio.isChecked = mode == selectedMode
        holder.itemView.setOnClickListener { onSelected(mode) }
    }

    class ViewHolder(val binding: ItemSortModeBinding) : RecyclerView.ViewHolder(binding.root)
}

private val PlaylistSortMode.labelRes: Int
    get() =
        when (this) {
            PlaylistSortMode.CUSTOM -> R.string.r16_playlist_sort_custom
            PlaylistSortMode.RECENTLY_ADDED -> R.string.lbl_date_added
            PlaylistSortMode.OLDEST_ADDED -> R.string.r16_playlist_sort_oldest_added
            PlaylistSortMode.TITLE -> R.string.r16_playlist_sort_title
            PlaylistSortMode.ARTIST -> R.string.r16_playlist_sort_artist
            PlaylistSortMode.ALBUM -> R.string.r16_playlist_sort_album
            PlaylistSortMode.DURATION -> R.string.r16_playlist_sort_duration
        }
