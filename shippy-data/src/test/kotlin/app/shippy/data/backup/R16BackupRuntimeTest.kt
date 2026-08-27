/*
 * Copyright (c) 2026 Auxio Project
 * R16BackupRuntimeTest.kt is part of Auxio.
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
package app.shippy.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.ShippyR16Database
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16BackupRuntimeTest {
    @Test
    fun `public facade exports verifies and restores the same archive contract`() {
        val source = newDatabase()
        val target = newDatabase()
        try {
            source.insertRecording("recording-1")
            val settings =
                R16PortableSettingsSnapshot(mapOf("ui.theme" to R16BackupValue.LongValue(2)))
            val lastFm = R16SanitizedLastFmConfig(username = "listener")
            val output = ByteArrayOutputStream()

            val exported =
                R16BackupRuntime(source)
                    .export(
                        output = output,
                        createdAtEpochMs = 42,
                        includeHistory = true,
                        portableSettingsProvider = { settings },
                        sanitizedLastFmConfigProvider = { lastFm },
                    )
            val verified =
                R16BackupRuntime(source).verify(ByteArrayInputStream(output.toByteArray()))

            assertEquals(exported.archive, verified)
            assertEquals(ShippyR16Database.SCHEMA_VERSION, verified.databaseSchemaVersion)
            assertTrue(verified.portableAppSettingsIncluded)
            assertTrue(verified.sanitizedLastFmConfigIncluded)
            assertTrue(verified.entries.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })

            val restored =
                R16BackupRuntime(target).restore(ByteArrayInputStream(output.toByteArray()))
            assertEquals(settings, restored.portableSettings)
            assertEquals(lastFm, restored.sanitizedLastFmConfig)
            assertEquals(1, restored.rowCounts.getValue("recording"))
            assertEquals(1L, target.recordingCount())
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun `unsupported verified archive is rejected before the empty target mutates`() {
        val source = newDatabase()
        val target = newDatabase()
        try {
            source.insertRecording("recording-1")
            val original = ByteArrayOutputStream()
            R16BackupRuntime(source).export(original, createdAtEpochMs = 42)
            val archive = ShippyBackupV1.read(ByteArrayInputStream(original.toByteArray()))
            val unsupported =
                ByteArrayOutputStream().also { output ->
                    ShippyBackupV1.write(
                        output = output,
                        databaseSchemaVersion = ShippyR16Database.SCHEMA_VERSION + 1,
                        createdAtEpochMs = archive.manifest.createdAtEpochMs,
                        includesHistory = archive.manifest.includesHistory,
                        sections = archive.sections,
                    )
                }
            val runtime = R16BackupRuntime(target)

            assertThrows(IllegalArgumentException::class.java) {
                runtime.verify(ByteArrayInputStream(unsupported.toByteArray()))
            }
            assertThrows(IllegalArgumentException::class.java) {
                runtime.restore(ByteArrayInputStream(unsupported.toByteArray()))
            }
            assertEquals(0L, target.recordingCount())
            assertFalse(target.openHelper.writableDatabase.inTransaction())
        } finally {
            source.close()
            target.close()
        }
    }

    private fun newDatabase(): ShippyR16Database =
        Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                ShippyR16Database::class.java,
            )
            .allowMainThreadQueries()
            .build()

    private fun ShippyR16Database.insertRecording(id: String) {
        openHelper.writableDatabase.execSQL(
            """
            INSERT INTO recording
            (recording_id, canonical_title, version_kind, explicitness, retention_kind,
             created_at_epoch_ms, updated_at_epoch_ms)
            VALUES (?, 'Track', 'ORIGINAL', 'UNKNOWN', 'DURABLE', 1, 1)
            """
                .trimIndent(),
            arrayOf(id),
        )
    }

    private fun ShippyR16Database.recordingCount(): Long =
        openHelper.writableDatabase.query("SELECT COUNT(*) FROM recording").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
