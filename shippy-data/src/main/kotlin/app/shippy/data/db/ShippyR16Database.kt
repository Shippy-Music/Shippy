/*
 * Copyright (c) 2026 Auxio Project
 * ShippyR16Database.kt is part of Auxio.
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
package app.shippy.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.ArtworkReferenceEntity
import app.shippy.data.db.entity.AudioFingerprintEntity
import app.shippy.data.db.entity.ExternalIdentifierEntity
import app.shippy.data.db.entity.MediaAssetEntity
import app.shippy.data.db.entity.MetadataObservationEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.entity.ReleaseEntity
import app.shippy.data.db.entity.ReleaseTrackEntity
import app.shippy.data.db.entity.SourceReferenceEntity

@Database(
    entities =
        [
            RecordingEntity::class,
            ArtistEntity::class,
            RecordingArtistCreditEntity::class,
            ReleaseEntity::class,
            ReleaseTrackEntity::class,
            SourceReferenceEntity::class,
            MetadataObservationEntity::class,
            ExternalIdentifierEntity::class,
            ArtworkReferenceEntity::class,
            MediaAssetEntity::class,
            AudioFingerprintEntity::class,
        ],
    version = ShippyR16Database.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class ShippyR16Database : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "shippy-r16.db"
        const val SCHEMA_VERSION = 1
    }
}
