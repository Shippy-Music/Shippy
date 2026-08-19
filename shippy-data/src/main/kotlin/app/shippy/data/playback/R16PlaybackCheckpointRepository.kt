/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackCheckpointRepository.kt is part of Auxio.
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
package app.shippy.data.playback

import androidx.room.withTransaction
import app.shippy.core.identity.ContributorId
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackCheckpoint
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.PlaybackOrigin
import app.shippy.core.queue.PlaybackOriginKind
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueState
import app.shippy.core.queue.ShuffleState
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.PlaybackCheckpointEntity
import app.shippy.data.db.entity.PlaybackCheckpointEntryEntity
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Source-neutral durable playback state. Provider locators and headers never cross this boundary.
 */
interface R16PlaybackCheckpointRepository {
    suspend fun load(): PlaybackCheckpoint?

    suspend fun save(checkpoint: PlaybackCheckpoint)

    suspend fun clear()
}

internal class RoomR16PlaybackCheckpointRepository(
    private val database: ShippyR16Database,
    private val now: () -> Instant = Instant::now,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) : R16PlaybackCheckpointRepository {
    override suspend fun load(): PlaybackCheckpoint? =
        database.withTransaction {
            val stored =
                database.playbackCheckpointDao().load(ACTIVE_SLOT) ?: return@withTransaction null
            val checkpoint = stored.checkpoint
            val entries = stored.entries
            require(checkpoint.checkpointVersion == PlaybackCheckpoint.CURRENT_VERSION) {
                "Unsupported R16 playback checkpoint version"
            }
            require(entries.size <= MAX_CHECKPOINT_ENTRIES) {
                "R16 playback checkpoint exceeds the queue limit"
            }
            require(checkpoint.updatedAtEpochMs >= 0) {
                "R16 playback checkpoint timestamp cannot be negative"
            }
            val expectedChecksum = PlaybackCheckpointIntegrity.checksum(checkpoint, entries)
            require(
                MessageDigest.isEqual(
                    expectedChecksum.toByteArray(),
                    checkpoint.checksum.toByteArray(),
                )
            ) {
                "R16 playback checkpoint checksum does not match"
            }

            val baseIds = JSONArray(checkpoint.baseOrderJson).stringValues()
            val traversalIds = JSONArray(checkpoint.traversalOrderJson).stringValues()
            require(baseIds == entries.map(PlaybackCheckpointEntryEntity::queueEntryId)) {
                "R16 playback checkpoint base order is inconsistent"
            }
            val entriesById = entries.associateBy(PlaybackCheckpointEntryEntity::queueEntryId)
            require(traversalIds.size == entries.size && traversalIds.toSet() == entriesById.keys) {
                "R16 playback checkpoint traversal is inconsistent"
            }

            val updatedAt = Instant.ofEpochMilli(checkpoint.updatedAtEpochMs)
            val queueEntries = entries.map { it.toDomain(updatedAt) }
            val queue =
                QueueState(
                    baseQueue = queueEntries,
                    traversalOrder = traversalIds.map(::QueueEntryId),
                    currentQueueEntryId = checkpoint.currentQueueEntryId?.let(::QueueEntryId),
                    shuffle =
                        if (checkpoint.shuffleEnabled) {
                            ShuffleState.On(checkpoint.shuffleSeed ?: LEGACY_SHUFFLE_SEED)
                        } else {
                            ShuffleState.Off
                        },
                )
            PlaybackCheckpoint(
                version = checkpoint.checkpointVersion,
                queue = queue,
                positionMs = checkpoint.positionMs,
                playWhenReady = checkpoint.playingIntent,
                repeatMode = RepeatMode.valueOf(checkpoint.repeatMode),
            )
        }

    override suspend fun save(checkpoint: PlaybackCheckpoint) {
        require(checkpoint.queue.baseQueue.size <= MAX_CHECKPOINT_ENTRIES) {
            "R16 playback checkpoint exceeds the queue limit"
        }
        database.withTransaction {
            val timestamp = now().toEpochMilli()
            require(timestamp >= 0) { "R16 playback checkpoint timestamp cannot be negative" }
            val existingSessionId =
                database.playbackCheckpointDao().load(ACTIVE_SLOT)?.checkpoint?.sessionId
            val sessionId = existingSessionId ?: sessionIdFactory()
            require(sessionId.isNotBlank()) { "R16 playback session ID cannot be blank" }
            val entries =
                checkpoint.queue.baseQueue.mapIndexed { position, entry ->
                    entry.toEntity(position)
                }
            val entityWithoutChecksum =
                PlaybackCheckpointEntity(
                    slot = ACTIVE_SLOT,
                    checkpointVersion = checkpoint.version,
                    sessionId = sessionId,
                    currentQueueEntryId = checkpoint.queue.currentQueueEntryId?.value,
                    positionMs = checkpoint.positionMs,
                    playingIntent = checkpoint.playWhenReady,
                    repeatMode = checkpoint.repeatMode.name,
                    shuffleEnabled = checkpoint.queue.shuffle is ShuffleState.On,
                    shuffleSeed = (checkpoint.queue.shuffle as? ShuffleState.On)?.seed,
                    baseOrderJson =
                        JSONArray(checkpoint.queue.baseQueue.map { it.id.value }).toString(),
                    traversalOrderJson =
                        JSONArray(checkpoint.queue.traversalOrder.map(QueueEntryId::value))
                            .toString(),
                    updatedAtEpochMs = timestamp,
                    checksum = "pending",
                )
            database
                .playbackCheckpointDao()
                .replace(
                    entityWithoutChecksum.copy(
                        checksum =
                            PlaybackCheckpointIntegrity.checksum(entityWithoutChecksum, entries)
                    ),
                    entries,
                )
        }
    }

    override suspend fun clear() {
        database.playbackCheckpointDao().clear(ACTIVE_SLOT)
    }
}

