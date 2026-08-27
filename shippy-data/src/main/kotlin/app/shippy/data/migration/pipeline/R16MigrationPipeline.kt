/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationPipeline.kt is part of Auxio.
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
package app.shippy.data.migration.pipeline

import androidx.room.withTransaction
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.migration.LegacyAssetVerifier
import app.shippy.data.migration.LegacyCandidateImporter
import app.shippy.data.migration.LegacyCanonicalTrackImporter
import app.shippy.data.migration.LegacyCrewCheckpointDispositionRecorder
import app.shippy.data.migration.LegacyDatabaseReader
import app.shippy.data.migration.LegacyDownloadArtifactVerifier
import app.shippy.data.migration.LegacyDownloadImporter
import app.shippy.data.migration.LegacyImportPhase
import app.shippy.data.migration.LegacyLastFmOutboxImporter
import app.shippy.data.migration.LegacyLibraryRelationshipImporter
import app.shippy.data.migration.LegacyLyricsImporter
import app.shippy.data.migration.LegacyPlaybackCheckpointImporter
import app.shippy.data.migration.LegacyPlaylistImporter
import app.shippy.data.migration.LegacySavedSourceImporter
import app.shippy.data.migration.MigrationExpectedCountEvidence
import app.shippy.data.migration.orchestration.R16MigrationPhaseContext
import app.shippy.data.migration.orchestration.R16MigrationPhaseHandler
import app.shippy.data.migration.orchestration.R16MigrationPhaseResult
import app.shippy.data.migration.requireActiveLegacyAudit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Result returned by an app-owned Musikr migration callback after one bounded page. The pipeline
 * owns the durable phase-audit checkpoint; a successful callback return is the callback evidence.
 */
public data class R16MigrationCallbackPageResult(
    val complete: Boolean,
    val lastStableKey: String?,
) {
    init {
        if (complete) {
            require(lastStableKey == null) {
                "A complete Musikr migration page cannot carry a checkpoint key"
            }
        } else {
            require(!lastStableKey.isNullOrBlank()) {
                "An incomplete Musikr migration page requires a stable checkpoint key"
            }
        }
    }
}

/** Request for one deterministic device-playlist page. */
public data class R16MusikrDevicePlaylistPageRequest(
    val migrationId: String,
    val lastStableKey: String?,
    val pageSize: Int,
    val importedAtEpochMs: Long,
)

/** Request for one deterministic local-reindex page. */
public data class R16MusikrLocalReindexPageRequest(
    val migrationId: String,
    val lastStableKey: String?,
    val pageSize: Int,
    val importedAtEpochMs: Long,
)

/**
 * App-side bridge for work that requires the active Musikr/local-media integration.
 *
 * The device-playlist callback may perform the small UID prepass needed by M5. Formal local
 * re-indexing remains the later M12 callback; this port never constructs a second media engine.
 */
public interface R16MusikrMigrationBridge {
    suspend fun importDevicePlaylistPage(
        request: R16MusikrDevicePlaylistPageRequest
    ): R16MigrationCallbackPageResult

    suspend fun reindexLocalPage(
        request: R16MusikrLocalReindexPageRequest
    ): R16MigrationCallbackPageResult
}

