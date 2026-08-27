/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryMutationRepository.kt is part of Auxio.
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
package app.shippy.data.library

import androidx.room.withTransaction
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.RecordingId
import app.shippy.core.library.PlaylistSort
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.dao.PlaylistAdjacentMoveDirection
import app.shippy.data.db.dao.PlaylistAdjacentMoveResult
import app.shippy.data.db.entity.LibraryLayoutEntryEntity
import app.shippy.data.db.entity.LibraryRecordingEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import app.shippy.data.db.util.SparseOrderKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Small canonical mutation seam for R16 Library relationships and layout. */
public interface R16LibraryMutationRepository {
    public fun observeLike(recordingId: RecordingId): Flow<Boolean>

    /** Returns false when the exact canonical recording no longer exists. */
    public suspend fun saveToLiked(recordingId: RecordingId): Boolean

    /**
     * Removes like while preserving other library relationships. Returns false when recording does
     * not exist.
     */
    public suspend fun removeFromLiked(recordingId: RecordingId): Boolean

    /** Toggles liked state for a recording. Returns true on success. */
    public suspend fun toggleLiked(recordingId: RecordingId): Boolean

    /** Returns false when the exact playlist no longer exists. */
    public suspend fun setPlaylistPinned(playlistId: PlaylistId, pinned: Boolean): Boolean

    /** Persists only display order metadata; canonical playlist order keys are never rewritten. */
    public suspend fun setPlaylistDisplaySort(playlistId: PlaylistId, sort: PlaylistSort): Boolean

    /** Creates a USER-owned playlist and its sole initial Library layout row atomically. */
    public suspend fun createUserPlaylist(rawName: String): R16PlaylistLifecycleResult

    /** Renames any exact canonical playlist while preserving its import provenance. */
    public suspend fun renamePlaylist(
        playlistId: PlaylistId,
        rawName: String,
    ): R16PlaylistLifecycleResult

    /** Deletes any exact canonical playlist after removing its Library layout row atomically. */
    public suspend fun deletePlaylist(playlistId: PlaylistId): R16PlaylistLifecycleResult

    /** Removes exactly one playlist occurrence; duplicate RecordingIds remain distinct. */
    public suspend fun removePlaylistEntry(
        playlistId: PlaylistId,
        playlistEntryId: PlaylistEntryId,
    ): Boolean

    /** Removes multiple playlist occurrences in one atomic transaction. */
    public suspend fun removePlaylistEntries(
        playlistId: PlaylistId,
        playlistEntryIds: Set<PlaylistEntryId>,
    ): Int

    /** Adds one durable playlist occurrence; duplicates are intentionally preserved. */
    public suspend fun addPlaylistEntry(
        playlistId: PlaylistId,
        recordingId: RecordingId,
    ): R16PlaylistEntryAddResult

    /** Adds multiple durable playlist occurrences in one atomic transaction. */
    public suspend fun addPlaylistEntries(
        playlistId: PlaylistId,
        recordingIds: List<RecordingId>,
    ): List<R16PlaylistEntryAddResult>

    /** Observes occurrences of a recording across all playlists. */
    public fun observePlaylistMemberships(recordingId: RecordingId): Flow<Map<PlaylistId, Int>>

    /** Removes all occurrences of a recording from a playlist. */
    public suspend fun removeRecordingFromPlaylist(
        playlistId: PlaylistId,
        recordingId: RecordingId,
    ): Boolean

    /** Moves one exact occurrence by one canonical position without collapsing duplicates. */
    public suspend fun movePlaylistEntry(
        playlistId: PlaylistId,
        playlistEntryId: PlaylistEntryId,
        direction: R16PlaylistEntryMoveDirection,
    ): R16PlaylistEntryMoveResult

    /** Moves a batch of exact occurrences relative to an anchor without collapsing duplicates. */
    public suspend fun movePlaylistEntries(
        playlistId: PlaylistId,
        entryIdsToMove: List<PlaylistEntryId>,
        targetAnchorEntryId: PlaylistEntryId?,
        moveBefore: Boolean,
    ): Boolean

    public suspend fun searchIdentifyCandidates(
        query: String,
        limit: Int = 30,
    ): List<R16IdentifyCandidate>

    public suspend fun confirmIdentifyCandidate(
        sourceReferenceId: String?,
        targetRecordingId: String,
    ): Boolean

    public suspend fun undoIdentifyCandidate(
        sourceReferenceId: String,
        previousRecordingId: String,
    ): Boolean

    /** The latest still-applicable manual source link, retained for Snackbar and later unlink. */
    public suspend fun latestIdentifyUndo(sourceReferenceId: String): R16IdentifyUndo?

    /** Reverses exactly the latest still-applicable manual source link. */
    public suspend fun undoLatestIdentifyCandidate(sourceReferenceId: String): Boolean

    public suspend fun updateMetadataOverride(
        recordingId: String,
        fieldName: String,
        valueJson: String,
    ): Boolean

    public suspend fun revertMetadataOverride(recordingId: String, fieldName: String): Boolean

    public suspend fun getMetadataOverrides(recordingId: String): Map<String, String>

    public suspend fun getSong(recordingId: String): R16IdentifyCandidate?

    public suspend fun mergeRecordings(
        survivorRecordingId: String,
        retiredRecordingId: String,
    ): Boolean

    public suspend fun unmergeRecording(recordingId: String): Boolean
}

public data class R16IdentifyCandidate(
    val recordingId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkLocation: String?,
    val isExact: Boolean,
)

/** Durable reversal information captured with a user-confirmed source identity decision. */
public data class R16IdentifyUndo(
    val sourceReferenceId: String,
    val previousRecordingId: String,
    val targetRecordingId: String,
)

/** Truthful lifecycle outcome for the small R16 user-playlist mutation surface. */
public sealed interface R16PlaylistLifecycleResult {
    public data class Created(val playlistId: PlaylistId) : R16PlaylistLifecycleResult

    public data object Updated : R16PlaylistLifecycleResult

    public data object Deleted : R16PlaylistLifecycleResult

    public data object InvalidName : R16PlaylistLifecycleResult

    public data object NotFound : R16PlaylistLifecycleResult

    /** The write did not complete, without misrepresenting the ownership/existence state. */
    public data object Failed : R16PlaylistLifecycleResult
}

/** Exact result of adding one Recording occurrence to one canonical Playlist. */
public sealed interface R16PlaylistEntryAddResult {
    public data class Added(val playlistEntryId: PlaylistEntryId) : R16PlaylistEntryAddResult

    public data object PlaylistNotFound : R16PlaylistEntryAddResult

    public data object RecordingNotFound : R16PlaylistEntryAddResult

    public data object Failed : R16PlaylistEntryAddResult
}

/** Canonical list direction; independent from recording identity and loaded UI pages. */
public enum class R16PlaylistEntryMoveDirection {
    TOWARD_START,
    TOWARD_END,
}

/** Truthful result for one exact adjacent playlist-occurrence move. */
public sealed interface R16PlaylistEntryMoveResult {
    public data object Moved : R16PlaylistEntryMoveResult

    public data object AtBoundary : R16PlaylistEntryMoveResult

    public data object NotFound : R16PlaylistEntryMoveResult

    public data object NotCustomOrder : R16PlaylistEntryMoveResult

    public data object Failed : R16PlaylistEntryMoveResult
}

