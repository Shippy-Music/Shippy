/*
 * Copyright (c) 2026 Auxio Project
 * SourceRepository.kt is part of Auxio.
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
package app.shippy.sources.repository

import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.source.AvailabilitySnapshot
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceReference
import app.shippy.sources.observation.SourceTrackObservation
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Persistent source authority. Provider discovery remains a separate read-only concern. */
interface SourceRepository {
    fun observe(recordingId: RecordingId): Flow<List<SourceReference>>

    suspend fun exact(key: SourceKey): SourceReference?

    suspend fun upsert(observation: SourceTrackObservation): SourceReferenceId

    suspend fun updateAvailability(update: SourceAvailabilityUpdate)
}

data class SourceAvailabilityUpdate(
    val key: SourceKey,
    val availability: AvailabilitySnapshot,
    val updatedAt: Instant,
) {
    init {
        require(availability.checkedAt == null || updatedAt >= availability.checkedAt) {
            "Availability update cannot precede its check"
        }
    }
}
