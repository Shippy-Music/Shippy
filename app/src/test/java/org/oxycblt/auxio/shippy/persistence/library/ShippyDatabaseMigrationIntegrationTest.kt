/*
 * Copyright (c) 2026 Auxio Project
 * ShippyDatabaseMigrationIntegrationTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.persistence.library

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ShippyDatabaseMigrationIntegrationTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `migration 10 to 11 preserves existing outbox rows and adds accountId column with empty string default`() {
        val helper = createV10Database(context)
        try {
            val sqlite = helper.writableDatabase
            ShippyDatabase.MIGRATION_10_11.migrate(sqlite)
            sqlite.version = 11

            // Verify user version upgraded to 11
            sqlite.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(11, cursor.getInt(0))
            }

            // Verify existing scrobble outbox data survived and accountId was populated with
            // default ""
            sqlite.query("SELECT * FROM `lastfm_scrobble_outbox`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "outbox-legacy-1",
                    cursor.getString(cursor.getColumnIndexOrThrow("id")),
                )
                assertEquals(
                    "Legacy Artist",
                    cursor.getString(cursor.getColumnIndexOrThrow("artist")),
                )
                assertEquals(
                    "Legacy Track",
                    cursor.getString(cursor.getColumnIndexOrThrow("track")),
                )
                assertEquals(
                    "Legacy Album",
                    cursor.getString(cursor.getColumnIndexOrThrow("album")),
                )
                assertEquals(200, cursor.getInt(cursor.getColumnIndexOrThrow("durationSeconds")))
                assertEquals(
                    1700000000L,
                    cursor.getLong(cursor.getColumnIndexOrThrow("startedAtEpochSeconds")),
                )
                assertEquals(
                    1700000000000L,
                    cursor.getLong(cursor.getColumnIndexOrThrow("queuedAtEpochMs")),
                )
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("accountId")))
                assertTrue(cursor.isLast)
            }
        } finally {
            helper.close()
        }
    }

    private fun createV10Database(context: Context): SupportSQLiteOpenHelper {
        val helper =
            FrameworkSQLiteOpenHelperFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration.builder(context)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(10) {
                                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            }
                        )
                        .build()
                )
        val db = helper.writableDatabase
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_relationship` (
                `trackId` TEXT NOT NULL,
                `liked` INTEGER NOT NULL,
                `downloaded` INTEGER NOT NULL,
                PRIMARY KEY(`trackId`)
            )
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `user_playlist` (
                `playlistId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `pinned` INTEGER NOT NULL,
                `position` INTEGER NOT NULL,
                `artworkUri` TEXT,
                PRIMARY KEY(`playlistId`)
            )
            """
                .trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_user_playlist_position` ON `user_playlist` (`position`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playlist_membership` (
                `trackId` TEXT NOT NULL,
                `playlistId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                PRIMARY KEY(`trackId`, `playlistId`),
                FOREIGN KEY(`trackId`) REFERENCES `library_relationship`(`trackId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`playlistId`) REFERENCES `user_playlist`(`playlistId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """
                .trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playlist_membership_trackId` ON `playlist_membership` (`trackId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playlist_membership_playlistId` ON `playlist_membership` (`playlistId`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `download_job` (
                `jobId` TEXT NOT NULL,
                `trackId` TEXT NOT NULL,
                `requestedCandidateId` TEXT NOT NULL,
                `trackRealm` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `artists` TEXT NOT NULL,
                `album` TEXT,
                `durationMs` INTEGER,
                `versionLabel` TEXT,
                `explicit` INTEGER,
                `live` INTEGER NOT NULL,
                `remix` INTEGER NOT NULL,
                `artwork` TEXT,
                `state` TEXT NOT NULL,
                `bytesTransferred` INTEGER NOT NULL,
                `expectedBytes` INTEGER,
                `failureCode` TEXT,
                `failureMessage` TEXT,
                `artifactUri` TEXT,
                `artifactLength` INTEGER,
                `artifactMimeType` TEXT,
                `artifactVerifiedAtEpochMs` INTEGER,
                `pendingUri` TEXT,
                `pendingDisplayName` TEXT,
                `pendingMimeType` TEXT,
                `createdAtEpochMs` INTEGER NOT NULL,
                `updatedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`jobId`)
            )
            """
                .trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_job_trackId` ON `download_job` (`trackId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_job_state` ON `download_job` (`state`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `download_candidate` (
                `jobId` TEXT NOT NULL,
                `candidateId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `sourceItemId` TEXT NOT NULL,
                `availability` TEXT NOT NULL,
                `locator` TEXT,
                `providerId` TEXT,
                `mimeType` TEXT,
                `container` TEXT,
                `bitrateBps` INTEGER,
                `contentLength` INTEGER,
                PRIMARY KEY(`jobId`, `candidateId`),
                FOREIGN KEY(`jobId`) REFERENCES `download_job`(`jobId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """
                .trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_candidate_jobId` ON `download_candidate` (`jobId`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `lyrics_cache` (
                `trackId` TEXT NOT NULL,
                `fingerprint` TEXT NOT NULL,
                `titleKey` TEXT NOT NULL,
                `artistsKey` TEXT NOT NULL,
                `albumKey` TEXT NOT NULL,
                `durationSeconds` INTEGER NOT NULL,
                `sourceId` TEXT NOT NULL,
                `recordId` INTEGER NOT NULL,
                `trackName` TEXT NOT NULL,
                `artistName` TEXT NOT NULL,
                `albumName` TEXT,
                `recordDurationSeconds` REAL,
                `instrumental` INTEGER NOT NULL,
                `plainLyrics` TEXT,
                `syncedLyrics` TEXT,
                `cachedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`trackId`, `fingerprint`)
            )
            """
                .trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_lyrics_cache_cachedAtEpochMs` ON `lyrics_cache` (`cachedAtEpochMs`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `crew_active_checkpoint` (
                `slot` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `protocolVersion` INTEGER NOT NULL,
                `coordinatorTerm` INTEGER NOT NULL,
                `eventSequence` INTEGER NOT NULL,
                `snapshotPayload` BLOB NOT NULL,
                `payloadSha256` BLOB NOT NULL,
                `updatedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`slot`)
            )
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `canonical_track` (
                `trackId` TEXT NOT NULL,
                `realm` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `artists` TEXT NOT NULL,
                `album` TEXT,
                `durationMs` INTEGER,
                `versionLabel` TEXT,
                `explicit` INTEGER,
                `live` INTEGER NOT NULL,
                `remix` INTEGER NOT NULL,
                `artwork` TEXT,
                PRIMARY KEY(`trackId`)
            )
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `canonical_track_candidate` (
                `trackId` TEXT NOT NULL,
                `candidateId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `sourceItemId` TEXT NOT NULL,
                `availability` TEXT NOT NULL,
                `locator` TEXT,
                `providerId` TEXT,
                `mimeType` TEXT,
                `container` TEXT,
                `bitrateBps` INTEGER,
                `contentLength` INTEGER,
                PRIMARY KEY(`trackId`, `candidateId`),
                FOREIGN KEY(`trackId`) REFERENCES `canonical_track`(`trackId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """
                .trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_canonical_track_candidate_trackId` ON `canonical_track_candidate` (`trackId`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `lastfm_scrobble_outbox` (
                `id` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `track` TEXT NOT NULL,
                `album` TEXT,
                `durationSeconds` INTEGER,
                `startedAtEpochSeconds` INTEGER NOT NULL,
                `queuedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """
                .trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_lastfm_scrobble_outbox_queuedAtEpochMs` ON `lastfm_scrobble_outbox` (`queuedAtEpochMs`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playback_checkpoint` (
                `slot` TEXT NOT NULL,
                `positionMs` INTEGER NOT NULL,
                `repeatMode` TEXT NOT NULL,
                `heapIndex` INTEGER NOT NULL,
                `shuffledMapping` TEXT NOT NULL,
                PRIMARY KEY(`slot`)
            )
            """
                .trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playback_checkpoint_item` (
                `slot` TEXT NOT NULL,
                `heapPosition` INTEGER NOT NULL,
                `queueItemId` TEXT NOT NULL,
                `trackId` TEXT NOT NULL,
                `contextId` TEXT,
                `contributorId` TEXT,
                PRIMARY KEY(`slot`, `heapPosition`),
                FOREIGN KEY(`slot`) REFERENCES `playback_checkpoint`(`slot`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """
                .trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playback_checkpoint_item_trackId` ON `playback_checkpoint_item` (`trackId`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_provider_entity` (
                `providerId` TEXT NOT NULL,
                `entityType` TEXT NOT NULL,
                `sourceItemId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `subtitle` TEXT,
                `artwork` TEXT,
                `originalUrl` TEXT,
                `pinned` INTEGER NOT NULL,
                `savedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`providerId`, `entityType`, `sourceItemId`)
            )
            """
                .trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_saved_provider_entity_pinned_savedAtEpochMs` ON `saved_provider_entity` (`pinned`, `savedAtEpochMs`)"
        )

        // Seed v10 outbox row
        db.execSQL(
            """
            INSERT INTO `lastfm_scrobble_outbox` VALUES (
                'outbox-legacy-1',
                'Legacy Artist',
                'Legacy Track',
                'Legacy Album',
                200,
                1700000000,
                1700000000000
            )
            """
                .trimIndent()
        )

        db.version = 10
        return helper
    }
}
