/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyDatabase.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.library

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import org.oxycblt.auxio.shippy.persistence.download.DownloadCandidateEntity
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobDao
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobEntity

@Database(
    entities =
        [
            LibraryRelationshipEntity::class,
            UserPlaylistEntity::class,
            PlaylistMembershipEntity::class,
            DownloadJobEntity::class,
            DownloadCandidateEntity::class,
        ],
    version = 3,
    exportSchema = false,
)
internal abstract class ShippyDatabase : RoomDatabase() {
    abstract fun libraryRelationshipDao(): LibraryRelationshipDao

    abstract fun downloadJobDao(): DownloadJobDao

    companion object {
        val MIGRATION_1_2 =
            Migration(1, 2) { database ->
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_playlist` (
                        `playlistId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `pinned` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        PRIMARY KEY(`playlistId`)
                    )
                    """
                        .trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `user_playlist` (`playlistId`, `name`, `pinned`, `position`)
                    SELECT DISTINCT current.`playlistId`, current.`playlistId`, 0,
                        (
                            SELECT COUNT(DISTINCT prior.`playlistId`)
                            FROM `playlist_membership` AS prior
                            WHERE prior.`playlistId` < current.`playlistId`
                        )
                    FROM `playlist_membership` AS current
                    WHERE current.`playlistId` NOT LIKE 'system:%'
                    """
                        .trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_membership_v2` (
                        `trackId` TEXT NOT NULL,
                        `playlistId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        PRIMARY KEY(`trackId`, `playlistId`),
                        FOREIGN KEY(`trackId`) REFERENCES `library_relationship`(`trackId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`playlistId`) REFERENCES `user_playlist`(`playlistId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """
                        .trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `playlist_membership_v2` (`trackId`, `playlistId`, `position`)
                    SELECT current.`trackId`, current.`playlistId`,
                        (
                            SELECT COUNT(*)
                            FROM `playlist_membership` AS prior
                            WHERE prior.`playlistId` = current.`playlistId`
                                AND prior.`trackId` < current.`trackId`
                        )
                    FROM `playlist_membership` AS current
                    INNER JOIN `user_playlist` AS playlist
                        ON playlist.`playlistId` = current.`playlistId`
                    """
                        .trimIndent()
                )
                database.execSQL("DROP TABLE `playlist_membership`")
                database.execSQL(
                    "ALTER TABLE `playlist_membership_v2` RENAME TO `playlist_membership`"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_playlist_position` " +
                        "ON `user_playlist` (`position`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_membership_trackId` " +
                        "ON `playlist_membership` (`trackId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_membership_playlistId` " +
                        "ON `playlist_membership` (`playlistId`)"
                )
            }

        val MIGRATION_2_3 =
            Migration(2, 3) { database ->
                database.execSQL(
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
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_download_job_trackId` " +
                        "ON `download_job` (`trackId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_download_job_state` " +
                        "ON `download_job` (`state`)"
                )
                database.execSQL(
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
                        FOREIGN KEY(`jobId`) REFERENCES `download_job`(`jobId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """
                        .trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_download_candidate_jobId` " +
                        "ON `download_candidate` (`jobId`)"
                )
            }
    }
}
