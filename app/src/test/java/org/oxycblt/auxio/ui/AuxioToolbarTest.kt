/*
 * Copyright (c) 2026 Auxio Project
 * AuxioToolbarTest.kt is part of Auxio.
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
package org.oxycblt.auxio.ui

import android.app.Activity
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.R
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuxioToolbarTest {
    private fun toolbar(): AuxioToolbar {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Auxio_App)
        return AuxioToolbar(activity).also {
            activity.setContentView(it)
            it.inflateMenu(R.menu.shippy_collection_detail)
        }
    }

    @Test
    fun searchButtonAttachesInputAndNavigationCollapsesIt() {
        val toolbar = toolbar()
        val item = toolbar.menu.findItem(R.id.action_search)
        toolbar.setOnMenuItemClickListener { it.expandActionView() }
        var navigated = false
        toolbar.setNavigationOnClickListener { navigated = true }
        toolbar.getMenuButton(R.id.action_search)!!.performClick()
        val search = item.actionView as SearchView
        assertTrue(item.isActionViewExpanded)
        assertTrue(search.parent is ViewGroup)
        assertFalse(search.isIconified)
        search.setQuery("matching song", false)
        toolbar.findViewById<android.view.View>(R.id.toolbar_navigation_button).performClick()
        assertFalse(navigated)
        assertFalse(item.isActionViewExpanded)
        assertNull(search.parent)
        assertEquals("", search.query.toString())
    }

    @Test
    fun menuMutationsUpdateExistingButtonsAndRevealHiddenActions() {
        val toolbar = toolbar()
        val search = toolbar.menu.findItem(R.id.action_search)
        val button = toolbar.getMenuButton(search.itemId)
        var hierarchyChanges = 0
        toolbar
            .findViewById<ViewGroup>(R.id.toolbar_action_group)
            .setOnHierarchyChangeListener(
                object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(
                        parent: android.view.View?,
                        child: android.view.View?,
                    ) {
                        hierarchyChanges++
                    }

                    override fun onChildViewRemoved(
                        parent: android.view.View?,
                        child: android.view.View?,
                    ) {
                        hierarchyChanges++
                    }
                }
            )
        search.isEnabled = false
        search.title = "Find in this playlist"
        assertEquals(0, hierarchyChanges)
        assertSame(button, toolbar.getMenuButton(search.itemId))
        assertFalse(button!!.isEnabled)
        assertEquals(search.title, button.contentDescription)
        search.isVisible = false
        assertNull(toolbar.getMenuButton(search.itemId))
        val rename = toolbar.menu.findItem(R.id.action_rename)
        rename.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        rename.isVisible = true
        assertNotNull(toolbar.getMenuButton(rename.itemId))
    }
}
