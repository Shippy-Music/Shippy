/*
 * Copyright (c) 2026 Auxio Project
 * EnrichmentCoordinator.kt is part of Auxio.
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
package app.shippy.sources.enrichment

import app.shippy.core.identity.RecordingId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

enum class EnrichmentTrigger {
    PLAYBACK_COMMITTED,
    DURABLE_INTENT,
    IDLE_MAINTENANCE,
}

data class EnrichmentWork(
    val recordingId: RecordingId,
    val trigger: EnrichmentTrigger,
    val uniqueName: String = uniqueEnrichmentWorkName(recordingId),
    val requiresUnmeteredNetwork: Boolean = trigger == EnrichmentTrigger.IDLE_MAINTENANCE,
    val replaceExisting: Boolean = trigger == EnrichmentTrigger.DURABLE_INTENT,
)

enum class EnrichmentWorkState {
    ENQUEUED,
    RUNNING,
    SUCCEEDED,
    RETRYING,
    FAILED,
    CANCELLED,
}

interface EnrichmentWorkScheduler {
    suspend fun enqueue(work: EnrichmentWork)

    suspend fun cancel(recordingId: RecordingId)

    fun observe(recordingId: RecordingId): Flow<EnrichmentWorkState>
}

/**
 * Event boundary for deferrable cross-provider enrichment. Search rendering and manual Identify do
 * not enter this background path; playback may call it only after the player has committed.
 */
class EnrichmentCoordinator(private val scheduler: EnrichmentWorkScheduler) {
    suspend fun afterPlaybackCommit(recordingId: RecordingId) {
        scheduler.enqueue(EnrichmentWork(recordingId, EnrichmentTrigger.PLAYBACK_COMMITTED))
    }

    suspend fun afterDurableIntent(recordingId: RecordingId) {
        scheduler.enqueue(EnrichmentWork(recordingId, EnrichmentTrigger.DURABLE_INTENT))
    }

    suspend fun scheduleIdle(
        recordingIds: List<RecordingId>,
        limit: Int = DEFAULT_IDLE_BATCH_SIZE,
    ) {
        require(limit in 1..MAX_IDLE_BATCH_SIZE) {
            "Idle enrichment batch size must be between 1 and $MAX_IDLE_BATCH_SIZE"
        }
        recordingIds.distinct().sortedBy(RecordingId::value).take(limit).forEach { recordingId ->
            currentCoroutineContext().ensureActive()
            scheduler.enqueue(EnrichmentWork(recordingId, EnrichmentTrigger.IDLE_MAINTENANCE))
        }
    }

    suspend fun cancel(recordingId: RecordingId) {
        scheduler.cancel(recordingId)
    }

    fun observe(recordingId: RecordingId): Flow<EnrichmentWorkState> =
        scheduler.observe(recordingId)
}

fun uniqueEnrichmentWorkName(recordingId: RecordingId): String =
    "enrich-recording:${recordingId.value}"

private const val DEFAULT_IDLE_BATCH_SIZE = 20
private const val MAX_IDLE_BATCH_SIZE = 50
