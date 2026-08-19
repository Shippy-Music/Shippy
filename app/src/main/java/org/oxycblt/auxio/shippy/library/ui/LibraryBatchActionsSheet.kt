/*
 * Copyright (c) 2026 Auxio Project
 * LibraryBatchActionsSheet.kt is part of Auxio.
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

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.view.children
import androidx.fragment.app.FragmentManager
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogMenuBinding
import org.oxycblt.auxio.list.ClickableListListener
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.list.menu.MenuItemAdapter
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment

/** Context-specific destructive actions for selected collection tracks. */
@AndroidEntryPoint
class LibraryBatchActionsSheet :
    ViewBindingBottomSheetDialogFragment<DialogMenuBinding>(), ClickableListListener<MenuItem> {
    private val adapter = MenuItemAdapter(this)

    override fun onCreateBinding(inflater: LayoutInflater) = DialogMenuBinding.inflate(inflater)

    @SuppressLint("RestrictedApi")
    override fun onBindingCreated(binding: DialogMenuBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val kind = requireArguments().getString(ARG_KIND).orEmpty()
        val count = requireArguments().getInt(ARG_COUNT)
        binding.menuType.setText(R.string.lbl_batch_actions)
        binding.menuName.text = getString(R.string.fmt_selected_tracks, count)
        binding.menuInfo.setText(R.string.lbl_choose_action)
        binding.menuCover.bindArtwork(R.drawable.ic_edit_24, getString(R.string.lbl_batch_actions))
        binding.menuOptionRecycler.adapter = adapter

        @Suppress("RestrictedApi") val menu = MenuBuilder(requireContext())
        SupportMenuInflater(requireContext()).inflate(R.menu.library_batch_actions, menu)
        menu.findItem(R.id.action_batch_remove_playlist).isVisible = kind == KIND_PLAYLIST
        menu.findItem(R.id.action_batch_remove_liked).isVisible = kind == KIND_LIKED
        menu.findItem(R.id.action_batch_remove_downloads).isVisible = kind == KIND_DOWNLOADS
        menu.findItem(R.id.action_batch_delete_local).isVisible = kind == KIND_LOCAL
        adapter.update(menu.children.filter(MenuItem::isVisible).toList(), UpdateInstructions.Diff)
    }

    override fun onDestroyBinding(binding: DialogMenuBinding) {
        binding.menuOptionRecycler.adapter = null
        super.onDestroyBinding(binding)
    }

    override fun onClick(
        item: MenuItem,
        viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
    ) {
        parentFragmentManager.setFragmentResult(
            RESULT,
            Bundle().apply { putInt(KEY_ACTION, item.itemId) },
        )
        dismiss()
    }

    companion object {
        const val RESULT = "shippy.library.batch.action"
        const val KEY_ACTION = "action"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_LIKED = "liked"
        const val KIND_DOWNLOADS = "downloads"
        const val KIND_LOCAL = "local"

        private const val TAG = "library_batch_actions"
        private const val ARG_KIND = "kind"
        private const val ARG_COUNT = "count"

        fun show(manager: FragmentManager, kind: String, count: Int) {
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            LibraryBatchActionsSheet()
                .apply {
                    arguments =
                        Bundle().apply {
                            putString(ARG_KIND, kind)
                            putInt(ARG_COUNT, count)
                        }
                }
                .show(manager, TAG)
        }
    }
}
