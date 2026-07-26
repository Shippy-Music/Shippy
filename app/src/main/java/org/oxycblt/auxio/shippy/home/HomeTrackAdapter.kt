/*
 * Copyright (c) 2026 Shippy contributors
 * HomeTrackAdapter.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemHomeTrackBinding

internal data class HomeTrackRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val artwork: String?,
)

internal class HomeTrackAdapter(
    private val onClick: (HomeTrackRow) -> Unit,
) : ListAdapter<HomeTrackRow, HomeTrackAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemHomeTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(
        private val binding: ItemHomeTrackBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HomeTrackRow) {
            binding.homeTrackCover.bindArtwork(row.artwork, row.title)
            binding.homeTrackTitle.text = row.title
            binding.homeTrackSubtitle.text = row.subtitle
            binding.root.setOnClickListener { onClick(row) }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<HomeTrackRow>() {
                override fun areItemsTheSame(oldItem: HomeTrackRow, newItem: HomeTrackRow) =
                    oldItem.key == newItem.key

                override fun areContentsTheSame(oldItem: HomeTrackRow, newItem: HomeTrackRow) =
                    oldItem == newItem
            }
    }
}
