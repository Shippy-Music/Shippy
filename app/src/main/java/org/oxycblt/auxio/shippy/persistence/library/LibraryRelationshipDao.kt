/*
 * Copyright (c) 2026 Shippy contributors
 * LibraryRelationshipDao.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.library

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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "library_relationship")
internal data class LibraryRelationshipEntity(
    @PrimaryKey val trackId: String,
    val liked: Boolean = false,
    val downloaded: Boolean = false,
)

@Entity(
    tableName = "playlist_membership",
    primaryKeys = ["trackId", "playlistId"],
    foreignKeys =
        [
            ForeignKey(
                entity = LibraryRelationshipEntity::class,
                parentColumns = ["trackId"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("trackId"), Index("playlistId")],
)
internal data class PlaylistMembershipEntity(
    val trackId: String,
    val playlistId: String,
)

internal data class StoredLibraryRelationship(
    @Embedded val relationship: LibraryRelationshipEntity,
    @Relation(parentColumn = "trackId", entityColumn = "trackId")
    val playlistMemberships: List<PlaylistMembershipEntity>,
)

@Dao
internal abstract class LibraryRelationshipDao {
    @Transaction
    @Query("SELECT * FROM library_relationship WHERE trackId = :trackId")
    abstract fun observe(trackId: String): Flow<StoredLibraryRelationship?>

    @Transaction
    @Query("SELECT * FROM library_relationship ORDER BY trackId")
    abstract fun observeAll(): Flow<List<StoredLibraryRelationship>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun ensureRelationship(entity: LibraryRelationshipEntity)

    @Query("UPDATE library_relationship SET liked = :liked WHERE trackId = :trackId")
    protected abstract suspend fun updateLiked(trackId: String, liked: Boolean)

    @Query("UPDATE library_relationship SET downloaded = :downloaded WHERE trackId = :trackId")
    protected abstract suspend fun updateDownloaded(trackId: String, downloaded: Boolean)

    @Query("DELETE FROM playlist_membership WHERE trackId = :trackId")
    protected abstract suspend fun deletePlaylistMemberships(trackId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPlaylistMemberships(
        memberships: List<PlaylistMembershipEntity>
    )

    @Transaction
    open suspend fun setLiked(trackId: String, liked: Boolean) {
        ensureRelationship(LibraryRelationshipEntity(trackId))
        updateLiked(trackId, liked)
    }

    @Transaction
    open suspend fun setDownloaded(trackId: String, downloaded: Boolean) {
        ensureRelationship(LibraryRelationshipEntity(trackId))
        updateDownloaded(trackId, downloaded)
    }

    @Transaction
    open suspend fun replacePlaylistMemberships(
        trackId: String,
        memberships: List<PlaylistMembershipEntity>,
    ) {
        ensureRelationship(LibraryRelationshipEntity(trackId))
        deletePlaylistMemberships(trackId)
        if (memberships.isNotEmpty()) {
            insertPlaylistMemberships(memberships)
        }
    }
}
