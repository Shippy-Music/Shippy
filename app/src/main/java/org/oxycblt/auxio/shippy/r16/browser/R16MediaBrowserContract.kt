/*
 * Copyright (c) 2026 Auxio Project
 * R16MediaBrowserContract.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.browser

/** Stable keys shared by R16 MediaBrowser callers and the service boundary. */
object R16MediaBrowserContract {
    const val EXTRA_SHUFFLE_SEED = "org.oxycblt.auxio.shippy.r16.browser.extra.SHUFFLE_SEED"
    const val EXTRA_PLAYLIST_FILTER = "org.oxycblt.auxio.shippy.r16.browser.extra.PLAYLIST_FILTER"
    const val EXTRA_PLAYLIST_SORT_MODE =
        "org.oxycblt.auxio.shippy.r16.browser.extra.PLAYLIST_SORT_MODE"
    const val EXTRA_PLAYLIST_SORT_DIRECTION =
        "org.oxycblt.auxio.shippy.r16.browser.extra.PLAYLIST_SORT_DIRECTION"
    /** Applied visible query, scoped by the Library Songs or system-collection media ID. */
    const val EXTRA_SONGS_QUERY = "org.oxycblt.auxio.shippy.r16.browser.extra.SONGS_QUERY"
}
