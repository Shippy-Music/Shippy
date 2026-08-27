/*
 * Copyright (c) 2026 Auxio Project
 * LegacyV10PreflightTest.kt is part of Auxio.
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
package app.shippy.data.migration.preflight

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyV10PreflightTest {
    private lateinit var legacyDatabase: File
    private lateinit var destinationDirectory: File

    @Before
    fun setUp() {
        val root =
            File(System.getProperty("java.io.tmpdir"), "shippy-preflight-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        legacyDatabase = File(root, "legacy-v10.db")
        destinationDirectory = File(root, "snapshots")
        assertTrue(destinationDirectory.mkdirs())
        createLegacyDatabase(LEGACY_SCHEMA_VERSION)
    }

    @After
    fun tearDown() {
        legacyDatabase.parentFile?.deleteRecursively()
    }

    @Test
    fun `success copies closed legacy database and writes deterministic evidence`() {
        val first = LegacyV10Preflight().capture(legacyDatabase, destinationDirectory, "legacy.db")
        val firstManifest = first.manifestFile.readText()

        val secondDestination = File(legacyDatabase.parentFile, "second")
        assertTrue(secondDestination.mkdirs())
        val second = LegacyV10Preflight().capture(legacyDatabase, secondDestination, "legacy.db")

        assertEquals(LEGACY_SCHEMA_VERSION, first.databaseSchemaVersion)
        assertEquals(2L, first.rowCounts.getValue("canonical_track"))
        assertEquals(first.byteCount, first.snapshotFile.length())
        assertEquals(first.sha256, second.sha256)
        assertArrayEquals(legacyDatabase.readBytes(), first.snapshotFile.readBytes())
        assertEquals(firstManifest, second.manifestFile.readText())
        assertTrue(firstManifest.contains("\"integrityCheck\":\"ok\""))
        assertTrue(firstManifest.contains("\"rowCounts\":{\"canonical_track\":2"))
        assertTrue(legacyDatabase.isFile)
    }

    @Test
    fun `checksum mismatch rejects and cleans the published snapshot`() {
        val corruptingCopier = LegacyPreflightSnapshotCopier { source, destination ->
            source.copyTo(destination, overwrite = true)
            val bytes = destination.readBytes()
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            destination.writeBytes(bytes)
        }

        assertThrows(IllegalArgumentException::class.java) {
            LegacyV10Preflight(snapshotCopier = corruptingCopier)
                .capture(legacyDatabase, destinationDirectory, "corrupt.db")
        }

        assertTrue(destinationDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(legacyDatabase.isFile)
    }

    @Test
    fun `insufficient free space is rejected before copying`() {
        val noSpace = LegacyPreflightFreeSpaceProbe { 0L }

        assertThrows(IllegalStateException::class.java) {
            LegacyV10Preflight(freeSpaceProbe = noSpace)
                .capture(legacyDatabase, destinationDirectory, "no-space.db")
        }

        assertTrue(destinationDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `incompatible legacy schema is rejected without output`() {
        createLegacyDatabase(version = LEGACY_SCHEMA_VERSION - 1)

        assertThrows(IllegalArgumentException::class.java) {
            LegacyV10Preflight().capture(legacyDatabase, destinationDirectory, "wrong-schema.db")
        }

        assertTrue(destinationDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `non-empty WAL sidecar is refused before opening or copying`() {
        val wal = File(legacyDatabase.path + "-wal")
        wal.writeBytes(byteArrayOf(1, 2, 3))

        assertThrows(IllegalStateException::class.java) {
            LegacyV10Preflight().capture(legacyDatabase, destinationDirectory, "wal.db")
        }

        assertTrue(destinationDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(legacyDatabase.isFile)
    }

    @Test
    fun `late non-empty WAL sidecar is refused before publication`() {
        val wal = File(legacyDatabase.path + "-wal")
        val lateWalCopier = LegacyPreflightSnapshotCopier { source, destination ->
            source.copyTo(destination, overwrite = true)
            wal.writeBytes(byteArrayOf(4, 5, 6))
        }

        assertThrows(IllegalStateException::class.java) {
            LegacyV10Preflight(snapshotCopier = lateWalCopier)
                .capture(legacyDatabase, destinationDirectory, "late-wal.db")
        }

        assertTrue(destinationDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(legacyDatabase.isFile)
    }

    @Test
    fun `failed streaming copy removes partial temporary files`() {
        val failingCopier = LegacyPreflightSnapshotCopier { _, destination ->
            destination.writeBytes(byteArrayOf(1, 2, 3))
            throw IOException("simulated copy failure")
        }

        assertThrows(IOException::class.java) {
            LegacyV10Preflight(snapshotCopier = failingCopier)
                .capture(legacyDatabase, destinationDirectory, "failed.db")
        }

        assertTrue(destinationDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(legacyDatabase.isFile)
    }

    private fun createLegacyDatabase(version: Int) {
        legacyDatabase.delete()
        SQLiteDatabase.openOrCreateDatabase(legacyDatabase, null).use { database ->
            database.version = version
            for (table in LEGACY_TABLES) {
                if (table == "canonical_track") {
                    database.execSQL(
                        """
                        CREATE TABLE canonical_track (
                            trackId TEXT NOT NULL PRIMARY KEY,
                            realm TEXT NOT NULL,
                            title TEXT NOT NULL
                        )
                        """
                            .trimIndent()
                    )
                    database.execSQL(
                        "INSERT INTO canonical_track(trackId, realm, title) VALUES " +
                            "('track-a', 'LOCAL', 'Alpha'), ('track-b', 'PROVIDER', 'Beta')"
                    )
                } else {
                    database.execSQL("CREATE TABLE `$table` (id TEXT)")
                }
            }
        }
    }

    companion object {
        private const val LEGACY_SCHEMA_VERSION = 10
        private val LEGACY_TABLES =
            listOf(
                "library_relationship",
                "user_playlist",
                "playlist_membership",
                "download_job",
                "download_candidate",
                "lyrics_cache",
                "crew_active_checkpoint",
                "canonical_track",
                "canonical_track_candidate",
                "lastfm_scrobble_outbox",
                "playback_checkpoint",
                "playback_checkpoint_item",
                "saved_provider_entity",
            )
    }
}