private data class StoredQueueEntryMetadata(
    val origin: PlaybackOrigin?,
    val playlistEntryId: PlaylistEntryId?,
    val addedAt: Instant,
)

private fun QueueEntry.toEntity(position: Int) =
    PlaybackCheckpointEntryEntity(
        slot = ACTIVE_SLOT,
        queueEntryId = id.value,
        position = position,
        recordingId = recordingId.value,
        originJson =
            JSONObject()
                .put("schemaVersion", QUEUE_ENTRY_METADATA_VERSION)
                .put("originKind", origin?.kind?.name)
                .put("originReferenceId", origin?.referenceId)
                .put("playlistEntryId", playlistEntryId?.value)
                .put("addedAtEpochMs", addedAt.toEpochMilli())
                .toString(),
        contributorId = contributor?.value,
        presentationFallbackJson = "{}",
    )

private fun PlaybackCheckpointEntryEntity.toDomain(defaultAddedAt: Instant): QueueEntry {
    val metadata = decodeMetadata(originJson, defaultAddedAt)
    return QueueEntry(
        id = QueueEntryId(queueEntryId),
        recordingId = RecordingId(recordingId),
        origin = metadata.origin,
        playlistEntryId = metadata.playlistEntryId,
        contributor = contributorId?.let(::ContributorId),
        addedAt = metadata.addedAt,
    )
}

private fun decodeMetadata(value: String?, defaultAddedAt: Instant): StoredQueueEntryMetadata {
    if (value == null) return StoredQueueEntryMetadata(null, null, defaultAddedAt)
    val json = JSONObject(value)
    if (json.optInt("schemaVersion") != QUEUE_ENTRY_METADATA_VERSION) {
        val legacyContextId = json.optNullableString("legacyContextId")
        return StoredQueueEntryMetadata(
            origin = legacyContextId?.let { PlaybackOrigin(PlaybackOriginKind.EXTERNAL, it) },
            playlistEntryId = null,
            addedAt = defaultAddedAt,
        )
    }
    val originKind = json.optNullableString("originKind")
    return StoredQueueEntryMetadata(
        origin =
            originKind?.let {
                PlaybackOrigin(
                    PlaybackOriginKind.valueOf(it),
                    json.optNullableString("originReferenceId"),
                )
            },
        playlistEntryId = json.optNullableString("playlistEntryId")?.let(::PlaylistEntryId),
        addedAt = Instant.ofEpochMilli(json.getLong("addedAtEpochMs")),
    )
}

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONArray.stringValues(): List<String> = (0 until length()).map(::getString)

private const val ACTIVE_SLOT = "active"
private const val QUEUE_ENTRY_METADATA_VERSION = 1
private const val MAX_CHECKPOINT_ENTRIES = 10_000
private const val LEGACY_SHUFFLE_SEED = 0L