internal class RoomR16LibraryMutationRepository(
    private val database: ShippyR16Database,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : R16LibraryMutationRepository {
    override fun observeLike(recordingId: RecordingId): Flow<Boolean> =
        database.libraryDao().observeRelationship(recordingId.value).map { it?.liked == true }

    override suspend fun saveToLiked(recordingId: RecordingId): Boolean =
        database.withTransaction {
            if (database.recordingDao().get(recordingId.value) == null) return@withTransaction false

            val library = database.libraryDao()
            val existing = library.relationship(recordingId.value)
            val now = nowEpochMs()
            when {
                existing == null ->
                    library.upsertRelationship(
                        LibraryRecordingEntity(
                            recordingId = recordingId.value,
                            liked = true,
                            explicitlySaved = false,
                            userEdited = false,
                            manuallyIdentified = false,
                            firstAddedAtEpochMs = now,
                            updatedAtEpochMs = now,
                        )
                    )
                !existing.liked -> library.updateLiked(recordingId.value, true, now)
            }
            true
        }

    override suspend fun removeFromLiked(recordingId: RecordingId): Boolean =
        database.withTransaction {
            if (database.recordingDao().get(recordingId.value) == null) return@withTransaction false
            val library = database.libraryDao()
            val existing = library.relationship(recordingId.value) ?: return@withTransaction false
            if (existing.liked) {
                library.updateLiked(recordingId.value, false, nowEpochMs())
            }
            true
        }

    override suspend fun toggleLiked(recordingId: RecordingId): Boolean =
        database.withTransaction {
            if (database.recordingDao().get(recordingId.value) == null) return@withTransaction false
            val library = database.libraryDao()
            val existing = library.relationship(recordingId.value)
            val now = nowEpochMs()
            if (existing == null || !existing.liked) {
                saveToLiked(recordingId)
            } else {
                removeFromLiked(recordingId)
            }
            true
        }

    override suspend fun setPlaylistPinned(playlistId: PlaylistId, pinned: Boolean): Boolean =
        database.withTransaction {
            if (database.playlistDao().get(playlistId.value) == null) return@withTransaction false

            val library = database.libraryDao()
            val existing = library.layoutEntry(PLAYLIST_LAYOUT_TARGET_TYPE, playlistId.value)
            val orderKey =
                existing?.orderKey
                    ?: SparseOrderKey.between(
                        library.maxLayoutOrderKey(PLAYLIST_LAYOUT_TARGET_TYPE),
                        null,
                    )
                    ?: Long.MAX_VALUE
            library.upsertLayout(
                listOf(
                    LibraryLayoutEntryEntity(
                        targetType = PLAYLIST_LAYOUT_TARGET_TYPE,
                        targetId = playlistId.value,
                        pinned = pinned,
                        orderKey = orderKey,
                    )
                )
            )
            true
        }

    override suspend fun setPlaylistDisplaySort(
        playlistId: PlaylistId,
        sort: PlaylistSort,
    ): Boolean =
        database.withTransaction {
            val normalizedSort = sort.normalized()
            if (database.playlistDao().get(playlistId.value) == null) {
                return@withTransaction false
            }
            database
                .playlistDao()
                .updateDisplaySort(
                    playlistId = playlistId.value,
                    displaySortMode = normalizedSort.mode.wireValue,
                    displaySortDirection = normalizedSort.direction.wireValue,
                    updatedAtEpochMs = nowEpochMs(),
                ) == 1
        }

    override suspend fun createUserPlaylist(rawName: String): R16PlaylistLifecycleResult =
        database.withTransaction {
            val name = rawName.trim()
            if (name.isEmpty()) return@withTransaction R16PlaylistLifecycleResult.InvalidName

            val library = database.libraryDao()
            val orderKey = nextPlaylistLayoutOrderKey(library)
            val playlistId = PlaylistId(UUID.randomUUID().toString())
            val now = nowEpochMs()
            database
                .playlistDao()
                .create(
                    PlaylistEntity(
                        playlistId = playlistId.value,
                        name = name,
                        pinned = false,
                        libraryOrderKey = orderKey,
                        artworkOverride = null,
                        displaySortMode = "CUSTOM",
                        displaySortDirection = "ASC",
                        originKind = ORIGIN_KIND_USER,
                        originKey = null,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    ),
                    initialEntries = emptyList(),
                )
            library.upsertLayout(
                listOf(
                    LibraryLayoutEntryEntity(
                        targetType = PLAYLIST_LAYOUT_TARGET_TYPE,
                        targetId = playlistId.value,
                        pinned = false,
                        orderKey = orderKey,
                    )
                )
            )
            R16PlaylistLifecycleResult.Created(playlistId)
        }

    override suspend fun renamePlaylist(
        playlistId: PlaylistId,
        rawName: String,
    ): R16PlaylistLifecycleResult =
        database.withTransaction {
            val name = rawName.trim()
            if (name.isEmpty()) return@withTransaction R16PlaylistLifecycleResult.InvalidName
            when (playlistStatus(playlistId)) {
                PlaylistOwnershipStatus.NOT_FOUND -> R16PlaylistLifecycleResult.NotFound
                PlaylistOwnershipStatus.MATERIALIZED -> {
                    check(
                        database
                            .playlistDao()
                            .renamePlaylist(playlistId.value, name, nowEpochMs()) == 1
                    ) {
                        "Canonical playlist disappeared during rename"
                    }
                    R16PlaylistLifecycleResult.Updated
                }
            }
        }

    override suspend fun deletePlaylist(playlistId: PlaylistId): R16PlaylistLifecycleResult =
        database.withTransaction {
            when (playlistStatus(playlistId)) {
                PlaylistOwnershipStatus.NOT_FOUND -> R16PlaylistLifecycleResult.NotFound
                PlaylistOwnershipStatus.MATERIALIZED -> {
                    database
                        .libraryDao()
                        .deleteLayout(PLAYLIST_LAYOUT_TARGET_TYPE, playlistId.value)
                    check(database.playlistDao().deletePlaylist(playlistId.value) == 1) {
                        "Canonical playlist disappeared during delete"
                    }
                    R16PlaylistLifecycleResult.Deleted
                }
            }
        }

    override suspend fun removePlaylistEntry(
        playlistId: PlaylistId,
        playlistEntryId: PlaylistEntryId,
    ): Boolean =
        database.withTransaction {
            database.playlistDao().deleteEntry(playlistId.value, playlistEntryId.value) == 1
        }

    override suspend fun removePlaylistEntries(
        playlistId: PlaylistId,
        playlistEntryIds: Set<PlaylistEntryId>,
    ): Int =
        database.withTransaction {
            if (playlistEntryIds.isEmpty()) return@withTransaction 0
            database
                .playlistDao()
                .deleteEntriesByIds(playlistId.value, playlistEntryIds.map { it.value }.toSet())
        }

    override suspend fun addPlaylistEntries(
        playlistId: PlaylistId,
        recordingIds: List<RecordingId>,
    ): List<R16PlaylistEntryAddResult> =
        try {
            database.withTransaction {
                val playlistDao = database.playlistDao()
                if (playlistDao.get(playlistId.value) == null) {
                    return@withTransaction recordingIds.map {
                        R16PlaylistEntryAddResult.PlaylistNotFound
                    }
                }
                recordingIds.map { recordingId -> addPlaylistEntry(playlistId, recordingId) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            recordingIds.map { R16PlaylistEntryAddResult.Failed }
        }

    override suspend fun addPlaylistEntry(
        playlistId: PlaylistId,
        recordingId: RecordingId,
    ): R16PlaylistEntryAddResult =
        try {
            database.withTransaction {
                val playlistDao = database.playlistDao()
                if (playlistDao.get(playlistId.value) == null) {
                    return@withTransaction R16PlaylistEntryAddResult.PlaylistNotFound
                }
                if (database.recordingDao().get(recordingId.value) == null) {
                    return@withTransaction R16PlaylistEntryAddResult.RecordingNotFound
                }

                val last = playlistDao.lastEntry(playlistId.value)
                val orderKey = SparseOrderKey.between(last?.orderKey, null)
                if (orderKey != null) {
                    val entryId = PlaylistEntryId(UUID.randomUUID().toString())
                    playlistDao.insertEntry(
                        PlaylistEntryEntity(
                            playlistEntryId = entryId.value,
                            playlistId = playlistId.value,
                            recordingId = recordingId.value,
                            orderKey = orderKey,
                            addedAtEpochMs = nowEpochMs(),
                        )
                    )
                    return@withTransaction R16PlaylistEntryAddResult.Added(entryId)
                }

                val entries = playlistDao.entries(playlistId.value)
                val keys = SparseOrderKey.rebalancedKeys(entries.size + 1)
                playlistDao.rekeyEntries(
                    entries.mapIndexed { index, entry -> entry.copy(orderKey = keys[index]) }
                )
                val entryId = PlaylistEntryId(UUID.randomUUID().toString())
                playlistDao.insertEntry(
                    PlaylistEntryEntity(
                        playlistEntryId = entryId.value,
                        playlistId = playlistId.value,
                        recordingId = recordingId.value,
                        orderKey = checkNotNull(keys.lastOrNull()),
                        addedAtEpochMs = nowEpochMs(),
                    )
                )
                R16PlaylistEntryAddResult.Added(entryId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            R16PlaylistEntryAddResult.Failed
        }

    override fun observePlaylistMemberships(recordingId: RecordingId): Flow<Map<PlaylistId, Int>> =
        database.playlistDao().observeRecordingMemberships(recordingId.value).map { list ->
            list.associate { PlaylistId(it.playlistId) to it.occurrenceCount }
        }

    override suspend fun removeRecordingFromPlaylist(
        playlistId: PlaylistId,
        recordingId: RecordingId,
    ): Boolean =
        database.withTransaction {
            database.playlistDao().deleteEntriesForRecording(playlistId.value, recordingId.value) >
                0
        }

    override suspend fun movePlaylistEntry(
        playlistId: PlaylistId,
        playlistEntryId: PlaylistEntryId,
        direction: R16PlaylistEntryMoveDirection,
    ): R16PlaylistEntryMoveResult =
        try {
            database.withTransaction {
                val playlistDao = database.playlistDao()
                val playlist =
                    playlistDao.get(playlistId.value)
                        ?: return@withTransaction R16PlaylistEntryMoveResult.NotFound
                if (
                    !PlaylistSort.fromWire(playlist.displaySortMode, playlist.displaySortDirection)
                        .isCustom
                ) {
                    return@withTransaction R16PlaylistEntryMoveResult.NotCustomOrder
                }
                when (
                    playlistDao.moveAdjacent(
                        playlistId = playlistId.value,
                        playlistEntryId = playlistEntryId.value,
                        direction = direction.toDaoDirection(),
                    )
                ) {
                    PlaylistAdjacentMoveResult.MOVED -> R16PlaylistEntryMoveResult.Moved
                    PlaylistAdjacentMoveResult.AT_BOUNDARY -> R16PlaylistEntryMoveResult.AtBoundary
                    PlaylistAdjacentMoveResult.NOT_FOUND -> R16PlaylistEntryMoveResult.NotFound
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            R16PlaylistEntryMoveResult.Failed
        }

    override suspend fun movePlaylistEntries(
        playlistId: PlaylistId,
        entryIdsToMove: List<PlaylistEntryId>,
        targetAnchorEntryId: PlaylistEntryId?,
        moveBefore: Boolean,
    ): Boolean =
        try {
            database.withTransaction {
                val playlistDao = database.playlistDao()
                val allEntries = playlistDao.entries(playlistId.value).toMutableList()
                if (allEntries.isEmpty()) return@withTransaction false

                val moveIdSet = entryIdsToMove.map { it.value }.toSet()
                val movingEntries = allEntries.filter { it.playlistEntryId in moveIdSet }
                if (movingEntries.size != entryIdsToMove.size) return@withTransaction false

                val orderedMovingEntries =
                    entryIdsToMove.mapNotNull { id ->
                        movingEntries.find { it.playlistEntryId == id.value }
                    }
                val remainingEntries = allEntries.filter { it.playlistEntryId !in moveIdSet }

                val newOrderedEntries = mutableListOf<PlaylistEntryEntity>()
                if (targetAnchorEntryId == null) {
                    if (moveBefore) {
                        newOrderedEntries.addAll(orderedMovingEntries)
                        newOrderedEntries.addAll(remainingEntries)
                    } else {
                        newOrderedEntries.addAll(remainingEntries)
                        newOrderedEntries.addAll(orderedMovingEntries)
                    }
                } else {
                    val anchorIndex =
                        remainingEntries.indexOfFirst {
                            it.playlistEntryId == targetAnchorEntryId.value
                        }
                    if (anchorIndex == -1) return@withTransaction false
                    val insertIndex = if (moveBefore) anchorIndex else anchorIndex + 1
                    newOrderedEntries.addAll(remainingEntries.subList(0, insertIndex))
                    newOrderedEntries.addAll(orderedMovingEntries)
                    newOrderedEntries.addAll(
                        remainingEntries.subList(insertIndex, remainingEntries.size)
                    )
                }

                val keys = SparseOrderKey.rebalancedKeys(newOrderedEntries.size)
                playlistDao.rekeyEntries(
                    newOrderedEntries.mapIndexed { index, entry ->
                        entry.copy(orderKey = keys[index])
                    }
                )
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }

    override suspend fun searchIdentifyCandidates(
        query: String,
        limit: Int,
    ): List<R16IdentifyCandidate> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val ftsResults =
            runCatching {
                    val ftsQuery =
                        trimmed
                            .replace("\"", "")
                            .split(Regex("\\s+"))
                            .filter { it.isNotBlank() }
                            .joinToString(" ") { "$it*" }
                    database.searchDao().searchRecordingIds(ftsQuery, limit)
                }
                .getOrDefault(emptyList())

        val candidates = linkedMapOf<String, R16IdentifyCandidate>()

        for (recId in ftsResults) {
            val song = database.readModelDao().librarySong(recId) ?: continue
            val isExact = song.title.equals(trimmed, ignoreCase = true)
            candidates[recId] =
                R16IdentifyCandidate(
                    recordingId = song.recordingId,
                    title = song.title,
                    artist = song.artistDisplay,
                    album = song.releaseTitle,
                    artworkLocation = song.artworkLocation,
                    isExact = isExact,
                )
        }

        if (candidates.size < limit) {
            val remaining = limit - candidates.size
            val fallbackRows = database.readModelDao().librarySongs(limit = 100, offset = 0)
            val filtered =
                fallbackRows
                    .filter { row ->
                        row.recordingId !in candidates &&
                            (row.title.contains(trimmed, ignoreCase = true) ||
                                row.artistDisplay.contains(trimmed, ignoreCase = true) ||
                                row.releaseTitle?.contains(trimmed, ignoreCase = true) == true)
                    }
                    .take(remaining)

            for (row in filtered) {
                candidates[row.recordingId] =
                    R16IdentifyCandidate(
                        recordingId = row.recordingId,
                        title = row.title,
                        artist = row.artistDisplay,
                        album = row.releaseTitle,
                        artworkLocation = row.artworkLocation,
                        isExact = row.title.equals(trimmed, ignoreCase = true),
                    )
            }
        }

        return candidates.values.sortedWith(
            compareByDescending<R16IdentifyCandidate> { it.isExact }.thenBy { it.title.lowercase() }
        )
    }

    override suspend fun confirmIdentifyCandidate(
        sourceReferenceId: String?,
        targetRecordingId: String,
    ): Boolean =
        try {
            database.withTransaction {
                val now = System.currentTimeMillis()
                val source =
                    if (sourceReferenceId != null) database.sourceDao().get(sourceReferenceId)
                    else null
                if (sourceReferenceId != null && source == null) return@withTransaction false
                if (
                    sourceReferenceId != null &&
                        database.recordingDao().get(targetRecordingId) == null
                ) {
                    return@withTransaction false
                }
                if (source?.recordingId == targetRecordingId) return@withTransaction false
                val decision =
                    app.shippy.data.db.entity.IdentityDecisionEntity(
                        decisionId = "decision-${java.util.UUID.randomUUID()}",
                        subjectType = if (sourceReferenceId != null) "SOURCE" else "RECORDING",
                        subjectId = sourceReferenceId ?: targetRecordingId,
                        targetRecordingId = targetRecordingId,
                        decisionKind = "USER_LINK",
                        confidence = 1.0,
                        evidenceJson =
                            org.json
                                .JSONObject()
                                .apply {
                                    put("version", 1)
                                    put(
                                        "previousRecordingId",
                                        source?.recordingId ?: org.json.JSONObject.NULL,
                                    )
                                }
                                .toString(),
                        userConfirmed = true,
                        createdAtEpochMs = now,
                    )
                database.identityDao().insertDecision(decision)
                if (sourceReferenceId != null) {
                    check(
                        database
                            .sourceDao()
                            .reassignRecordingId(sourceReferenceId, targetRecordingId) == 1
                    ) {
                        "Identify source disappeared during confirmation"
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }

    override suspend fun undoIdentifyCandidate(
        sourceReferenceId: String,
        previousRecordingId: String,
    ): Boolean =
        try {
            database.withTransaction {
                database.identityDao().deleteDecisionsForSubject("SOURCE", sourceReferenceId)
                database.sourceDao().reassignRecordingId(sourceReferenceId, previousRecordingId)
            }
            true
        } catch (_: Throwable) {
            false
        }

    override suspend fun updateMetadataOverride(
        recordingId: String,
        fieldName: String,
        valueJson: String,
    ): Boolean =
        try {
            val now = System.currentTimeMillis()
            database
                .libraryDao()
                .upsertOverride(
                    app.shippy.data.db.entity.UserMetadataOverrideEntity(
                        recordingId = recordingId,
                        fieldName = fieldName,
                        valueJson = valueJson,
                        updatedAtEpochMs = now,
                    )
                )
            true
        } catch (_: Throwable) {
            false
        }

    override suspend fun revertMetadataOverride(recordingId: String, fieldName: String): Boolean =
        try {
            database.libraryDao().deleteOverride(recordingId, fieldName) >= 0
        } catch (_: Throwable) {
            false
        }

    override suspend fun getMetadataOverrides(recordingId: String): Map<String, String> =
        try {
            database.libraryDao().overridesFor(recordingId).associate {
                it.fieldName to it.valueJson
            }
        } catch (_: Throwable) {
            emptyMap()
        }

    override suspend fun getSong(recordingId: String): R16IdentifyCandidate? =
        try {
            val row = database.readModelDao().librarySong(recordingId)
            row?.let {
                R16IdentifyCandidate(
                    recordingId = it.recordingId,
                    title = it.title,
                    artist = it.artistDisplay,
                    album = it.releaseTitle,
                    artworkLocation = it.artworkLocation,
                    isExact = true,
                )
            }
        } catch (_: Throwable) {
            null
        }

    override suspend fun mergeRecordings(
        survivorRecordingId: String,
        retiredRecordingId: String,
    ): Boolean =
        try {
            if (survivorRecordingId == retiredRecordingId) return false
            database.withTransaction {
                database.recordingDao().get(survivorRecordingId) ?: return@withTransaction false
                val retired =
                    database.recordingDao().get(retiredRecordingId) ?: return@withTransaction false
                val retiredCredits = database.recordingDao().artistCredits(retiredRecordingId)
                val sources = database.sourceDao().forRecording(retiredRecordingId)
                val assets = database.assetDao().forRecording(retiredRecordingId)
                val retiredLibraryRel = database.libraryDao().relationship(retiredRecordingId)
                val survivorLibraryRel = database.libraryDao().relationship(survivorRecordingId)
                val playlistEntries = database.playlistDao().entriesForRecording(retiredRecordingId)
                val externalIds = database.recordingDao().externalIdentifiers(retiredRecordingId)
                val historyEntries = database.historyDao().allForRecording(retiredRecordingId)
                val retiredOverrides = database.libraryDao().overridesFor(retiredRecordingId)
                val survivorOverrides = database.libraryDao().overridesFor(survivorRecordingId)
                val survivorOverrideFields =
                    survivorOverrides.mapTo(mutableSetOf()) { it.fieldName }
                val movedOverrides =
                    retiredOverrides.filter { it.fieldName !in survivorOverrideFields }

                val movedReferences =
                    mutableSetOf<app.shippy.core.identitymatch.RecordingReferenceMove>()
                sources.forEach {
                    movedReferences.add(
                        app.shippy.core.identitymatch.RecordingReferenceMove(
                            app.shippy.core.identitymatch.RecordingReferenceKind.SOURCE_REFERENCE,
                            it.sourceReferenceId,
                        )
                    )
                }
                assets.forEach {
                    movedReferences.add(
                        app.shippy.core.identitymatch.RecordingReferenceMove(
                            app.shippy.core.identitymatch.RecordingReferenceKind.MEDIA_ASSET,
                            it.assetId,
                        )
                    )
                }
                if (retiredLibraryRel != null) {
                    movedReferences.add(
                        app.shippy.core.identitymatch.RecordingReferenceMove(
                            app.shippy.core.identitymatch.RecordingReferenceKind
                                .LIBRARY_RELATIONSHIP,
                            retiredRecordingId,
                        )
                    )
                }
                playlistEntries.forEach {
                    movedReferences.add(
                        app.shippy.core.identitymatch.RecordingReferenceMove(
                            app.shippy.core.identitymatch.RecordingReferenceKind.PLAYLIST_ENTRY,
                            it.playlistEntryId,
                        )
                    )
                }
                externalIds.forEach {
                    movedReferences.add(
                        app.shippy.core.identitymatch.RecordingReferenceMove(
                            app.shippy.core.identitymatch.RecordingReferenceKind
                                .EXTERNAL_IDENTIFIER,
                            it.externalIdentifierId,
                        )
                    )
                }
                historyEntries.forEach {
                    movedReferences.add(
                        app.shippy.core.identitymatch.RecordingReferenceMove(
                            app.shippy.core.identitymatch.RecordingReferenceKind.LISTENING_SESSION,
                            it.listeningSessionId,
                        )
                    )
                }
                movedOverrides.forEach {
                    movedReferences.add(
                        app.shippy.core.identitymatch.RecordingReferenceMove(
                            app.shippy.core.identitymatch.RecordingReferenceKind.USER_OVERRIDE,
                            it.fieldName,
                        )
                    )
                }

                val now = nowEpochMs()
                val snapshot =
                    org.json.JSONObject().apply {
                        put("version", 2)
                        put(
                            "retiredRecording",
                            org.json.JSONObject().apply {
                                put("recordingId", retired.recordingId)
                                put("canonicalTitle", retired.canonicalTitle)
                                put("durationMs", retired.durationMs ?: org.json.JSONObject.NULL)
                                put("versionKind", retired.versionKind)
                                put(
                                    "versionLabel",
                                    retired.versionLabel ?: org.json.JSONObject.NULL,
                                )
                                put("explicitness", retired.explicitness)
                                put(
                                    "preferredReleaseId",
                                    retired.preferredReleaseId ?: org.json.JSONObject.NULL,
                                )
                                put(
                                    "preferredArtworkId",
                                    retired.preferredArtworkId ?: org.json.JSONObject.NULL,
                                )
                                put("retentionKind", retired.retentionKind)
                                put(
                                    "retainedUntilEpochMs",
                                    retired.retainedUntilEpochMs ?: org.json.JSONObject.NULL,
                                )
                                put("createdAtEpochMs", retired.createdAtEpochMs)
                                put("updatedAtEpochMs", retired.updatedAtEpochMs)
                            },
                        )
                        put(
                            "retiredCredits",
                            org.json.JSONArray().apply {
                                retiredCredits.forEach { credit ->
                                    put(
                                        org.json.JSONObject().apply {
                                            put("recordingId", credit.recordingId)
                                            put("position", credit.position)
                                            put("artistId", credit.artistId)
                                            put("creditedName", credit.creditedName)
                                            put("joinPhrase", credit.joinPhrase)
                                        }
                                    )
                                }
                            },
                        )
                        put("sources", org.json.JSONArray(sources.map { it.sourceReferenceId }))
                        put("assets", org.json.JSONArray(assets.map { it.assetId }))
                        put(
                            "retiredLibraryRelationship",
                            libraryRelationshipJson(retiredLibraryRel),
                        )
                        put(
                            "survivorLibraryRelationship",
                            libraryRelationshipJson(survivorLibraryRel),
                        )
                        put(
                            "playlistEntries",
                            org.json.JSONArray(playlistEntries.map { it.playlistEntryId }),
                        )
                        put(
                            "externalIdentifiers",
                            org.json.JSONArray().apply {
                                externalIds.forEach { id ->
                                    put(
                                        org.json.JSONObject().apply {
                                            put("externalIdentifierId", id.externalIdentifierId)
                                            put("ownerType", id.ownerType)
                                            put("ownerId", id.ownerId)
                                            put("scheme", id.scheme)
                                            put("value", id.value)
                                            put("verified", id.verified)
                                            put(
                                                "sourceObservationId",
                                                id.sourceObservationId ?: org.json.JSONObject.NULL,
                                            )
                                            put("createdAtEpochMs", id.createdAtEpochMs)
                                        }
                                    )
                                }
                            },
                        )
                        put(
                            "historySessionIds",
                            org.json.JSONArray(historyEntries.map { it.listeningSessionId }),
                        )
                        put(
                            "userOverrides",
                            org.json.JSONArray().apply {
                                retiredOverrides.forEach { ov ->
                                    put(
                                        org.json.JSONObject().apply {
                                            put("recordingId", ov.recordingId)
                                            put("fieldName", ov.fieldName)
                                            put("valueJson", ov.valueJson)
                                            put("updatedAtEpochMs", ov.updatedAtEpochMs)
                                        }
                                    )
                                }
                            },
                        )
                        put(
                            "survivorUserOverrides",
                            org.json.JSONArray().apply {
                                survivorOverrides.forEach { ov ->
                                    put(
                                        org.json.JSONObject().apply {
                                            put("recordingId", ov.recordingId)
                                            put("fieldName", ov.fieldName)
                                            put("valueJson", ov.valueJson)
                                            put("updatedAtEpochMs", ov.updatedAtEpochMs)
                                        }
                                    )
                                }
                            },
                        )
                        put(
                            "movedUserOverrideFields",
                            org.json.JSONArray(movedOverrides.map { it.fieldName }),
                        )
                        put(
                            "movedReferences",
                            org.json.JSONArray().apply {
                                movedReferences.forEach { ref ->
                                    put(
                                        org.json.JSONObject().apply {
                                            put("kind", ref.kind.name)
                                            put("stableId", ref.stableId)
                                        }
                                    )
                                }
                            },
                        )
                    }

                if (sources.isNotEmpty()) {
                    check(
                        database
                            .sourceDao()
                            .reassignSourcesForRecording(
                                retiredRecordingId,
                                survivorRecordingId,
                                now,
                            ) == sources.size
                    ) {
                        "Sources changed during merge"
                    }
                }
                if (assets.isNotEmpty()) {
                    check(
                        database
                            .assetDao()
                            .reassignAssetsForRecording(
                                retiredRecordingId,
                                survivorRecordingId,
                                now,
                            ) == assets.size
                    ) {
                        "Assets changed during merge"
                    }
                }
                if (playlistEntries.isNotEmpty()) {
                    check(
                        database
                            .playlistDao()
                            .reassignEntriesForRecording(retiredRecordingId, survivorRecordingId) ==
                            playlistEntries.size
                    ) {
                        "Playlist entries changed during merge"
                    }
                }
                if (externalIds.isNotEmpty()) {
                    check(
                        database
                            .recordingDao()
                            .reassignExternalIdentifiers(retiredRecordingId, survivorRecordingId) ==
                            externalIds.size
                    ) {
                        "External identifiers changed during merge"
                    }
                }
                if (historyEntries.isNotEmpty()) {
                    check(
                        database
                            .historyDao()
                            .reassignHistoryForRecording(retiredRecordingId, survivorRecordingId) ==
                            historyEntries.size
                    ) {
                        "Listening history changed during merge"
                    }
                }
                if (movedOverrides.isNotEmpty()) {
                    movedOverrides.forEach { ov ->
                        check(
                            database
                                .libraryDao()
                                .reassignOverride(
                                    retiredRecordingId,
                                    survivorRecordingId,
                                    ov.fieldName,
                                ) == 1
                        ) {
                            "User override changed during merge"
                        }
                    }
                }
                if (retiredLibraryRel != null) {
                    if (survivorLibraryRel == null) {
                        database
                            .libraryDao()
                            .upsertRelationship(
                                retiredLibraryRel.copy(
                                    recordingId = survivorRecordingId,
                                    updatedAtEpochMs = now,
                                )
                            )
                    } else if (retiredLibraryRel.liked || retiredLibraryRel.explicitlySaved) {
                        database
                            .libraryDao()
                            .upsertRelationship(
                                survivorLibraryRel.copy(
                                    liked = survivorLibraryRel.liked || retiredLibraryRel.liked,
                                    explicitlySaved =
                                        survivorLibraryRel.explicitlySaved ||
                                            retiredLibraryRel.explicitlySaved,
                                    updatedAtEpochMs = now,
                                )
                            )
                    }
                    check(database.libraryDao().deleteRelationship(retiredRecordingId) == 1) {
                        "Retired library relationship changed during merge"
                    }
                }

                val mergeAuditId = "audit-${java.util.UUID.randomUUID()}"
                val redirect =
                    app.shippy.data.db.entity.EntityRedirectEntity(
                        oldRecordingId = retiredRecordingId,
                        canonicalRecordingId = survivorRecordingId,
                        mergeAuditId = mergeAuditId,
                        createdAtEpochMs = now,
                    )
                val audit =
                    app.shippy.data.db.entity.MergeAuditEntity(
                        mergeAuditId = mergeAuditId,
                        survivorRecordingId = survivorRecordingId,
                        mergedRecordingId = retiredRecordingId,
                        snapshotJson = snapshot.toString(),
                        userConfirmed = true,
                        createdAtEpochMs = now,
                        reversedAtEpochMs = null,
                    )
                check(database.identityDao().recordRedirect(redirect, audit)) {
                    "Failed to record merge redirect and audit"
                }

                check(database.recordingDao().deleteRecording(retiredRecordingId) == 1) {
                    "Retired recording disappeared during merge"
                }
                true
            }
        } catch (_: Throwable) {
            false
        }

    override suspend fun unmergeRecording(recordingId: String): Boolean =
        try {
            database.withTransaction {
                val now = nowEpochMs()
                val activeAudits = database.identityDao().activeMergeAuditsForRecording(recordingId)
                if (activeAudits.isEmpty()) return@withTransaction false

                for (audit in activeAudits) {
                    val snapshot = MergeAuditSnapshot.parse(audit.snapshotJson)
                    check(snapshot.retiredRecording.recordingId == audit.mergedRecordingId) {
                        "Merge audit has a mismatched retired recording"
                    }
                    check(database.recordingDao().get(audit.survivorRecordingId) != null) {
                        "Merge survivor is missing"
                    }
                    check(database.recordingDao().get(audit.mergedRecordingId) == null) {
                        "Merged recording unexpectedly still exists"
                    }
                    check(
                        database.identityDao().redirectFrom(audit.mergedRecordingId) ==
                            app.shippy.data.db.entity.EntityRedirectEntity(
                                oldRecordingId = audit.mergedRecordingId,
                                canonicalRecordingId = audit.survivorRecordingId,
                                mergeAuditId = audit.mergeAuditId,
                                createdAtEpochMs = audit.createdAtEpochMs,
                            )
                    ) {
                        "Merge redirect no longer matches its audit"
                    }

                    preflightUnmerge(audit, snapshot)

                    database.recordingDao().upsertRecording(snapshot.retiredRecording)
                    if (snapshot.retiredCredits.isNotEmpty()) {
                        database.recordingDao().insertCredits(snapshot.retiredCredits)
                    }
                    snapshot.sourceIds.forEach { sourceId ->
                        check(
                            database
                                .sourceDao()
                                .reassignRecordingIdPreservingIdentityStatus(
                                    sourceId,
                                    audit.mergedRecordingId,
                                    now,
                                ) == 1
                        ) {
                            "Source disappeared during unmerge"
                        }
                    }
                    snapshot.assetIds.forEach { assetId ->
                        check(
                            database
                                .assetDao()
                                .reassignAssetById(assetId, audit.mergedRecordingId, now) == 1
                        ) {
                            "Asset disappeared during unmerge"
                        }
                    }
                    snapshot.playlistEntryIds.forEach { entryId ->
                        check(
                            database
                                .playlistDao()
                                .reassignEntryRecordingId(entryId, audit.mergedRecordingId) == 1
                        ) {
                            "Playlist entry disappeared during unmerge"
                        }
                    }
                    snapshot.externalIdentifierIds.forEach { identifierId ->
                        check(
                            database
                                .recordingDao()
                                .reassignExternalIdentifierById(
                                    identifierId,
                                    audit.mergedRecordingId,
                                ) == 1
                        ) {
                            "External identifier disappeared during unmerge"
                        }
                    }
                    snapshot.historySessionIds.forEach { sessionId ->
                        check(
                            database
                                .historyDao()
                                .reassignHistorySessionRecordingId(
                                    sessionId,
                                    audit.mergedRecordingId,
                                ) == 1
                        ) {
                            "Listening session disappeared during unmerge"
                        }
                    }

                    restoreLibraryRelationship(
                        audit.survivorRecordingId,
                        snapshot.survivorLibraryRelationship,
                    )
                    restoreLibraryRelationship(
                        audit.mergedRecordingId,
                        snapshot.retiredLibraryRelationship,
                    )
                    restoreOverrides(audit.survivorRecordingId, snapshot.survivorOverrides)
                    restoreOverrides(audit.mergedRecordingId, snapshot.retiredOverrides)

                    check(database.identityDao().reverseRedirect(audit.mergeAuditId, now)) {
                        "Failed to reverse redirect for audit ${audit.mergeAuditId}"
                    }
                }
                true
            }
        } catch (_: Throwable) {
            false
        }

    override suspend fun latestIdentifyUndo(sourceReferenceId: String): R16IdentifyUndo? =
        try {
            database.withTransaction {
                val source =
                    database.sourceDao().get(sourceReferenceId) ?: return@withTransaction null
                val decision =
                    database.identityDao().decisionsFor("SOURCE", sourceReferenceId).firstOrNull {
                        it.decisionKind == "USER_LINK" &&
                            it.userConfirmed &&
                            it.targetRecordingId != null &&
                            it.previousRecordingIdOrNull() != null
                    } ?: return@withTransaction null
                val targetRecordingId = checkNotNull(decision.targetRecordingId)
                if (source.recordingId != targetRecordingId) return@withTransaction null
                R16IdentifyUndo(
                    sourceReferenceId = sourceReferenceId,
                    previousRecordingId = checkNotNull(decision.previousRecordingIdOrNull()),
                    targetRecordingId = targetRecordingId,
                )
            }
        } catch (_: Throwable) {
            null
        }

    override suspend fun undoLatestIdentifyCandidate(sourceReferenceId: String): Boolean =
        try {
            database.withTransaction {
                val source =
                    database.sourceDao().get(sourceReferenceId) ?: return@withTransaction false
                val decision =
                    database.identityDao().decisionsFor("SOURCE", sourceReferenceId).firstOrNull {
                        it.decisionKind == "USER_LINK" &&
                            it.userConfirmed &&
                            it.targetRecordingId != null &&
                            it.previousRecordingIdOrNull() != null
                    } ?: return@withTransaction false
                val previousRecordingId =
                    decision.previousRecordingIdOrNull() ?: return@withTransaction false
                if (source.recordingId != decision.targetRecordingId) return@withTransaction false
                check(database.recordingDao().get(previousRecordingId) != null) {
                    "Identify undo target no longer exists"
                }
                check(
                    database
                        .sourceDao()
                        .reassignRecordingId(sourceReferenceId, previousRecordingId) == 1
                ) {
                    "Identify source disappeared during undo"
                }
                check(database.identityDao().deleteDecision(decision.decisionId) == 1) {
                    "Identify decision disappeared during undo"
                }
                true
            }
        } catch (_: Throwable) {
            false
        }

    private suspend fun preflightUnmerge(
        audit: app.shippy.data.db.entity.MergeAuditEntity,
        snapshot: MergeAuditSnapshot,
    ) {
        check(snapshot.retiredCredits.all { it.recordingId == audit.mergedRecordingId }) {
            "Merge audit has invalid retired artist credits"
        }
        check(
            snapshot.retiredLibraryRelationship?.recordingId?.let { it == audit.mergedRecordingId }
                ?: true
        ) {
            "Merge audit has an invalid retired library relationship"
        }
        check(
            snapshot.survivorLibraryRelationship?.recordingId?.let {
                it == audit.survivorRecordingId
            } ?: true
        ) {
            "Merge audit has an invalid survivor library relationship"
        }
        check(snapshot.retiredOverrides.all { it.recordingId == audit.mergedRecordingId }) {
            "Merge audit has invalid retired metadata overrides"
        }
        check(snapshot.survivorOverrides.all { it.recordingId == audit.survivorRecordingId }) {
            "Merge audit has invalid survivor metadata overrides"
        }
        check(
            snapshot.retiredOverrides.map { it.fieldName }.toSet().size ==
                snapshot.retiredOverrides.size
        ) {
            "Merge audit has duplicate retired metadata overrides"
        }
        check(
            snapshot.survivorOverrides.map { it.fieldName }.toSet().size ==
                snapshot.survivorOverrides.size
        ) {
            "Merge audit has duplicate survivor metadata overrides"
        }
        check(
            listOf(
                    snapshot.sourceIds,
                    snapshot.assetIds,
                    snapshot.playlistEntryIds,
                    snapshot.externalIdentifierIds,
                    snapshot.historySessionIds,
                )
                .all { it.size == it.toSet().size }
        ) {
            "Merge audit contains duplicate reference IDs"
        }
        snapshot.sourceIds.forEach { sourceId ->
            check(database.sourceDao().get(sourceId)?.recordingId == audit.survivorRecordingId) {
                "Merge source no longer belongs to the survivor"
            }
        }
        snapshot.assetIds.forEach { assetId ->
            check(database.assetDao().get(assetId)?.recordingId == audit.survivorRecordingId) {
                "Merge asset no longer belongs to the survivor"
            }
        }
        snapshot.playlistEntryIds.forEach { entryId ->
            check(database.playlistDao().entry(entryId)?.recordingId == audit.survivorRecordingId) {
                "Merge playlist entry no longer belongs to the survivor"
            }
        }
        snapshot.externalIdentifierIds.forEach { identifierId ->
            val identifier = database.recordingDao().externalIdentifier(identifierId)
            check(
                identifier?.ownerType == "RECORDING" &&
                    identifier.ownerId == audit.survivorRecordingId
            ) {
                "Merge external identifier no longer belongs to the survivor"
            }
        }
        snapshot.historySessionIds.forEach { sessionId ->
            check(
                database.historyDao().session(sessionId)?.recordingId == audit.survivorRecordingId
            ) {
                "Merge listening session no longer belongs to the survivor"
            }
        }

        val expectedRelationship =
            mergedLibraryRelationship(
                survivor = snapshot.survivorLibraryRelationship,
                retired = snapshot.retiredLibraryRelationship,
                survivorRecordingId = audit.survivorRecordingId,
            )
        check(
            sameLibraryRelationship(
                database.libraryDao().relationship(audit.survivorRecordingId),
                expectedRelationship,
            )
        ) {
            "Survivor library relationship changed after merge"
        }

        val retiredByField = snapshot.retiredOverrides.associateBy { it.fieldName }
        check(snapshot.movedOverrideFields.all { it in retiredByField }) {
            "Merge audit lists an override that was not captured"
        }
        val expectedOverrides =
            snapshot.survivorOverrides.associateBy { it.fieldName }.toMutableMap()
        check(snapshot.movedOverrideFields.none { it in expectedOverrides }) {
            "Merge audit moved a conflicting survivor override"
        }
        snapshot.movedOverrideFields.forEach { fieldName ->
            expectedOverrides[fieldName] =
                checkNotNull(retiredByField[fieldName])
                    .copy(recordingId = audit.survivorRecordingId)
        }
        check(
            database.libraryDao().overridesFor(audit.survivorRecordingId).associateBy {
                it.fieldName
            } == expectedOverrides
        ) {
            "Survivor metadata overrides changed after merge"
        }
    }

    private suspend fun restoreLibraryRelationship(
        recordingId: String,
        relationship: LibraryRecordingEntity?,
    ) {
        if (relationship == null) {
            database.libraryDao().relationship(recordingId)?.let {
                check(database.libraryDao().deleteRelationship(recordingId) == 1) {
                    "Library relationship disappeared during restoration"
                }
            }
        } else {
            check(relationship.recordingId == recordingId) {
                "Merge audit has an invalid library relationship"
            }
            database.libraryDao().upsertRelationship(relationship)
        }
    }

    private suspend fun restoreOverrides(
        recordingId: String,
        overrides: List<app.shippy.data.db.entity.UserMetadataOverrideEntity>,
    ) {
        check(overrides.all { it.recordingId == recordingId }) {
            "Merge audit has an invalid metadata override"
        }
        database.libraryDao().deleteAllOverrides(recordingId)
        overrides.forEach { override ->
            database
                .libraryDao()
                .upsertOverride(
                    app.shippy.data.db.entity.UserMetadataOverrideEntity(
                        recordingId = recordingId,
                        fieldName = override.fieldName,
                        valueJson = override.valueJson,
                        updatedAtEpochMs = override.updatedAtEpochMs,
                    )
                )
        }
    }

    private suspend fun nextPlaylistLayoutOrderKey(
        library: app.shippy.data.db.dao.LibraryDao
    ): Long {
        val current = library.layoutEntries(PLAYLIST_LAYOUT_TARGET_TYPE)
        SparseOrderKey.between(current.lastOrNull()?.orderKey, null)?.let {
            return it
        }

        val rebalancedKeys = SparseOrderKey.rebalancedKeys(current.size)
        library.upsertLayout(
            current.mapIndexed { index, entry -> entry.copy(orderKey = rebalancedKeys[index]) }
        )
        return checkNotNull(SparseOrderKey.between(rebalancedKeys.lastOrNull(), null)) {
            "Unable to append a playlist Library layout entry"
        }
    }

    private suspend fun playlistStatus(playlistId: PlaylistId): PlaylistOwnershipStatus =
        if (database.playlistDao().get(playlistId.value) == null) {
            PlaylistOwnershipStatus.NOT_FOUND
        } else {
            PlaylistOwnershipStatus.MATERIALIZED
        }

    private companion object {
        const val PLAYLIST_LAYOUT_TARGET_TYPE = "PLAYLIST"
        const val ORIGIN_KIND_USER = "USER"
    }
}

private fun R16PlaylistEntryMoveDirection.toDaoDirection(): PlaylistAdjacentMoveDirection =
    when (this) {
        R16PlaylistEntryMoveDirection.TOWARD_START -> PlaylistAdjacentMoveDirection.TOWARD_START
        R16PlaylistEntryMoveDirection.TOWARD_END -> PlaylistAdjacentMoveDirection.TOWARD_END
    }

private enum class PlaylistOwnershipStatus {
    MATERIALIZED,
    NOT_FOUND,
}

private data class MergeAuditSnapshot(
    val retiredRecording: app.shippy.data.db.entity.RecordingEntity,
    val retiredCredits: List<app.shippy.data.db.entity.RecordingArtistCreditEntity>,
    val sourceIds: List<String>,
    val assetIds: List<String>,
    val playlistEntryIds: List<String>,
    val externalIdentifierIds: List<String>,
    val historySessionIds: List<String>,
    val retiredLibraryRelationship: LibraryRecordingEntity?,
    val survivorLibraryRelationship: LibraryRecordingEntity?,
    val retiredOverrides: List<app.shippy.data.db.entity.UserMetadataOverrideEntity>,
    val survivorOverrides: List<app.shippy.data.db.entity.UserMetadataOverrideEntity>,
    val movedOverrideFields: Set<String>,
) {
    companion object {
        fun parse(raw: String): MergeAuditSnapshot {
            val root = org.json.JSONObject(raw)
            check(root.optInt("version", 0) >= 2) {
                "Merge audit predates complete unmerge snapshots"
            }
            return MergeAuditSnapshot(
                retiredRecording = recording(root.getJSONObject("retiredRecording")),
                retiredCredits = credits(root.getJSONArray("retiredCredits")),
                sourceIds = stringList(root.getJSONArray("sources")),
                assetIds = stringList(root.getJSONArray("assets")),
                playlistEntryIds = stringList(root.getJSONArray("playlistEntries")),
                externalIdentifierIds = externalIds(root.getJSONArray("externalIdentifiers")),
                historySessionIds = stringList(root.getJSONArray("historySessionIds")),
                retiredLibraryRelationship = relationship(root, "retiredLibraryRelationship"),
                survivorLibraryRelationship = relationship(root, "survivorLibraryRelationship"),
                retiredOverrides = overrides(root.getJSONArray("userOverrides")),
                survivorOverrides = overrides(root.getJSONArray("survivorUserOverrides")),
                movedOverrideFields =
                    stringList(root.getJSONArray("movedUserOverrideFields")).toSet(),
            )
        }

        private fun recording(value: org.json.JSONObject) =
            app.shippy.data.db.entity.RecordingEntity(
                recordingId = value.getString("recordingId"),
                canonicalTitle = value.getString("canonicalTitle"),
                durationMs = value.longOrNull("durationMs"),
                versionKind = value.getString("versionKind"),
                versionLabel = value.stringOrNull("versionLabel"),
                explicitness = value.getString("explicitness"),
                preferredReleaseId = value.stringOrNull("preferredReleaseId"),
                preferredArtworkId = value.stringOrNull("preferredArtworkId"),
                retentionKind = value.getString("retentionKind"),
                retainedUntilEpochMs = value.longOrNull("retainedUntilEpochMs"),
                createdAtEpochMs = value.getLong("createdAtEpochMs"),
                updatedAtEpochMs = value.getLong("updatedAtEpochMs"),
            )

        private fun credits(
            value: org.json.JSONArray
        ): List<app.shippy.data.db.entity.RecordingArtistCreditEntity> = buildList {
            for (index in 0 until value.length()) {
                val credit = value.getJSONObject(index)
                add(
                    app.shippy.data.db.entity.RecordingArtistCreditEntity(
                        recordingId = credit.getString("recordingId"),
                        position = credit.getInt("position"),
                        artistId = credit.getString("artistId"),
                        creditedName = credit.getString("creditedName"),
                        joinPhrase = credit.getString("joinPhrase"),
                    )
                )
            }
        }

        private fun externalIds(value: org.json.JSONArray): List<String> = buildList {
            for (index in 0 until value.length()) {
                add(value.getJSONObject(index).getString("externalIdentifierId"))
            }
        }

        private fun overrides(
            value: org.json.JSONArray
        ): List<app.shippy.data.db.entity.UserMetadataOverrideEntity> = buildList {
            for (index in 0 until value.length()) {
                val override = value.getJSONObject(index)
                add(
                    app.shippy.data.db.entity.UserMetadataOverrideEntity(
                        recordingId = override.getString("recordingId"),
                        fieldName = override.getString("fieldName"),
                        valueJson = override.getString("valueJson"),
                        updatedAtEpochMs = override.getLong("updatedAtEpochMs"),
                    )
                )
            }
        }

        private fun relationship(root: org.json.JSONObject, key: String): LibraryRecordingEntity? {
            if (root.isNull(key)) return null
            val value = root.getJSONObject(key)
            return LibraryRecordingEntity(
                recordingId = value.getString("recordingId"),
                liked = value.getBoolean("liked"),
                explicitlySaved = value.getBoolean("explicitlySaved"),
                userEdited = value.getBoolean("userEdited"),
                manuallyIdentified = value.getBoolean("manuallyIdentified"),
                firstAddedAtEpochMs = value.longOrNull("firstAddedAtEpochMs"),
                updatedAtEpochMs = value.getLong("updatedAtEpochMs"),
            )
        }

        private fun stringList(value: org.json.JSONArray): List<String> = buildList {
            for (index in 0 until value.length()) add(value.getString(index))
        }

        private fun org.json.JSONObject.stringOrNull(key: String): String? =
            if (isNull(key)) null else getString(key)

        private fun org.json.JSONObject.longOrNull(key: String): Long? =
            if (isNull(key)) null else getLong(key)
    }
}

private fun libraryRelationshipJson(value: LibraryRecordingEntity?): Any =
    value?.let {
        org.json.JSONObject().apply {
            put("recordingId", it.recordingId)
            put("liked", it.liked)
            put("explicitlySaved", it.explicitlySaved)
            put("userEdited", it.userEdited)
            put("manuallyIdentified", it.manuallyIdentified)
            put("firstAddedAtEpochMs", it.firstAddedAtEpochMs ?: org.json.JSONObject.NULL)
            put("updatedAtEpochMs", it.updatedAtEpochMs)
        }
    } ?: org.json.JSONObject.NULL

private fun mergedLibraryRelationship(
    survivor: LibraryRecordingEntity?,
    retired: LibraryRecordingEntity?,
    survivorRecordingId: String,
): LibraryRecordingEntity? =
    when {
        retired == null -> survivor
        survivor == null -> retired.copy(recordingId = survivorRecordingId)
        else ->
            survivor.copy(
                liked = survivor.liked || retired.liked,
                explicitlySaved = survivor.explicitlySaved || retired.explicitlySaved,
            )
    }

private fun sameLibraryRelationship(
    left: LibraryRecordingEntity?,
    right: LibraryRecordingEntity?,
): Boolean =
    if (left == null || right == null) {
        left == right
    } else {
        left.recordingId == right.recordingId &&
            left.liked == right.liked &&
            left.explicitlySaved == right.explicitlySaved &&
            left.userEdited == right.userEdited &&
            left.manuallyIdentified == right.manuallyIdentified &&
            left.firstAddedAtEpochMs == right.firstAddedAtEpochMs
    }

private fun app.shippy.data.db.entity.IdentityDecisionEntity.previousRecordingIdOrNull(): String? =
    runCatching {
            org.json.JSONObject(evidenceJson).let { evidence ->
                if (evidence.isNull("previousRecordingId")) null
                else evidence.optString("previousRecordingId").takeIf(String::isNotBlank)
            }
        }
        .getOrNull()
