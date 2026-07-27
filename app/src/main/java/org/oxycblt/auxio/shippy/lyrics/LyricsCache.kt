/*
 * Copyright (c) 2026 Auxio Project
 * LyricsCache.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.lyrics

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache identity is deliberately narrower than a title alone: a canonical track can only reuse
 * cached lyrics when its normalized recording metadata is still exactly the same.
 */
internal data class LyricsCacheKey(
    val trackId: String,
    val fingerprint: String,
    val titleKey: String,
    val artistsKey: String,
    val albumKey: String,
    val durationSeconds: Long,
)

internal fun LyricsRequest.cacheKey(): LyricsCacheKey {
    val titleKey = normalizeLyricsIdentity(title)
    val artistsKey = artists.joinToString(" ", transform = ::normalizeLyricsIdentity)
    val albumKey = normalizeLyricsIdentity(album.orEmpty())
    // -1 means that the provider did not give a duration. It is not a valid duration value.
    val durationSeconds = durationMs?.div(1_000) ?: -1L
    val fingerprint =
        listOf(titleKey, artistsKey, albumKey, durationSeconds.toString()).joinToString("\u001F")
    return LyricsCacheKey(
        trackId.value,
        fingerprint,
        titleKey,
        artistsKey,
        albumKey,
        durationSeconds,
    )
}

internal fun normalizeLyricsIdentity(value: String): String =
    value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

@Entity(
    tableName = "lyrics_cache",
    primaryKeys = ["trackId", "fingerprint"],
    indices = [Index("cachedAtEpochMs")],
)
data class LyricsCacheEntity(
    val trackId: String,
    val fingerprint: String,
    val titleKey: String,
    val artistsKey: String,
    val albumKey: String,
    val durationSeconds: Long,
    val sourceId: String,
    val recordId: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val recordDurationSeconds: Double?,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val cachedAtEpochMs: Long,
)

@Dao
abstract class LyricsCacheDao {
    @Query(
        """
        SELECT * FROM lyrics_cache
        WHERE trackId = :trackId AND fingerprint = :fingerprint
        LIMIT 1
        """
    )
    abstract suspend fun get(trackId: String, fingerprint: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsert(entity: LyricsCacheEntity)

    @Query(
        """
        DELETE FROM lyrics_cache
        WHERE rowid IN (
            SELECT rowid FROM lyrics_cache
            ORDER BY cachedAtEpochMs DESC, trackId DESC, fingerprint DESC
            LIMIT -1 OFFSET :maxEntries
        )
        """
    )
    protected abstract suspend fun evictBeyond(maxEntries: Int)

    @Query("DELETE FROM lyrics_cache WHERE trackId = :trackId AND fingerprint = :fingerprint")
    abstract suspend fun remove(trackId: String, fingerprint: String)

    @androidx.room.Transaction
    open suspend fun putBounded(entity: LyricsCacheEntity, maxEntries: Int) {
        upsert(entity)
        evictBeyond(maxEntries)
    }
}

interface LyricsCache {
    suspend fun get(request: LyricsRequest): CachedLyrics?

    suspend fun put(request: LyricsRequest, record: LyricsRecord, cachedAtEpochMs: Long)

    suspend fun remove(request: LyricsRequest)
}

data class CachedLyrics(val record: LyricsRecord, val cachedAtEpochMs: Long)

fun interface LyricsCacheClock {
    fun nowEpochMs(): Long
}

@Singleton
class SystemLyricsCacheClock @Inject constructor() : LyricsCacheClock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

@Singleton
class RoomLyricsCache @Inject constructor(private val dao: LyricsCacheDao) : LyricsCache {
    override suspend fun get(request: LyricsRequest): CachedLyrics? {
        val key = request.cacheKey()
        return dao.get(key.trackId, key.fingerprint)?.let { entity ->
            CachedLyrics(entity.toRecord(), entity.cachedAtEpochMs)
        }
    }

    override suspend fun put(request: LyricsRequest, record: LyricsRecord, cachedAtEpochMs: Long) {
        val key = request.cacheKey()
        dao.putBounded(
            LyricsCacheEntity(
                trackId = key.trackId,
                fingerprint = key.fingerprint,
                titleKey = key.titleKey,
                artistsKey = key.artistsKey,
                albumKey = key.albumKey,
                durationSeconds = key.durationSeconds,
                sourceId = record.sourceId,
                recordId = record.id,
                trackName = record.trackName,
                artistName = record.artistName,
                albumName = record.albumName,
                recordDurationSeconds = record.durationSeconds,
                instrumental = record.instrumental,
                plainLyrics = record.plainLyrics,
                syncedLyrics = record.syncedLyrics,
                cachedAtEpochMs = cachedAtEpochMs,
            ),
            MAX_CACHE_ENTRIES,
        )
    }

    override suspend fun remove(request: LyricsRequest) {
        val key = request.cacheKey()
        dao.remove(key.trackId, key.fingerprint)
    }

    private fun LyricsCacheEntity.toRecord() =
        LyricsRecord(
            id = recordId,
            trackName = trackName,
            artistName = artistName,
            albumName = albumName,
            durationSeconds = recordDurationSeconds,
            instrumental = instrumental,
            plainLyrics = plainLyrics,
            syncedLyrics = syncedLyrics,
            sourceId = sourceId,
        )

    private companion object {
        const val MAX_CACHE_ENTRIES = 500
    }
}