/** Creates the concrete M1-M12 adapters around the existing migration implementations. */
internal class R16MigrationPipelineFactory(
    private val legacyReader: LegacyDatabaseReader,
    private val database: ShippyR16Database,
    private val assetVerifier: LegacyAssetVerifier,
    private val artifactVerifier: LegacyDownloadArtifactVerifier,
    private val musikrBridge: R16MusikrMigrationBridge,
    private val importedAtEpochMs: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) {
            "Migration page size must be between 1 and $MAX_PAGE_SIZE"
        }
    }

    fun handlers(): List<R16MigrationPhaseHandler> {
        val canonicalImporter = LegacyCanonicalTrackImporter(database)
        val candidateImporter = LegacyCandidateImporter(database, assetVerifier)
        val libraryImporter = LegacyLibraryRelationshipImporter(database)
        val playlistImporter = LegacyPlaylistImporter(database)
        val downloadImporter = LegacyDownloadImporter(database, artifactVerifier)
        val lyricsImporter = LegacyLyricsImporter(database)
        val outboxImporter = LegacyLastFmOutboxImporter(database)
        val checkpointImporter = LegacyPlaybackCheckpointImporter(database)
        val savedSourceImporter = LegacySavedSourceImporter(database)
        val crewRecorder = LegacyCrewCheckpointDispositionRecorder(database)

        return listOf(
            handler(LegacyImportPhase.CANONICAL_TRACKS) { context ->
                val afterTrackId =
                    context.lastStableKey?.let {
                        R16MigrationCheckpointCodec.decodeSingle(it, "M1")
                    }
                val rows = legacyReader.canonicalTracks(afterTrackId, pageSize)
                canonicalImporter.importPage(context.migrationId, rows, importedAt())
                pageResult(
                    context,
                    complete = rows.size < pageSize,
                    nextKey =
                        rows.lastOrNull()?.trackId?.let {
                            R16MigrationCheckpointCodec.encodeSingle("M1", it)
                        },
                )
            },
            handler(LegacyImportPhase.CANDIDATES) { context ->
                val checkpoint =
                    context.lastStableKey?.let { R16MigrationCheckpointCodec.decodePair(it, "M2") }
                val rows =
                    legacyReader.canonicalCandidates(
                        afterTrackId = checkpoint?.first,
                        afterCandidateId = checkpoint?.second,
                        limit = pageSize,
                    )
                candidateImporter.importPage(context.migrationId, rows, importedAt())
                pageResult(
                    context,
                    complete = rows.size < pageSize,
                    nextKey =
                        rows.lastOrNull()?.let { row ->
                            R16MigrationCheckpointCodec.encodePair(
                                "M2",
                                row.trackId,
                                row.candidateId,
                            )
                        },
                )
            },
            handler(LegacyImportPhase.LIBRARY_RELATIONSHIPS) { context ->
                val afterTrackId =
                    context.lastStableKey?.let {
                        R16MigrationCheckpointCodec.decodeSingle(it, "M3")
                    }
                val rows = legacyReader.libraryRelationships(afterTrackId, pageSize)
                libraryImporter.importPage(context.migrationId, rows, importedAt())
                pageResult(
                    context,
                    complete = rows.size < pageSize,
                    nextKey =
                        rows.lastOrNull()?.trackId?.let {
                            R16MigrationCheckpointCodec.encodeSingle("M3", it)
                        },
                )
            },
            handler(LegacyImportPhase.USER_PLAYLISTS) { context ->
                val cursor =
                    context.lastStableKey?.let(R16MigrationCheckpointCodec::decodePlaylist)
                        ?: R16PlaylistCheckpoint.startPlaylists()
                when (cursor.section) {
                    R16PlaylistCheckpoint.Section.PLAYLISTS -> {
                        val rows =
                            legacyReader.userPlaylists(
                                afterPosition = cursor.position,
                                afterPlaylistId = cursor.playlistId,
                                limit = pageSize,
                            )
                        playlistImporter.importPlaylistPage(context.migrationId, rows, importedAt())
                        if (rows.size < pageSize) {
                            pageResult(
                                context,
                                complete = false,
                                nextKey = R16MigrationCheckpointCodec.encodeMembershipStart(),
                            )
                        } else {
                            pageResult(
                                context,
                                complete = false,
                                nextKey =
                                    R16MigrationCheckpointCodec.encodePlaylistPage(
                                        rows.last().position,
                                        rows.last().playlistId,
                                    ),
                            )
                        }
                    }
                    R16PlaylistCheckpoint.Section.MEMBERSHIPS -> {
                        val rows =
                            legacyReader.playlistMemberships(
                                afterPlaylistId = cursor.playlistId,
                                afterPosition = cursor.position,
                                afterTrackId = cursor.trackId,
                                limit = pageSize,
                            )
                        playlistImporter.importMembershipPage(
                            context.migrationId,
                            rows,
                            importedAt(),
                        )
                        pageResult(
                            context,
                            complete = rows.size < pageSize,
                            nextKey =
                                rows.lastOrNull()?.let { row ->
                                    R16MigrationCheckpointCodec.encodeMembershipPage(
                                        row.playlistId,
                                        row.position,
                                        row.trackId,
                                    )
                                },
                        )
                    }
                }
            },
            handler(LegacyImportPhase.DEVICE_PLAYLISTS) { context ->
                val callback =
                    musikrBridge.importDevicePlaylistPage(
                        R16MusikrDevicePlaylistPageRequest(
                            migrationId = context.migrationId,
                            lastStableKey =
                                context.lastStableKey?.let {
                                    R16MigrationCheckpointCodec.decodeSingle(it, "M5")
                                },
                            pageSize = pageSize,
                            importedAtEpochMs = importedAt(),
                        )
                    )
                callbackResult(context, "M5", callback)
            },
            handler(LegacyImportPhase.DOWNLOADS) { context ->
                val afterJobId =
                    context.lastStableKey?.let {
                        R16MigrationCheckpointCodec.decodeSingle(it, "M6")
                    }
                val rows = legacyReader.downloadJobs(afterJobId, pageSize)
                downloadImporter.importPage(context.migrationId, rows, importedAt())
                pageResult(
                    context,
                    complete = rows.size < pageSize,
                    nextKey =
                        rows.lastOrNull()?.jobId?.let {
                            R16MigrationCheckpointCodec.encodeSingle("M6", it)
                        },
                )
            },
            handler(LegacyImportPhase.LYRICS) { context ->
                val checkpoint =
                    context.lastStableKey?.let { R16MigrationCheckpointCodec.decodePair(it, "M7") }
                val rows =
                    legacyReader.lyrics(
                        afterTrackId = checkpoint?.first,
                        afterFingerprint = checkpoint?.second,
                        limit = pageSize,
                    )
                lyricsImporter.importPage(context.migrationId, rows, importedAt())
                pageResult(
                    context,
                    complete = rows.size < pageSize,
                    nextKey =
                        rows.lastOrNull()?.let { row ->
                            R16MigrationCheckpointCodec.encodePair(
                                "M7",
                                row.trackId,
                                row.fingerprint,
                            )
                        },
                )
            },
            handler(LegacyImportPhase.LASTFM_OUTBOX) { context ->
                val checkpoint =
                    context.lastStableKey?.let {
                        R16MigrationCheckpointCodec.decodeLongAndString(it, "M8")
                    }
                val rows =
                    legacyReader.lastFmOutbox(
                        afterQueuedAtEpochMs = checkpoint?.first,
                        afterId = checkpoint?.second,
                        limit = pageSize,
                    )
                outboxImporter.importPage(context.migrationId, rows, importedAt())
                pageResult(
                    context,
                    complete = rows.size < pageSize,
                    nextKey =
                        rows.lastOrNull()?.let { row ->
                            R16MigrationCheckpointCodec.encodeLongAndString(
                                "M8",
                                row.queuedAtEpochMs,
                                row.id,
                            )
                        },
                )
            },
            handler(LegacyImportPhase.PLAYBACK_CHECKPOINT) { context ->
                check(context.lastStableKey == null) {
                    "M9 playback checkpoint does not support a page cursor"
                }
                checkpointImporter.importCheckpoint(
                    context.migrationId,
                    legacyReader.playbackCheckpoint(ACTIVE_PLAYBACK_SLOT),
                    importedAt(),
                )
                pageResult(context, complete = true, nextKey = null)
            },
            handler(LegacyImportPhase.SAVED_PROVIDER_ENTITIES) { context ->
                val checkpoint =
                    context.lastStableKey?.let {
                        R16MigrationCheckpointCodec.decodeTriple(it, "M10")
                    }
                val rows =
                    legacyReader.savedProviderEntities(
                        afterProviderId = checkpoint?.first,
                        afterEntityType = checkpoint?.second,
                        afterSourceItemId = checkpoint?.third,
                        limit = pageSize,
                    )
                savedSourceImporter.importPage(context.migrationId, rows, importedAt())
                pageResult(
                    context,
                    complete = rows.size < pageSize,
                    nextKey =
                        rows.lastOrNull()?.let { row ->
                            R16MigrationCheckpointCodec.encodeTriple(
                                "M10",
                                row.providerId,
                                row.entityType,
                                row.sourceItemId,
                            )
                        },
                )
            },
            handler(LegacyImportPhase.CREW) { context ->
                check(context.lastStableKey == null) { "M11 Crew disposition is not paged" }
                crewRecorder.record(context.migrationId, legacyReader.crewActiveCheckpoint())
                pageResult(context, complete = true, nextKey = null)
            },
            handler(LegacyImportPhase.LOCAL_REINDEX) { context ->
                val callback =
                    musikrBridge.reindexLocalPage(
                        R16MusikrLocalReindexPageRequest(
                            migrationId = context.migrationId,
                            lastStableKey =
                                context.lastStableKey?.let {
                                    R16MigrationCheckpointCodec.decodeSingle(it, "M12")
                                },
                            pageSize = pageSize,
                            importedAtEpochMs = importedAt(),
                        )
                    )
                callbackResult(context, "M12", callback)
            },
        )
    }

    private fun importedAt(): Long =
        importedAtEpochMs().also { require(it >= 0) { "Migration timestamp cannot be negative" } }

    private fun handler(
        phase: LegacyImportPhase,
        block: suspend (R16MigrationPhaseContext) -> R16MigrationPhaseResult,
    ): R16MigrationPhaseHandler =
        object : R16MigrationPhaseHandler {
            override val phase = phase

            override suspend fun run(context: R16MigrationPhaseContext): R16MigrationPhaseResult =
                block(context)
        }

    private suspend fun pageResult(
        context: R16MigrationPhaseContext,
        complete: Boolean,
        nextKey: String?,
    ): R16MigrationPhaseResult {
        val stableKey = if (complete) null else checkNotNull(nextKey)
        return R16MigrationPhaseResult(
            complete = complete,
            lastStableKey = stableKey,
            auditCommitted = commitPipelineAudit(context, complete, stableKey),
        )
    }

    private suspend fun callbackResult(
        context: R16MigrationPhaseContext,
        kind: String,
        callback: R16MigrationCallbackPageResult,
    ): R16MigrationPhaseResult {
        val stableKey =
            if (callback.complete) {
                null
            } else {
                R16MigrationCheckpointCodec.encodeSingle(kind, checkNotNull(callback.lastStableKey))
            }
        return R16MigrationPhaseResult(
            complete = callback.complete,
            lastStableKey = stableKey,
            auditCommitted = commitPipelineAudit(context, callback.complete, stableKey),
        )
    }

    private suspend fun commitPipelineAudit(
        context: R16MigrationPhaseContext,
        complete: Boolean,
        lastStableKey: String?,
    ): Boolean =
        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, context.migrationId)
            val targetCounts = audit.targetCountsJson?.let(::JSONObject) ?: JSONObject()
            val hasExpectedEvidence =
                targetCounts
                    .optJSONObject(MigrationExpectedCountEvidence.JSON_KEY)
                    ?.optJSONObject("phases")
                    ?.optJSONObject(context.phase.code) != null
            if (
                MigrationExpectedCountEvidence.tracks(context.phase) &&
                    complete &&
                    (context.phase != LegacyImportPhase.DEVICE_PLAYLISTS || hasExpectedEvidence)
            ) {
                MigrationExpectedCountEvidence.markPhaseComplete(
                    target = targetCounts,
                    phase = context.phase,
                    allowEmpty = context.phase != LegacyImportPhase.DEVICE_PLAYLISTS,
                )
            }
            targetCounts.put(
                "pipelineCheckpoint",
                JSONObject()
                    .put("version", CHECKPOINT_VERSION)
                    .put("phase", context.phase.code)
                    .put("complete", complete)
                    .put("lastStableKey", lastStableKey ?: JSONObject.NULL),
            )
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = context.migrationId,
                    targetCountsJson = targetCounts.toString(),
                    warningsJson = audit.warningsJson,
                    status = "IMPORTING",
                )
            true
        }

    internal companion object {
        const val DEFAULT_PAGE_SIZE = 500
        const val MAX_PAGE_SIZE = 500
        const val ACTIVE_PLAYBACK_SLOT = "active"
        const val CHECKPOINT_VERSION = 1
    }
}

