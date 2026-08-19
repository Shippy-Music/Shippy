/*
 * Copyright (c) 2026 Auxio Project
 * CollectionSortSheet.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.library.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.radiobutton.MaterialRadioButton
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogCollectionSortBinding
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment

/** Shippy-styled sort choice sheet for one collection projection. */
@AndroidEntryPoint
class CollectionSortSheet : ViewBindingBottomSheetDialogFragment<DialogCollectionSortBinding>() {
    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogCollectionSortBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: DialogCollectionSortBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        val selected = requireArguments().getInt(ARG_SELECTED).coerceIn(0, SORT_LABELS.lastIndex)
        SORT_LABELS.forEachIndexed { index, labelRes ->
            binding.collectionSortOptions.addView(
                MaterialRadioButton(requireContext()).apply {
                    id = View.generateViewId()
                    setText(labelRes)
                    isChecked = index == selected
                    minHeight = resources.getDimensionPixelSize(R.dimen.size_touchable_small)
                    setOnClickListener { isChecked = true }
                }
            )
        }
        binding.collectionSortCancel.setOnClickListener { dismiss() }
        binding.collectionSortDone.setOnClickListener {
            val checkedId = binding.collectionSortOptions.checkedRadioButtonId
            val chosen =
                (0 until binding.collectionSortOptions.childCount).firstOrNull {
                    binding.collectionSortOptions.getChildAt(it).id == checkedId
                } ?: 0
            parentFragmentManager.setFragmentResult(
                RESULT,
                Bundle().apply { putInt(KEY_SELECTED, chosen.coerceAtLeast(0)) },
            )
            dismiss()
        }
    }

    companion object {
        const val RESULT = "shippy.library.sort.result"
        const val KEY_SELECTED = "selected"
        private const val TAG = "library_collection_sort"
        private const val ARG_SELECTED = "selected_arg"
        private val SORT_LABELS =
            intArrayOf(
                R.string.lbl_collection_order,
                R.string.lbl_collection_by_title,
                R.string.lbl_collection_by_artist,
                R.string.lbl_collection_by_duration,
            )

        fun show(manager: androidx.fragment.app.FragmentManager, selected: Int) {
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            CollectionSortSheet()
                .apply { arguments = Bundle().apply { putInt(ARG_SELECTED, selected) } }
                .show(manager, TAG)
        }
    }
}
