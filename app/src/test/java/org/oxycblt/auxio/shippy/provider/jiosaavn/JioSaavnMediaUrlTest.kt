/*
 * Copyright (c) 2026 Auxio Project
 * JioSaavnMediaUrlTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider.jiosaavn

import org.junit.Assert.assertEquals
import org.junit.Test

class JioSaavnMediaUrlTest {
    @Test
    fun `donor DES media URL is decoded and sanitized`() {
        assertEquals(
            "https://example.test/audio_96.mp4",
            JioSaavnMediaUrl.decode(
                "GcRAsZwDauvrg33FNzm387nSeNKjABoQsa1xxv++mkrP3qvFt5bTKvUcZXRcCSW8"
            ),
        )
    }

    @Test
    fun `quality suffix is selected without changing unrelated digits`() {
        assertEquals(
            "https://cdn.test/320/track_160.mp4?token=_96",
            JioSaavnMediaUrl.selectQuality(
                "https://cdn.test/320/track_96.mp4?token=_96",
                JioSaavnQuality.MEDIUM,
            ),
        )
    }

    @Test
    fun `URL without a quality suffix is preserved`() {
        val url = "https://cdn.test/audio.m4a"

        assertEquals(url, JioSaavnMediaUrl.selectQuality(url, JioSaavnQuality.HIGH))
    }
}