internal data class R16PlaylistCheckpoint(
    val section: Section,
    val position: Int?,
    val playlistId: String?,
    val trackId: String?,
) {
    enum class Section {
        PLAYLISTS,
        MEMBERSHIPS,
    }

    init {
        when (section) {
            Section.PLAYLISTS -> {
                require((position == null) == (playlistId == null)) {
                    "Playlist cursor must contain both playlist key parts"
                }
                require(trackId == null) { "Playlist cursor cannot contain a track key" }
            }
            Section.MEMBERSHIPS -> {
                require((position == null) == (playlistId == null)) {
                    "Membership cursor must contain playlist key parts"
                }
                require((position == null) == (trackId == null)) {
                    "Membership cursor must contain all key parts"
                }
            }
        }
    }

    companion object {
        fun startPlaylists() = R16PlaylistCheckpoint(Section.PLAYLISTS, null, null, null)
    }
}

/** Small strict, versioned checkpoint codec shared by the page adapters. */
internal object R16MigrationCheckpointCodec {
    fun encodeSingle(kind: String, value: String): String = encode(kind, value)

    fun decodeSingle(value: String, expectedKind: String): String =
        decode(value, expectedKind, 1).single().also {
            require(it.isNotEmpty()) { "Checkpoint key is blank" }
        }

