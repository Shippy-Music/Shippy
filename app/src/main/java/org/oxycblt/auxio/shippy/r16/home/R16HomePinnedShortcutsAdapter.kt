/*
 * Copyright (c) 2026 Auxio Project
 * R16HomePinnedShortcutsAdapter.kt is part of Auxio.
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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.shippy.data.home.R16HomePinnedPlaylistShortcut
import org.oxycblt.auxio.databinding.ItemR16HomePinnedShortcutBinding

/** Bounded Home-only navigation chips for canonical Library-layout playlist targets. */
internal class R16HomePinnedShortcutsAdapter(
    private val onOpen: (R16HomePinnedPlaylistShortcut) -> Unit
) : ListAdapter<R16HomePinnedPlaylistShortcut, R16HomePinnedShortcutsAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemR16HomePinnedShortcutBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
            onOpen,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    internal class ViewHolder(
        private val binding: ItemR16HomePinnedShortcutBinding,
        private val onOpen: (R16HomePinnedPlaylistShortcut) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: R16HomePinnedPlaylistShortcut) {
            binding.r16HomePinnedShortcut.text = item.name
            binding.r16HomePinnedShortcut.setOnClickListener { onOpen(item) }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<R16HomePinnedPlaylistShortcut>() {
                override fun areItemsTheSame(
                    oldItem: R16HomePinnedPlaylistShortcut,
                    newItem: R16HomePinnedPlaylistShortcut,
                ) = oldItem.playlistId == newItem.playlistId

                override fun areContentsTheSame(
                    oldItem: R16HomePinnedPlaylistShortcut,
                    newItem: R16HomePinnedPlaylistShortcut,
                ) = oldItem == newItem
            }
    }
}
