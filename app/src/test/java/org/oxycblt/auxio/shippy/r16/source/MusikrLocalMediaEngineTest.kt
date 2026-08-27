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
        assertEquals("local-file", observation.sourceKey.providerId.value)
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

    @Test
    fun `plain lexical titles do not produce version traits`() {
        assertEquals(VersionKind.ORIGINAL, extractLocalRecordingVersion("Live Forever").kind)
        assertEquals(VersionKind.ORIGINAL, extractLocalRecordingVersion("Acoustic Love").kind)
        assertEquals(VersionKind.ORIGINAL, extractLocalRecordingVersion("Remix My Heart").kind)
        assertEquals(VersionKind.ORIGINAL, extractLocalRecordingVersion("Yesterday").kind)
        assertEquals(VersionKind.ORIGINAL, extractLocalRecordingVersion("").kind)
        assertEquals(VersionKind.ORIGINAL, extractLocalRecordingVersion(null).kind)
    }

    @Test
    fun `explicit qualifiers produce correct version kinds and traits`() {
        val live = extractLocalRecordingVersion("Track (Live)")
        assertEquals(VersionKind.LIVE, live.kind)
        assertEquals(setOf(app.shippy.core.music.VersionTrait.LIVE), live.traits)

        val remix = extractLocalRecordingVersion("Track [Club Remix]")
        assertEquals(VersionKind.REMIX, remix.kind)
        assertEquals(setOf(app.shippy.core.music.VersionTrait.REMIX), remix.traits)

        val acoustic = extractLocalRecordingVersion("Track - Acoustic")
        assertEquals(VersionKind.ACOUSTIC, acoustic.kind)
        assertEquals(setOf(app.shippy.core.music.VersionTrait.ACOUSTIC), acoustic.traits)

        val multi = extractLocalRecordingVersion("Track (Live Acoustic)")
        assertEquals(VersionKind.LIVE, multi.kind)
        assertEquals(
            setOf(
                app.shippy.core.music.VersionTrait.LIVE,
                app.shippy.core.music.VersionTrait.ACOUSTIC,
            ),
            multi.traits,
        )

        val remaster = extractLocalRecordingVersion("Track (2021 Remaster)")
        assertEquals(VersionKind.REMASTER, remaster.kind)
        assertEquals(setOf(app.shippy.core.music.VersionTrait.REMASTERED), remaster.traits)

        val unicodeDash = extractLocalRecordingVersion("Track — Instrumental")
        assertEquals(VersionKind.INSTRUMENTAL, unicodeDash.kind)
        assertEquals(setOf(app.shippy.core.music.VersionTrait.INSTRUMENTAL), unicodeDash.traits)
    }
}
