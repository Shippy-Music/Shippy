/*
 * Copyright (c) 2026 Auxio Project
 * R16DataRuntime.kt is part of Auxio.
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
package app.shippy.data

import android.content.Context
import androidx.room.Room
import app.shippy.data.backup.R16BackupRuntime
import app.shippy.data.browser.R16MediaBrowserRepository
import app.shippy.data.browser.RoomR16MediaBrowserRepository
import app.shippy.data.db.R16LibraryMembershipTriggers
import app.shippy.data.db.R16PlaylistSearchTriggers
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.ShippyR16DatabaseMigrations
import app.shippy.data.home.R16HomeReadRepository
import app.shippy.data.home.RoomR16HomeReadRepository
import app.shippy.data.ingest.R16IngestionRepository
import app.shippy.data.ingest.RoomR16IngestionRepository
import app.shippy.data.lastfm.R16LastFmOutboxRepository
import app.shippy.data.lastfm.RoomR16LastFmOutboxRepository
import app.shippy.data.library.R16LibraryMutationRepository
import app.shippy.data.library.R16LibraryReadRepository
import app.shippy.data.library.RoomR16LibraryMutationRepository
import app.shippy.data.library.RoomR16LibraryReadRepository
import app.shippy.data.listening.R16ListeningSessionRepository
import app.shippy.data.listening.RoomR16ListeningSessionRepository
import app.shippy.data.maintenance.R16CatalogueMaintenance
import app.shippy.data.maintenance.RoomR16CatalogueMaintenance
import app.shippy.data.migration.R16DevicePlaylistImportRepository
import app.shippy.data.migration.R16LocalReindexAudit
import app.shippy.data.migration.RoomR16DevicePlaylistImportRepository
import app.shippy.data.migration.RoomR16LocalReindexAudit
import app.shippy.data.offline.R16OfflineRepository
import app.shippy.data.offline.RoomR16OfflineRepository
import app.shippy.data.playback.R16PlaybackCheckpointRepository
import app.shippy.data.playback.R16PlaybackPresentationRepository
import app.shippy.data.playback.R16PlaybackSourceRepository
import app.shippy.data.playback.RoomR16PlaybackCheckpointRepository
import app.shippy.data.playback.RoomR16PlaybackPresentationRepository
import app.shippy.data.playback.RoomR16PlaybackSourceRepository
import app.shippy.data.source.R16SourceStateRepository
import app.shippy.data.source.RoomR16SourceStateRepository
import java.io.Closeable

/** Explicitly opened R16 data owner; constructing it does not change active app authority. */
class R16DataRuntime private constructor(private val database: ShippyR16Database) : Closeable {
    /** Internal bridge for data-layer facades that must share this exact Room instance. */
    internal val roomDatabase: ShippyR16Database
        get() = database

    val ingestion: R16IngestionRepository = RoomR16IngestionRepository(database)
    val backups: R16BackupRuntime = R16BackupRuntime(database)
    val libraryReadModels: R16LibraryReadRepository = RoomR16LibraryReadRepository(database)
    val libraryMutations: R16LibraryMutationRepository = RoomR16LibraryMutationRepository(database)
    val mediaBrowser: R16MediaBrowserRepository = RoomR16MediaBrowserRepository(database)
    val localReindexAudit: R16LocalReindexAudit = RoomR16LocalReindexAudit(database)
    val devicePlaylists: R16DevicePlaylistImportRepository =
        RoomR16DevicePlaylistImportRepository(database)
    val sources: R16SourceStateRepository = RoomR16SourceStateRepository(database)
    val playbackCheckpoints: R16PlaybackCheckpointRepository =
        RoomR16PlaybackCheckpointRepository(database)
    val playbackPresentations: R16PlaybackPresentationRepository =
        RoomR16PlaybackPresentationRepository(database)
    val playbackSources: R16PlaybackSourceRepository = RoomR16PlaybackSourceRepository(database)
    val listeningSessions: R16ListeningSessionRepository =
        RoomR16ListeningSessionRepository(database)
    val home: R16HomeReadRepository = RoomR16HomeReadRepository(database)
    val lastFmOutbox: R16LastFmOutboxRepository = RoomR16LastFmOutboxRepository(database)
    val catalogueMaintenance: R16CatalogueMaintenance = RoomR16CatalogueMaintenance(database)
    val offline: R16OfflineRepository = RoomR16OfflineRepository(database)

    override fun close() {
        database.close()
    }

    companion object {
        fun open(context: Context): R16DataRuntime =
            R16DataRuntime(
                Room.databaseBuilder(
                        context.applicationContext,
                        ShippyR16Database::class.java,
                        ShippyR16Database.DATABASE_NAME,
                    )
                    .addCallback(R16LibraryMembershipTriggers)
                    .addCallback(R16PlaylistSearchTriggers)
                    .addMigrations(
                        ShippyR16DatabaseMigrations.MIGRATION_1_2,
                        ShippyR16DatabaseMigrations.MIGRATION_2_3,
                        ShippyR16DatabaseMigrations.MIGRATION_3_4,
                        ShippyR16DatabaseMigrations.MIGRATION_4_5,
                        ShippyR16DatabaseMigrations.MIGRATION_5_6,
                    )
                    .build()
            )
    }
}
