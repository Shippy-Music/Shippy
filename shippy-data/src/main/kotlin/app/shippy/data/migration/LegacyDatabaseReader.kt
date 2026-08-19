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

private fun Cursor.stringOrNull(column: Int): String? =
    if (isNull(column)) null else getString(column)

private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

private fun Cursor.intOrNull(column: Int): Int? = if (isNull(column)) null else getInt(column)

private fun Cursor.booleanOrNull(column: Int): Boolean? =
    if (isNull(column)) null else getInt(column) != 0

internal const val LEGACY_SCHEMA_VERSION = 10
private const val MAX_PAGE_SIZE = 500
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
