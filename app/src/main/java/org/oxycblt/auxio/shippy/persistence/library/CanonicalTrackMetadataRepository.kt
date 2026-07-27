/*
 * Copyright (c) 2026 Auxio Project
 * CanonicalTrackMetadataRepository.kt is part of Auxio.
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
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.domain.TrackVersion
import timber.log.Timber as L

/** Durable canonical metadata for relationship-backed library rows, independent of downloads. */
interface CanonicalTrackMetadataRepository {
    fun observeAll(): Flow<List<Track>>

    suspend fun getByIds(ids: List<TrackId>): Map<TrackId, Track>

    suspend fun upsert(track: Track)
}

@Entity(tableName = "canonical_track")
internal data class CanonicalTrackEntity(
    @PrimaryKey val trackId: String,
    val realm: String,
    val title: String,
    val artists: String,
    val album: String?,
    val durationMs: Long?,
    val versionLabel: String?,
    val explicit: Boolean?,
    val live: Boolean,
    val remix: Boolean,
    val artwork: String?,
)

@Entity(
    tableName = "canonical_track_candidate",
    primaryKeys = ["trackId", "candidateId"],
    foreignKeys =
        [
            ForeignKey(
                entity = CanonicalTrackEntity::class,
                parentColumns = ["trackId"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("trackId")],
)
internal data class CanonicalTrackCandidateEntity(
    val trackId: String,
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

internal data class StoredCanonicalTrack(
    @androidx.room.Embedded val track: CanonicalTrackEntity,
    @androidx.room.Relation(parentColumn = "trackId", entityColumn = "trackId")
    val candidates: List<CanonicalTrackCandidateEntity>,
)

@Dao
internal abstract class CanonicalTrackMetadataDao {
    @androidx.room.Transaction
    @Query("SELECT * FROM canonical_track ORDER BY trackId")
    abstract fun observeAll(): Flow<List<StoredCanonicalTrack>>

    @androidx.room.Transaction
    @Query("SELECT * FROM canonical_track WHERE trackId IN (:trackIds)")
    abstract suspend fun getByIds(trackIds: List<String>): List<StoredCanonicalTrack>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTrack(track: CanonicalTrackEntity)

    @Query("DELETE FROM canonical_track_candidate WHERE trackId = :trackId")
    protected abstract suspend fun deleteCandidates(trackId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCandidates(candidates: List<CanonicalTrackCandidateEntity>)

    @Transaction
    open suspend fun upsert(
        track: CanonicalTrackEntity,
        candidates: List<CanonicalTrackCandidateEntity>,
    ) {
        insertTrack(track)
        deleteCandidates(track.trackId)
        if (candidates.isNotEmpty()) insertCandidates(candidates)
    }
}

@Singleton
internal class RoomCanonicalTrackMetadataRepository
@Inject
constructor(private val dao: CanonicalTrackMetadataDao) : CanonicalTrackMetadataRepository {
    override fun observeAll(): Flow<List<Track>> =
        dao.observeAll().map { stored -> stored.mapNotNull(StoredCanonicalTrack::toDomainOrNull) }

    override suspend fun getByIds(ids: List<TrackId>): Map<TrackId, Track> =
        ids.distinct()
            .chunked(ROOM_QUERY_ID_CHUNK_SIZE)
            .flatMap { chunk -> dao.getByIds(chunk.map(TrackId::value)) }
            .mapNotNull(StoredCanonicalTrack::toDomainOrNull)
            .associateBy(Track::id)

    override suspend fun upsert(track: Track) =
        dao.upsert(
            track.toEntity(),
            track.candidates.mapIndexed { index, candidate -> candidate.toEntity(track.id, index) },
        )
}

// Android SQLite historically limits one statement to 999 bind parameters.
private const val ROOM_QUERY_ID_CHUNK_SIZE = 900

private fun Track.toEntity() =
    CanonicalTrackEntity(
        id.value,
        realm.name,
        title,
        artists.encode(),
        album,
        durationMs,
        version.label,
        version.explicit,
        version.isLive,
        version.isRemix,
        artwork,
    )

private fun TrackCandidate.toEntity(trackId: TrackId, position: Int) =
    CanonicalTrackCandidateEntity(
        trackId.value,
        id.value,
        position,
        kind.name,
        sourceId,
        sourceItemId,
        availability.name,
        locator,
        providerId?.value,
        media?.mimeType,
        media?.container,
        media?.bitrateBps,
        media?.contentLength,
    )

private fun StoredCanonicalTrack.toDomainOrNull(): Track? =
    runCatching {
            Track(
                TrackId(track.trackId),
                TrackRealm.valueOf(track.realm),
                track.title,
                track.artists.decode(),
                track.album,
                track.durationMs,
                TrackVersion(track.versionLabel, track.explicit, track.live, track.remix),
                track.artwork,
                candidates
                    .sortedBy { it.position }
                    .map { candidate ->
                        TrackCandidate(
                            CandidateId(candidate.candidateId),
                            TrackId(track.trackId),
                            CandidateKind.valueOf(candidate.kind),
                            candidate.sourceId,
                            candidate.sourceItemId,
                            CandidateAvailability.valueOf(candidate.availability),
                            candidate.locator,
                            candidate.providerId?.let(::ProviderId),
                            candidate.toMediaDescriptor(),
                        )
                    },
            )
        }
        .onFailure { error ->
            L.e(error, "Ignoring invalid canonical track metadata for ${track.trackId}")
        }
        .getOrNull()

private fun CanonicalTrackCandidateEntity.toMediaDescriptor(): MediaDescriptor? =
    if (mimeType == null && container == null && bitrateBps == null && contentLength == null) {
        null
    } else {
        MediaDescriptor(mimeType, container, bitrateBps, contentLength)
    }

private fun List<String>.encode() = joinToString(separator = "") { "${it.length}:$it" }

private fun String.decode(): List<String> = buildList {
    var cursor = 0
    while (cursor < length) {
        val separator = indexOf(':', cursor)
        require(separator > cursor)
        val size = substring(cursor, separator).toInt()
        val start = separator + 1
        require(start + size <= length)
        add(substring(start, start + size))
        cursor = start + size
    }
}
