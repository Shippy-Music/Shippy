/*
 * Copyright (c) 2026 Auxio Project
 * CanonicalTrackMetadataMigrationTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.persistence.library

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalTrackMetadataMigrationTest {
    @Test
    fun `canonical track catalog has an explicit v5 to v6 migration`() {
        assertEquals(5, ShippyDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, ShippyDatabase.MIGRATION_5_6.endVersion)
    }

    @Test
    fun `lastfm outbox has an explicit v6 to v7 migration`() {
        assertEquals(6, ShippyDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, ShippyDatabase.MIGRATION_6_7.endVersion)
    }

    @Test
    fun `saved provider entities have an explicit non-destructive v8 to v9 migration`() {
        assertEquals(8, ShippyDatabase.MIGRATION_8_9.startVersion)
        assertEquals(9, ShippyDatabase.MIGRATION_8_9.endVersion)
    }

    @Test
    fun `playlist artwork has an explicit non-destructive v9 to v10 migration`() {
        assertEquals(9, ShippyDatabase.MIGRATION_9_10.startVersion)
        assertEquals(10, ShippyDatabase.MIGRATION_9_10.endVersion)
    }

    @Test
    fun `lastfm scrobble outbox has an explicit non-destructive v10 to v11 migration`() {
        assertEquals(10, ShippyDatabase.MIGRATION_10_11.startVersion)
        assertEquals(11, ShippyDatabase.MIGRATION_10_11.endVersion)
    }

    @Test
    fun `v10 to v11 migration executes alter table adding accountId column`() {
        val executedStatements = mutableListOf<String>()
        val dbProxy =
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                    executedStatements.add(args[0] as String)
                }
                null
            } as SupportSQLiteDatabase

        ShippyDatabase.MIGRATION_10_11.migrate(dbProxy)

        assertEquals(1, executedStatements.size)
        assertTrue(
            executedStatements[0].contains(
                "ALTER TABLE `lastfm_scrobble_outbox` ADD COLUMN `accountId` TEXT NOT NULL DEFAULT ''"
            )
        )
    }
}
