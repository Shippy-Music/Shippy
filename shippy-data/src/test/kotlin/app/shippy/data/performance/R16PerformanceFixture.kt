/*
 * Copyright (c) 2026 Auxio Project
 * R16PerformanceFixture.kt is part of Auxio.
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
package app.shippy.data.performance

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import app.shippy.data.db.R16LibraryMembershipTriggers
import app.shippy.data.db.ShippyR16Database

/** Deterministic data sizes from the R16 performance contract. */
internal data class R16PerformanceProfile(
    val name: String,
    val recordingCount: Int,
    val playlistCount: Int,
    val largePlaylistEntryCount: Int,
    val historyRowCount: Int,
    val duplicateAssetStride: Int,
) {
    init {
        require(recordingCount > 0) { "A performance fixture needs recordings" }
        require(playlistCount > 0) { "A performance fixture needs playlists" }
        require(largePlaylistEntryCount > 0) { "The duplicate-capable playlist cannot be empty" }
        require(historyRowCount >= 0) { "History row count cannot be negative" }
        require(duplicateAssetStride > 0) { "Duplicate asset stride must be positive" }
    }

    val duplicateAssetCount: Int
        get() = (recordingCount + duplicateAssetStride - 1) / duplicateAssetStride

    val additionalPlaylistEntryCount: Int
        get() = (playlistCount - 1).coerceAtLeast(0)

    val totalPlaylistEntryCount: Int
        get() = largePlaylistEntryCount + additionalPlaylistEntryCount

    val totalAssetCount: Int
        get() = recordingCount + duplicateAssetCount
}

/**
 * The 100-row profile is the ordinary deterministic unit fixture. The 10k and 50k profiles are
 * excluded from routine tests; the dedicated benchmark-fixture export task seeds the 50k profile.
 */
internal object R16PerformanceProfiles {
    val hundred =
        R16PerformanceProfile(
            name = "100-recordings",
            recordingCount = 100,
            playlistCount = 8,
            largePlaylistEntryCount = 100,
            historyRowCount = 100,
            duplicateAssetStride = 10,
        )

    val tenThousand =
        R16PerformanceProfile(
            name = "10k-recordings",
            recordingCount = 10_000,
            playlistCount = 1_000,
            largePlaylistEntryCount = 10_000,
            historyRowCount = 20_000,
            duplicateAssetStride = 10,
        )

    val fiftyThousand =
        R16PerformanceProfile(
            name = "50k-recordings",
            recordingCount = 50_000,
            playlistCount = 1_000,
            largePlaylistEntryCount = 10_000,
            historyRowCount = 20_000,
            duplicateAssetStride = 10,
        )
}

internal data class R16PerformanceFixture(
    val profile: R16PerformanceProfile,
    val duplicatePlaylistId: String,
    val probeRecordingId: String,
)

/**
 * Seeds only the tables needed by the R16 Library, search, playlist, playback-source, and history
 * performance paths. Raw statements are reused inside bounded transactions so the 10k/50k profiles
 * remain practical for the benchmark-only fixture export.
 */
internal class R16PerformanceFixtureGenerator(private val database: ShippyR16Database) {
    fun seed(profile: R16PerformanceProfile): R16PerformanceFixture {
        val sqlite = database.openHelper.writableDatabase
        insertArtists(sqlite, profile)
        insertRecordings(sqlite, profile)
        insertArtistCredits(sqlite, profile)
        insertLibraryRelationships(sqlite, profile)
        insertAssets(sqlite, profile)
        insertPlaylists(sqlite, profile)
        insertPlaylistEntries(sqlite, profile)
        insertSearchDocuments(sqlite, profile)
        insertPlaybackSourceProbe(sqlite)
        insertHistory(sqlite, profile)
        database.libraryMembershipDao().rebuild()
        return R16PerformanceFixture(profile, DUPLICATE_PLAYLIST_ID, recordingId(0))
    }

