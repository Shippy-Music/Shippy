/*
 * Copyright (c) 2026 Auxio Project
 * LegacyImportDao.kt is part of Auxio.
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
package app.shippy.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.CanonicalFieldProvenanceEntity
import app.shippy.data.db.entity.LibraryLayoutEntryEntity
import app.shippy.data.db.entity.LibraryRecordingEntity
import app.shippy.data.db.entity.MetadataObservationEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.entity.ReleaseEntity
import app.shippy.data.db.entity.ReleaseTrackEntity
import app.shippy.data.db.entity.SourceReferenceEntity

@Dao
internal interface LegacyImportDao {
    @Upsert suspend fun upsertRelease(entity: ReleaseEntity)

    @Upsert suspend fun upsertArtists(entities: List<ArtistEntity>)

    @Upsert suspend fun upsertRecording(entity: RecordingEntity)

    @Query("DELETE FROM recording_artist_credit WHERE recording_id = :recordingId")
    suspend fun deleteArtistCredits(recordingId: String): Int

    @Upsert suspend fun upsertArtistCredits(entities: List<RecordingArtistCreditEntity>)

    @Upsert suspend fun upsertReleaseTrack(entity: ReleaseTrackEntity)

    @Upsert suspend fun upsertObservation(entity: MetadataObservationEntity)

    @Upsert suspend fun upsertProvenance(entities: List<CanonicalFieldProvenanceEntity>)

    @Query("SELECT COUNT(*) FROM recording") suspend fun recordingCount(): Long

    @Query("SELECT COUNT(*) FROM source_reference") suspend fun sourceCount(): Long

    @Query("SELECT COUNT(*) FROM media_asset") suspend fun assetCount(): Long

    @Query("SELECT COUNT(*) FROM library_recording") suspend fun libraryRecordingCount(): Long

    @Query("SELECT COUNT(*) FROM playlist") suspend fun playlistCount(): Long

    @Query("SELECT COUNT(*) FROM playlist_entry") suspend fun playlistEntryCount(): Long

    @Query("SELECT COUNT(*) FROM download_job") suspend fun downloadJobCount(): Long

    @Query("SELECT COUNT(*) FROM lyrics_cache") suspend fun lyricsCount(): Long

    @Query("SELECT COUNT(*) FROM saved_source_entity") suspend fun savedSourceCount(): Long

    @Query("SELECT * FROM source_reference WHERE source_reference_id = :sourceReferenceId")
    suspend fun source(sourceReferenceId: String): SourceReferenceEntity?

    @Query("SELECT * FROM library_recording WHERE recording_id = :recordingId")
    suspend fun libraryRelationship(recordingId: String): LibraryRecordingEntity?

    @Upsert suspend fun upsertLibraryRelationship(entity: LibraryRecordingEntity)

    @Query(
        """
        UPDATE recording SET retention_kind = 'DURABLE', retained_until_epoch_ms = NULL,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE recording_id = :recordingId
        """
    )
    suspend fun makeRecordingDurable(recordingId: String, updatedAtEpochMs: Long): Int

    @Query("SELECT * FROM playlist WHERE playlist_id = :playlistId")
    suspend fun playlist(playlistId: String): PlaylistEntity?

    @Upsert suspend fun upsertPlaylist(entity: PlaylistEntity)

    @Upsert suspend fun upsertLibraryLayout(entity: LibraryLayoutEntryEntity)

    @Query(
        """
        SELECT * FROM library_layout_entry
        WHERE target_type = :targetType AND target_id = :targetId
        """
    )
    suspend fun libraryLayout(targetType: String, targetId: String): LibraryLayoutEntryEntity?

    @Query("SELECT * FROM playlist_entry WHERE playlist_entry_id = :playlistEntryId")
    suspend fun playlistEntry(playlistEntryId: String): PlaylistEntryEntity?

    @Upsert suspend fun upsertPlaylistEntry(entity: PlaylistEntryEntity)

    @Query("SELECT * FROM release WHERE release_id = :releaseId")
    suspend fun release(releaseId: String): ReleaseEntity?

    @Query("SELECT * FROM metadata_observation WHERE observation_id = :observationId")
    suspend fun observation(observationId: String): MetadataObservationEntity?

    @Query(
        """
        SELECT * FROM canonical_field_provenance
        WHERE recording_id = :recordingId
        ORDER BY field_name
        """
    )
    suspend fun provenance(recordingId: String): List<CanonicalFieldProvenanceEntity>
}
