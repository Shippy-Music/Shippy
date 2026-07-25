/*
 * Copyright (c) 2026 Shippy contributors
 * LibraryRelationshipRepository.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.library

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.LibraryRelationship
import org.oxycblt.auxio.shippy.domain.TrackId

interface LibraryRelationshipRepository {
    fun observe(trackId: TrackId): Flow<LibraryRelationship>

    fun observeAll(): Flow<List<LibraryRelationship>>

    suspend fun setLiked(trackId: TrackId, liked: Boolean)

    suspend fun setDownloaded(trackId: TrackId, downloaded: Boolean)

    suspend fun replacePlaylistMemberships(
        trackId: TrackId,
        playlistIds: Set<LibraryCollectionId>,
    )
}

internal class RoomLibraryRelationshipRepository
@Inject
constructor(private val dao: LibraryRelationshipDao) : LibraryRelationshipRepository {
    override fun observe(trackId: TrackId): Flow<LibraryRelationship> =
        dao.observe(trackId.value).map { stored ->
            stored?.toDomain() ?: LibraryRelationship(trackId)
        }

    override fun observeAll(): Flow<List<LibraryRelationship>> =
        dao.observeAll().map { relationships -> relationships.map(StoredLibraryRelationship::toDomain) }

    override suspend fun setLiked(trackId: TrackId, liked: Boolean) {
        dao.setLiked(trackId.value, liked)
    }

    override suspend fun setDownloaded(trackId: TrackId, downloaded: Boolean) {
        dao.setDownloaded(trackId.value, downloaded)
    }

    override suspend fun replacePlaylistMemberships(
        trackId: TrackId,
        playlistIds: Set<LibraryCollectionId>,
    ) {
        require(playlistIds.none(LibraryCollectionId::isSystem)) {
            "System collections cannot be stored as user playlist memberships"
        }
        dao.replacePlaylistMemberships(
            trackId.value,
            playlistIds.map { PlaylistMembershipEntity(trackId.value, it.value) },
        )
    }
}

internal fun StoredLibraryRelationship.toDomain(): LibraryRelationship =
    LibraryRelationship(
        trackId = TrackId(relationship.trackId),
        liked = relationship.liked,
        downloaded = relationship.downloaded,
        playlistIds = playlistMemberships.mapTo(linkedSetOf()) { LibraryCollectionId(it.playlistId) },
    )