    fun encodePair(kind: String, first: String, second: String): String =
        encode(kind, first, second)

    fun decodePair(value: String, expectedKind: String): Pair<String, String> {
        val parts = decode(value, expectedKind, 2)
        require(parts.all(String::isNotEmpty)) { "Checkpoint key is blank" }
        return parts[0] to parts[1]
    }

    fun encodeTriple(kind: String, first: String, second: String, third: String): String =
        encode(kind, first, second, third)

    fun decodeTriple(value: String, expectedKind: String): Triple<String, String, String> {
        val parts = decode(value, expectedKind, 3)
        require(parts.all(String::isNotEmpty)) { "Checkpoint key is blank" }
        return Triple(parts[0], parts[1], parts[2])
    }

    fun encodeLongAndString(kind: String, first: Long, second: String): String =
        encode(kind, first.toString(), second)

    fun decodeLongAndString(value: String, expectedKind: String): Pair<Long, String> {
        val parts = decodePair(value, expectedKind)
        return parts.first.toLong() to parts.second
    }

    fun encodePlaylistPage(position: Int, playlistId: String): String =
        encode("M4", "PLAYLISTS", position.toString(), playlistId, "")

    fun encodeMembershipStart(): String = encode("M4", "MEMBERSHIPS", "", "", "")

