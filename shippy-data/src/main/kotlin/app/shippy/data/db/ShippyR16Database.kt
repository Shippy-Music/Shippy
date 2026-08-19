/*
 * Copyright (c) 2026 Auxio Project
 * ShippyR16Database.kt is part of Auxio.
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
package app.shippy.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import app.shippy.data.db.dao.AssetDao
import app.shippy.data.db.dao.DownloadDao
import app.shippy.data.db.dao.HistoryDao
import app.shippy.data.db.dao.IdentityDao
import app.shippy.data.db.dao.LastFmOutboxDao
import app.shippy.data.db.dao.LibraryDao
import app.shippy.data.db.dao.LyricsDao
import app.shippy.data.db.dao.MigrationAuditDao
import app.shippy.data.db.dao.PlaybackCheckpointDao
import app.shippy.data.db.dao.PlaylistDao
import app.shippy.data.db.dao.ReadModelDao
import app.shippy.data.db.dao.RecordingDao
import app.shippy.data.db.dao.SavedSourceDao
import app.shippy.data.db.dao.SourceDao
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.ArtworkReferenceEntity
import app.shippy.data.db.entity.AudioFingerprintEntity
import app.shippy.data.db.entity.CanonicalFieldProvenanceEntity
import app.shippy.data.db.entity.DownloadJobEntity
import app.shippy.data.db.entity.EntityRedirectEntity
import app.shippy.data.db.entity.ExternalIdentifierEntity
import app.shippy.data.db.entity.IdentityDecisionEntity
import app.shippy.data.db.entity.IdentityRejectionEntity
import app.shippy.data.db.entity.LastFmScrobbleOutboxEntity
import app.shippy.data.db.entity.LibraryLayoutEntryEntity
import app.shippy.data.db.entity.LibraryRecordingEntity
import app.shippy.data.db.entity.LyricsCacheEntity
import app.shippy.data.db.entity.MediaAssetEntity
import app.shippy.data.db.entity.MergeAuditEntity
import app.shippy.data.db.entity.MetadataObservationEntity
import app.shippy.data.db.entity.MigrationAuditEntity
import app.shippy.data.db.entity.PlayHistoryEntity
import app.shippy.data.db.entity.PlaybackCheckpointEntity
import app.shippy.data.db.entity.PlaybackCheckpointEntryEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.entity.ReleaseEntity
import app.shippy.data.db.entity.ReleaseTrackEntity
import app.shippy.data.db.entity.SavedSourceEntity
import app.shippy.data.db.entity.SourceReferenceEntity
import app.shippy.data.db.entity.UserMetadataOverrideEntity
import app.shippy.data.db.fts.RecordingFtsEntity
import app.shippy.data.db.fts.SearchDao
import app.shippy.data.db.view.LibrarySongRowView
import app.shippy.data.db.view.PlaylistEntryRowView

@Database(
    entities =
        [
            RecordingEntity::class,
            ArtistEntity::class,
            RecordingArtistCreditEntity::class,
            ReleaseEntity::class,
            ReleaseTrackEntity::class,
            SourceReferenceEntity::class,
            MetadataObservationEntity::class,
            ExternalIdentifierEntity::class,
            ArtworkReferenceEntity::class,
            MediaAssetEntity::class,
            AudioFingerprintEntity::class,
            LibraryRecordingEntity::class,
            PlaylistEntity::class,
            PlaylistEntryEntity::class,
            LibraryLayoutEntryEntity::class,
            UserMetadataOverrideEntity::class,
            CanonicalFieldProvenanceEntity::class,
            IdentityDecisionEntity::class,
            IdentityRejectionEntity::class,
            EntityRedirectEntity::class,
            MergeAuditEntity::class,
            PlayHistoryEntity::class,
            PlaybackCheckpointEntity::class,
            PlaybackCheckpointEntryEntity::class,
            DownloadJobEntity::class,
            LastFmScrobbleOutboxEntity::class,
            LyricsCacheEntity::class,
            SavedSourceEntity::class,
            MigrationAuditEntity::class,
            RecordingFtsEntity::class,
        ],
    views = [LibrarySongRowView::class, PlaylistEntryRowView::class],
    version = ShippyR16Database.SCHEMA_VERSION,
    exportSchema = true,
)
internal abstract class ShippyR16Database : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    abstract fun sourceDao(): SourceDao

    abstract fun assetDao(): AssetDao

    abstract fun libraryDao(): LibraryDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun readModelDao(): ReadModelDao

    abstract fun searchDao(): SearchDao

    abstract fun identityDao(): IdentityDao

    abstract fun historyDao(): HistoryDao

    abstract fun playbackCheckpointDao(): PlaybackCheckpointDao

    abstract fun downloadDao(): DownloadDao

    abstract fun lastFmOutboxDao(): LastFmOutboxDao

    abstract fun lyricsDao(): LyricsDao

    abstract fun savedSourceDao(): SavedSourceDao

    abstract fun migrationAuditDao(): MigrationAuditDao

    companion object {
        const val DATABASE_NAME = "shippy-r16.db"
        const val SCHEMA_VERSION = 1
    }
}
