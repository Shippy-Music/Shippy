/*
 * Copyright (c) 2026 Auxio Project
 * R16SyncedLyricsAdapter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MR
import org.oxycblt.auxio.databinding.ItemR16SyncedLyricBinding
import org.oxycblt.auxio.shippy.lyrics.SyncedLyricLine
import org.oxycblt.auxio.util.getAttrColorCompat

internal class R16SyncedLyricsAdapter(private val onLineClick: (SyncedLyricLine) -> Unit) :
    RecyclerView.Adapter<R16SyncedLyricsAdapter.ViewHolder>() {
    private var lines: List<SyncedLyricLine> = emptyList()
    private var activeLineIndex: Int = -1

    fun submitLines(newLines: List<SyncedLyricLine>) {
        lines = newLines
        notifyDataSetChanged()
    }

    fun setActiveIndex(newIndex: Int) {
        if (activeLineIndex == newIndex) return
        val prevIndex = activeLineIndex
        activeLineIndex = newIndex
        if (prevIndex in lines.indices) notifyItemChanged(prevIndex)
        if (newIndex in lines.indices) notifyItemChanged(newIndex)
    }

    override fun getItemCount(): Int = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemR16SyncedLyricBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onLineClick,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(lines[position], position == activeLineIndex)
    }

    internal class ViewHolder(
        private val binding: ItemR16SyncedLyricBinding,
        private val onLineClick: (SyncedLyricLine) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(line: SyncedLyricLine, isActive: Boolean) {
            val context = binding.root.context
            val activeColor = context.getAttrColorCompat(MR.attr.colorOnSurface)
            val inactiveColor = context.getAttrColorCompat(MR.attr.colorOutline)

            binding.lyricLineText.apply {
                text = line.text
                if (isActive) {
                    setTextColor(activeColor)
                    setTypeface(null, Typeface.BOLD)
                    textSize = 20f
                } else {
                    setTextColor(inactiveColor)
                    setTypeface(null, Typeface.NORMAL)
                    textSize = 16f
                }
                setOnClickListener { onLineClick(line) }
            }
        }
    }
}
