/*
 * Copyright (c) 2026 Auxio Project
 * R16LastFmOutboxRepository.kt is part of Auxio.
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
package app.shippy.data.lastfm

import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.LastFmScrobbleOutboxEntity

/**
 * Source-neutral Last.fm work item exposed to the delivery boundary.
 *
 * The app/network layer must not depend on Room entities. Queue identity remains the durable
 * listening-session identity, while attempt fields are retained so a retry worker can make a
 * decision without re-reading a legacy model.
 */
data class R16LastFmOutboxEntry(
    val outboxId: String,
    val accountId: String,
    val listeningSessionId: String,
    val recordingId: String,
    val artist: String,
    val track: String,
    val album: String?,
    val durationSeconds: Long?,
    val startedAtEpochSeconds: Long,
    val chosenByUser: Boolean,
    val queuedAtEpochMs: Long,
    val attemptCount: Int,
    val lastAttemptAtEpochMs: Long?,
)

/** Durable FIFO operations required by the Last.fm delivery worker. */
interface R16LastFmOutboxRepository {
    /** Returns at most [MAX_BATCH_SIZE] oldest entries in deterministic FIFO order. */
    suspend fun oldest(limit: Int = MAX_BATCH_SIZE): List<R16LastFmOutboxEntry> =
        oldest(accountId = "", limit = limit)

    /**
     * Returns at most [MAX_BATCH_SIZE] oldest entries for the given account in deterministic FIFO
     * order.
     */
    suspend fun oldest(accountId: String, limit: Int = MAX_BATCH_SIZE): List<R16LastFmOutboxEntry>

    /** Records one delivery attempt; false means the durable entry no longer exists. */
    suspend fun recordAttempt(outboxId: String, attemptedAtEpochMs: Long): Boolean

    /** Removes accepted or terminally ignored entries from one bounded delivery batch. */
    suspend fun deleteAccepted(outboxIds: Set<String>): Int

    suspend fun count(): Int

    companion object {
        const val MAX_BATCH_SIZE: Int = 50
    }
}

internal class RoomR16LastFmOutboxRepository(private val database: ShippyR16Database) :
    R16LastFmOutboxRepository {
    override suspend fun oldest(limit: Int): List<R16LastFmOutboxEntry> {
        require(limit > 0) { "Last.fm outbox limit must be positive" }
        return database
            .lastFmOutboxDao()
            .oldest(limit.coerceAtMost(R16LastFmOutboxRepository.MAX_BATCH_SIZE))
            .map(LastFmScrobbleOutboxEntity::toR16Entry)
    }

    override suspend fun oldest(accountId: String, limit: Int): List<R16LastFmOutboxEntry> {
        require(limit > 0) { "Last.fm outbox limit must be positive" }
        return database
            .lastFmOutboxDao()
            .oldest(accountId, limit.coerceAtMost(R16LastFmOutboxRepository.MAX_BATCH_SIZE))
            .map(LastFmScrobbleOutboxEntity::toR16Entry)
    }

    override suspend fun recordAttempt(outboxId: String, attemptedAtEpochMs: Long): Boolean {
        require(outboxId.isNotBlank()) { "Last.fm outbox ID must not be blank" }
        require(attemptedAtEpochMs >= 0) { "Last.fm outbox attempt timestamp cannot be negative" }
        return database.lastFmOutboxDao().recordAttempt(outboxId, attemptedAtEpochMs) == 1
    }

    override suspend fun deleteAccepted(outboxIds: Set<String>): Int {
        if (outboxIds.isEmpty()) return 0
        require(outboxIds.size <= R16LastFmOutboxRepository.MAX_BATCH_SIZE) {
            "Last.fm outbox deletion batch exceeds ${R16LastFmOutboxRepository.MAX_BATCH_SIZE}"
        }
        require(outboxIds.none(String::isBlank)) { "Last.fm outbox IDs must not be blank" }
        return database.lastFmOutboxDao().deleteAccepted(outboxIds)
    }

    override suspend fun count(): Int = database.lastFmOutboxDao().count()
}

private fun LastFmScrobbleOutboxEntity.toR16Entry() =
    R16LastFmOutboxEntry(
        outboxId = outboxId,
        accountId = accountId,
        listeningSessionId = listeningSessionId,
        recordingId = recordingId,
        artist = artist,
        track = track,
        album = album,
        durationSeconds = durationSeconds,
        startedAtEpochSeconds = startedAtEpochSeconds,
        chosenByUser = chosenByUser,
        queuedAtEpochMs = queuedAtEpochMs,
        attemptCount = attemptCount,
        lastAttemptAtEpochMs = lastAttemptAtEpochMs,
    )
