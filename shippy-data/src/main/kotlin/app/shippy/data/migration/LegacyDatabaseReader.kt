/*
 * Copyright (c) 2026 Auxio Project
 * LegacyDatabaseReader.kt is part of Auxio.
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
package app.shippy.data.migration

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

internal data class LegacySchemaSnapshot(
    val version: Int,
    val presentTables: Set<String>,
    val missingRequiredTables: Set<String>,
    val rowCounts: Map<String, Long>,
) {
    val compatible: Boolean
        get() = version == LEGACY_SCHEMA_VERSION && missingRequiredTables.isEmpty()
}

internal data class LegacyCanonicalTrackRow(
    val trackId: String,
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

internal data class LegacyCanonicalCandidateRow(
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

internal data class LegacyLibraryRelationshipRow(
    val trackId: String,
    val liked: Boolean,
    val downloaded: Boolean,
)

internal data class LegacyUserPlaylistRow(
    val playlistId: String,
    val name: String,
    val pinned: Boolean,
    val position: Int,
    val artworkUri: String?,
    val orderOrdinal: Long,
)

internal data class LegacyPlaylistMembershipRow(
    val trackId: String,
    val playlistId: String,
    val position: Int,
    val orderOrdinal: Long,
)

internal data class LegacyDownloadJobRow(
    val jobId: String,
    val trackId: String,
    val requestedCandidateId: String,
    val trackRealm: String,
    val title: String,
    val artists: String,
    val album: String?,
    val durationMs: Long?,
    val state: String,
    val bytesTransferred: Long,
    val expectedBytes: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val artifactUri: String?,
    val artifactLength: Long?,
    val artifactMimeType: String?,
    val artifactVerifiedAtEpochMs: Long?,
    val pendingUri: String?,
    val pendingDisplayName: String?,
    val pendingMimeType: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val requestedKind: String?,
    val requestedSourceId: String?,
    val requestedSourceItemId: String?,
    val requestedProviderId: String?,
)

internal data class LegacyLyricsRow(
    val trackId: String,
    val fingerprint: String,
    val titleKey: String,
    val artistsKey: String,
    val albumKey: String,
    val durationSeconds: Long,
    val sourceId: String,
    val recordId: Long,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val cachedAtEpochMs: Long,
)

internal data class LegacyLastFmOutboxRow(
    val id: String,
    val artist: String,
    val track: String,
    val album: String?,
    val durationSeconds: Int?,
    val startedAtEpochSeconds: Long,
    val queuedAtEpochMs: Long,
    val trackId: String?,
)

internal data class LegacyPlaybackCheckpointItemRow(
    val heapPosition: Int,
    val queueItemId: String,
    val trackId: String,
    val contextId: String?,
    val contributorId: String?,
)

internal data class LegacyPlaybackCheckpointRow(
    val slot: String,
    val positionMs: Long,
    val repeatMode: String,
    val heapIndex: Int,
    val shuffledMapping: String,
    val items: List<LegacyPlaybackCheckpointItemRow>,
)

internal data class LegacySavedProviderEntityRow(
    val providerId: String,
    val entityType: String,
    val sourceItemId: String,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    val originalUrl: String?,
    val pinned: Boolean,
    val savedAtEpochMs: Long,
)

internal data class LegacyCrewCheckpointRow(
    val slot: String,
    val sessionId: String,
    val protocolVersion: Int,
    val coordinatorTerm: Long,
    val eventSequence: Long,
    val payloadLengthBytes: Int,
    val payloadChecksumValid: Boolean,
    val updatedAtEpochMs: Long,
)

internal class LegacyDatabaseReader private constructor(private val database: SQLiteDatabase) :
    Closeable {
    init {
        check(database.isReadOnly) { "Legacy database must be opened read-only" }
    }

    fun schemaSnapshot(): LegacySchemaSnapshot {
        val present =
            database
                .rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                    emptyArray(),
                )
                .useRows { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
        val requiredPresent = REQUIRED_TABLES.intersect(present)
        val counts = requiredPresent.associateWith(::countRows)
        return LegacySchemaSnapshot(
            version = database.version,
            presentTables = present,
            missingRequiredTables = REQUIRED_TABLES - present,
            rowCounts = counts,
        )
    }

    fun canonicalTracks(afterTrackId: String?, limit: Int): List<LegacyCanonicalTrackRow> {
        require(limit in 1..MAX_PAGE_SIZE) {
            "Legacy page size must be between 1 and $MAX_PAGE_SIZE"
        }
        val selection = if (afterTrackId == null) "" else "WHERE trackId > ?"
        val arguments: Array<String> =
            if (afterTrackId == null) emptyArray() else arrayOf(afterTrackId)
        return database
            .rawQuery(
                """
                SELECT trackId, realm, title, artists, album, durationMs, versionLabel,
                       explicit, live, remix, artwork
                FROM canonical_track
                $selection
                ORDER BY trackId
                LIMIT $limit
                """
                    .trimIndent(),
                arguments,
            )
            .useRows { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyCanonicalTrackRow(
                                trackId = cursor.getString(0),
                                realm = cursor.getString(1),
                                title = cursor.getString(2),
                                artists = cursor.getString(3),
                                album = cursor.stringOrNull(4),
                                durationMs = cursor.longOrNull(5),
                                versionLabel = cursor.stringOrNull(6),
                                explicit = cursor.booleanOrNull(7),
                                live = cursor.getInt(8) != 0,
                                remix = cursor.getInt(9) != 0,
                                artwork = cursor.stringOrNull(10),
                            )
                        )
                    }
                }
            }
    }

    fun canonicalCandidates(
        afterTrackId: String?,
        afterCandidateId: String?,
        limit: Int,
    ): List<LegacyCanonicalCandidateRow> {
        require(limit in 1..MAX_PAGE_SIZE) {
            "Legacy page size must be between 1 and $MAX_PAGE_SIZE"
        }
        require((afterTrackId == null) == (afterCandidateId == null)) {
            "Legacy candidate checkpoint must contain both key parts"
        }
        val selection =
            if (afterTrackId == null) {
                ""
            } else {
                "WHERE trackId > ? OR (trackId = ? AND candidateId > ?)"
            }
        val arguments: Array<String> =
            if (afterTrackId == null) {
                emptyArray()
            } else {
                arrayOf(afterTrackId, afterTrackId, checkNotNull(afterCandidateId))
            }
        return database
            .rawQuery(
                """
                SELECT trackId, candidateId, position, kind, sourceId, sourceItemId,
                       availability, locator, providerId, mimeType, container,
                       bitrateBps, contentLength
                FROM canonical_track_candidate
                $selection
                ORDER BY trackId, candidateId
                LIMIT $limit
                """
                    .trimIndent(),
                arguments,
            )
            .useRows { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyCanonicalCandidateRow(
                                trackId = cursor.getString(0),
                                candidateId = cursor.getString(1),
                                position = cursor.getInt(2),
                                kind = cursor.getString(3),
                                sourceId = cursor.getString(4),
                                sourceItemId = cursor.getString(5),
                                availability = cursor.getString(6),
                                locator = cursor.stringOrNull(7),
                                providerId = cursor.stringOrNull(8),
                                mimeType = cursor.stringOrNull(9),
                                container = cursor.stringOrNull(10),
                                bitrateBps = cursor.intOrNull(11),
                                contentLength = cursor.longOrNull(12),
                            )
                        )
                    }
                }
            }
    }

    fun libraryRelationships(
        afterTrackId: String?,
        limit: Int,
    ): List<LegacyLibraryRelationshipRow> {
        requirePageSize(limit)
        val selection = if (afterTrackId == null) "" else "WHERE trackId > ?"
        val arguments = afterTrackId?.let { arrayOf(it) } ?: emptyArray()
        return database
            .rawQuery(
                """
                SELECT trackId, liked, downloaded
                FROM library_relationship
                $selection
                ORDER BY trackId
                LIMIT $limit
                """
                    .trimIndent(),
                arguments,
            )
            .useRows { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyLibraryRelationshipRow(
                                trackId = cursor.getString(0),
                                liked = cursor.getInt(1) != 0,
                                downloaded = cursor.getInt(2) != 0,
                            )
                        )
                    }
                }
            }
    }

    fun userPlaylists(
        afterPosition: Int?,
        afterPlaylistId: String?,
        limit: Int,
    ): List<LegacyUserPlaylistRow> {
        requirePageSize(limit)
        require((afterPosition == null) == (afterPlaylistId == null)) {
            "Legacy playlist checkpoint must contain both key parts"
        }
        val selection =
            if (afterPosition == null) {
                ""
            } else {
                "WHERE playlist.position > ? OR (playlist.position = ? AND playlist.playlistId > ?)"
            }
        val arguments =
            if (afterPosition == null) {
                emptyArray()
            } else {
                arrayOf(
                    afterPosition.toString(),
                    afterPosition.toString(),
                    checkNotNull(afterPlaylistId),
                )
            }
        return database
            .rawQuery(
                """
                SELECT playlist.playlistId, playlist.name, playlist.pinned, playlist.position,
                       playlist.artworkUri,
                       (
                           SELECT COUNT(*) FROM user_playlist AS preceding
                           WHERE preceding.position < playlist.position
                              OR (preceding.position = playlist.position
                                  AND preceding.playlistId < playlist.playlistId)
                       ) AS orderOrdinal
                FROM user_playlist AS playlist
                $selection
                ORDER BY playlist.position, playlist.playlistId
                LIMIT $limit
                """
                    .trimIndent(),
                arguments,
            )
            .useRows { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyUserPlaylistRow(
                                playlistId = cursor.getString(0),
                                name = cursor.getString(1),
                                pinned = cursor.getInt(2) != 0,
                                position = cursor.getInt(3),
                                artworkUri = cursor.stringOrNull(4),
                                orderOrdinal = cursor.getLong(5),
                            )
                        )
                    }
                }
            }
    }

    fun playlistMemberships(
        afterPlaylistId: String?,
        afterPosition: Int?,
        afterTrackId: String?,
        limit: Int,
    ): List<LegacyPlaylistMembershipRow> {
        requirePageSize(limit)
        val checkpointParts = listOf(afterPlaylistId, afterPosition, afterTrackId)
        require(checkpointParts.all { it == null } || checkpointParts.all { it != null }) {
            "Legacy playlist-membership checkpoint must contain all key parts"
        }
        val selection =
            if (afterPlaylistId == null) {
                ""
            } else {
                """
                WHERE membership.playlistId > ?
                   OR (membership.playlistId = ? AND membership.position > ?)
                   OR (membership.playlistId = ? AND membership.position = ?
                       AND membership.trackId > ?)
                """
                    .trimIndent()
            }
        val arguments =
            if (afterPlaylistId == null) {
                emptyArray()
            } else {
                arrayOf(
                    afterPlaylistId,
                    afterPlaylistId,
                    checkNotNull(afterPosition).toString(),
                    afterPlaylistId,
                    afterPosition.toString(),
                    checkNotNull(afterTrackId),
                )
            }
        return database
            .rawQuery(
                """
                SELECT membership.trackId, membership.playlistId, membership.position,
                       (
                           SELECT COUNT(*) FROM playlist_membership AS preceding
                           WHERE preceding.playlistId = membership.playlistId
                             AND (preceding.position < membership.position
                                  OR (preceding.position = membership.position
                                      AND preceding.trackId < membership.trackId))
                       ) AS orderOrdinal
                FROM playlist_membership AS membership
                $selection
                ORDER BY membership.playlistId, membership.position, membership.trackId
                LIMIT $limit
                """
                    .trimIndent(),
                arguments,
            )
            .useRows { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyPlaylistMembershipRow(
                                trackId = cursor.getString(0),
                                playlistId = cursor.getString(1),
                                position = cursor.getInt(2),
                                orderOrdinal = cursor.getLong(3),
                            )
                        )
                    }
                }
            }
    }

    fun downloadJobs(afterJobId: String?, limit: Int): List<LegacyDownloadJobRow> {
        requirePageSize(limit)
        val selection = if (afterJobId == null) "" else "WHERE job.jobId > ?"
        val arguments = afterJobId?.let { arrayOf(it) } ?: emptyArray()
        return database
            .rawQuery(
                """
                SELECT job.jobId, job.trackId, job.requestedCandidateId, job.trackRealm,
                       job.title, job.artists, job.album, job.durationMs, job.state,
                       job.bytesTransferred, job.expectedBytes, job.failureCode,
                       job.failureMessage, job.artifactUri, job.artifactLength,
                       job.artifactMimeType, job.artifactVerifiedAtEpochMs, job.pendingUri,
                       job.pendingDisplayName, job.pendingMimeType, job.createdAtEpochMs,
                       job.updatedAtEpochMs, candidate.kind, candidate.sourceId,
                       candidate.sourceItemId, candidate.providerId
                FROM download_job AS job
                LEFT JOIN download_candidate AS candidate
                  ON candidate.jobId = job.jobId
                 AND candidate.candidateId = job.requestedCandidateId
                $selection
                ORDER BY job.jobId
                LIMIT $limit
                """
                    .trimIndent(),
                arguments,
            )
            .useRows { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyDownloadJobRow(
                                jobId = cursor.getString(0),
                                trackId = cursor.getString(1),
                                requestedCandidateId = cursor.getString(2),
                                trackRealm = cursor.getString(3),
                                title = cursor.getString(4),
                                artists = cursor.getString(5),
                                album = cursor.stringOrNull(6),
                                durationMs = cursor.longOrNull(7),
                                state = cursor.getString(8),
                                bytesTransferred = cursor.getLong(9),
                                expectedBytes = cursor.longOrNull(10),
                                failureCode = cursor.stringOrNull(11),
                                failureMessage = cursor.stringOrNull(12),
                                artifactUri = cursor.stringOrNull(13),
                                artifactLength = cursor.longOrNull(14),
                                artifactMimeType = cursor.stringOrNull(15),
                                artifactVerifiedAtEpochMs = cursor.longOrNull(16),
                                pendingUri = cursor.stringOrNull(17),
                                pendingDisplayName = cursor.stringOrNull(18),
                                pendingMimeType = cursor.stringOrNull(19),
                                createdAtEpochMs = cursor.getLong(20),
                                updatedAtEpochMs = cursor.getLong(21),
                                requestedKind = cursor.stringOrNull(22),
                                requestedSourceId = cursor.stringOrNull(23),
                                requestedSourceItemId = cursor.stringOrNull(24),
                                requestedProviderId = cursor.stringOrNull(25),
                            )
                        )
                    }
                }
            }
    }

    fun lyrics(
        afterTrackId: String?,
        afterFingerprint: String?,
        limit: Int,
    ): List<LegacyLyricsRow> {
        requirePageSize(limit)
        require((afterTrackId == null) == (afterFingerprint == null)) {
            "Legacy lyrics checkpoint must contain both key parts"
        }
        val selection =
            if (afterTrackId == null) {
                ""
            } else {
                "WHERE trackId > ? OR (trackId = ? AND fingerprint > ?)"
            }
        val arguments =
            if (afterTrackId == null) {
                emptyArray()
            } else {
                arrayOf(afterTrackId, afterTrackId, checkNotNull(afterFingerprint))
            }
        return database
            .rawQuery(
                """
                SELECT trackId, fingerprint, titleKey, artistsKey, albumKey, durationSeconds,
                       sourceId, recordId, instrumental, plainLyrics, syncedLyrics, cachedAtEpochMs
                FROM lyrics_cache
                $selection
                ORDER BY trackId, fingerprint
                LIMIT $limit
                """
                    .trimIndent(),
                arguments,
            )
            .useRows { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyLyricsRow(
                                trackId = cursor.getString(0),
                                fingerprint = cursor.getString(1),
                                titleKey = cursor.getString(2),
                                artistsKey = cursor.getString(3),
                                albumKey = cursor.getString(4),
                                durationSeconds = cursor.getLong(5),
                                sourceId = cursor.getString(6),
                                recordId = cursor.getLong(7),
                                instrumental = cursor.getInt(8) != 0,
                                plainLyrics = cursor.stringOrNull(9),
                                syncedLyrics = cursor.stringOrNull(10),
                                cachedAtEpochMs = cursor.getLong(11),
                            )
                        )
                    }
                }
            }
    }

    fun lastFmOutbox(
        afterQueuedAtEpochMs: Long?,
        afterId: String?,
        limit: Int,
    ): List<LegacyLastFmOutboxRow> {
        requirePageSize(limit)
        require((afterQueuedAtEpochMs == null) == (afterId == null)) {
            "Legacy Last.fm checkpoint must contain both key parts"
        }
        val selection =
            if (afterQueuedAtEpochMs == null) {
                ""
            } else {
                "WHERE outbox.queuedAtEpochMs > ? OR (outbox.queuedAtEpochMs = ? AND outbox.id > ?)"
            }
        val arguments =
            if (afterQueuedAtEpochMs == null) {
                emptyArray()
            } else {
                arrayOf(
                    afterQueuedAtEpochMs.toString(),
                    afterQueuedAtEpochMs.toString(),
                    checkNotNull(afterId),
                )
            }
        return database
            .rawQuery(
                """
                SELECT outbox.id, outbox.artist, outbox.track, outbox.album,
                       outbox.durationSeconds, outbox.startedAtEpochSeconds,
                       outbox.queuedAtEpochMs,
                       (
                           SELECT item.trackId FROM playback_checkpoint_item AS item
                           WHERE 'queue-item:' || item.queueItemId = outbox.id
                           ORDER BY item.slot, item.heapPosition
                           LIMIT 1
                       ) AS trackId
                FROM lastfm_scrobble_outbox AS outbox
                $selection
                ORDER BY outbox.queuedAtEpochMs, outbox.id
                LIMIT $limit
                """
                    .trimIndent(),
                arguments,
            )
            .useRows { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyLastFmOutboxRow(
                                id = cursor.getString(0),
                                artist = cursor.getString(1),
                                track = cursor.getString(2),
                                album = cursor.stringOrNull(3),
                                durationSeconds = cursor.intOrNull(4),
                                startedAtEpochSeconds = cursor.getLong(5),
                                queuedAtEpochMs = cursor.getLong(6),
                                trackId = cursor.stringOrNull(7),
                            )
                        )
                    }
                }
            }
    }

    fun playbackCheckpoint(slot: String): LegacyPlaybackCheckpointRow? {
        require(slot.isNotBlank()) { "Legacy playback checkpoint slot must not be blank" }
        val header =
            database
                .rawQuery(
                    """
                    SELECT slot, positionMs, repeatMode, heapIndex, shuffledMapping
                    FROM playback_checkpoint
                    WHERE slot = ?
                    """
                        .trimIndent(),
                    arrayOf(slot),
                )
                .useRows { cursor ->
                    if (!cursor.moveToFirst()) {
                        null
                    } else {
                        LegacyPlaybackCheckpointRow(
                            slot = cursor.getString(0),
                            positionMs = cursor.getLong(1),
                            repeatMode = cursor.getString(2),
                            heapIndex = cursor.getInt(3),
                            shuffledMapping = cursor.getString(4),
                            items = emptyList(),
                        )
                    }
                } ?: return null
        val items =
            database
                .rawQuery(
                    """
                    SELECT heapPosition, queueItemId, trackId, contextId, contributorId
                    FROM playback_checkpoint_item
                    WHERE slot = ?
                    ORDER BY heapPosition, queueItemId
                    LIMIT ${MAX_PLAYBACK_CHECKPOINT_ITEMS + 1}
                    """
                        .trimIndent(),
                    arrayOf(slot),
                )
                .useRows { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                LegacyPlaybackCheckpointItemRow(
                                    heapPosition = cursor.getInt(0),
                                    queueItemId = cursor.getString(1),
                                    trackId = cursor.getString(2),
                                    contextId = cursor.stringOrNull(3),
                                    contributorId = cursor.stringOrNull(4),
                                )
                            )
                        }
                    }
                }
        check(items.size <= MAX_PLAYBACK_CHECKPOINT_ITEMS) {
            "Legacy playback checkpoint exceeds $MAX_PLAYBACK_CHECKPOINT_ITEMS entries"
        }
        return header.copy(items = items)
    }

    fun crewActiveCheckpoint(): LegacyCrewCheckpointRow? {
        val header =
            database
                .rawQuery(
                    """
                    SELECT slot, sessionId, protocolVersion, coordinatorTerm, eventSequence,
                           length(snapshotPayload), payloadSha256, updatedAtEpochMs
                    FROM crew_active_checkpoint
                    WHERE slot = 'active'
                    LIMIT 1
                    """
                        .trimIndent(),
                    emptyArray(),
                )
                .useRows { cursor ->
                    if (!cursor.moveToFirst()) {
                        null
                    } else {
                        LegacyCrewCheckpointHeader(
                            slot = cursor.getString(0),
                            sessionId = cursor.getString(1),
                            protocolVersion = cursor.getInt(2),
                            coordinatorTerm = cursor.getLong(3),
                            eventSequence = cursor.getLong(4),
                            payloadLengthBytes = cursor.getInt(5),
                            payloadSha256 = cursor.getBlob(6),
                            updatedAtEpochMs = cursor.getLong(7),
                        )
                    }
                } ?: return null
        val payload =
            if (header.payloadLengthBytes in 1..MAX_CREW_CHECKPOINT_PAYLOAD_BYTES) {
                database
                    .rawQuery(
                        """
                        SELECT snapshotPayload FROM crew_active_checkpoint
                        WHERE slot = 'active' AND sessionId = ? AND updatedAtEpochMs = ?
                        LIMIT 1
                        """
                            .trimIndent(),
                        arrayOf(header.sessionId, header.updatedAtEpochMs.toString()),
                    )
                    .useRows { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
            } else {
                null
            }
        return LegacyCrewCheckpointRow(
            slot = header.slot,
            sessionId = header.sessionId,
            protocolVersion = header.protocolVersion,
            coordinatorTerm = header.coordinatorTerm,
            eventSequence = header.eventSequence,
            payloadLengthBytes = header.payloadLengthBytes,
            payloadChecksumValid =
                payload != null &&
                    header.payloadSha256.size == SHA256_BYTES &&
                    MessageDigest.isEqual(
                        MessageDigest.getInstance("SHA-256").digest(payload),
                        header.payloadSha256,
                    ),
            updatedAtEpochMs = header.updatedAtEpochMs,
        )
    }

    fun savedProviderEntities(
        afterProviderId: String?,
        afterEntityType: String?,
        afterSourceItemId: String?,
        limit: Int,
    ): List<LegacySavedProviderEntityRow> {
        requirePageSize(limit)
        require(
            listOf(afterProviderId, afterEntityType, afterSourceItemId).all { it == null } ||
                listOf(afterProviderId, afterEntityType, afterSourceItemId).all { it != null }
        ) {
            "Legacy saved-provider checkpoint must contain all three key parts"
        }
        val selection =
            if (afterProviderId == null) {
                ""
            } else {
                """
                WHERE providerId > ?
                   OR (providerId = ? AND entityType > ?)
                   OR (providerId = ? AND entityType = ? AND sourceItemId > ?)
                """
                    .trimIndent()
            }
        val arguments =
            if (afterProviderId == null) {
                emptyArray()
            } else {
                arrayOf(
                    afterProviderId,
                    afterProviderId,
                    checkNotNull(afterEntityType),
                    afterProviderId,
                    afterEntityType,
                    checkNotNull(afterSourceItemId),
                )
            }
        return database
            .rawQuery(
                """
                SELECT providerId, entityType, sourceItemId, title, subtitle, artwork,
                       originalUrl, pinned, savedAtEpochMs
                FROM saved_provider_entity
                $selection
                ORDER BY providerId, entityType, sourceItemId
                LIMIT $limit
                """
                    .trimIndent(),
                arguments,
            )
            .useRows { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LegacySavedProviderEntityRow(
                                providerId = cursor.getString(0),
                                entityType = cursor.getString(1),
                                sourceItemId = cursor.getString(2),
                                title = cursor.getString(3),
                                subtitle = cursor.stringOrNull(4),
                                artwork = cursor.stringOrNull(5),
                                originalUrl = cursor.stringOrNull(6),
                                pinned = cursor.getInt(7) != 0,
                                savedAtEpochMs = cursor.getLong(8),
                            )
                        )
                    }
                }
            }
    }

    override fun close() {
        database.close()
    }

    private fun countRows(table: String): Long {
        require(table in REQUIRED_TABLES) { "Legacy table is not allow-listed" }
        return database.rawQuery("SELECT COUNT(*) FROM `$table`", emptyArray()).useRows { cursor ->
            check(cursor.moveToFirst()) { "Legacy count query returned no row" }
            cursor.getLong(0)
        }
    }

    private fun requirePageSize(limit: Int) {
        require(limit in 1..MAX_PAGE_SIZE) {
            "Legacy page size must be between 1 and $MAX_PAGE_SIZE"
        }
    }

    companion object {
        fun openReadOnly(file: File): LegacyDatabaseReader {
            require(file.isFile) { "Legacy database file is missing" }
            val database =
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
            return LegacyDatabaseReader(database)
        }
    }
}

private inline fun <Result> Cursor.useRows(block: (Cursor) -> Result): Result = use(block)

private data class LegacyCrewCheckpointHeader(
    val slot: String,
    val sessionId: String,
    val protocolVersion: Int,
    val coordinatorTerm: Long,
    val eventSequence: Long,
    val payloadLengthBytes: Int,
    val payloadSha256: ByteArray,
    val updatedAtEpochMs: Long,
)

private fun Cursor.stringOrNull(column: Int): String? =
    if (isNull(column)) null else getString(column)

private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

private fun Cursor.intOrNull(column: Int): Int? = if (isNull(column)) null else getInt(column)

private fun Cursor.booleanOrNull(column: Int): Boolean? =
    if (isNull(column)) null else getInt(column) != 0

internal const val LEGACY_SCHEMA_VERSION = 10
private const val MAX_PAGE_SIZE = 500
private const val MAX_PLAYBACK_CHECKPOINT_ITEMS = 10_000
private const val MAX_CREW_CHECKPOINT_PAYLOAD_BYTES = 4 * 1024 * 1024
private const val SHA256_BYTES = 32
private val REQUIRED_TABLES =
    setOf(
        "library_relationship",
        "user_playlist",
        "playlist_membership",
        "download_job",
        "download_candidate",
        "lyrics_cache",
        "crew_active_checkpoint",
        "canonical_track",
        "canonical_track_candidate",
        "lastfm_scrobble_outbox",
        "playback_checkpoint",
        "playback_checkpoint_item",
        "saved_provider_entity",
    )
