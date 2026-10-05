/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryFragment.kt is part of Auxio.
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
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentR16LibraryBinding
import org.oxycblt.auxio.pushR16Destination

/** Primary Library host fragment embedding tabs for Songs, Playlists, Artists, and Collections. */
@AndroidEntryPoint
class R16LibraryFragment : Fragment(R.layout.fragment_r16_library) {
    private var binding: FragmentR16LibraryBinding? = null
    private var tabLayoutMediator: TabLayoutMediator? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val bound = FragmentR16LibraryBinding.bind(view)
        binding = bound
        bound.r16LibraryToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_search) {
                pushR16Destination(R16LibrarySearchFragment(), "r16-library-search")
                true
            } else {
                false
            }
        }

        val adapter = LibraryPagerAdapter(this)
        bound.r16LibraryPager.adapter = adapter

        tabLayoutMediator =
            TabLayoutMediator(bound.r16LibraryTabs, bound.r16LibraryPager) { tab, position ->
                    tab.text =
                        when (position) {
                            0 -> getString(R.string.r16_library_songs)
                            1 -> getString(R.string.r16_library_albums)
                            2 -> getString(R.string.r16_library_artists)
                            3 -> getString(R.string.r16_library_genres)
                            4 -> getString(R.string.r16_library_playlists)
                            5 -> getString(R.string.r16_library_collections)
                            else -> ""
                        }
                }
                .also { it.attach() }
    }

    override fun onDestroyView() {
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        binding?.r16LibraryPager?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private class LibraryPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 6

        override fun createFragment(position: Int): Fragment =
            when (position) {
                0 -> R16LibrarySongsFragment()
                1 -> R16LibraryAlbumsFragment()
                2 -> R16LibraryArtistsFragment()
                3 -> R16LibraryGenresFragment()
                4 -> R16LibraryPlaylistsFragment()
                5 -> R16SystemCollectionsFragment()
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
    }
}
