/*
 * Copyright (c) 2026 Auxio Project
 * LibraryRelationshipRepository.kt is part of Auxio.
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

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.LibraryRelationship
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId

interface LibraryRelationshipRepository {
    fun observe(trackId: TrackId): Flow<LibraryRelationship>

    fun observeAll(): Flow<List<LibraryRelationship>>

    fun observeLikedTrackIds(): Flow<List<TrackId>>

    fun observeDownloadedTrackIds(): Flow<List<TrackId>>

    fun observeUserPlaylists(): Flow<List<LibraryCollection.Playlist>>

    fun observeUserPlaylist(playlistId: LibraryCollectionId): Flow<LibraryCollection.Playlist?>

    fun observePlaylistArtwork(): Flow<Map<LibraryCollectionId, String>>

    fun observePlaylistTrackIds(playlistId: LibraryCollectionId): Flow<List<TrackId>>

    suspend fun setLiked(trackId: TrackId, liked: Boolean)

    suspend fun setLiked(track: Track, liked: Boolean)

    suspend fun setDownloaded(trackId: TrackId, downloaded: Boolean)

    suspend fun createPlaylist(playlist: LibraryCollection.Playlist)

    suspend fun renamePlaylist(playlistId: LibraryCollectionId, name: String)

    suspend fun setPlaylistPinned(playlistId: LibraryCollectionId, pinned: Boolean)

    suspend fun setPlaylistArtwork(playlistId: LibraryCollectionId, artworkUri: String?)

    suspend fun replacePlaylistOrder(playlistIds: List<LibraryCollectionId>)

    suspend fun replacePlaylistLayout(playlists: List<LibraryCollection.Playlist>)

    suspend fun deletePlaylist(playlistId: LibraryCollectionId)

    suspend fun replacePlaylistTracks(playlistId: LibraryCollectionId, trackIds: List<TrackId>)

    suspend fun replacePlaylistMemberships(trackId: TrackId, playlistIds: Set<LibraryCollectionId>)

    suspend fun replacePlaylistMemberships(track: Track, playlistIds: Set<LibraryCollectionId>)
}

internal class RoomLibraryRelationshipRepository
@Inject
constructor(
    private val dao: LibraryRelationshipDao,
    private val metadata: CanonicalTrackMetadataRepository,
) : LibraryRelationshipRepository {
    override fun observe(trackId: TrackId): Flow<LibraryRelationship> =
        dao.observe(trackId.value).map { stored ->
            stored?.toDomain() ?: LibraryRelationship(trackId)
        }

    override fun observeAll(): Flow<List<LibraryRelationship>> =
        dao.observeAll().map { relationships ->
            relationships.map(StoredLibraryRelationship::toDomain)
        }

    override fun observeLikedTrackIds(): Flow<List<TrackId>> =
        dao.observeLikedTrackIds().map(::toTrackIds)

    override fun observeDownloadedTrackIds(): Flow<List<TrackId>> =
        dao.observeDownloadedTrackIds().map(::toTrackIds)

    override fun observeUserPlaylists(): Flow<List<LibraryCollection.Playlist>> =
        dao.observeUserPlaylists().map { playlists -> playlists.map(UserPlaylistEntity::toDomain) }

    override fun observeUserPlaylist(
        playlistId: LibraryCollectionId
    ): Flow<LibraryCollection.Playlist?> {
        requireUserPlaylistId(playlistId)
        return dao.observeUserPlaylist(playlistId.value).map { it?.toDomain() }
    }

    override fun observePlaylistArtwork(): Flow<Map<LibraryCollectionId, String>> =
        dao.observePlaylistArtwork().map { rows ->
            buildMap {
                rows.forEach { row ->
                    val artwork = row.artwork ?: return@forEach
                    putIfAbsent(LibraryCollectionId(row.playlistId), artwork)
                }
            }
        }

    override fun observePlaylistTrackIds(playlistId: LibraryCollectionId): Flow<List<TrackId>> {
        requireUserPlaylistId(playlistId)
        return dao.observePlaylistTrackIds(playlistId.value).map(::toTrackIds)
    }

    override suspend fun setLiked(trackId: TrackId, liked: Boolean) {
        dao.setLiked(trackId.value, liked)
    }

    override suspend fun setLiked(track: Track, liked: Boolean) {
        metadata.upsert(track)
        setLiked(track.id, liked)
    }

    override suspend fun setDownloaded(trackId: TrackId, downloaded: Boolean) {
        dao.setDownloaded(trackId.value, downloaded)
    }

    override suspend fun createPlaylist(playlist: LibraryCollection.Playlist) {
        requireUserPlaylistId(playlist.id)
        dao.createPlaylist(playlist.id.value, playlist.displayName, playlist.isPinned)
    }

    override suspend fun renamePlaylist(playlistId: LibraryCollectionId, name: String) {
        requireUserPlaylistId(playlistId)
        require(name.isNotBlank()) { "Playlist name cannot be blank" }
        dao.renamePlaylist(playlistId.value, name)
    }

    override suspend fun setPlaylistPinned(playlistId: LibraryCollectionId, pinned: Boolean) {
        requireUserPlaylistId(playlistId)
        dao.setPlaylistPinned(playlistId.value, pinned)
    }

    override suspend fun setPlaylistArtwork(playlistId: LibraryCollectionId, artworkUri: String?) {
        requireUserPlaylistId(playlistId)
        dao.setPlaylistArtwork(playlistId.value, artworkUri?.takeIf(String::isNotBlank))
    }

    override suspend fun replacePlaylistOrder(playlistIds: List<LibraryCollectionId>) {
        playlistIds.forEach(::requireUserPlaylistId)
        require(playlistIds.distinct().size == playlistIds.size) {
            "Playlist order cannot contain duplicate IDs"
        }
        dao.replacePlaylistOrder(playlistIds.map(LibraryCollectionId::value))
    }

    override suspend fun replacePlaylistLayout(playlists: List<LibraryCollection.Playlist>) {
        playlists.forEach { requireUserPlaylistId(it.id) }
        require(playlists.map { it.id }.distinct().size == playlists.size) {
            "Playlist layout cannot contain duplicate IDs"
        }
        dao.replacePlaylistLayout(playlists.map { it.id.value to it.isPinned })
    }

    override suspend fun deletePlaylist(playlistId: LibraryCollectionId) {
        requireUserPlaylistId(playlistId)
        dao.deletePlaylist(playlistId.value)
    }

    override suspend fun replacePlaylistTracks(
        playlistId: LibraryCollectionId,
        trackIds: List<TrackId>,
    ) {
        requireUserPlaylistId(playlistId)
        require(trackIds.distinct().size == trackIds.size) {
            "A track can occur only once in a user playlist"
        }
        dao.replacePlaylistTracks(playlistId.value, trackIds.map(TrackId::value))
    }

    override suspend fun replacePlaylistMemberships(
        trackId: TrackId,
        playlistIds: Set<LibraryCollectionId>,
    ) {
        playlistIds.forEach(::requireUserPlaylistId)
        dao.replacePlaylistMemberships(
            trackId.value,
            playlistIds.map(LibraryCollectionId::value).sorted(),
        )
    }

    override suspend fun replacePlaylistMemberships(
        track: Track,
        playlistIds: Set<LibraryCollectionId>,
    ) {
        metadata.upsert(track)
        replacePlaylistMemberships(track.id, playlistIds)
    }
}

internal fun StoredLibraryRelationship.toDomain(): LibraryRelationship =
    LibraryRelationship(
        trackId = TrackId(relationship.trackId),
        liked = relationship.liked,
        downloaded = relationship.downloaded,
        playlistIds =
            playlistMemberships.mapTo(linkedSetOf()) { LibraryCollectionId(it.playlistId) },
    )

internal fun UserPlaylistEntity.toDomain(): LibraryCollection.Playlist =
    LibraryCollection.Playlist(
        id = LibraryCollectionId(playlistId),
        displayName = name,
        isPinned = pinned,
        artworkUri = artworkUri,
    )

internal fun toTrackIds(values: List<String>): List<TrackId> = values.map(::TrackId)

internal fun requireUserPlaylistId(playlistId: LibraryCollectionId) {
    require(!playlistId.isSystem) {
        "System collections cannot be stored or mutated as user playlists"
    }
}
