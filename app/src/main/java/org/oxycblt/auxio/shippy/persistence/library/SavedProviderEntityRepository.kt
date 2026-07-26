/*
 * Copyright (c) 2026 Shippy contributors
 * SavedProviderEntityRepository.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.library

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntityType

/** A user-saved provider browse target, including local save state. */
data class SavedProviderEntity(
    val entity: ProviderEntity,
    val isPinned: Boolean,
    val savedAtEpochMs: Long,
) {
    init {
        require(savedAtEpochMs >= 0) { "Saved provider entity time cannot be negative" }
    }
}

interface SavedProviderEntityRepository {
    fun observeAll(): Flow<List<SavedProviderEntity>>

    fun observe(entity: ProviderEntity): Flow<SavedProviderEntity?>

    /** Saves new metadata or refreshes it without changing an existing pin or save time. */
    suspend fun save(entity: ProviderEntity, savedAtEpochMs: Long = System.currentTimeMillis())

    /** Refreshes presentation metadata only when this exact entity is already saved. */
    suspend fun refreshIfSaved(entity: ProviderEntity)

    suspend fun remove(entity: ProviderEntity)

    suspend fun setPinned(entity: ProviderEntity, pinned: Boolean)
}

@Entity(
    tableName = "saved_provider_entity",
    primaryKeys = ["providerId", "entityType", "sourceItemId"],
    indices = [Index(value = ["pinned", "savedAtEpochMs"])],
)
internal data class SavedProviderEntityRecord(
    val providerId: String,
    val entityType: String,
    val sourceItemId: String,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    val originalUrl: String?,
    val pinned: Boolean,
    val savedAtEpochMs: Long,
)

@Dao
internal abstract class SavedProviderEntityDao {
    @Query(
        "SELECT * FROM saved_provider_entity " +
            "ORDER BY pinned DESC, savedAtEpochMs DESC, providerId, entityType, sourceItemId"
    )
    abstract fun observeAll(): Flow<List<SavedProviderEntityRecord>>

    @Query(
        "SELECT * FROM saved_provider_entity " +
            "WHERE providerId = :providerId AND entityType = :entityType AND sourceItemId = :sourceItemId"
    )
    abstract fun observe(
        providerId: String,
        entityType: String,
        sourceItemId: String,
    ): Flow<SavedProviderEntityRecord?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(record: SavedProviderEntityRecord)

    @Query(
        "UPDATE saved_provider_entity SET title = :title, subtitle = :subtitle, artwork = :artwork, " +
            "originalUrl = :originalUrl WHERE providerId = :providerId AND entityType = :entityType " +
            "AND sourceItemId = :sourceItemId"
    )
    protected abstract suspend fun updateMetadata(
        providerId: String,
        entityType: String,
        sourceItemId: String,
        title: String,
        subtitle: String?,
        artwork: String?,
        originalUrl: String?,
    ): Int

    @Query(
        "DELETE FROM saved_provider_entity WHERE providerId = :providerId AND entityType = :entityType " +
            "AND sourceItemId = :sourceItemId"
    )
    abstract suspend fun remove(providerId: String, entityType: String, sourceItemId: String): Int

    @Query(
        "UPDATE saved_provider_entity SET pinned = :pinned WHERE providerId = :providerId " +
            "AND entityType = :entityType AND sourceItemId = :sourceItemId"
    )
    protected abstract suspend fun updatePinned(
        providerId: String,
        entityType: String,
        sourceItemId: String,
        pinned: Boolean,
    ): Int

    @Transaction
    open suspend fun save(record: SavedProviderEntityRecord) {
        if (!refreshIfPresent(record)) {
            insert(record)
        }
    }

    @Transaction
    open suspend fun refreshIfPresent(record: SavedProviderEntityRecord): Boolean =
        updateMetadata(
            record.providerId,
            record.entityType,
            record.sourceItemId,
            record.title,
            record.subtitle,
            record.artwork,
            record.originalUrl,
        ) == 1

    @Transaction
    open suspend fun setPinned(
        providerId: String,
        entityType: String,
        sourceItemId: String,
        pinned: Boolean,
    ) {
        check(updatePinned(providerId, entityType, sourceItemId, pinned) == 1) {
            "Saved provider entity does not exist"
        }
    }
}

@Singleton
internal class RoomSavedProviderEntityRepository
@Inject
constructor(private val dao: SavedProviderEntityDao) : SavedProviderEntityRepository {
    override fun observeAll(): Flow<List<SavedProviderEntity>> =
        dao.observeAll().map { records -> records.map(SavedProviderEntityRecord::toDomain) }

    override fun observe(entity: ProviderEntity): Flow<SavedProviderEntity?> =
        dao.observe(entity.providerId.value, entity.type.name, entity.sourceItemId)
            .map { record -> record?.toDomain() }

    override suspend fun save(entity: ProviderEntity, savedAtEpochMs: Long) {
        require(savedAtEpochMs >= 0) { "Saved provider entity time cannot be negative" }
        dao.save(entity.toRecord(savedAtEpochMs))
    }

    override suspend fun refreshIfSaved(entity: ProviderEntity) {
        dao.refreshIfPresent(entity.toRecord(savedAtEpochMs = 0))
    }

    override suspend fun remove(entity: ProviderEntity) {
        dao.remove(entity.providerId.value, entity.type.name, entity.sourceItemId)
    }

    override suspend fun setPinned(entity: ProviderEntity, pinned: Boolean) {
        dao.setPinned(entity.providerId.value, entity.type.name, entity.sourceItemId, pinned)
    }
}

internal fun ProviderEntity.toRecord(savedAtEpochMs: Long, pinned: Boolean = false) =
    SavedProviderEntityRecord(
        providerId.value,
        type.name,
        sourceItemId,
        title,
        subtitle,
        artwork,
        originalUrl,
        pinned,
        savedAtEpochMs,
    )

internal fun SavedProviderEntityRecord.toDomain() =
    SavedProviderEntity(
        ProviderEntity(
            ProviderId(providerId),
            sourceItemId,
            ProviderEntityType.valueOf(entityType),
            title,
            subtitle,
            artwork,
            originalUrl,
        ),
        pinned,
        savedAtEpochMs,
    )
