/*
 * Copyright (c) 2026 Auxio Project
 * LibraryRelationshipDao.kt is part of Auxio.
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

@Entity(tableName = "user_playlist", indices = [Index("position")])
internal data class UserPlaylistEntity(
    @PrimaryKey val playlistId: String,
    val name: String,
    val pinned: Boolean,
    val position: Int,
    val artworkUri: String? = null,
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
            ),
            ForeignKey(
                entity = UserPlaylistEntity::class,
                parentColumns = ["playlistId"],
                childColumns = ["playlistId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("trackId"), Index("playlistId")],
)
internal data class PlaylistMembershipEntity(
    val trackId: String,
    val playlistId: String,
    val position: Int,
)

internal data class StoredLibraryRelationship(
    @Embedded val relationship: LibraryRelationshipEntity,
    @Relation(parentColumn = "trackId", entityColumn = "trackId")
    val playlistMemberships: List<PlaylistMembershipEntity>,
)

internal data class PlaylistArtworkProjection(val playlistId: String, val artwork: String?)

@Dao
internal abstract class LibraryRelationshipDao {
    @Transaction
    @Query("SELECT * FROM library_relationship WHERE trackId = :trackId")
    abstract fun observe(trackId: String): Flow<StoredLibraryRelationship?>

    @Transaction
    @Query("SELECT * FROM library_relationship ORDER BY trackId")
    abstract fun observeAll(): Flow<List<StoredLibraryRelationship>>

    @Query("SELECT trackId FROM library_relationship WHERE liked = 1 ORDER BY trackId")
    abstract fun observeLikedTrackIds(): Flow<List<String>>

    @Query("SELECT trackId FROM library_relationship WHERE downloaded = 1 ORDER BY trackId")
    abstract fun observeDownloadedTrackIds(): Flow<List<String>>

    @Query("SELECT * FROM user_playlist ORDER BY pinned DESC, position, playlistId")
    abstract fun observeUserPlaylists(): Flow<List<UserPlaylistEntity>>

    @Query("SELECT * FROM user_playlist WHERE playlistId = :playlistId")
    abstract fun observeUserPlaylist(playlistId: String): Flow<UserPlaylistEntity?>

    @Query(
        """
        SELECT membership.playlistId AS playlistId, track.artwork AS artwork
        FROM playlist_membership AS membership
        INNER JOIN canonical_track AS track ON track.trackId = membership.trackId
        WHERE track.artwork IS NOT NULL
        ORDER BY membership.playlistId, membership.position
        """
    )
    abstract fun observePlaylistArtwork(): Flow<List<PlaylistArtworkProjection>>

    @Query(
        """
        SELECT trackId FROM playlist_membership
        WHERE playlistId = :playlistId
        ORDER BY position, trackId
        """
    )
    abstract fun observePlaylistTrackIds(playlistId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun ensureRelationship(entity: LibraryRelationshipEntity)

    @Query("UPDATE library_relationship SET liked = :liked WHERE trackId = :trackId")
    protected abstract suspend fun updateLiked(trackId: String, liked: Boolean)

    @Query("UPDATE library_relationship SET downloaded = :downloaded WHERE trackId = :trackId")
    protected abstract suspend fun updateDownloaded(trackId: String, downloaded: Boolean)

    @Query("DELETE FROM playlist_membership WHERE trackId = :trackId AND playlistId = :playlistId")
    protected abstract suspend fun deletePlaylistMembership(trackId: String, playlistId: String)

    @Query("DELETE FROM playlist_membership WHERE playlistId = :playlistId")
    protected abstract suspend fun deletePlaylistTracks(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPlaylistMemberships(
        memberships: List<PlaylistMembershipEntity>
    )

    @Query("SELECT * FROM playlist_membership WHERE trackId = :trackId")
    protected abstract suspend fun getPlaylistMemberships(
        trackId: String
    ): List<PlaylistMembershipEntity>

    @Query(
        "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_membership WHERE playlistId = :playlistId"
    )
    protected abstract suspend fun nextTrackPosition(playlistId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM user_playlist WHERE playlistId = :playlistId)")
    protected abstract suspend fun playlistExists(playlistId: String): Boolean

    @Query("SELECT playlistId FROM user_playlist ORDER BY position, playlistId")
    protected abstract suspend fun getPlaylistIds(): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM user_playlist")
    protected abstract suspend fun nextPlaylistPosition(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPlaylist(entity: UserPlaylistEntity)

    @Query("UPDATE user_playlist SET name = :name WHERE playlistId = :playlistId")
    protected abstract suspend fun updatePlaylistName(playlistId: String, name: String): Int

    @Query("UPDATE user_playlist SET pinned = :pinned WHERE playlistId = :playlistId")
    protected abstract suspend fun updatePlaylistPinned(playlistId: String, pinned: Boolean): Int

    @Query("UPDATE user_playlist SET artworkUri = :artworkUri WHERE playlistId = :playlistId")
    protected abstract suspend fun updatePlaylistArtwork(
        playlistId: String,
        artworkUri: String?,
    ): Int

    @Query("UPDATE user_playlist SET position = :position WHERE playlistId = :playlistId")
    protected abstract suspend fun updatePlaylistPosition(playlistId: String, position: Int)

    @Query("DELETE FROM user_playlist WHERE playlistId = :playlistId")
    protected abstract suspend fun deletePlaylistEntity(playlistId: String): Int

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
    open suspend fun replacePlaylistMemberships(trackId: String, playlistIds: List<String>) {
        ensureRelationship(LibraryRelationshipEntity(trackId))
        check(playlistIds.all { playlistExists(it) }) { "User playlist does not exist" }

        val requested = playlistIds.toSet()
        val current = getPlaylistMemberships(trackId)
        current
            .filterNot { it.playlistId in requested }
            .forEach { deletePlaylistMembership(trackId, it.playlistId) }

        val currentIds = current.mapTo(mutableSetOf()) { it.playlistId }
        playlistIds
            .filterNot { it in currentIds }
            .forEach { playlistId ->
                insertPlaylistMemberships(
                    listOf(
                        PlaylistMembershipEntity(
                            trackId = trackId,
                            playlistId = playlistId,
                            position = nextTrackPosition(playlistId),
                        )
                    )
                )
            }
    }

    @Transaction
    open suspend fun createPlaylist(playlistId: String, name: String, pinned: Boolean) {
        check(!playlistId.startsWith("system:")) {
            "System collections cannot be stored as user playlists"
        }
        insertPlaylist(
            UserPlaylistEntity(
                playlistId = playlistId,
                name = name,
                pinned = pinned,
                position = nextPlaylistPosition(),
                artworkUri = null,
            )
        )
    }

    @Transaction
    open suspend fun renamePlaylist(playlistId: String, name: String) {
        check(updatePlaylistName(playlistId, name) == 1) { "User playlist does not exist" }
    }

    @Transaction
    open suspend fun setPlaylistPinned(playlistId: String, pinned: Boolean) {
        check(updatePlaylistPinned(playlistId, pinned) == 1) { "User playlist does not exist" }
    }

    @Transaction
    open suspend fun setPlaylistArtwork(playlistId: String, artworkUri: String?) {
        check(updatePlaylistArtwork(playlistId, artworkUri) == 1) { "User playlist does not exist" }
    }

    @Transaction
    open suspend fun replacePlaylistOrder(playlistIds: List<String>) {
        check(playlistIds.distinct().size == playlistIds.size) {
            "Playlist order cannot contain duplicate IDs"
        }
        check(getPlaylistIds().toSet() == playlistIds.toSet()) {
            "Playlist order must contain every user playlist exactly once"
        }
        playlistIds.forEachIndexed { position, playlistId ->
            updatePlaylistPosition(playlistId, position)
        }
    }

    @Transaction
    open suspend fun replacePlaylistLayout(playlists: List<Pair<String, Boolean>>) {
        val playlistIds = playlists.map(Pair<String, Boolean>::first)
        check(playlistIds.distinct().size == playlistIds.size) {
            "Playlist layout cannot contain duplicate IDs"
        }
        check(getPlaylistIds().toSet() == playlistIds.toSet()) {
            "Playlist layout must contain every user playlist exactly once"
        }
        playlists.forEachIndexed { position, (playlistId, pinned) ->
            updatePlaylistPinned(playlistId, pinned)
            updatePlaylistPosition(playlistId, position)
        }
    }

    @Transaction
    open suspend fun deletePlaylist(playlistId: String) {
        check(deletePlaylistEntity(playlistId) == 1) { "User playlist does not exist" }
    }

    @Transaction
    open suspend fun replacePlaylistTracks(playlistId: String, trackIds: List<String>) {
        check(playlistExists(playlistId)) { "User playlist does not exist" }
        trackIds.forEach { ensureRelationship(LibraryRelationshipEntity(it)) }
        deletePlaylistTracks(playlistId)
        if (trackIds.isNotEmpty()) {
            insertPlaylistMemberships(
                trackIds.mapIndexed { position, trackId ->
                    PlaylistMembershipEntity(trackId, playlistId, position)
                }
            )
        }
    }
}
