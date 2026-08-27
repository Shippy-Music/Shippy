/*
 * Copyright (c) 2026 Auxio Project
 * R16DevicePlaylistMigration.kt is part of Auxio.
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

import app.shippy.core.identity.RecordingId
import app.shippy.data.R16DataRuntime
import app.shippy.data.ingest.R16IngestionRepository
import app.shippy.data.migration.R16DevicePlaylistImport
import app.shippy.data.migration.R16DevicePlaylistImportRepository
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Playlist

internal enum class R16DevicePlaylistMigrationStage {
    VALIDATE,
    IMPORT,
}

internal data class R16DevicePlaylistMigrationCheckpoint(
    val stage: R16DevicePlaylistMigrationStage,
    val snapshotFingerprint: String,
    val processedPlaylistCount: Int,
) {
    init {
        require(snapshotFingerprint.isNotBlank()) {
            "Device playlist snapshot fingerprint is blank"
        }
        require(processedPlaylistCount >= 0) { "Processed playlist count cannot be negative" }
    }
}

internal data class R16DevicePlaylistMigrationPage(
    val stage: R16DevicePlaylistMigrationStage,
    val snapshotFingerprint: String,
    val snapshotPlaylistCount: Int,
    val processedPlaylistCount: Int,
    val importedPlaylistCount: Int,
    val reusedPlaylistCount: Int,
    val importedEntryCount: Int,
    val unresolvedSongUids: Set<String>,
    val unresolvedCount: Int,
    val complete: Boolean,
    val nextCheckpoint: R16DevicePlaylistMigrationCheckpoint?,
)

/** Inactive M5 adapter. Its UID prerequisite and playlist import have separate cursors. */
class R16DevicePlaylistMigration
private constructor(
    private val musicRepository: MusicRepository,
    private val ingestion: R16IngestionRepository,
    private val importer: R16DevicePlaylistImportRepository,
    private val pageSize: Int,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Device playlist page size is out of bounds" }
    }

    /**
     * Processes one bounded M5 page.
     *
     * VALIDATE resolves only the current playlist page and never calls the playlist importer. Once
     * the complete stable snapshot has validated, the returned IMPORT checkpoint starts at ordinal
     * zero. IMPORT then resolves/maps only its current page before atomically importing that page.
     * A fingerprint change discards either cursor and starts VALIDATE again.
     */
    internal suspend fun runPage(
        migrationId: String,
        checkpoint: R16DevicePlaylistMigrationCheckpoint? = null,
        importedAtEpochMs: Long = System.currentTimeMillis(),
        pageSize: Int = this.pageSize,
    ): R16DevicePlaylistMigrationPage {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "Device playlist page size is out of bounds" }

        val library = checkNotNull(musicRepository.library) { "Musikr library is unavailable" }
        val playlists = library.playlists.stableOrder()
        val originKeys = playlists.map { it.uid.toString() }
        check(originKeys.toSet().size == originKeys.size) {
            "Musikr playlist snapshot contains duplicate identities"
        }
        val fingerprint = playlists.snapshotFingerprint()
        val resumable =
            checkpoint?.takeIf {
                it.snapshotFingerprint == fingerprint && it.processedPlaylistCount <= playlists.size
            }
        val state =
            resumable
                ?: R16DevicePlaylistMigrationCheckpoint(
                    stage = R16DevicePlaylistMigrationStage.VALIDATE,
                    snapshotFingerprint = fingerprint,
                    processedPlaylistCount = 0,
                )
        val snapshotOriginKeys = originKeys.toSet()

        return when (state.stage) {
            R16DevicePlaylistMigrationStage.VALIDATE ->
                validatePage(
                    playlists = playlists,
                    snapshotFingerprint = fingerprint,
                    checkpoint = state,
                    pageSize = pageSize,
                )
            R16DevicePlaylistMigrationStage.IMPORT -> {
                require(playlists.isEmpty() || state.processedPlaylistCount < playlists.size) {
                    "A completed device playlist import cannot be resumed"
                }
                importPage(
                    migrationId = migrationId,
                    playlists = playlists,
                    snapshotFingerprint = fingerprint,
                    snapshotOriginKeys = snapshotOriginKeys,
                    checkpoint = state,
                    importedAtEpochMs = importedAtEpochMs,
                    pageSize = pageSize,
                )
            }
        }
    }

    private suspend fun validatePage(
        playlists: List<Playlist>,
        snapshotFingerprint: String,
        checkpoint: R16DevicePlaylistMigrationCheckpoint,
        pageSize: Int,
    ): R16DevicePlaylistMigrationPage {
        val start = checkpoint.processedPlaylistCount
        if (start == playlists.size) {
            return validationTransitionPage(
                snapshotFingerprint = snapshotFingerprint,
                snapshotPlaylistCount = playlists.size,
            )
        }
        val end = minOf(playlists.size, Math.addExact(start, pageSize))
        val page = playlists.subList(start, end)
        val resolution = resolvePage(page)
        if (resolution.unresolvedCount != 0) {
            return R16DevicePlaylistMigrationPage(
                stage = R16DevicePlaylistMigrationStage.VALIDATE,
                snapshotFingerprint = snapshotFingerprint,
                snapshotPlaylistCount = playlists.size,
                processedPlaylistCount = start,
                importedPlaylistCount = 0,
                reusedPlaylistCount = 0,
                importedEntryCount = 0,
                unresolvedSongUids = resolution.unresolvedSongUids,
                unresolvedCount = resolution.unresolvedCount,
                complete = false,
                nextCheckpoint = checkpoint,
            )
        }
        return R16DevicePlaylistMigrationPage(
            stage = R16DevicePlaylistMigrationStage.VALIDATE,
            snapshotFingerprint = snapshotFingerprint,
            snapshotPlaylistCount = playlists.size,
            processedPlaylistCount = end,
            importedPlaylistCount = 0,
            reusedPlaylistCount = 0,
            importedEntryCount = 0,
            unresolvedSongUids = emptySet(),
            unresolvedCount = 0,
            complete = false,
            nextCheckpoint =
                R16DevicePlaylistMigrationCheckpoint(
                    stage = R16DevicePlaylistMigrationStage.VALIDATE,
                    snapshotFingerprint = snapshotFingerprint,
                    processedPlaylistCount = end,
                ),
        )
    }

    private fun validationTransitionPage(
        snapshotFingerprint: String,
        snapshotPlaylistCount: Int,
    ): R16DevicePlaylistMigrationPage =
        R16DevicePlaylistMigrationPage(
            stage = R16DevicePlaylistMigrationStage.VALIDATE,
            snapshotFingerprint = snapshotFingerprint,
            snapshotPlaylistCount = snapshotPlaylistCount,
            processedPlaylistCount = snapshotPlaylistCount,
            importedPlaylistCount = 0,
            reusedPlaylistCount = 0,
            importedEntryCount = 0,
            unresolvedSongUids = emptySet(),
            unresolvedCount = 0,
            complete = false,
            nextCheckpoint =
                R16DevicePlaylistMigrationCheckpoint(
                    stage = R16DevicePlaylistMigrationStage.IMPORT,
                    snapshotFingerprint = snapshotFingerprint,
                    processedPlaylistCount = 0,
                ),
        )

    private suspend fun importPage(
        migrationId: String,
        playlists: List<Playlist>,
        snapshotFingerprint: String,
        snapshotOriginKeys: Set<String>,
        checkpoint: R16DevicePlaylistMigrationCheckpoint,
        importedAtEpochMs: Long,
        pageSize: Int,
    ): R16DevicePlaylistMigrationPage {
        val start = checkpoint.processedPlaylistCount
        val end = minOf(playlists.size, Math.addExact(start, pageSize))
        val page = playlists.subList(start, end)
        val resolution = resolvePage(page)
        if (resolution.unresolvedCount != 0) {
            return R16DevicePlaylistMigrationPage(
                stage = R16DevicePlaylistMigrationStage.IMPORT,
                snapshotFingerprint = snapshotFingerprint,
                snapshotPlaylistCount = playlists.size,
                processedPlaylistCount = start,
                importedPlaylistCount = 0,
                reusedPlaylistCount = 0,
                importedEntryCount = 0,
                unresolvedSongUids = resolution.unresolvedSongUids,
                unresolvedCount = resolution.unresolvedCount,
                complete = false,
                nextCheckpoint = checkpoint,
            )
        }
        val result =
            importer.importPage(
                migrationId = migrationId,
                snapshotOriginKeys = snapshotOriginKeys,
                startOrdinal = start,
                playlists = page.toImports(resolution.recordingIds),
                importedAtEpochMs = importedAtEpochMs,
            )
        return R16DevicePlaylistMigrationPage(
            stage = R16DevicePlaylistMigrationStage.IMPORT,
            snapshotFingerprint = snapshotFingerprint,
            snapshotPlaylistCount = playlists.size,
            processedPlaylistCount = result.processedPlaylistCount,
            importedPlaylistCount = result.importedPlaylistCount,
            reusedPlaylistCount = result.reusedPlaylistCount,
            importedEntryCount = result.importedEntryCount,
            unresolvedSongUids = emptySet(),
            unresolvedCount = 0,
            complete = result.complete,
            nextCheckpoint =
                if (result.complete) {
                    null
                } else {
                    R16DevicePlaylistMigrationCheckpoint(
                        stage = R16DevicePlaylistMigrationStage.IMPORT,
                        snapshotFingerprint = snapshotFingerprint,
                        processedPlaylistCount = result.processedPlaylistCount,
                    )
                },
        )
    }

    private suspend fun resolvePage(playlists: List<Playlist>): Resolution {
        val songUids =
            playlists
                .asSequence()
                .flatMap { playlist -> playlist.songs.asSequence() }
                .map { song -> song.uid.toString() }
                .toSortedSet()
        val recordingIds =
            ingestion.transaction {
                songUids.associateWith { uid ->
                    exactSource(musikrLocalSourceKey(uid))?.recordingId
                }
            }
        val unresolved = recordingIds.filterValues { it == null }.keys.toSortedSet()
        return Resolution(
            recordingIds = recordingIds,
            unresolvedSongUids = unresolved.take(MAX_UNRESOLVED_SAMPLE).toSet(),
            unresolvedCount = unresolved.size,
        )
    }

    private data class Resolution(
        val recordingIds: Map<String, RecordingId?>,
        val unresolvedSongUids: Set<String>,
        val unresolvedCount: Int,
    )

    companion object {
        fun create(
            musicRepository: MusicRepository,
            dataRuntime: R16DataRuntime,
            pageSize: Int = DEFAULT_PAGE_SIZE,
        ): R16DevicePlaylistMigration =
            R16DevicePlaylistMigration(
                musicRepository = musicRepository,
                ingestion = dataRuntime.ingestion,
                importer = dataRuntime.devicePlaylists,
                pageSize = pageSize,
            )

        internal fun createForTesting(
            musicRepository: MusicRepository,
            ingestion: R16IngestionRepository,
            importer: R16DevicePlaylistImportRepository,
            pageSize: Int = DEFAULT_PAGE_SIZE,
        ): R16DevicePlaylistMigration =
            R16DevicePlaylistMigration(
                musicRepository = musicRepository,
                ingestion = ingestion,
                importer = importer,
                pageSize = pageSize,
            )

        const val DEFAULT_PAGE_SIZE = 50
        internal const val MAX_PAGE_SIZE = 100
        private const val MAX_UNRESOLVED_SAMPLE = 8
    }
}

private suspend fun List<Playlist>.toImports(
    recordingIds: Map<String, RecordingId?>
): List<R16DevicePlaylistImport> = map { playlist ->
    R16DevicePlaylistImport(
        originKey = playlist.uid.toString(),
        name = playlist.name.raw,
        orderedRecordingIds =
            playlist.songs.map { song ->
                checkNotNull(recordingIds[song.uid.toString()]) {
                    "Resolved device playlist recording disappeared"
                }
            },
    )
}

private fun Collection<Playlist>.stableOrder(): List<Playlist> =
    sortedWith(compareBy<Playlist>({ it.name.raw.lowercase(Locale.ROOT) }, { it.uid.toString() }))

private fun List<Playlist>.snapshotFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    forEach { playlist ->
        digest.put(playlist.uid.toString())
        digest.put(playlist.name.raw)
        digest.put(playlist.songs.size.toString())
        playlist.songs.forEach { song -> digest.put(song.uid.toString()) }
    }
    return digest.digest().joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }
}

private fun MessageDigest.put(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}
