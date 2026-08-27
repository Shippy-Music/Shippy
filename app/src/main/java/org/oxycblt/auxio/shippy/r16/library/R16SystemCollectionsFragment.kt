/*
 * Copyright (c) 2026 Auxio Project
 * R16SystemCollectionsFragment.kt is part of Auxio.
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
import android.view.View
import androidx.fragment.app.Fragment
import app.shippy.core.library.R16SystemCollection
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16SystemCollectionsBinding
import org.oxycblt.auxio.pushR16Destination

/** Small Library entrypoint for rule-driven views, distinct from mutable playlists. */
@AndroidEntryPoint
internal class R16SystemCollectionsFragment : Fragment(R.layout.fragment_r16_system_collections) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        FragmentR16SystemCollectionsBinding.bind(view).apply {
            r16CollectionsLiked.setOnClickListener { open(R16SystemCollection.LIKED) }
            r16CollectionsDownloads.setOnClickListener { open(R16SystemCollection.DOWNLOADS) }
            r16CollectionsLocal.setOnClickListener { open(R16SystemCollection.LOCAL) }
        }
    }

    private fun open(collection: R16SystemCollection) {
        pushR16Destination(
            R16SystemCollectionDetailFragment.newInstance(collection),
            "r16-system-collection-${collection.id}",
        )
    }
}
