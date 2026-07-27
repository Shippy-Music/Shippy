/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCheckpointRepository.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.persistence.playback

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
import kotlinx.coroutines.CancellationException
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.persistence.library.CanonicalTrackMetadataRepository

private const val ACTIVE_SLOT = "active"
internal const val MAX_PLAYBACK_CHECKPOINT_ITEMS = 10_000

data class CanonicalPlaybackCheckpoint(
    val heap: List<QueueItem>,
    val mapping: List<Int>,
    val heapIndex: Int,
    val positionMs: Long,
    val repeatMode: RepeatMode,
)

interface PlaybackCheckpointRepository {
    suspend fun replace(checkpoint: CanonicalPlaybackCheckpoint)

    suspend fun read(): CanonicalPlaybackCheckpoint?

    suspend fun clear()
}

@Entity(tableName = "playback_checkpoint")
internal data class PlaybackCheckpointEntity(
    @PrimaryKey val slot: String = ACTIVE_SLOT,
    val positionMs: Long,
    val repeatMode: String,
    val heapIndex: Int,
    val shuffledMapping: String,
)

@Entity(
    tableName = "playback_checkpoint_item",
    primaryKeys = ["slot", "heapPosition"],
    foreignKeys =
        [
            ForeignKey(
                entity = PlaybackCheckpointEntity::class,
                parentColumns = ["slot"],
                childColumns = ["slot"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("trackId")],
)
internal data class PlaybackCheckpointItemEntity(
    val slot: String = ACTIVE_SLOT,
    val heapPosition: Int,
    val queueItemId: String,
    val trackId: String,
    val contextId: String?,
    val contributorId: String?,
)

@Dao
internal abstract class PlaybackCheckpointDao {
    @Query("SELECT * FROM playback_checkpoint WHERE slot = :slot")
    abstract suspend fun checkpoint(slot: String = ACTIVE_SLOT): PlaybackCheckpointEntity?

    @Query("SELECT * FROM playback_checkpoint_item WHERE slot = :slot ORDER BY heapPosition")
    abstract suspend fun items(slot: String = ACTIVE_SLOT): List<PlaybackCheckpointItemEntity>

    @Query("DELETE FROM playback_checkpoint WHERE slot = :slot")
    abstract suspend fun clear(slot: String = ACTIVE_SLOT)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertCheckpoint(value: PlaybackCheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertItems(values: List<PlaybackCheckpointItemEntity>)

    @Transaction
    open suspend fun replace(
        value: PlaybackCheckpointEntity,
        items: List<PlaybackCheckpointItemEntity>,
    ) {
        clear()
        insertCheckpoint(value)
        insertItems(items)
    }
}

@Singleton
internal class RoomPlaybackCheckpointRepository
@Inject
constructor(
    private val dao: PlaybackCheckpointDao,
    private val tracks: CanonicalTrackMetadataRepository,
) : PlaybackCheckpointRepository {
    override suspend fun replace(checkpoint: CanonicalPlaybackCheckpoint) {
        if (!checkpoint.valid()) return clear()
        checkpoint.heap.map(QueueItem::track).distinctBy(Track::id).forEach {
            tracks.upsert(sanitizeCheckpointTrack(it))
        }
        dao.replace(
            PlaybackCheckpointEntity(
                positionMs = checkpoint.positionMs,
                repeatMode = checkpoint.repeatMode.name,
                heapIndex = checkpoint.heapIndex,
                shuffledMapping = checkpoint.mapping.joinToString(","),
            ),
            checkpoint.heap.mapIndexed { index, item ->
                PlaybackCheckpointItemEntity(
                    heapPosition = index,
                    queueItemId = item.id.value,
                    trackId = item.track.id.value,
                    contextId = item.contextId,
                    contributorId = item.contributorId,
                )
            },
        )
    }

    override suspend fun read(): CanonicalPlaybackCheckpoint? {
        return try {
            val checkpoint = dao.checkpoint() ?: return null
            val items = dao.items()
            if (items.size !in 1..MAX_PLAYBACK_CHECKPOINT_ITEMS) return corrupt()
            val mapping =
                checkpoint.shuffledMapping
                    .takeIf(String::isNotEmpty)
                    ?.split(',')
                    ?.map(String::toInt) ?: emptyList()
            if (items.map(PlaybackCheckpointItemEntity::heapPosition) != items.indices.toList())
                return corrupt()
            val metadata = tracks.getByIds(items.map { TrackId(it.trackId) })
            if (metadata.size != items.map { it.trackId }.distinct().size) return corrupt()
            CanonicalPlaybackCheckpoint(
                    items.map {
                        QueueItem(
                            QueueItemId(it.queueItemId),
                            requireNotNull(metadata[TrackId(it.trackId)]),
                            it.contextId,
                            it.contributorId,
                        )
                    },
                    mapping,
                    checkpoint.heapIndex,
                    checkpoint.positionMs,
                    RepeatMode.valueOf(checkpoint.repeatMode),
                )
                .takeIf(CanonicalPlaybackCheckpoint::valid) ?: corrupt()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            corrupt()
        }
    }

    override suspend fun clear() = dao.clear()

    private suspend fun corrupt(): Nothing? {
        clear()
        return null
    }
}

internal fun sanitizeCheckpointTrack(track: Track) =
    track.copy(
        candidates =
            track.candidates.filterNot {
                it.kind == CandidateKind.DOWNLOAD || it.kind == CandidateKind.CREW_TEMPORARY
            }
    )

internal fun CanonicalPlaybackCheckpoint.valid() =
    heap.size in 1..MAX_PLAYBACK_CHECKPOINT_ITEMS &&
        positionMs >= 0 &&
        heapIndex in heap.indices &&
        heap.map { it.id }.distinct().size == heap.size &&
        mapping.let {
            it.isEmpty() || (it.size == heap.size && it.sorted() == heap.indices.toList())
        }
