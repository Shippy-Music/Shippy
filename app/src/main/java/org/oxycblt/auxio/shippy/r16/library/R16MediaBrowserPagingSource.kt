/*
 * Copyright (c) 2026 Auxio Project
 * R16MediaBrowserPagingSource.kt is part of Auxio.
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

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.shippy.data.browser.R16MediaBrowserPage
import app.shippy.data.browser.R16MediaBrowserPageRequest
import app.shippy.data.browser.R16MediaBrowserRepository
import kotlinx.coroutines.CancellationException

/** Adapts the bounded canonical browser pages to Paging without introducing a second data path. */
internal class R16MediaBrowserPagingSource<Item : Any>(
    private val loadPage: suspend (R16MediaBrowserPageRequest) -> R16MediaBrowserPage<Item>
) : PagingSource<Int, Item>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Item> {
        val offset = params.key ?: 0
        val pageSize = params.loadSize.coerceAtMost(R16MediaBrowserRepository.MAX_PAGE_SIZE)
        return try {
            val page = loadPage(R16MediaBrowserPageRequest(offset = offset, pageSize = pageSize))
            LoadResult.Page(
                data = page.items,
                prevKey = if (offset == 0) null else (offset - page.pageSize).coerceAtLeast(0),
                nextKey = page.nextOffset?.takeIf { it > offset },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Item>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.plus(state.config.pageSize)
            ?: page.nextKey?.minus(state.config.pageSize)
    }
}
