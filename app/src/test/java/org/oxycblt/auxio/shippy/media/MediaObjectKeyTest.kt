/*
 * Copyright (c) 2026 Auxio Project
 * MediaObjectKeyTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId

class MediaObjectKeyTest {
    @Test
    fun `same provider rendition ignores queue occurrence and rotating locator`() {
        val original = candidate(locator = "https://stream.example/one")
        val refreshed = candidate(locator = "https://stream.example/two")

        assertEquals(MediaObjectKey.from(original), MediaObjectKey.from(refreshed))
    }

    @Test
    fun `different rendition does not collide`() {
        val low = candidate(bitrate = 128_000)
        val high = candidate(bitrate = 320_000)

        assertNotEquals(MediaObjectKey.from(low), MediaObjectKey.from(high))
    }

    @Test
    fun `different provider recording does not collide`() {
        val first = candidate(sourceItemId = "recording-one")
        val second = candidate(sourceItemId = "recording-two")

        assertNotEquals(MediaObjectKey.from(first), MediaObjectKey.from(second))
    }

    @Test
    fun `canonical source reference keys are stable and distinguish media variants`() {
        val sourceId =
            app.shippy.core.identity.SourceReferenceId(java.util.UUID.randomUUID().toString())
        val keyDefault1 = MediaObjectKey.fromSourceReference(sourceId, "DEFAULT")
        val keyDefault2 = MediaObjectKey.fromSourceReference(sourceId, "DEFAULT")
        val keyLossless = MediaObjectKey.fromSourceReference(sourceId, "LOSSLESS")

        assertEquals(keyDefault1, keyDefault2)
        assertNotEquals(keyDefault1, keyLossless)
        org.junit.Assert.assertTrue(keyDefault1.value.startsWith(MediaObjectKey.CACHE_KEY_PREFIX))
    }

    private fun candidate(
        sourceItemId: String = "recording-one",
        locator: String = "https://stream.example/audio",
        bitrate: Int = 256_000,
    ) =
        TrackCandidate(
            id = CandidateId("ytmusic:$sourceItemId"),
            trackId = TrackId("track-one"),
            kind = CandidateKind.PROVIDER,
            sourceId = "youtube-music",
            sourceItemId = sourceItemId,
            availability = CandidateAvailability.RESOLVABLE,
            locator = locator,
            providerId = ProviderId("youtube-music"),
            media =
                MediaDescriptor(container = "webm", mimeType = "audio/webm", bitrateBps = bitrate),
        )
}
