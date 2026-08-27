/*
 * Copyright (c) 2026 Auxio Project
 * LibraryCollectionActionsSheet.kt is part of Auxio.
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

/** Shared management surface for system collections and user playlists. */
@AndroidEntryPoint
class LibraryCollectionActionsSheet :
    ViewBindingBottomSheetDialogFragment<DialogMenuBinding>(), ClickableListListener<MenuItem> {
    private val adapter = MenuItemAdapter(this)

    override fun onCreateBinding(inflater: LayoutInflater) = DialogMenuBinding.inflate(inflater)

    @SuppressLint("RestrictedApi")
    override fun onBindingCreated(binding: DialogMenuBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        val artwork = requireArguments().getString(ARG_ARTWORK)
        val isSystem = requireArguments().getBoolean(ARG_SYSTEM)
        val isPinned = requireArguments().getBoolean(ARG_PINNED)

        binding.menuType.setText(R.string.lbl_collections)
        binding.menuName.text = title
        binding.menuInfo.setText(
            if (isSystem) R.string.lbl_system_collection else R.string.lng_shippy_playlist
        )
        binding.menuName.isSelected = true
        binding.menuInfo.isSelected = true
        binding.menuCover.bindArtwork(artwork ?: R.drawable.ic_playlist_48, title)
        binding.menuOptionRecycler.apply {
            adapter = this@LibraryCollectionActionsSheet.adapter
            itemAnimator = null
        }

        @Suppress("RestrictedApi") val menu = MenuBuilder(requireContext())
        SupportMenuInflater(requireContext()).inflate(R.menu.library_collection_actions, menu)
        menu.findItem(R.id.action_library_pin).title =
            getString(if (isPinned) R.string.lbl_unpin else R.string.lbl_pin)
        menu.findItem(R.id.action_library_edit_order).isVisible = false
        menu.findItem(R.id.action_library_rename).isVisible = !isSystem
        menu.findItem(R.id.action_library_artwork).isVisible = !isSystem
        menu.findItem(R.id.action_library_delete).isVisible = !isSystem
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
            Bundle().apply {
                putString(KEY_COLLECTION_ID, requireArguments().getString(ARG_COLLECTION_ID))
                putInt(KEY_ACTION, item.itemId)
            },
        )
        dismiss()
    }

    companion object {
        const val RESULT = "shippy.library.collection.action"
        const val KEY_COLLECTION_ID = "collection_id"
        const val KEY_ACTION = "action"

        private const val TAG = "library_collection_actions"
        private const val ARG_COLLECTION_ID = "collection_id_arg"
        private const val ARG_TITLE = "title"
        private const val ARG_ARTWORK = "artwork"
        private const val ARG_SYSTEM = "system"
        private const val ARG_PINNED = "pinned"

        fun show(
            manager: FragmentManager,
            collectionId: String,
            title: String,
            isSystem: Boolean,
            isPinned: Boolean,
            artwork: String?,
        ) {
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            LibraryCollectionActionsSheet()
                .apply {
                    arguments =
                        Bundle().apply {
                            putString(ARG_COLLECTION_ID, collectionId)
                            putString(ARG_TITLE, title)
                            putString(ARG_ARTWORK, artwork)
                            putBoolean(ARG_SYSTEM, isSystem)
                            putBoolean(ARG_PINNED, isPinned)
                        }
                }
                .show(manager, TAG)
        }
    }
}
