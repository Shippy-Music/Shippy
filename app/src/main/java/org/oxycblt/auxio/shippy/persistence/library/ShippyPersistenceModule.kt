/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyPersistenceModule.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.library

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointDao
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointRepository
import org.oxycblt.auxio.shippy.persistence.crew.RoomCrewCheckpointRepository
import org.oxycblt.auxio.shippy.persistence.crew.AndroidKeystoreCrewRejoinLeaseStore
import org.oxycblt.auxio.shippy.persistence.crew.CrewRejoinLeaseStore
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobDao
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.RoomDownloadJobRepository
import org.oxycblt.auxio.shippy.lyrics.LyricsCacheDao

@Module
@InstallIn(SingletonComponent::class)
object ShippyPersistenceModule {
    @Provides
    @Singleton
    internal fun database(@ApplicationContext context: Context): ShippyDatabase =
        Room.databaseBuilder(
                context.applicationContext,
                ShippyDatabase::class.java,
                "shippy.db",
            )
            .addMigrations(
                ShippyDatabase.MIGRATION_1_2,
                ShippyDatabase.MIGRATION_2_3,
                ShippyDatabase.MIGRATION_3_4,
                ShippyDatabase.MIGRATION_4_5,
                ShippyDatabase.MIGRATION_5_6,
            )
            .build()

    @Provides
    internal fun libraryRelationshipDao(database: ShippyDatabase): LibraryRelationshipDao =
        database.libraryRelationshipDao()

    @Provides
    @Singleton
    internal fun libraryRelationshipRepository(
        repository: RoomLibraryRelationshipRepository
    ): LibraryRelationshipRepository = repository

    @Provides
    internal fun downloadJobDao(database: ShippyDatabase): DownloadJobDao =
        database.downloadJobDao()

    @Provides
    internal fun canonicalTrackMetadataDao(database: ShippyDatabase): CanonicalTrackMetadataDao =
        database.canonicalTrackMetadataDao()

    @Provides
    @Singleton
    internal fun canonicalTrackMetadataRepository(
        repository: RoomCanonicalTrackMetadataRepository
    ): CanonicalTrackMetadataRepository = repository

    @Provides
    internal fun lyricsCacheDao(database: ShippyDatabase): LyricsCacheDao =
        database.lyricsCacheDao()

    @Provides
    internal fun crewCheckpointDao(database: ShippyDatabase): CrewCheckpointDao =
        database.crewCheckpointDao()

    @Provides
    @Singleton
    internal fun crewCheckpointRepository(
        repository: RoomCrewCheckpointRepository
    ): CrewCheckpointRepository = repository

    @Provides
    @Singleton
    internal fun crewRejoinLeaseStore(
        store: AndroidKeystoreCrewRejoinLeaseStore
    ): CrewRejoinLeaseStore = store

    @Provides
    @Singleton
    internal fun downloadJobRepository(
        repository: RoomDownloadJobRepository
    ): DownloadJobRepository = repository
}
