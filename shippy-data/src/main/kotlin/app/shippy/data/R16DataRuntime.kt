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
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.ingest.R16IngestionRepository
import app.shippy.data.ingest.RoomR16IngestionRepository
import app.shippy.data.maintenance.R16CatalogueMaintenance
import app.shippy.data.maintenance.RoomR16CatalogueMaintenance
import app.shippy.data.migration.R16LocalReindexAudit
import app.shippy.data.migration.RoomR16LocalReindexAudit
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
    val ingestion: R16IngestionRepository = RoomR16IngestionRepository(database)
    val localReindexAudit: R16LocalReindexAudit = RoomR16LocalReindexAudit(database)
    val sources: R16SourceStateRepository = RoomR16SourceStateRepository(database)
    val playbackCheckpoints: R16PlaybackCheckpointRepository =
        RoomR16PlaybackCheckpointRepository(database)
    val playbackPresentations: R16PlaybackPresentationRepository =
        RoomR16PlaybackPresentationRepository(database)
    val playbackSources: R16PlaybackSourceRepository = RoomR16PlaybackSourceRepository(database)
    val catalogueMaintenance: R16CatalogueMaintenance = RoomR16CatalogueMaintenance(database)

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
                    .build()
            )
    }
}
