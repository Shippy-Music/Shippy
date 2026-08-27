/*
 * Copyright (c) 2026 Auxio Project
 * R16PerformanceFixtureExportTest.kt is part of Auxio.
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
package app.shippy.data.performance

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.R16LibraryMembershipTriggers
import app.shippy.data.db.ShippyR16Database
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.SQLiteMode

/** Exports the single-source 50k fixture for the benchmark-only app variant. */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R16PerformanceFixtureExportTest {
    @Test
    fun `export deterministic fifty-thousand-row benchmark database`() {
        val property = System.getProperty(OUTPUT_PROPERTY)
        org.junit.Assume.assumeNotNull(property)
        val output = File(checkNotNull(property))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "r16-benchmark-fixture-export.db"
        val source = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)

        val database =
            Room.databaseBuilder(context, ShippyR16Database::class.java, databaseName)
                .addCallback(R16LibraryMembershipTriggers)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .build()
        try {
            val fixture =
                R16PerformanceFixtureGenerator(database).seed(R16PerformanceProfiles.fiftyThousand)
            val sqlite = database.openHelper.writableDatabase
            assertEquals(50_000L, sqlite.count("recording"))
            assertEquals(1_000L, sqlite.count("playlist"))
            assertEquals(20_000L, sqlite.count("play_history"))
            assertEquals(
                10_000L,
                sqlite.count(
                    table = "playlist_entry",
                    where = "playlist_id = ?",
                    args = arrayOf(fixture.duplicatePlaylistId),
                ),
            )
        } finally {
            database.close()
        }

        assertTrue("Fixture database was not created", source.isFile)
        val outputDirectory = checkNotNull(output.parentFile) { "Fixture output needs a directory" }
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "Could not create fixture output directory ${outputDirectory.absolutePath}"
        }
        val temporary = File(outputDirectory, "${output.name}.tmp")
        temporary.delete()
        source.copyTo(temporary, overwrite = true)
        publish(temporary, output)
        assertTrue("Exported fixture database is empty", output.length() > 0L)
        context.deleteDatabase(databaseName)
    }

    private fun publish(temporary: File, output: File) {
        try {
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (atomicFailure: IOException) {
            try {
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (fallbackFailure: IOException) {
                fallbackFailure.addSuppressed(atomicFailure)
                throw fallbackFailure
            }
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(
        table: String,
        where: String? = null,
        args: Array<String> = emptyArray(),
    ): Long {
        val selection = where?.let { " WHERE $it" }.orEmpty()
        return query("SELECT COUNT(*) FROM $table$selection", args).use { cursor ->
            check(cursor.moveToFirst()) { "No count returned for $table" }
            cursor.getLong(0)
        }
    }

    private companion object {
        const val OUTPUT_PROPERTY = "shippy.r16.benchmarkFixtureOutput"
    }
}
