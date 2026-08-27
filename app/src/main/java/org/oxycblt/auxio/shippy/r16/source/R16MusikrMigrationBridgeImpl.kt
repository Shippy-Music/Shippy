/*
 * Copyright (c) 2026 Auxio Project
 * R16MusikrMigrationBridgeImpl.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.source

import app.shippy.data.R16DataRuntime
import app.shippy.data.migration.R16LocalReindexCheckpoint
import app.shippy.data.migration.pipeline.R16MigrationCallbackPageResult
import app.shippy.data.migration.pipeline.R16MusikrDevicePlaylistPageRequest
import app.shippy.data.migration.pipeline.R16MusikrLocalReindexPageRequest
import app.shippy.data.migration.pipeline.R16MusikrMigrationBridge
import org.json.JSONObject
import org.oxycblt.auxio.music.MusicRepository

/**
 * App-owned M5/M12 adapter. It reuses the process-owned Musikr repository/engine and the two
 * migration implementations; it never constructs or starts another scanner.
 */
class R16MusikrMigrationBridgeImpl
private constructor(
    private val localReindex: R16LocalReindexMigration,
    private val devicePlaylists: R16DevicePlaylistMigration,
) : R16MusikrMigrationBridge {
    override suspend fun importDevicePlaylistPage(
        request: R16MusikrDevicePlaylistPageRequest
    ): R16MigrationCallbackPageResult {
        request.validate()
        return when (val checkpoint = request.lastStableKey?.let(BridgeCheckpointCodec::decodeM5)) {
            null,
            is M5Checkpoint.Local -> {
                val local =
                    localReindex.runPage(
                        migrationId = request.migrationId,
                        checkpoint = checkpoint?.let { it.value },
                        pageSize = request.pageSize,
                        // M5's bounded UID prerequisite is not formal M12. Its cursor is the
                        // M5 pipeline checkpoint; do not write the M12 audit slot here.
                        persistAudit = false,
                    )
                if (local.complete) {
                    // Keep the transition separate: this invocation performed only the UID
                    // pre-pass page. The next invocation is the first playlist page.
                    R16MigrationCallbackPageResult(
                        complete = false,
                        lastStableKey = BridgeCheckpointCodec.encodeM5Playlists(null),
                    )
                } else {
                    R16MigrationCallbackPageResult(
                        complete = false,
                        lastStableKey = BridgeCheckpointCodec.encodeM5Local(local),
                    )
                }
            }
            is M5Checkpoint.Playlists -> {
                val page =
                    devicePlaylists.runPage(
                        migrationId = request.migrationId,
                        checkpoint = checkpoint.value,
                        importedAtEpochMs = request.importedAtEpochMs,
                        pageSize =
                            request.pageSize.coerceAtMost(R16DevicePlaylistMigration.MAX_PAGE_SIZE),
                    )
                if (page.unresolvedCount != 0) {
                    throw R16MusikrPlaylistResolutionException(
                        unresolvedCount = page.unresolvedCount,
                        unresolvedSongUids = page.unresolvedSongUids,
                    )
                }
                R16MigrationCallbackPageResult(
                    complete = page.complete,
                    lastStableKey =
                        page.nextCheckpoint?.let(BridgeCheckpointCodec::encodeM5Playlists),
                )
            }
        }
    }

    override suspend fun reindexLocalPage(
        request: R16MusikrLocalReindexPageRequest
    ): R16MigrationCallbackPageResult {
        request.validate()
        val checkpoint = request.lastStableKey?.let(BridgeCheckpointCodec::decodeM12)
        val page =
            localReindex.runPage(
                migrationId = request.migrationId,
                checkpoint = checkpoint,
                pageSize = request.pageSize,
            )
        return R16MigrationCallbackPageResult(
            complete = page.complete,
            lastStableKey = if (page.complete) null else BridgeCheckpointCodec.encodeM12(page),
        )
    }

    companion object {
        /** Builds one bridge around the already-owned repository, engine, and R16 runtime. */
        fun create(
            musicRepository: MusicRepository,
            localMediaEngine: MusikrLocalMediaEngine,
            dataRuntime: R16DataRuntime,
            pageSize: Int = R16DevicePlaylistMigration.DEFAULT_PAGE_SIZE,
        ): R16MusikrMigrationBridgeImpl {
            val localReindex =
                R16LocalReindexMigration.create(
                    localMediaEngine = localMediaEngine,
                    dataRuntime = dataRuntime,
                    pageSize = pageSize.coerceAtMost(500),
                )
            return R16MusikrMigrationBridgeImpl(
                localReindex = localReindex,
                devicePlaylists =
                    R16DevicePlaylistMigration.create(
                        musicRepository = musicRepository,
                        dataRuntime = dataRuntime,
                        pageSize = pageSize.coerceAtMost(R16DevicePlaylistMigration.MAX_PAGE_SIZE),
                    ),
            )
        }
    }
}

/** Recoverable M5 condition: retry after the local UID mapping has been repaired. */
class R16MusikrPlaylistResolutionException
internal constructor(val unresolvedCount: Int, val unresolvedSongUids: Set<String>) :
    IllegalStateException(
        "M5 cannot import playlists until all Musikr song UIDs resolve " +
            "($unresolvedCount unresolved; sample: " +
            unresolvedSongUids.sorted().joinToString(", ") +
            ")"
    )

