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
                    FakeSource(
                        "fallback",
                        100,
                        calls,
                        LyricsLookupResult.Found(fallbackRecord, PlainLyrics("Lyrics")),
                    ),
                    FakeSource("primary", 10, calls, LyricsLookupResult.NotFound),
                ),
                FakeCache(),
                FakeClock(),
            )

        val result = repository.lookup(request("Track", "Artist", null, null))

        assertTrue(result is LyricsLookupResult.Found)
        assertEquals(listOf("primary", "fallback"), calls)
    }

    @Test
    fun `cache hit serves lyrics offline without consulting a source`() = kotlinx.coroutines.runBlocking {
        val request = request("Track", "Artist", "Album", 120_000)
        val cached =
            record(
                id = 8,
                title = "Track",
                artist = "Artist",
                album = "Album",
                durationSeconds = 120.0,
                synced = "[00:01.00] Cached",
            )
        val calls = mutableListOf<String>()
        val repository =
            ChainedLyricsRepository(
                setOf(FakeSource("network", 1, calls, LyricsLookupResult.NotFound)),
                FakeCache(mapOf(request.cacheKey() to CachedLyrics(cached, 100))),
                FakeClock(100),
            )

        val result = repository.lookup(request)

        assertEquals(LyricsLookupResult.Found(cached, cached.parse()!!), result)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `cache miss falls back to sources and stores successful instrumental result`() =
        kotlinx.coroutines.runBlocking {
            val request = request("Instrumental", "Artist", null, null)
            val instrumental =
                record(
                    id = 9,
                    title = "Instrumental",
                    artist = "Artist",
                    album = null,
                    durationSeconds = null,
                    instrumental = true,
                )
            val cache = FakeCache()
            val repository =
                ChainedLyricsRepository(
                    setOf(
                        FakeSource(
                            "network",
                            1,
                            mutableListOf(),
                            LyricsLookupResult.Found(instrumental, PlainLyrics("")),
                        )
                    ),
                    cache,
                    FakeClock(100),
                )

            assertEquals(
                LyricsLookupResult.Found(instrumental, PlainLyrics("")),
                repository.lookup(request),
            )
            assertEquals(instrumental, cache.entries[request.cacheKey()]?.record)
        }

    @Test
    fun `cache identity prevents a reused track id from serving another recording`() {
        val original = request("Song", "Artist", "Original", 180_000)
        val remix = request("Song Remix", "Artist", "Original", 180_000)

        assertTrue(original.cacheKey() != remix.cacheKey())
    }

    @Test
    fun `invalid cache entry is removed before source fallback`() = kotlinx.coroutines.runBlocking {
        val request = request("Track", "Artist", null, null)
        val invalid =
            record(
                id = 10,
                title = "Track",
                artist = "Artist",
                album = null,
                durationSeconds = null,
            )
        val resolved =
            record(
                id = 11,
                title = "Track",
                artist = "Artist",
                album = null,
                durationSeconds = null,
                plain = "Fresh lyrics",
            )
        val cache = FakeCache(mapOf(request.cacheKey() to CachedLyrics(invalid, 100)))
        val repository =
            ChainedLyricsRepository(
                setOf(
                    FakeSource(
                        "network",
                        1,
                        mutableListOf(),
                        LyricsLookupResult.Found(resolved, PlainLyrics("Fresh lyrics")),
                    )
                ),
                cache,
                FakeClock(100),
            )

        assertEquals(
            LyricsLookupResult.Found(resolved, PlainLyrics("Fresh lyrics")),
            repository.lookup(request),
        )
        assertEquals(resolved, cache.entries[request.cacheKey()]?.record)
        assertEquals(listOf(request.cacheKey()), cache.removed)
    }

    @Test
    fun `stale cache is retained when every source is unavailable`() = kotlinx.coroutines.runBlocking {
        val request = request("Track", "Artist", null, null)
        val cached =
            record(
                id = 12,
                title = "Track",
                artist = "Artist",
                album = null,
                durationSeconds = null,
                plain = "Offline lyrics",
            )
        val calls = mutableListOf<String>()
        val repository =
            ChainedLyricsRepository(
                setOf(
                    FakeSource(
                        "network",
                        1,
                        calls,
                        LyricsLookupResult.Failure(
                            LyricsFailureKind.NETWORK,
                            retryable = true,
                        ),
                    )
                ),
                FakeCache(mapOf(request.cacheKey() to CachedLyrics(cached, 0))),
                FakeClock(8L * 24 * 60 * 60 * 1_000),
            )

        assertEquals(
            LyricsLookupResult.Found(cached, PlainLyrics("Offline lyrics")),
            repository.lookup(request),
        )
        assertEquals(listOf("network"), calls)
    }

    @Test
    fun `stale cache refreshes from a successful source`() = kotlinx.coroutines.runBlocking {
        val request = request("Track", "Artist", null, null)
        val stale =
            record(
                id = 13,
                title = "Track",
                artist = "Artist",
                album = null,
                durationSeconds = null,
                plain = "Old lyrics",
            )
        val fresh =
            record(
                id = 14,
                title = "Track",
                artist = "Artist",
                album = null,
                durationSeconds = null,
                plain = "New lyrics",
            )
        val cache = FakeCache(mapOf(request.cacheKey() to CachedLyrics(stale, 0)))
        val now = 8L * 24 * 60 * 60 * 1_000
        val repository =
            ChainedLyricsRepository(
                setOf(
                    FakeSource(
                        "network",
                        1,
                        mutableListOf(),
                        LyricsLookupResult.Found(fresh, PlainLyrics("New lyrics")),
                    )
                ),
                cache,
                FakeClock(now),
            )

        assertEquals(
            LyricsLookupResult.Found(fresh, PlainLyrics("New lyrics")),
            repository.lookup(request),
        )
        assertEquals(CachedLyrics(fresh, now), cache.entries[request.cacheKey()])
    }

    @Test
    fun `definitive source miss invalidates stale cache`() = kotlinx.coroutines.runBlocking {
        val request = request("Track", "Artist", null, null)
        val stale =
            record(
                id = 15,
                title = "Track",
                artist = "Artist",
                album = null,
                durationSeconds = null,
                plain = "Obsolete lyrics",
            )
        val cache = FakeCache(mapOf(request.cacheKey() to CachedLyrics(stale, 0)))
        val repository =
            ChainedLyricsRepository(
                setOf(
                    FakeSource(
                        "network",
                        1,
                        mutableListOf(),
                        LyricsLookupResult.NotFound,
                    )
                ),
                cache,
                FakeClock(8L * 24 * 60 * 60 * 1_000),
            )

        assertEquals(LyricsLookupResult.NotFound, repository.lookup(request))
        assertFalse(cache.entries.containsKey(request.cacheKey()))
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

    private class FakeCache(
        initial: Map<LyricsCacheKey, CachedLyrics> = emptyMap(),
    ) : LyricsCache {
        val entries = initial.toMutableMap()
        val removed = mutableListOf<LyricsCacheKey>()

        override suspend fun get(request: LyricsRequest): CachedLyrics? = entries[request.cacheKey()]

        override suspend fun put(
            request: LyricsRequest,
            record: LyricsRecord,
            cachedAtEpochMs: Long,
        ) {
            entries[request.cacheKey()] = CachedLyrics(record, cachedAtEpochMs)
        }

        override suspend fun remove(request: LyricsRequest) {
            val key = request.cacheKey()
            removed += key
            entries.remove(key)
        }
    }

    private class FakeClock(
        private val nowEpochMs: Long = 0,
    ) : LyricsCacheClock {
        override fun nowEpochMs(): Long = nowEpochMs
    }
}
