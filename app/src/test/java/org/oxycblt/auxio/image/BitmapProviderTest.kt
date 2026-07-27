/*
 * Copyright (c) 2026 Auxio Project
 * BitmapProviderTest.kt is part of Auxio.
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
package org.oxycblt.auxio.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapProviderTest {
    @Test
    fun acceptsOnlyCredentialFreeHttpsArtwork() {
        assertTrue(BitmapProvider.isValidArtworkUrl("https://images.example.test/cover.jpg"))
        assertFalse(BitmapProvider.isValidArtworkUrl(null))
        assertFalse(BitmapProvider.isValidArtworkUrl(""))
        assertFalse(BitmapProvider.isValidArtworkUrl(" https://images.example.test/cover.jpg"))
        assertFalse(BitmapProvider.isValidArtworkUrl("http://images.example.test/cover.jpg"))
        assertFalse(
            BitmapProvider.isValidArtworkUrl("https://user:pass@images.example.test/cover.jpg")
        )
        assertFalse(BitmapProvider.isValidArtworkUrl("https:///cover.jpg"))
        assertFalse(BitmapProvider.isValidArtworkUrl("file:///data/user/0/cover.jpg"))
    }
}
