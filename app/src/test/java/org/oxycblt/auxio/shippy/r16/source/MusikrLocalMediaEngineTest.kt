/*
 * Copyright (c) 2026 Auxio Project
 * MusikrLocalMediaEngineTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.source

import app.shippy.core.music.ExternalIdentifierKind
import app.shippy.core.music.VersionKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusikrLocalMediaEngineTest {
    @Test
    fun `snapshot maps exact Musikr identity and device asset without public locator`() {
        val capturedAt = Instant.ofEpochMilli(123)
        val observation =
            MusikrSongSnapshot(
                    uid = "ums00000000-0000-0000-0000-000000000001",
                    title = "Example (Live)",
                    artistNames = listOf("Artist"),
                    releaseTitle = "Release",
                    uri = "content://media/external/audio/media/7",
                    documentId = null,
                    mediaStoreId = 7,
                    pathToken = "external:primary/Music/example.flac",
                    mimeType = "audio/flac",
                    size = 42,
                    durationMs = 180_000,
                    bitrateBps = 900_000,
                    sampleRateHz = 48_000,
                    modifiedAtEpochMs = 100,
                    musicBrainzRecordingId = "00000000-0000-0000-0000-000000000001",
                )
                .toObservation(capturedAt)

        assertEquals("ums00000000-0000-0000-0000-000000000001", observation.sourceKey.sourceItemId)
        assertEquals(VersionKind.LIVE, observation.version.kind)
        assertEquals(
            "content://media/external/audio/media/7",
            observation.asset?.location?.opaqueHandle,
        )
        assertEquals(7L, observation.asset?.mediaStoreId)
        assertEquals(capturedAt, observation.asset?.verifiedAt)
        assertEquals(
            ExternalIdentifierKind.MUSICBRAINZ_RECORDING,
            observation.externalIdentifiers.single().kind,
        )
        assertNull(observation.originalUrl)
    }
}
