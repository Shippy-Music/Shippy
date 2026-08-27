/*
 * Copyright (c) 2026 Auxio Project
 * R16LibrarySearchSectionHeaderAdapter.kt is part of Auxio.
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

import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isGone
import androidx.recyclerview.widget.RecyclerView

/** One lightweight section title per paged result stream; hidden while Library Search is empty. */
internal class R16LibrarySearchSectionHeaderAdapter(private val titleRes: Int) :
    RecyclerView.Adapter<R16LibrarySearchSectionHeaderAdapter.ViewHolder>() {
    private var visible = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            TextView(parent.context).apply {
                setPadding(0, 24, 0, 8)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
                )
                setText(titleRes)
            }
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(visible)

    override fun getItemCount(): Int = 1

    fun setVisible(next: Boolean) {
        if (visible == next) return
        visible = next
        notifyItemChanged(0)
    }

    internal class ViewHolder(private val view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(visible: Boolean) {
            view.isGone = !visible
        }
    }
}
