/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadJobDao.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.download

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "download_job",
    indices = [Index("trackId"), Index("state")],
)
internal data class DownloadJobEntity(
    @PrimaryKey val jobId: String,
    val trackId: String,
    val requestedCandidateId: String,
    val trackRealm: String,
    val title: String,
    val artists: String,
    val album: String?,
    val durationMs: Long?,
    val versionLabel: String?,
    val explicit: Boolean?,
    val live: Boolean,
    val remix: Boolean,
    val artwork: String?,
    val state: String,
    val bytesTransferred: Long,
    val expectedBytes: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val artifactUri: String?,
    val artifactLength: Long?,
    val artifactMimeType: String?,
    val artifactVerifiedAtEpochMs: Long?,
    val pendingUri: String?,
    val pendingDisplayName: String?,
    val pendingMimeType: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "download_candidate",
    primaryKeys = ["jobId", "candidateId"],
    foreignKeys =
        [
            ForeignKey(
                entity = DownloadJobEntity::class,
                parentColumns = ["jobId"],
                childColumns = ["jobId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("jobId")],
)
internal data class DownloadCandidateEntity(
    val jobId: String,
    val candidateId: String,
    val position: Int,
    val kind: String,
    val sourceId: String,
    val sourceItemId: String,
    val availability: String,
    val locator: String?,
    val providerId: String?,
    val mimeType: String?,
    val container: String?,
    val bitrateBps: Int?,
    val contentLength: Long?,
)

internal data class StoredDownloadJob(
    @Embedded val job: DownloadJobEntity,
    @Relation(parentColumn = "jobId", entityColumn = "jobId")
    val candidates: List<DownloadCandidateEntity>,
)

@Dao
internal abstract class DownloadJobDao {
    @Transaction
    @Query("SELECT * FROM download_job ORDER BY createdAtEpochMs DESC, jobId")
    abstract fun observeAll(): Flow<List<StoredDownloadJob>>

    @Transaction
    @Query("SELECT * FROM download_job WHERE state = 'AVAILABLE' ORDER BY createdAtEpochMs DESC")
    abstract fun observeAvailable(): Flow<List<StoredDownloadJob>>

    @Transaction
    @Query("SELECT * FROM download_job WHERE jobId = :jobId")
    abstract suspend fun get(jobId: String): StoredDownloadJob?

    @Transaction
    @Query(
        """
        SELECT * FROM download_job
        WHERE trackId = :trackId
        ORDER BY createdAtEpochMs DESC, jobId DESC
        LIMIT 1
        """
    )
    abstract suspend fun getLatestForTrack(trackId: String): StoredDownloadJob?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM download_job
            WHERE trackId = :trackId AND state = 'AVAILABLE'
        )
        """
    )
    abstract suspend fun hasAvailableForTrack(trackId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertJob(job: DownloadJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCandidates(candidates: List<DownloadCandidateEntity>)

    @Update
    abstract suspend fun updateJob(job: DownloadJobEntity)

    @Query("DELETE FROM download_job WHERE jobId = :jobId")
    abstract suspend fun delete(jobId: String): Int

    @Transaction
    open suspend fun insert(job: DownloadJobEntity, candidates: List<DownloadCandidateEntity>) {
        check(candidates.isNotEmpty()) { "Download track requires at least one candidate" }
        check(candidates.all { it.jobId == job.jobId }) {
            "Download candidates must belong to their job"
        }
        insertJob(job)
        insertCandidates(candidates)
    }
}
