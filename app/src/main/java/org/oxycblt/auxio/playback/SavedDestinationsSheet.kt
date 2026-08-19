/*
 * Copyright (c) 2026 Auxio Project
 * SavedDestinationsSheet.kt is part of Auxio.
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
package org.oxycblt.auxio.playback

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogSavedDestinationsBinding
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment

@AndroidEntryPoint
class SavedDestinationsSheet :
    ViewBindingBottomSheetDialogFragment<DialogSavedDestinationsBinding>() {
    private val checks = mutableListOf<MaterialCheckBox>()

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogSavedDestinationsBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: DialogSavedDestinationsBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        checks.clear()
        val labels = requireArguments().getStringArray(ARG_LABELS).orEmpty()
        val checked = requireArguments().getBooleanArray(ARG_CHECKED) ?: booleanArrayOf()
        val playlistIds = requireArguments().getStringArray(ARG_PLAYLIST_IDS).orEmpty()
        labels.forEachIndexed { index, label ->
            MaterialCheckBox(requireContext()).apply {
                text = label
                isChecked = checked.getOrNull(index) == true
                minHeight = resources.getDimensionPixelSize(R.dimen.size_touchable_small)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                binding.savedDestinationsOptions.addView(this)
                checks += this
            }
        }
        binding.savedDestinationsDone.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                RESULT,
                Bundle().apply {
                    putString(ARG_QUEUE_ITEM_ID, requireArguments().getString(ARG_QUEUE_ITEM_ID))
                    putBoolean(KEY_LIKED, checks.firstOrNull()?.isChecked == true)
                    putStringArray(
                        KEY_PLAYLIST_IDS,
                        checks
                            .drop(1)
                            .mapIndexedNotNull { index, check ->
                                check.takeIf { it.isChecked }?.let { playlistIds.getOrNull(index) }
                            }
                            .toTypedArray(),
                    )
                },
            )
            dismiss()
        }
    }

    companion object {
        const val RESULT = "shippy.saved_destinations.result"
        const val KEY_LIKED = "liked"
        const val KEY_PLAYLIST_IDS = "playlist_ids"
        private const val ARG_QUEUE_ITEM_ID = "queue_item_id"
        private const val ARG_LABELS = "labels"
        private const val ARG_CHECKED = "checked"
        private const val ARG_PLAYLIST_IDS = "playlist_ids_options"

        fun show(
            manager: androidx.fragment.app.FragmentManager,
            queueItemId: String,
            labels: Array<String>,
            checked: BooleanArray,
            playlistIds: Array<String>,
        ) {
            if (manager.isStateSaved || manager.findFragmentByTag(RESULT) != null) return
            SavedDestinationsSheet()
                .apply {
                    arguments =
                        Bundle().apply {
                            putString(ARG_QUEUE_ITEM_ID, queueItemId)
                            putStringArray(ARG_LABELS, labels)
                            putBooleanArray(ARG_CHECKED, checked)
                            putStringArray(ARG_PLAYLIST_IDS, playlistIds)
                        }
                }
                .show(manager, RESULT)
        }
    }
}