    private fun insertArtists(sqlite: SupportSQLiteDatabase, profile: R16PerformanceProfile) {
        val artistCount = minOf(profile.recordingCount, ARTIST_COUNT)
        sqlite.insertRows(
            sql =
                """
                INSERT INTO artist (
                    artist_id, canonical_name, sort_name, disambiguation,
                    created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = artistCount,
        ) { index ->
            bindString(1, artistId(index))
            bindString(2, "R16 Artist ${index.toString().padStart(3, '0')}")
            bindString(3, "R16 Artist ${index.toString().padStart(3, '0')}")
            bindNull(4)
            bindLong(5, EPOCH)
            bindLong(6, EPOCH)
        }
    }

    private fun insertRecordings(sqlite: SupportSQLiteDatabase, profile: R16PerformanceProfile) {
        sqlite.insertRows(
            sql =
                """
                INSERT INTO recording (
                    recording_id, canonical_title, duration_ms, version_kind, version_label,
                    explicitness, preferred_release_id, preferred_artwork_id, retention_kind,
                    retained_until_epoch_ms, created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = profile.recordingCount,
        ) { index ->
            bindString(1, recordingId(index))
            bindString(2, title(index))
            bindLong(3, 180_000L)
            bindString(4, "ORIGINAL")
            bindNull(5)
            bindString(6, "UNKNOWN")
            bindNull(7)
            bindNull(8)
            bindString(9, "DURABLE")
            bindNull(10)
            bindLong(11, EPOCH)
            bindLong(12, EPOCH)
        }
    }

    private fun insertArtistCredits(sqlite: SupportSQLiteDatabase, profile: R16PerformanceProfile) {
        val artistCount = minOf(profile.recordingCount, ARTIST_COUNT)
        sqlite.insertRows(
            sql =
                """
                INSERT INTO recording_artist_credit (
                    recording_id, position, artist_id, credited_name, join_phrase
                ) VALUES (?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = profile.recordingCount,
        ) { index ->
            val artistIndex = index % artistCount
            bindString(1, recordingId(index))
            bindLong(2, 0L)
            bindString(3, artistId(artistIndex))
            bindString(4, "R16 Artist ${artistIndex.toString().padStart(3, '0')}")
            bindString(5, "")
        }
    }

    private fun insertLibraryRelationships(
        sqlite: SupportSQLiteDatabase,
        profile: R16PerformanceProfile,
    ) {
        sqlite.insertRows(
            sql =
                """
                INSERT INTO library_recording (
                    recording_id, liked, explicitly_saved, user_edited, manually_identified,
                    first_added_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = profile.recordingCount,
        ) { index ->
            bindString(1, recordingId(index))
            bindLong(2, if (index % 2 == 0) 1L else 0L)
            bindLong(3, if (index % 5 == 0) 1L else 0L)
            bindLong(4, 0L)
            bindLong(5, if (index % 7 == 0) 1L else 0L)
            bindLong(6, EPOCH + index)
            bindLong(7, EPOCH + index)
        }
    }

    private fun insertAssets(sqlite: SupportSQLiteDatabase, profile: R16PerformanceProfile) {
        sqlite.insertRows(
            sql =
                """
                INSERT INTO media_asset (
                    asset_id, recording_id, source_reference_id, asset_kind, asset_state,
                    location_type, location, document_id, media_store_id, display_name, mime_type,
                    container, codec, bitrate_bps, sample_rate_hz, channel_count, content_length,
                    content_checksum, fingerprint_id, created_at_epoch_ms, updated_at_epoch_ms,
                    last_verified_at_epoch_ms, normalized_path_token, last_modified_epoch_ms,
                    download_job_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = profile.totalAssetCount,
        ) { row ->
            val duplicate = row >= profile.recordingCount
            val recordingIndex =
                if (duplicate) (row - profile.recordingCount) * profile.duplicateAssetStride
                else row
            val suffix = if (duplicate) "duplicate" else "primary"
            bindString(1, "r16-asset-${recordingIndex.toString().padStart(5, '0')}-$suffix")
            bindString(2, recordingId(recordingIndex))
            bindNull(3)
            bindString(4, "LOCAL_FILE")
            bindString(5, "AVAILABLE")
            bindString(6, "CONTENT_URI")
            bindString(7, "content://r16/$recordingIndex/$suffix")
            bindNull(8)
            bindLong(9, (50_000L + recordingIndex).takeIf { !duplicate })
            bindString(10, "R16 Track $recordingIndex.flac")
            bindString(11, "audio/flac")
            bindString(12, "flac")
            bindString(13, "flac")
            bindLong(14, 320_000L)
            bindLong(15, 48_000L)
            bindLong(16, 2L)
            bindLong(17, 4_000_000L)
            bindString(18, "r16-checksum-${recordingIndex.toString().padStart(5, '0')}")
            bindNull(19)
            bindLong(20, EPOCH)
            bindLong(21, EPOCH)
            bindLong(22, EPOCH)
            bindString(23, "r16-path-${recordingIndex.toString().padStart(5, '0')}-$suffix")
            bindLong(24, EPOCH)
            bindNull(25)
        }
    }

    private fun insertPlaylists(sqlite: SupportSQLiteDatabase, profile: R16PerformanceProfile) {
        sqlite.insertRows(
            sql =
                """
                INSERT INTO playlist (
                    playlist_id, name, pinned, library_order_key, artwork_override,
                    display_sort_mode, display_sort_direction, origin_kind, origin_key,
                    created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = profile.playlistCount,
        ) { index ->
            bindString(1, playlistId(index))
            bindString(2, if (index == 0) "R16 Duplicate Playlist" else "R16 Playlist $index")
            bindLong(3, if (index == 0) 1L else 0L)
            bindLong(4, (index + 1) * 1_024L)
            bindNull(5)
            bindString(6, "CUSTOM")
            bindString(7, "ASC")
            bindString(8, "USER")
            bindNull(9)
            bindLong(10, EPOCH)
            bindLong(11, EPOCH)
        }
    }

    private fun insertPlaylistEntries(
        sqlite: SupportSQLiteDatabase,
        profile: R16PerformanceProfile,
    ) {
        sqlite.insertRows(
            sql =
                """
                INSERT INTO playlist_entry (
                    playlist_entry_id, playlist_id, recording_id, order_key, added_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = profile.totalPlaylistEntryCount,
        ) { index ->
            val inLargePlaylist = index < profile.largePlaylistEntryCount
            val playlistIndex =
                if (inLargePlaylist) 0 else index - profile.largePlaylistEntryCount + 1
            val recordingIndex =
                if (inLargePlaylist) {
                    index % duplicatePlaylistRecordingPool(profile)
                } else {
                    index % profile.recordingCount
                }
            val entryPrefix = if (inLargePlaylist) "large" else "small"
            bindString(1, "r16-entry-$entryPrefix-${index.toString().padStart(5, '0')}")
            bindString(2, playlistId(playlistIndex))
            bindString(3, recordingId(recordingIndex))
            bindLong(4, (index + 1) * 1_024L)
            bindLong(5, EPOCH + index)
        }
    }

    private fun insertSearchDocuments(
        sqlite: SupportSQLiteDatabase,
        profile: R16PerformanceProfile,
    ) {
        sqlite.insertRows(
            sql =
                """
                INSERT INTO recording_fts (
                    recording_id, title, artist_names, release_title, aliases,
                    source_titles, user_override_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = profile.recordingCount,
        ) { index ->
            bindString(1, recordingId(index))
            bindString(2, title(index))
            bindString(3, "R16 Artist ${index % minOf(profile.recordingCount, ARTIST_COUNT)}")
            bindString(4, "R16 Release")
            bindString(5, "alias-${index.toString().padStart(5, '0')}")
            bindString(6, "R16 Source Title")
            bindString(7, "")
        }
    }

    private fun insertPlaybackSourceProbe(sqlite: SupportSQLiteDatabase) {
        sqlite.insertRows(
            sql =
                """
                INSERT INTO metadata_observation (
                    observation_id, source_type, source_reference_id, asset_id, title,
                    artist_credit_json, release_title, release_artist, duration_ms, artwork_json,
                    release_year, track_number, disc_number, genres_json, version_hints_json,
                    external_ids_json, extras_json, captured_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = 1,
        ) {
            bindString(1, PROBE_OBSERVATION_ID)
            bindString(2, "R16_PERFORMANCE")
            bindString(3, PROBE_SOURCE_ID)
            bindNull(4)
            bindString(5, "R16 Probe")
            bindNull(6)
            bindString(7, "R16 Release")
            bindString(8, "R16 Artist")
            bindLong(9, 180_000L)
            bindNull(10)
            bindLong(11, 2026L)
            bindLong(12, 1L)
            bindLong(13, 1L)
            bindNull(14)
            bindNull(15)
            bindNull(16)
            bindNull(17)
            bindLong(18, EPOCH)
        }
        sqlite.insertRows(
            sql =
                """
                INSERT INTO source_reference (
                    source_reference_id, recording_id, provider_id, source_kind, item_type,
                    source_item_id, original_url, availability_state,
                    availability_checked_at_epoch_ms, availability_expires_at_epoch_ms,
                    failure_kind, failure_retryable, identity_status,
                    raw_metadata_observation_id, created_at_epoch_ms, updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = 1,
        ) {
            bindString(1, PROBE_SOURCE_ID)
            bindString(2, recordingId(0))
            bindString(3, "r16-performance")
            bindString(4, "PROVIDER")
            bindString(5, "RECORDING")
            bindString(6, "r16-probe-item")
            bindString(7, "https://example.invalid/r16-probe")
            bindString(8, "AVAILABLE")
            bindLong(9, EPOCH)
            bindNull(10)
            bindNull(11)
            bindNull(12)
            bindString(13, "USER_CONFIRMED")
            bindString(14, PROBE_OBSERVATION_ID)
            bindLong(15, EPOCH)
            bindLong(16, EPOCH)
        }
    }

    private fun insertHistory(sqlite: SupportSQLiteDatabase, profile: R16PerformanceProfile) {
        sqlite.insertRows(
            sql =
                """
                INSERT INTO play_history (
                    listening_session_id, recording_id, queue_entry_id, source_reference_id,
                    started_at_epoch_ms, ended_at_epoch_ms, active_listened_ms, last_position_ms,
                    completion_kind, chosen_by_user
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent(),
            rowCount = profile.historyRowCount,
        ) { index ->
            bindString(1, "r16-session-${index.toString().padStart(5, '0')}")
            bindString(2, recordingId(index % profile.recordingCount))
            bindString(3, "r16-queue-entry-${index.toString().padStart(5, '0')}")
            if (index == 0) bindString(4, PROBE_SOURCE_ID) else bindNull(4)
            bindLong(5, EPOCH + index)
            bindLong(6, EPOCH + index + 180_000L)
            bindLong(7, 180_000L)
            bindLong(8, 180_000L)
            bindString(9, "COMPLETED")
            bindLong(10, if (index % 2 == 0) 1L else 0L)
        }
    }

    private fun title(index: Int): String =
        if (index % SEARCH_STRIDE == 0) {
            "R16 Target Track ${index.toString().padStart(5, '0')}"
        } else {
            "R16 Track ${index.toString().padStart(5, '0')}"
        }

    private fun recordingId(index: Int): String =
        "r16-recording-${index.toString().padStart(5, '0')}"

    private fun artistId(index: Int): String = "r16-artist-${index.toString().padStart(3, '0')}"

    private fun playlistId(index: Int): String =
        if (index == 0) DUPLICATE_PLAYLIST_ID
        else "r16-playlist-${index.toString().padStart(4, '0')}"

    private fun duplicatePlaylistRecordingPool(profile: R16PerformanceProfile): Int =
        minOf(
            profile.recordingCount,
            DUPLICATE_PLAYLIST_RECORDING_POOL,
            (profile.largePlaylistEntryCount / 2).coerceAtLeast(1),
        )

    private companion object {
        const val ARTIST_COUNT = 128
        const val BATCH_SIZE = 1_000
        const val EPOCH = 1_700_000_000_000L
        const val SEARCH_STRIDE = 10
        const val DUPLICATE_PLAYLIST_RECORDING_POOL = 256
        const val DUPLICATE_PLAYLIST_ID = "r16-playlist-duplicate-capable"
        const val PROBE_OBSERVATION_ID = "r16-observation-probe"
        const val PROBE_SOURCE_ID = "r16-source-probe"
    }
}

internal fun newR16PerformanceDatabase(context: Context): ShippyR16Database =
    Room.inMemoryDatabaseBuilder(context, ShippyR16Database::class.java)
        .addCallback(R16LibraryMembershipTriggers)
        .allowMainThreadQueries()
        .build()

private fun SupportSQLiteDatabase.insertRows(
    sql: String,
    rowCount: Int,
    bind: SupportSQLiteStatement.(index: Int) -> Unit,
) {
    if (rowCount == 0) return
    compileStatement(sql).use { statement ->
        var start = 0
        while (start < rowCount) {
            val end = minOf(start + BATCH_SIZE, rowCount)
            beginTransaction()
            try {
                for (index in start until end) {
                    statement.clearBindings()
                    statement.bind(index)
                    statement.executeInsert()
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
            start = end
        }
    }
}

private const val BATCH_SIZE = 1_000

private fun SupportSQLiteStatement.bindLong(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

private fun SupportSQLiteStatement.bindString(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindString(index, value)
}
