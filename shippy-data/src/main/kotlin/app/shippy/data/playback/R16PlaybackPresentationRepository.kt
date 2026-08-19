/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackPresentationRepository.kt is part of Auxio.
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

import app.shippy.core.identity.RecordingId
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.view.LibrarySongRowView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Canonical metadata needed to present playback outside the app UI. */
data class R16RecordingPresentation(
    val recordingId: RecordingId,
    val title: String,
    val artist: String,
    val releaseTitle: String?,
    val artworkLocation: String?,
    val durationMs: Long?,
)

interface R16PlaybackPresentationRepository {
    /** Observes only the requested recordings and keys every result by durable identity. */
    fun observe(recordingIds: Set<RecordingId>): Flow<Map<RecordingId, R16RecordingPresentation>>
}

internal class RoomR16PlaybackPresentationRepository(private val database: ShippyR16Database) :
    R16PlaybackPresentationRepository {
    override fun observe(
        recordingIds: Set<RecordingId>
    ): Flow<Map<RecordingId, R16RecordingPresentation>> {
        if (recordingIds.isEmpty()) return flowOf(emptyMap())
        val batches =
            recordingIds.map(RecordingId::value).sorted().chunked(SQLITE_BIND_BATCH_SIZE).map {
                batch ->
                database.readModelDao().observeLibrarySongs(batch.toSet())
            }
        return combine(batches) { batchRows ->
            batchRows
                .flatMap { it }
                .associate { row -> row.toPresentation().let { it.recordingId to it } }
        }
    }
}

private fun LibrarySongRowView.toPresentation() =
    R16RecordingPresentation(
        recordingId = RecordingId(recordingId),
        title = title,
        artist = artistDisplay,
        releaseTitle = releaseTitle,
        artworkLocation = artworkLocation,
        durationMs = durationMs,
    )

private const val SQLITE_BIND_BATCH_SIZE = 900
