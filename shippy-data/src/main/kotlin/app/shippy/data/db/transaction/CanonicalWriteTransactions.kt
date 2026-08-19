/*
 * Copyright (c) 2026 Auxio Project
 * CanonicalWriteTransactions.kt is part of Auxio.
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
package app.shippy.data.db.transaction

import androidx.room.withTransaction
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.MetadataObservationEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.entity.SourceReferenceEntity

internal class CanonicalWriteTransactions(private val database: ShippyR16Database) {
    suspend fun upsertRecordingGraph(
        recording: RecordingEntity,
        artists: List<ArtistEntity>,
        credits: List<RecordingArtistCreditEntity>,
    ) {
        database.withTransaction {
            database.recordingDao().upsertRecordingGraph(recording, artists, credits)
            database.searchDao().refresh(recording.recordingId)
        }
    }

    suspend fun ingestExactSource(
        observation: MetadataObservationEntity,
        source: SourceReferenceEntity,
    ): SourceReferenceEntity =
        database.withTransaction {
            val stored = database.sourceDao().ingestExact(observation, source)
            stored.recordingId?.let { database.searchDao().refresh(it) }
            stored
        }
}
