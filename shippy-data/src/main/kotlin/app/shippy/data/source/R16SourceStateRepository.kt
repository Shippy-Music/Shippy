/*
 * Copyright (c) 2026 Auxio Project
 * R16SourceStateRepository.kt is part of Auxio.
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
package app.shippy.data.source

import androidx.room.withTransaction
import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.source.AvailabilityFailure
import app.shippy.core.source.AvailabilitySnapshot
import app.shippy.core.source.AvailabilityState
import app.shippy.core.source.IdentityStatus
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.core.source.SourceReference
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.SourceReferenceEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface R16SourceStateRepository {
    fun observe(recordingId: RecordingId): Flow<List<SourceReference>>

    suspend fun get(sourceReferenceId: SourceReferenceId): SourceReference?

    suspend fun exact(key: SourceKey): SourceReference?

    suspend fun updateAvailability(
        key: SourceKey,
        availability: AvailabilitySnapshot,
        updatedAt: Instant,
    )
}

internal class RoomR16SourceStateRepository(private val database: ShippyR16Database) :
    R16SourceStateRepository {
    override fun observe(recordingId: RecordingId): Flow<List<SourceReference>> =
        database.sourceDao().observeForRecording(recordingId.value).map { sources ->
            sources.map(SourceReferenceEntity::toDomain)
        }

    override suspend fun get(sourceReferenceId: SourceReferenceId): SourceReference? =
        database.sourceDao().get(sourceReferenceId.value)?.toDomain()

    override suspend fun exact(key: SourceKey): SourceReference? =
        database
            .sourceDao()
            .exact(key.providerId.value, key.itemType.name, key.sourceItemId)
            ?.toDomain()

    override suspend fun updateAvailability(
        key: SourceKey,
        availability: AvailabilitySnapshot,
        updatedAt: Instant,
    ) {
        require(availability.checkedAt == null || updatedAt >= availability.checkedAt) {
            "Availability update cannot precede its check"
        }
        database.withTransaction {
            check(
                database
                    .sourceDao()
                    .updateAvailability(
                        providerId = key.providerId.value,
                        itemType = key.itemType.name,
                        sourceItemId = key.sourceItemId,
                        availabilityState = availability.state.name,
                        checkedAtEpochMs = availability.checkedAt?.toEpochMilli(),
                        expiresAtEpochMs = availability.expiresAt?.toEpochMilli(),
                        failureKind = availability.failure?.code,
                        failureRetryable = availability.failure?.retryable,
                        updatedAtEpochMs = updatedAt.toEpochMilli(),
                    ) == 1
            ) {
                "Source availability target is missing"
            }
        }
    }
}

internal fun SourceReferenceEntity.toDomain() =
    SourceReference(
        id = SourceReferenceId(sourceReferenceId),
        recordingId = recordingId?.let(::RecordingId),
        source =
            SourceKey(
                providerId = ProviderId(providerId),
                itemType = SourceItemType.valueOf(itemType),
                sourceItemId = sourceItemId,
            ),
        kind = SourceKind.valueOf(sourceKind),
        originalUrl = originalUrl,
        availability =
            AvailabilitySnapshot(
                state = AvailabilityState.valueOf(availabilityState),
                checkedAt = availabilityCheckedAtEpochMs?.let(Instant::ofEpochMilli),
                expiresAt = availabilityExpiresAtEpochMs?.let(Instant::ofEpochMilli),
                failure =
                    failureKind?.let { kind ->
                        AvailabilityFailure(kind, retryable = failureRetryable == true)
                    },
            ),
        rawMetadataId = rawMetadataObservationId,
        identityStatus = IdentityStatus.valueOf(identityStatus),
    )