private sealed interface M5Checkpoint {
    data class Local(val value: R16LocalReindexCheckpoint) : M5Checkpoint

    data class Playlists(val value: R16DevicePlaylistMigrationCheckpoint?) : M5Checkpoint
}

private object BridgeCheckpointCodec {
    private const val VERSION = 1

    fun encodeM5Local(value: R16LocalReindexCheckpoint): String = encodeLocal("M5_LOCAL", value)

    fun encodeM12(value: R16LocalReindexCheckpoint): String = encodeLocal("M12_LOCAL", value)

    fun encodeM5Playlists(value: R16DevicePlaylistMigrationCheckpoint?): String =
        JSONObject()
            .put("v", VERSION)
            .put("kind", "M5_PLAYLISTS")
            .put("stage", value?.stage?.name ?: R16DevicePlaylistMigrationStage.VALIDATE.name)
            .put("snapshotFingerprint", value?.snapshotFingerprint ?: JSONObject.NULL)
            .put("processedPlaylistCount", value?.processedPlaylistCount ?: 0)
            .toString()

    fun decodeM5(raw: String): M5Checkpoint {
        val json = parse(raw)
        return when (json.getString("kind")) {
            "M5_LOCAL" -> M5Checkpoint.Local(decodeLocal(json, "M5_LOCAL"))
            "M5_PLAYLISTS" -> M5Checkpoint.Playlists(decodePlaylistCheckpoint(json))
            else -> error("Unknown M5 Musikr checkpoint kind")
        }
    }

    fun decodeM12(raw: String): R16LocalReindexCheckpoint {
        val json = parse(raw)
        require(json.getString("kind") == "M12_LOCAL") {
            "M12 Musikr checkpoint kind does not match"
        }
        return decodeLocal(json, "M12_LOCAL")
    }

    private fun encodeLocal(kind: String, value: R16LocalReindexCheckpoint): String =
        JSONObject()
            .put("v", VERSION)
            .put("kind", kind)
            .put("snapshotFingerprint", value.snapshotFingerprint)
            .put("snapshotCount", value.snapshotCount)
            .put("processedCount", value.processedCount)
            .put("linkedCount", value.linkedCount)
            .put("unresolvedCount", value.unresolvedCount)
            .put("lastSourceKey", value.lastSourceKey ?: JSONObject.NULL)
            .put("complete", value.complete)
            .toString()

    private fun decodeLocal(json: JSONObject, expectedKind: String): R16LocalReindexCheckpoint {
        require(json.getString("kind") == expectedKind) { "Musikr checkpoint kind does not match" }
        val checkpoint =
            R16LocalReindexCheckpoint(
                snapshotFingerprint = json.getString("snapshotFingerprint"),
                snapshotCount = json.getLong("snapshotCount"),
                processedCount = json.getLong("processedCount"),
                linkedCount = json.getLong("linkedCount"),
                unresolvedCount = json.getLong("unresolvedCount"),
                lastSourceKey =
                    if (json.isNull("lastSourceKey")) null else json.getString("lastSourceKey"),
                complete = json.getBoolean("complete"),
            )
        require(!checkpoint.complete) {
            "A complete local page must not be used as a resume checkpoint"
        }
        return checkpoint
    }

    private fun decodePlaylistCheckpoint(json: JSONObject): R16DevicePlaylistMigrationCheckpoint? {
        val stage =
            runCatching { R16DevicePlaylistMigrationStage.valueOf(json.getString("stage")) }
                .getOrElse { error("M5 playlist checkpoint stage is invalid") }
        val processed = json.getInt("processedPlaylistCount")
        require(processed >= 0) { "M5 playlist checkpoint ordinal is negative" }
        val hasFingerprint = !json.isNull("snapshotFingerprint")
        if (!hasFingerprint) {
            require(stage == R16DevicePlaylistMigrationStage.VALIDATE && processed == 0) {
                "Only the initial M5 validation checkpoint may omit its fingerprint"
            }
            return null
        }
        return R16DevicePlaylistMigrationCheckpoint(
            stage = stage,
            snapshotFingerprint = json.getString("snapshotFingerprint"),
            processedPlaylistCount = processed,
        )
    }

    private fun parse(raw: String): JSONObject {
        require(raw.isNotBlank()) { "Musikr migration checkpoint is blank" }
        val json =
            runCatching { JSONObject(raw) }
                .getOrElse { error("Musikr migration checkpoint is not valid JSON") }
        require(json.getInt("v") == VERSION) {
            "Musikr migration checkpoint version is unsupported"
        }
        return json
    }
}

private fun R16MusikrDevicePlaylistPageRequest.validate() {
    require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
    require(pageSize in 1..500) { "Musikr migration page size is out of bounds" }
    require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
}

private fun R16MusikrLocalReindexPageRequest.validate() {
    require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
    require(pageSize in 1..500) { "Musikr migration page size is out of bounds" }
    require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
}
