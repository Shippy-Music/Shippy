/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyDatabase.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.library

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [LibraryRelationshipEntity::class, PlaylistMembershipEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class ShippyDatabase : RoomDatabase() {
    abstract fun libraryRelationshipDao(): LibraryRelationshipDao
}
