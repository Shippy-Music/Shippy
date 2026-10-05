/*
 * Copyright (c) 2026 Auxio Project
 * R16ShellNavigationTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.shell

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.R
import org.oxycblt.auxio.pushR16Destination
import org.oxycblt.auxio.shippy.r16.search.R16GlobalSearchFragment
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Exercises the production non-destructive R16 navigation transaction directly. */
@RunWith(RobolectricTestRunner::class)
class R16ShellNavigationTest {
    private lateinit var activity: FragmentActivity
    private lateinit var root: RootFragment

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setContentView(
            FrameLayout(activity).apply {
                id = R.id.r16_active_content
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        )
        root = RootFragment()
        activity.supportFragmentManager
            .beginTransaction()
            .add(R.id.r16_active_content, root, "root")
            .commit()
        activity.supportFragmentManager.executePendingTransactions()
    }

    @Test
    fun detailBack_keepsTheExactOriginFragmentAndView() {
        val rootView = root.view
        val detail = DetailFragment()

        root.pushR16Destination(detail, "detail")
        activity.supportFragmentManager.executePendingTransactions()

        assertTrue(root.isHidden)
        assertSame(
            detail,
            activity.supportFragmentManager.findFragmentById(R.id.r16_active_content),
        )

        activity.supportFragmentManager.popBackStack()
        activity.supportFragmentManager.executePendingTransactions()

        assertFalse(root.isHidden)
        assertSame(root, activity.supportFragmentManager.findFragmentById(R.id.r16_active_content))
        assertSame(rootView, root.view)
    }

    @Test
    fun detailUsesVisibleOriginWhenAnotherTabWasAddedMoreRecently() {
        val otherTab = RootFragment()
        activity.supportFragmentManager
            .beginTransaction()
            .add(R.id.r16_active_content, otherTab, "other-tab")
            .hide(otherTab)
            .setMaxLifecycle(otherTab, Lifecycle.State.STARTED)
            .commit()
        activity.supportFragmentManager.executePendingTransactions()
        val rootView = root.view
        val detail = DetailFragment()

        root.pushR16Destination(detail, "detail")
        activity.supportFragmentManager.executePendingTransactions()

        assertTrue(root.isHidden)
        assertTrue(otherTab.isHidden)
        assertEquals(Lifecycle.State.STARTED, root.lifecycle.currentState)
        assertSame(detail, activity.supportFragmentManager.primaryNavigationFragment)
        activity.supportFragmentManager.popBackStackImmediate()
        assertFalse(root.isHidden)
        assertTrue(otherTab.isHidden)
        assertSame(rootView, root.view)
        assertEquals(Lifecycle.State.RESUMED, root.lifecycle.currentState)
    }

    @Test
    fun recommendationSearch_carriesBothTitleAndArtistIntoTheProductionFragment() {
        val fragment = R16GlobalSearchFragment.forRecommendation("Track Title", "Track Artist")

        assertEquals(
            "Track Title Track Artist",
            fragment.arguments?.getString("r16_global_search_initial_query"),
        )
    }

    class RootFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View = FrameLayout(requireContext())
    }

    class DetailFragment : Fragment()
}
