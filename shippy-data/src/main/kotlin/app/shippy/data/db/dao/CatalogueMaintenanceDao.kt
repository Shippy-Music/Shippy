/*
 * Copyright (c) 2026 Auxio Project
 * CatalogueMaintenanceDao.kt is part of Auxio.
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

@Dao
internal abstract class CatalogueMaintenanceDao {
    @Query(
        """
        SELECT recording.recording_id FROM recording
        WHERE recording.retention_kind = 'TRANSIENT'
          AND recording.retained_until_epoch_ms IS NOT NULL
          AND recording.retained_until_epoch_ms <= :expiredAtEpochMs
          AND NOT EXISTS (
              SELECT 1 FROM library_recording
              WHERE library_recording.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM playlist_entry
              WHERE playlist_entry.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM media_asset
              WHERE media_asset.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM user_metadata_override
              WHERE user_metadata_override.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM playback_checkpoint_entry
              WHERE playback_checkpoint_entry.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM download_job
              WHERE download_job.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM lastfm_scrobble_outbox
              WHERE lastfm_scrobble_outbox.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM identity_decision
              WHERE identity_decision.user_confirmed = 1
                AND (
                    identity_decision.target_recording_id = recording.recording_id
                    OR (
                        identity_decision.subject_type = 'RECORDING'
                        AND identity_decision.subject_id = recording.recording_id
                    )
                    OR (
                        identity_decision.subject_type = 'SOURCE'
                        AND identity_decision.subject_id IN (
                            SELECT source_reference.source_reference_id FROM source_reference
                            WHERE source_reference.recording_id = recording.recording_id
                        )
                    )
                )
          )
          AND NOT EXISTS (
              SELECT 1 FROM identity_rejection
              WHERE identity_rejection.rejected_recording_id = recording.recording_id
                 OR (
                     identity_rejection.subject_type = 'RECORDING'
                     AND identity_rejection.subject_id = recording.recording_id
                 )
                 OR (
                     identity_rejection.subject_type = 'SOURCE'
                     AND identity_rejection.subject_id IN (
                         SELECT source_reference.source_reference_id FROM source_reference
                         WHERE source_reference.recording_id = recording.recording_id
                     )
                 )
          )
          AND NOT EXISTS (
              SELECT 1 FROM entity_redirect
              WHERE entity_redirect.old_recording_id = recording.recording_id
                 OR entity_redirect.canonical_recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM merge_audit
              WHERE merge_audit.survivor_recording_id = recording.recording_id
                 OR merge_audit.merged_recording_id = recording.recording_id
          )
        ORDER BY recording.retained_until_epoch_ms, recording.recording_id
        LIMIT :limit
        """
    )
    abstract suspend fun expiredEligibleRecordingIds(
        expiredAtEpochMs: Long,
        limit: Int,
    ): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM recording
        WHERE recording.recording_id = :recordingId
          AND recording.retention_kind = 'TRANSIENT'
          AND recording.retained_until_epoch_ms IS NOT NULL
          AND recording.retained_until_epoch_ms <= :expiredAtEpochMs
          AND NOT EXISTS (
              SELECT 1 FROM library_recording
              WHERE library_recording.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM playlist_entry
              WHERE playlist_entry.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM media_asset
              WHERE media_asset.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM user_metadata_override
              WHERE user_metadata_override.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM playback_checkpoint_entry
              WHERE playback_checkpoint_entry.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM download_job
              WHERE download_job.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM lastfm_scrobble_outbox
              WHERE lastfm_scrobble_outbox.recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM identity_decision
              WHERE identity_decision.user_confirmed = 1
                AND (
                    identity_decision.target_recording_id = recording.recording_id
                    OR (
                        identity_decision.subject_type = 'RECORDING'
                        AND identity_decision.subject_id = recording.recording_id
                    )
                    OR (
                        identity_decision.subject_type = 'SOURCE'
                        AND identity_decision.subject_id IN (
                            SELECT source_reference.source_reference_id FROM source_reference
                            WHERE source_reference.recording_id = recording.recording_id
                        )
                    )
                )
          )
          AND NOT EXISTS (
              SELECT 1 FROM identity_rejection
              WHERE identity_rejection.rejected_recording_id = recording.recording_id
                 OR (
                     identity_rejection.subject_type = 'RECORDING'
                     AND identity_rejection.subject_id = recording.recording_id
                 )
                 OR (
                     identity_rejection.subject_type = 'SOURCE'
                     AND identity_rejection.subject_id IN (
                         SELECT source_reference.source_reference_id FROM source_reference
                         WHERE source_reference.recording_id = recording.recording_id
                     )
                 )
          )
          AND NOT EXISTS (
              SELECT 1 FROM entity_redirect
              WHERE entity_redirect.old_recording_id = recording.recording_id
                 OR entity_redirect.canonical_recording_id = recording.recording_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM merge_audit
              WHERE merge_audit.survivor_recording_id = recording.recording_id
                 OR merge_audit.merged_recording_id = recording.recording_id
          )
        """
    )
    abstract suspend fun eligibleRecordingCount(recordingId: String, expiredAtEpochMs: Long): Int

    @Query("SELECT artist_id FROM recording_artist_credit WHERE recording_id = :recordingId")
    abstract suspend fun artistIds(recordingId: String): List<String>

    @Query(
        """
        SELECT preferred_release_id FROM recording
        WHERE recording_id = :recordingId AND preferred_release_id IS NOT NULL
        UNION
        SELECT release_id FROM release_track WHERE recording_id = :recordingId
        """
    )
    abstract suspend fun releaseIds(recordingId: String): List<String>

    @Query("DELETE FROM recording_fts WHERE recording_id = :recordingId")
    abstract suspend fun deleteSearchDocument(recordingId: String)

    @Query(
        """
        DELETE FROM external_identifier
        WHERE (owner_type = 'RECORDING' AND owner_id = :recordingId)
           OR (
               owner_type IN ('SOURCE', 'SOURCE_REFERENCE')
               AND owner_id IN (
                   SELECT source_reference_id FROM source_reference
                   WHERE recording_id = :recordingId
               )
           )
           OR source_observation_id IN (
               SELECT observation_id FROM metadata_observation
               WHERE source_reference_id IN (
                   SELECT source_reference_id FROM source_reference
                   WHERE recording_id = :recordingId
               )
           )
        """
    )
    abstract suspend fun deleteExternalIdentifiers(recordingId: String)

    @Query(
        """
        DELETE FROM artwork_reference
        WHERE (owner_type = 'RECORDING' AND owner_id = :recordingId)
           OR (
               owner_type IN ('SOURCE', 'SOURCE_REFERENCE')
               AND owner_id IN (
                   SELECT source_reference_id FROM source_reference
                   WHERE recording_id = :recordingId
               )
           )
           OR source_observation_id IN (
               SELECT observation_id FROM metadata_observation
               WHERE source_reference_id IN (
                   SELECT source_reference_id FROM source_reference
                   WHERE recording_id = :recordingId
               )
           )
        """
    )
    abstract suspend fun deleteArtwork(recordingId: String)

    @Query(
        """
        DELETE FROM identity_decision
        WHERE target_recording_id = :recordingId
           OR (subject_type = 'RECORDING' AND subject_id = :recordingId)
           OR (
               subject_type = 'SOURCE'
               AND subject_id IN (
                   SELECT source_reference_id FROM source_reference
                   WHERE recording_id = :recordingId
               )
           )
        """
    )
    abstract suspend fun deleteDerivedIdentityDecisions(recordingId: String)

    @Query("DELETE FROM release_track WHERE recording_id = :recordingId")
    abstract suspend fun deleteReleaseTracks(recordingId: String)

    @Query(
        """
        DELETE FROM metadata_observation
        WHERE source_reference_id IN (
            SELECT source_reference_id FROM source_reference WHERE recording_id = :recordingId
        )
        """
    )
    abstract suspend fun deleteSourceObservations(recordingId: String)

    @Query("DELETE FROM source_reference WHERE recording_id = :recordingId")
    abstract suspend fun deleteSources(recordingId: String)

    @Query("DELETE FROM recording WHERE recording_id = :recordingId")
    abstract suspend fun deleteRecording(recordingId: String): Int

    @Query(
        """
        SELECT artist_id FROM artist
        WHERE artist_id IN (:artistIds)
          AND NOT EXISTS (
              SELECT 1 FROM recording_artist_credit
              WHERE recording_artist_credit.artist_id = artist.artist_id
          )
        """
    )
    abstract suspend fun orphanArtistIds(artistIds: List<String>): List<String>

    @Query(
        """
        SELECT release_id FROM release
        WHERE release_id IN (:releaseIds)
          AND NOT EXISTS (
              SELECT 1 FROM release_track
              WHERE release_track.release_id = release.release_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM recording
              WHERE recording.preferred_release_id = release.release_id
          )
        """
    )
    abstract suspend fun orphanReleaseIds(releaseIds: List<String>): List<String>

    @Query(
        "DELETE FROM external_identifier WHERE owner_type = :ownerType AND owner_id IN (:ownerIds)"
    )
    abstract suspend fun deleteOwnerExternalIdentifiers(ownerType: String, ownerIds: List<String>)

    @Query(
        "DELETE FROM artwork_reference WHERE owner_type = :ownerType AND owner_id IN (:ownerIds)"
    )
    abstract suspend fun deleteOwnerArtwork(ownerType: String, ownerIds: List<String>)

    @Query("DELETE FROM artist WHERE artist_id IN (:artistIds)")
    abstract suspend fun deleteArtists(artistIds: List<String>)

    @Query("DELETE FROM release WHERE release_id IN (:releaseIds)")
    abstract suspend fun deleteReleases(releaseIds: List<String>)
}
