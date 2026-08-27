/*
 * Copyright (c) 2026 Auxio Project
 * R16BenchmarkFixtureProvider.kt is part of Auxio.
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
package org.oxycblt.auxio.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Benchmark-variant-only installer and probe for the deterministic R16 reference database.
 *
 * The macrobenchmark and baseline-profile modules call this provider before any fixture-backed
 * journey. Keeping the installer in the app's benchmark source set prevents this test-only
 * authority, bundled database, and database access path from being packaged in release variants.
 */
class R16BenchmarkFixtureProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = checkNotNull(context) { "R16 benchmark fixture provider has no context" }
        val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
        val prepared =
            appContext.getSharedPreferences(PREFERENCES_NAME, 0).getBoolean(PREPARED_KEY, false)
        if (!prepared || !databaseFile.isFile) {
            prepareFixture(databaseFile)
            markPrepared()
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        check(method == PREPARE_METHOD || method == PROBE_METHOD) {
            "Unsupported R16 benchmark fixture method: $method"
        }

        val appContext = checkNotNull(context) { "R16 benchmark fixture provider has no context" }
        val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
        if (method == PREPARE_METHOD) {
            prepareFixture(databaseFile)
            markPrepared()
        }
        check(databaseFile.isFile) {
            "R16 benchmark fixture database is missing: ${databaseFile.absolutePath}"
        }

        val snapshot = readSnapshot(databaseFile)
        snapshot.requireExpected()
        return snapshot.toBundle()
    }

    private fun markPrepared() {
        check(
            checkNotNull(context)
                .getSharedPreferences(PREFERENCES_NAME, 0)
                .edit()
                .putBoolean(PREPARED_KEY, true)
                .commit()
        ) {
            "Could not persist benchmark fixture readiness"
        }
    }

    private fun prepareFixture(databaseFile: File) {
        synchronized(INSTALL_LOCK) {
            val installed =
                databaseFile
                    .takeIf { it.isFile }
                    ?.let { existing ->
                        runCatching {
                                readSnapshot(existing).also(FixtureSnapshot::requireExpected)
                            }
                            .isSuccess
                    } == true
            if (installed) return

            databaseFile.parentFile?.mkdirs()
            val temporary = File(databaseFile.parentFile, "$DATABASE_NAME.installing")
            temporary.delete()
            checkNotNull(context).assets.open(FIXTURE_ASSET_NAME).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, bufferSize = COPY_BUFFER_BYTES)
                    output.fd.sync()
                }
            }

            File("${databaseFile.path}-wal").delete()
            File("${databaseFile.path}-shm").delete()
            publish(temporary, databaseFile)
        }
    }

    private fun publish(temporary: File, destination: File) {
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (atomicFailure: IOException) {
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (fallbackFailure: IOException) {
                fallbackFailure.addSuppressed(atomicFailure)
                throw fallbackFailure
            }
        }
    }

    private fun readSnapshot(databaseFile: File): FixtureSnapshot =
        SQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            .use { database ->
                val schemaVersion = pragmaUserVersion(database)
                val recordings = count(database, "SELECT COUNT(*) FROM recording")
                val playlists = count(database, "SELECT COUNT(*) FROM playlist")
                val playlistEntries =
                    count(
                        database,
                        "SELECT COUNT(*) FROM playlist_entry WHERE playlist_id = ?",
                        arrayOf(DUPLICATE_PLAYLIST_ID),
                    )
                val distinctPlaylistRecordings =
                    count(
                        database,
                        "SELECT COUNT(DISTINCT recording_id) FROM playlist_entry WHERE playlist_id = ?",
                        arrayOf(DUPLICATE_PLAYLIST_ID),
                    )
                val historyRows = count(database, "SELECT COUNT(*) FROM play_history")
                val mediaAssets = count(database, "SELECT COUNT(*) FROM media_asset")
                val duplicateChecksums =
                    count(
                        database,
                        """
                        SELECT COUNT(*) FROM (
                            SELECT content_checksum
                            FROM media_asset
                            WHERE content_checksum IS NOT NULL
                            GROUP BY content_checksum
                            HAVING COUNT(*) > 1
                        )
                        """
                            .trimIndent(),
                    )
                val knownRecording =
                    count(
                        database,
                        """
                        SELECT COUNT(*)
                        FROM recording
                        WHERE recording_id = ? AND canonical_title = ?
                        """
                            .trimIndent(),
                        arrayOf("r16-recording-00000", "R16 Target Track 00000"),
                    )
                val knownPlaylist =
                    count(
                        database,
                        """
                        SELECT COUNT(*)
                        FROM playlist
                        WHERE playlist_id = ? AND name = ?
                        """
                            .trimIndent(),
                        arrayOf(DUPLICATE_PLAYLIST_ID, "R16 Duplicate Playlist"),
                    )

                FixtureSnapshot(
                    schemaVersion = schemaVersion,
                    recordings = recordings,
                    playlists = playlists,
                    playlistEntries = playlistEntries,
                    distinctPlaylistRecordings = distinctPlaylistRecordings,
                    historyRows = historyRows,
                    mediaAssets = mediaAssets,
                    duplicateChecksums = duplicateChecksums,
                    knownRecording = knownRecording,
                    knownPlaylist = knownPlaylist,
                )
            }

    private data class FixtureSnapshot(
        val schemaVersion: Long,
        val recordings: Long,
        val playlists: Long,
        val playlistEntries: Long,
        val distinctPlaylistRecordings: Long,
        val historyRows: Long,
        val mediaAssets: Long,
        val duplicateChecksums: Long,
        val knownRecording: Long,
        val knownPlaylist: Long,
    ) {
        fun requireExpected() {
            check(recordings == RECORDING_COUNT) {
                "R16 fixture scale mismatch: recordings=$recordings expected=$RECORDING_COUNT"
            }
            check(schemaVersion == EXPECTED_SCHEMA_VERSION) {
                "R16 fixture schema mismatch: user_version=$schemaVersion " +
                    "expected=$EXPECTED_SCHEMA_VERSION"
            }
            check(playlists == PLAYLIST_COUNT) {
                "R16 fixture scale mismatch: playlists=$playlists expected=$PLAYLIST_COUNT"
            }
            check(playlistEntries == LARGE_PLAYLIST_ENTRY_COUNT) {
                "R16 fixture scale mismatch: duplicate-playlist entries=$playlistEntries " +
                    "expected=$LARGE_PLAYLIST_ENTRY_COUNT"
            }
            check(distinctPlaylistRecordings < playlistEntries) {
                "R16 fixture contract mismatch: duplicate-capable playlist has no duplicates"
            }
            check(historyRows == HISTORY_ROW_COUNT) {
                "R16 fixture scale mismatch: history=$historyRows expected=$HISTORY_ROW_COUNT"
            }
            check(mediaAssets == MEDIA_ASSET_COUNT) {
                "R16 fixture scale mismatch: media assets=$mediaAssets expected=$MEDIA_ASSET_COUNT"
            }
            check(duplicateChecksums > 0) {
                "R16 fixture contract mismatch: duplicate media checksum is missing"
            }
            check(knownRecording == 1L) {
                "R16 fixture identity mismatch: deterministic first recording is missing"
            }
            check(knownPlaylist == 1L) {
                "R16 fixture identity mismatch: deterministic large playlist is missing"
            }
        }

        fun toBundle() =
            Bundle().apply {
                putBoolean(KEY_READY, true)
                putInt(KEY_SCHEMA_VERSION, schemaVersion.toInt())
                putInt(KEY_RECORDINGS, recordings.toInt())
                putInt(KEY_PLAYLISTS, playlists.toInt())
                putInt(KEY_PLAYLIST_ENTRIES, playlistEntries.toInt())
                putInt(KEY_HISTORY_ROWS, historyRows.toInt())
            }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = unsupported()

    override fun getType(uri: Uri): String? = unsupported()

    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        unsupported()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = unsupported()

    private fun count(
        database: SQLiteDatabase,
        sql: String,
        args: Array<String> = emptyArray(),
    ): Long =
        database.rawQuery(sql, args).use { cursor ->
            check(cursor.moveToFirst()) { "R16 fixture probe returned no count for: $sql" }
            cursor.getLong(0)
        }

    private fun pragmaUserVersion(database: SQLiteDatabase): Long =
        database.rawQuery("PRAGMA user_version", emptyArray()).use { cursor ->
            check(cursor.moveToFirst()) { "R16 fixture probe returned no schema version" }
            cursor.getLong(0)
        }

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("R16 benchmark fixture provider supports call() only")

    private companion object {
        // Must match app.shippy.data.db.ShippyR16Database.SCHEMA_VERSION.
        const val EXPECTED_SCHEMA_VERSION = 2L
        const val DATABASE_NAME = "shippy-r16.db"
        const val FIXTURE_ASSET_NAME = "r16-benchmark-fixture-v2.db"
        const val PREPARE_METHOD = "prepare_scale_v1"
        const val PROBE_METHOD = "probe_scale_v1"
        const val PREFERENCES_NAME = "r16-benchmark-fixture"
        const val PREPARED_KEY = "fixture-v2-prepared"
        const val DUPLICATE_PLAYLIST_ID = "r16-playlist-duplicate-capable"
        const val COPY_BUFFER_BYTES = 64 * 1024

        val INSTALL_LOCK = Any()

        const val RECORDING_COUNT = 50_000L
        const val PLAYLIST_COUNT = 1_000L
        const val LARGE_PLAYLIST_ENTRY_COUNT = 10_000L
        const val HISTORY_ROW_COUNT = 20_000L
        const val MEDIA_ASSET_COUNT = 55_000L

        const val KEY_READY = "ready"
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_RECORDINGS = "recordings"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_PLAYLIST_ENTRIES = "playlist_entries"
        const val KEY_HISTORY_ROWS = "history_rows"
    }
}
