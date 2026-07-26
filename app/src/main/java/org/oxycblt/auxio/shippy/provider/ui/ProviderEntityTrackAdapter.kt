/*
 * Copyright (c) 2026 Shippy contributors
 * ProviderEntityTrackAdapter.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.provider.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import org.oxycblt.auxio.search.ProviderTrackItem
import org.oxycblt.auxio.search.ProviderTrackViewHolder
import org.oxycblt.auxio.shippy.domain.Track

internal class ProviderEntityTrackAdapter(
    private val providerName: String,
    private val onClick: (index: Int) -> Unit,
) : ListAdapter<Track, ProviderTrackViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ProviderTrackViewHolder.from(parent)

    override fun onBindViewHolder(holder: ProviderTrackViewHolder, position: Int) {
        holder.bind(ProviderTrackItem(providerName, getItem(position))) {
            val index = holder.bindingAdapterPosition
            if (index != androidx.recyclerview.widget.RecyclerView.NO_POSITION) onClick(index)
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<Track>() {
                override fun areItemsTheSame(oldItem: Track, newItem: Track) =
                    oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: Track, newItem: Track) =
                    oldItem == newItem
            }
    }
}