    fun encodeMembershipPage(playlistId: String, position: Int, trackId: String): String =
        encode("M4", "MEMBERSHIPS", position.toString(), playlistId, trackId)

    fun decodePlaylist(value: String): R16PlaylistCheckpoint {
        val parts = decode(value, "M4", 4)
        val section =
            runCatching { R16PlaylistCheckpoint.Section.valueOf(parts[0]) }
                .getOrElse { error("Unknown M4 checkpoint section") }
        val position = parts[1].takeIf(String::isNotEmpty)?.toIntOrNull()
        require(parts[1].isEmpty() || position != null) { "M4 checkpoint position is malformed" }
        val playlistId = parts[2].takeIf(String::isNotEmpty)
        val trackId = parts[3].takeIf(String::isNotEmpty)
        return R16PlaylistCheckpoint(section, position, playlistId, trackId)
    }

    private fun encode(kind: String, vararg parts: String): String =
        JSONObject()
            .put("v", CHECKPOINT_VERSION)
            .put("kind", kind)
            .put("parts", JSONArray(parts.toList()))
            .toString()

    private fun decode(value: String, expectedKind: String, expectedParts: Int): List<String> {
        val json =
            runCatching { JSONObject(value) }
                .getOrElse { error("Migration checkpoint is not valid JSON") }
        require(json.optInt("v", -1) == CHECKPOINT_VERSION) {
            "Migration checkpoint version is unsupported"
        }
        require(json.optString("kind") == expectedKind) {
            "Migration checkpoint kind does not match $expectedKind"
        }
        val parts = json.optJSONArray("parts") ?: error("Migration checkpoint parts missing")
        require(parts.length() == expectedParts) {
            "Migration checkpoint has the wrong number of key parts"
        }
        return List(parts.length()) { index -> parts.optString(index) }
    }

    private const val CHECKPOINT_VERSION = 1
}
