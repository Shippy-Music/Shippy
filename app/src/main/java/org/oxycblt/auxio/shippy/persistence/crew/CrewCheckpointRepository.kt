/*
 * Copyright (c) 2026 Auxio Project
 * CrewCheckpointRepository.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.persistence.crew

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlCodec
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlDecodeResult
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlMessage

data class PersistedCrewCheckpoint(val snapshot: CrewSnapshot, val updatedAtEpochMs: Long)

sealed interface CrewCheckpointLoadResult {
    data object Empty : CrewCheckpointLoadResult

    data class Loaded(val checkpoint: PersistedCrewCheckpoint) : CrewCheckpointLoadResult

    /**
     * The row failed integrity/semantic validation and was removed if no newer write replaced it.
     */
    data class CorruptRemoved(val storedSessionId: String) : CrewCheckpointLoadResult
}

interface CrewCheckpointRepository {
    suspend fun load(): CrewCheckpointLoadResult

    suspend fun save(snapshot: CrewSnapshot, nowEpochMs: Long)

    /** Clears only the named session, so a delayed leave cannot erase a newly joined Crew. */
    suspend fun clear(sessionId: CrewSessionId): Boolean
}

@Singleton
internal class RoomCrewCheckpointRepository
@Inject
constructor(private val dao: CrewCheckpointDao) : CrewCheckpointRepository {
    private val mutationMutex = Mutex()

    override suspend fun load(): CrewCheckpointLoadResult =
        mutationMutex.withLock {
            val entity = dao.getActive() ?: return@withLock CrewCheckpointLoadResult.Empty
            when (val decoded = CrewCheckpointPersistence.decode(entity)) {
                is CrewCheckpointLoadResult.Loaded -> decoded
                is CrewCheckpointLoadResult.CorruptRemoved -> {
                    dao.deleteIfUnchanged(entity.sessionId, entity.updatedAtEpochMs)
                    decoded
                }
                CrewCheckpointLoadResult.Empty -> error("Stored checkpoint decoded as empty")
            }
        }

    override suspend fun save(snapshot: CrewSnapshot, nowEpochMs: Long) {
        mutationMutex.withLock {
            dao.replace(CrewCheckpointPersistence.encode(snapshot, nowEpochMs))
        }
    }

    override suspend fun clear(sessionId: CrewSessionId): Boolean =
        mutationMutex.withLock { dao.deleteSession(sessionId.value) == 1 }
}

internal object CrewCheckpointPersistence {
    private const val ACTIVE_SLOT = "active"

    fun encode(snapshot: CrewSnapshot, nowEpochMs: Long): CrewCheckpointEntity {
        require(nowEpochMs >= 0) { "Crew checkpoint timestamp cannot be negative" }
        val payload =
            CrewControlCodec.encode(
                CrewControlMessage.SnapshotInstalled(
                    snapshot = snapshot,
                    electionVotes = emptyList(),
                )
            )
        return CrewCheckpointEntity(
            slot = ACTIVE_SLOT,
            sessionId = snapshot.sessionId.value,
            protocolVersion = snapshot.protocolVersion.value,
            coordinatorTerm = snapshot.term.value,
            eventSequence = snapshot.lastSequence.value,
            snapshotPayload = payload,
            payloadSha256 = payload.sha256(),
            updatedAtEpochMs = nowEpochMs,
        )
    }

    fun decode(entity: CrewCheckpointEntity): CrewCheckpointLoadResult {
        if (
            entity.slot != ACTIVE_SLOT ||
                entity.sessionId.isBlank() ||
                entity.protocolVersion <= 0 ||
                entity.coordinatorTerm <= 0 ||
                entity.eventSequence < 0 ||
                entity.updatedAtEpochMs < 0 ||
                entity.payloadSha256.size != 32 ||
                !entity.snapshotPayload.sha256().contentEquals(entity.payloadSha256)
        ) {
            return CrewCheckpointLoadResult.CorruptRemoved(entity.sessionId)
        }
        val message =
            when (val result = CrewControlCodec.decode(entity.snapshotPayload)) {
                is CrewControlDecodeResult.Accepted -> result.message
                is CrewControlDecodeResult.Rejected ->
                    return CrewCheckpointLoadResult.CorruptRemoved(entity.sessionId)
            }
        if (
            message !is CrewControlMessage.SnapshotInstalled || message.electionVotes.isNotEmpty()
        ) {
            return CrewCheckpointLoadResult.CorruptRemoved(entity.sessionId)
        }
        val snapshot = message.snapshot
        if (
            snapshot.sessionId.value != entity.sessionId ||
                snapshot.protocolVersion.value != entity.protocolVersion ||
                snapshot.term.value != entity.coordinatorTerm ||
                snapshot.lastSequence.value != entity.eventSequence
        ) {
            return CrewCheckpointLoadResult.CorruptRemoved(entity.sessionId)
        }
        return CrewCheckpointLoadResult.Loaded(
            PersistedCrewCheckpoint(snapshot, entity.updatedAtEpochMs)
        )
    }

    private fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
}
