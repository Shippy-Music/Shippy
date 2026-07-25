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
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobDao
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.RoomDownloadJobRepository

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
            .addMigrations(ShippyDatabase.MIGRATION_1_2, ShippyDatabase.MIGRATION_2_3)
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
    @Singleton
    internal fun downloadJobRepository(
        repository: RoomDownloadJobRepository
    ): DownloadJobRepository = repository
}
