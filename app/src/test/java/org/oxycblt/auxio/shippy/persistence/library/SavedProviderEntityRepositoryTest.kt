/*
 * Copyright (c) 2026 Shippy contributors
 * SavedProviderEntityRepositoryTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntityType

class SavedProviderEntityRepositoryTest {
    @Test
    fun `record mapping retains provider browse metadata and local save state`() {
        val entity =
            ProviderEntity(
                ProviderId("jiosaavn"),
                "fixture-album",
                ProviderEntityType.ALBUM,
                "Fixture Album",
                subtitle = "Fixture Artist",
                artwork = "https://example.test/art.jpg",
                originalUrl = "https://example.test/album/fixture-album",
            )

        val record = entity.toRecord(savedAtEpochMs = 123, pinned = false)
        val saved = record.toDomain()

        assertEquals(entity, saved.entity)
        assertFalse(saved.isPinned)
        assertEquals(123L, saved.savedAtEpochMs)
    }

    @Test
    fun `metadata refresh retains the existing pin and save time`() = runBlocking {
        val dao = FakeDao()
        val repository = RoomSavedProviderEntityRepository(dao)
        val original = entity(title = "Original")

        repository.save(original, savedAtEpochMs = 123)
        repository.setPinned(original, pinned = true)
        repository.refreshIfSaved(entity(title = "Refreshed"))

        val saved = dao.single().toDomain()
        assertEquals("Refreshed", saved.entity.title)
        assertEquals(123L, saved.savedAtEpochMs)
        assertEquals(true, saved.isPinned)
    }

    @Test
    fun `metadata refresh does not silently save an unsaved entity`() = runBlocking {
        val dao = FakeDao()
        val repository = RoomSavedProviderEntityRepository(dao)

        repository.refreshIfSaved(entity(title = "Not saved"))

        assertEquals(emptyList<SavedProviderEntityRecord>(), dao.all())
    }

    private fun entity(title: String) =
        ProviderEntity(
            ProviderId("jiosaavn"),
            "fixture-album",
            ProviderEntityType.ALBUM,
            title,
            originalUrl = "https://example.test/album/fixture-album",
        )

    private class FakeDao : SavedProviderEntityDao() {
        private val records = MutableStateFlow<List<SavedProviderEntityRecord>>(emptyList())

        override fun observeAll(): Flow<List<SavedProviderEntityRecord>> = records

        override fun observe(
            providerId: String,
            entityType: String,
            sourceItemId: String,
        ): Flow<SavedProviderEntityRecord?> =
            records.map { all -> all.firstOrNull { it.matches(providerId, entityType, sourceItemId) } }

        override suspend fun insert(record: SavedProviderEntityRecord) {
            check(records.value.none { it.matches(record.providerId, record.entityType, record.sourceItemId) })
            records.value += record
        }

        override suspend fun updateMetadata(
            providerId: String,
            entityType: String,
            sourceItemId: String,
            title: String,
            subtitle: String?,
            artwork: String?,
            originalUrl: String?,
        ): Int {
            val index = records.value.indexOfFirst { it.matches(providerId, entityType, sourceItemId) }
            if (index < 0) return 0
            records.value = records.value.toMutableList().also { current ->
                current[index] = current[index].copy(title = title, subtitle = subtitle, artwork = artwork, originalUrl = originalUrl)
            }
            return 1
        }

        override suspend fun remove(providerId: String, entityType: String, sourceItemId: String): Int {
            val current = records.value
            records.value = current.filterNot { it.matches(providerId, entityType, sourceItemId) }
            return current.size - records.value.size
        }

        override suspend fun updatePinned(
            providerId: String,
            entityType: String,
            sourceItemId: String,
            pinned: Boolean,
        ): Int {
            val index = records.value.indexOfFirst { it.matches(providerId, entityType, sourceItemId) }
            if (index < 0) return 0
            records.value = records.value.toMutableList().also { current -> current[index] = current[index].copy(pinned = pinned) }
            return 1
        }

        fun single() = records.value.single()

        fun all() = records.value
    }
}

private fun SavedProviderEntityRecord.matches(
    providerId: String,
    entityType: String,
    sourceItemId: String,
) =
    this.providerId == providerId && this.entityType == entityType && this.sourceItemId == sourceItemId
