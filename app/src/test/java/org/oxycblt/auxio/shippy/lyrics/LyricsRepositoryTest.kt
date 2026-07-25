/*
 * Copyright (c) 2026 Shippy contributors
 * LyricsRepositoryTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.TrackId

class LyricsRepositoryTest {
    @Test
    fun `matching rejects a different recording version`() {
        val request = request("Midnight City", "M83", "Hurry Up, We're Dreaming", 244_000)
        val wrong =
            record(
                id = 1,
                title = "Midnight City Live Remix",
                artist = "Other Artist",
                album = "Festival Bootlegs",
                durationSeconds = 401.0,
            )

        assertNull(selectBestLyrics(request, listOf(wrong)))
    }

    @Test
    fun `exact endpoint response still passes the recording identity gate`() {
        val request = request("Song", "Artist", "Album", 180_000)
        val wrong =
            record(
                id = 7,
                title = "Song Live Remix",
                artist = "Cover Band",
                album = "Festival",
                durationSeconds = 320.0,
                synced = "[00:01.00] Wrong recording",
            )

        assertNull(selectBestLyrics(request, listOf(wrong)))
    }

    @Test
    fun `synced result wins only inside the identity margin`() {
        val request = request("Midnight City", "M83", "Hurry Up, We're Dreaming", 244_000)
        val plain =
            record(
                id = 2,
                title = "Midnight City",
                artist = "M83",
                album = "Hurry Up, We're Dreaming",
                durationSeconds = 244.0,
                plain = "Waiting in a car",
            )
        val synced =
            record(
                id = 3,
                title = "Midnight City",
                artist = "M83",
                album = "Hurry Up We're Dreaming",
                durationSeconds = 246.0,
                synced = "[00:01.00] Waiting in a car",
            )

        assertEquals(synced, selectBestLyrics(request, listOf(plain, synced)))
    }

    @Test
    fun `record parses synced lyrics before plain lyrics`() {
        val record =
            record(
                id = 4,
                title = "Track",
                artist = "Artist",
                album = "Album",
                durationSeconds = 120.0,
                plain = "Plain",
                synced = "[00:01.25] Synced",
            )

        val parsed = record.parse()

        assertTrue(parsed is SyncedLyrics)
        assertEquals(1250L, (parsed as SyncedLyrics).lines.single().startMs)
    }

    @Test
    fun `instrumental record is a successful empty lyric document`() {
        val record =
            record(
                id = 5,
                title = "Instrumental",
                artist = "Artist",
                album = null,
                durationSeconds = null,
                instrumental = true,
            )

        assertEquals(PlainLyrics(""), record.parse())
    }

    @Test
    fun `source chain falls back and preserves priority`() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val fallbackRecord =
            record(
                id = 6,
                title = "Track",
                artist = "Artist",
                album = null,
                durationSeconds = null,
                plain = "Lyrics",
            )
        val repository =
            ChainedLyricsRepository(
                setOf(
                    FakeSource("fallback", 100, calls, LyricsLookupResult.Found(fallbackRecord, PlainLyrics("Lyrics"))),
                    FakeSource("primary", 10, calls, LyricsLookupResult.NotFound),
                )
            )

        val result = repository.lookup(request("Track", "Artist", null, null))

        assertTrue(result is LyricsLookupResult.Found)
        assertEquals(listOf("primary", "fallback"), calls)
    }

    private fun request(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
    ) =
        LyricsRequest(
            trackId = TrackId("track"),
            title = title,
            artists = listOf(artist),
            album = album,
            durationMs = durationMs,
        )

    private fun record(
        id: Long,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Double?,
        plain: String? = null,
        synced: String? = null,
        instrumental: Boolean = false,
    ) =
        LyricsRecord(
            id = id,
            trackName = title,
            artistName = artist,
            albumName = album,
            durationSeconds = durationSeconds,
            instrumental = instrumental,
            plainLyrics = plain,
            syncedLyrics = synced,
        )

    private class FakeSource(
        override val id: String,
        override val priority: Int,
        private val calls: MutableList<String>,
        private val result: LyricsLookupResult,
    ) : LyricsSource {
        override suspend fun lookup(request: LyricsRequest): LyricsLookupResult {
            calls += id
            return result
        }
    }
}
