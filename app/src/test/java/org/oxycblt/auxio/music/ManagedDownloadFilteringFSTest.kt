/*
 * Copyright (c) 2026 Auxio Project
 * ManagedDownloadFilteringFSTest.kt is part of Auxio.
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
package org.oxycblt.auxio.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ManagedDownloadFilteringFSTest {
    @Test
    fun `exact managed URI suppresses only registered bytes`() {
        val index =
            ManagedDownloadFileIndex(
                listOf(
                    ManagedDownloadFileIdentity(
                        contentUri = "content://documents/managed",
                        contentLength = 100,
                        path = null,
                    ),
                    ManagedDownloadFileIdentity(
                        contentUri = "content://documents/pending",
                        contentLength = null,
                        path = null,
                    ),
                )
            )

        assertTrue(index.contains("content://documents/managed", path = null, contentLength = 100))
        assertTrue(index.contains("content://documents/pending", path = null, contentLength = 7))
        assertFalse(index.contains("content://documents/managed", path = null, contentLength = 99))
        assertFalse(index.contains("content://documents/user", path = null, contentLength = 100))
    }

    @Test
    fun `exact R16 pending URI suppresses any observed length`() {
        val index =
            ManagedDownloadFileIndex.from(
                context = RuntimeEnvironment.getApplication(),
                downloads = emptyList(),
                r16PendingLocations = listOf("content://documents/r16-pending"),
            )

        assertTrue(
            index.contains("content://documents/r16-pending", path = null, contentLength = 7)
        )
        assertFalse(
            index.contains("content://documents/r16-unregistered", path = null, contentLength = 7)
        )
    }
}
