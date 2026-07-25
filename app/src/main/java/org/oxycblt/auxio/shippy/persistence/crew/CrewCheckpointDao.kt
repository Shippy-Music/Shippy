/*
 * Copyright (c) 2026 Shippy contributors
 * CrewCheckpointDao.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.crew

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "crew_active_checkpoint")
internal data class CrewCheckpointEntity(
    @PrimaryKey val slot: String,
    val sessionId: String,
    val protocolVersion: Int,
    val coordinatorTerm: Long,
    val eventSequence: Long,
    val snapshotPayload: ByteArray,
    val payloadSha256: ByteArray,
    val updatedAtEpochMs: Long,
)

@Dao
internal interface CrewCheckpointDao {
    @Query("SELECT * FROM crew_active_checkpoint WHERE slot = 'active' LIMIT 1")
    suspend fun getActive(): CrewCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entity: CrewCheckpointEntity)

    @Query(
        """
        DELETE FROM crew_active_checkpoint
        WHERE slot = 'active' AND sessionId = :sessionId
        """
    )
    suspend fun deleteSession(sessionId: String): Int

    @Query(
        """
        DELETE FROM crew_active_checkpoint
        WHERE slot = 'active'
            AND sessionId = :sessionId
            AND updatedAtEpochMs = :updatedAtEpochMs
        """
    )
    suspend fun deleteIfUnchanged(
        sessionId: String,
        updatedAtEpochMs: Long,
    ): Int
}
