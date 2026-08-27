/*
 * Copyright (c) 2026 Auxio Project
 * R16ListeningSessionRepository.kt is part of Auxio.
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
package app.shippy.data.listening

import androidx.room.withTransaction
import app.shippy.core.identity.ListeningSessionId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.listening.ListeningThresholdPolicy
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.CanonicalFieldProvenanceEntity
import app.shippy.data.db.entity.LastFmScrobbleOutboxEntity
import app.shippy.data.db.entity.PlayHistoryEntity
import java.time.Instant
import java.util.Locale

/** Durable input produced when a playback listening session is closed or checkpointed. */
data class R16ListeningSessionRecord(
    val sessionId: ListeningSessionId,
    val recordingId: RecordingId,
    val queueEntryId: QueueEntryId,
    val sourceReferenceId: SourceReferenceId?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val activeListenedMs: Long,
    val lastPositionMs: Long,
    val completionKind: String,
    val chosenByUser: Boolean,
    val scrobbleAuthorized: Boolean = true,
    val accountId: String? = null,
) {
    init {
        require(activeListenedMs >= 0) { "Active listening time cannot be negative" }
        require(lastPositionMs >= 0) { "Last position cannot be negative" }
        require(endedAt == null || !endedAt.isBefore(startedAt)) {
            "Listening session cannot end before it starts"
        }
        require(completionKind.isNotBlank()) { "Completion kind must not be blank" }
    }
}

data class R16ListeningSessionPersistResult(val scrobbleQueued: Boolean)

/**
 * Persists listening history and, when eligible, prepares a durable Last.fm outbox item.
 *
 * Scrobble rows are created only when canonical metadata is available and listening thresholds are
 * met. The Last.fm outbox is strictly downstream of finalized local listening history.
 */
interface R16ListeningSessionRepository {
    suspend fun persist(session: R16ListeningSessionRecord): R16ListeningSessionPersistResult
}

internal class RoomR16ListeningSessionRepository(
    private val database: ShippyR16Database,
    private val now: () -> Instant = Instant::now,
) : R16ListeningSessionRepository {
    override suspend fun persist(
        session: R16ListeningSessionRecord
    ): R16ListeningSessionPersistResult =
        database.withTransaction {
            val previousDisposition =
                database.historyDao().session(session.sessionId.value)?.scrobbleDisposition
            val recording = database.recordingDao().get(session.recordingId.value)
            if (recording == null) return@withTransaction R16ListeningSessionPersistResult(false)

            val songView = database.readModelDao().librarySong(session.recordingId.value)
            val isFilenameOnly =
                database
                    .legacyImportDao()
                    .provenance(session.recordingId.value)
                    .hasFilenameOnlyIdentity()
            val title =
                (songView?.title ?: recording.canonicalTitle).trim().takeIf(String::isNotEmpty)
            val artist = (songView?.artistDisplay ?: "").trim().takeIf(String::isNotEmpty)
            val durationMs = songView?.durationMs ?: recording.durationMs
            val releaseTitle = songView?.releaseTitle?.trim()?.takeIf(String::isNotEmpty)

            val threshold =
                durationMs?.let {
                    runCatching { ListeningThresholdPolicy.scrobbleThresholdMs(it) }.getOrNull()
                }
            val disposition =
                when {
                    session.endedAt == null ->
                        app.shippy.data.db.entity.ScrobbleDisposition.ACTIVE_CHECKPOINT
                    title == null || artist == null || isFilenameOnly ->
                        app.shippy.data.db.entity.ScrobbleDisposition.METADATA_UNIDENTIFIED
                    !session.scrobbleAuthorized ->
                        app.shippy.data.db.entity.ScrobbleDisposition.NOT_AUTHORIZED
                    durationMs == null || threshold == null ->
                        app.shippy.data.db.entity.ScrobbleDisposition.INELIGIBLE_DURATION
                    session.activeListenedMs < threshold ->
                        app.shippy.data.db.entity.ScrobbleDisposition.INELIGIBLE_ACTIVE_TIME
                    else -> app.shippy.data.db.entity.ScrobbleDisposition.ENQUEUED
                }

            database
                .historyDao()
                .save(
                    session.toEntity(
                        snapshotTitle = title ?: recording.canonicalTitle,
                        snapshotArtistDisplay = artist,
                        snapshotArtworkLocation = songView?.artworkLocation,
                        scrobbleDisposition = disposition.name,
                    )
                )

            if (
                disposition != app.shippy.data.db.entity.ScrobbleDisposition.ENQUEUED ||
                    title == null ||
                    artist == null ||
                    durationMs == null
            ) {
                return@withTransaction R16ListeningSessionPersistResult(false)
            }
            if (
                previousDisposition == app.shippy.data.db.entity.ScrobbleDisposition.ENQUEUED.name
            ) {
                return@withTransaction R16ListeningSessionPersistResult(false)
            }

            val queuedAtEpochMs = now().toEpochMilli()
            require(queuedAtEpochMs >= 0) { "Outbox timestamp cannot be negative" }
            val inserted =
                database
                    .lastFmOutboxDao()
                    .enqueue(
                        LastFmScrobbleOutboxEntity(
                            // One deterministic outbox identity per listening session makes retries
                            // idempotent even before the unique-session index is consulted.
                            outboxId = session.sessionId.value,
                            accountId = session.accountId ?: "",
                            listeningSessionId = session.sessionId.value,
                            recordingId = session.recordingId.value,
                            artist = artist,
                            track = title,
                            album = releaseTitle,
                            durationSeconds = durationMs / 1_000L,
                            startedAtEpochSeconds = session.startedAt.epochSecond,
                            chosenByUser = session.chosenByUser,
                            queuedAtEpochMs = queuedAtEpochMs,
                            attemptCount = 0,
                            lastAttemptAtEpochMs = null,
                        )
                    )
            R16ListeningSessionPersistResult(scrobbleQueued = inserted != -1L)
        }
}

private fun R16ListeningSessionRecord.toEntity(
    snapshotTitle: String? = null,
    snapshotArtistDisplay: String? = null,
    snapshotArtworkLocation: String? = null,
    scrobbleDisposition: String = app.shippy.data.db.entity.ScrobbleDisposition.LEGACY_UNKNOWN.name,
) =
    PlayHistoryEntity(
        listeningSessionId = sessionId.value,
        recordingId = recordingId.value,
        queueEntryId = queueEntryId.value,
        sourceReferenceId = sourceReferenceId?.value,
        startedAtEpochMs = startedAt.toEpochMilli(),
        endedAtEpochMs = endedAt?.toEpochMilli(),
        activeListenedMs = activeListenedMs,
        lastPositionMs = lastPositionMs,
        completionKind = completionKind,
        chosenByUser = chosenByUser,
        snapshotTitle = snapshotTitle,
        snapshotArtistDisplay = snapshotArtistDisplay,
        snapshotArtworkLocation = snapshotArtworkLocation,
        scrobbleDisposition = scrobbleDisposition,
    )

private fun List<CanonicalFieldProvenanceEntity>.hasFilenameOnlyIdentity(): Boolean =
    any { provenance ->
        val field = provenance.fieldName.trim().uppercase(Locale.ROOT)
        val identityField =
            field == "TITLE" || field == "ARTIST" || field == "ARTISTS" || field == "ARTIST_CREDIT"
        identityField &&
            provenance.selectedSourceType.trim().uppercase(Locale.ROOT).startsWith("FILENAME")
    }
