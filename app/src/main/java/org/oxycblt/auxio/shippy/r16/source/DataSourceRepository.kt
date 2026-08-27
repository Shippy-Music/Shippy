/*
 * Copyright (c) 2026 Auxio Project
 * DataSourceRepository.kt is part of Auxio.
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
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.core.source.SourceReference
import app.shippy.data.ingest.R16IngestionRepository
import app.shippy.data.source.R16SourceStateRepository
import app.shippy.sources.ingest.RecordingIngestor
import app.shippy.sources.observation.SourceTrackObservation
import app.shippy.sources.repository.SourceAvailabilityUpdate
import app.shippy.sources.repository.SourceRepository
import kotlinx.coroutines.flow.Flow

/**
 * Composes source ingestion with Room state without making either pure module depend on the other.
 */
class DataSourceRepository(
    private val state: R16SourceStateRepository,
    ingestion: R16IngestionRepository,
) : SourceRepository {
    private val ingestor = RecordingIngestor(DataRecordingIngestionStore(ingestion))
    private val exactProviderIngestor =
        RecordingIngestor(DataRecordingIngestionStore(ingestion, identityMatchingEnabled = false))

    override fun observe(recordingId: RecordingId): Flow<List<SourceReference>> =
        state.observe(recordingId)

    override suspend fun exact(key: SourceKey): SourceReference? = state.exact(key)

    override suspend fun upsert(observation: SourceTrackObservation): SourceReferenceId {
        ingestor.ingest(observation)
        return checkNotNull(state.exact(observation.sourceKey)).id
    }

    /**
     * Persists an exact provider source without metadata-only cross-provider auto-linking. Existing
     * identical source keys remain idempotent. New provider keys stay separate until a later
     * explicit identity-confirmation flow links them.
     */
    suspend fun upsertExactProvider(observation: SourceTrackObservation): SourceReferenceId {
        require(
            observation.sourceKind !in setOf(SourceKind.LOCAL_FILE, SourceKind.SHIPPY_DOWNLOAD)
        ) {
            "Exact provider ingestion requires a provider observation"
        }
        exactProviderIngestor.ingest(observation)
        return checkNotNull(state.exact(observation.sourceKey)).id
    }

    override suspend fun updateAvailability(update: SourceAvailabilityUpdate) {
        state.updateAvailability(update.key, update.availability, update.updatedAt)
    }
}
