/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationBootstrapStoreTest.kt is part of Auxio.
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
package app.shippy.data.migration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16MigrationBootstrapStoreTest {
    @Test
    fun `bootstrap is atomic revisioned and rejects stale authority changes`() {
        val file = tempFile()
        try {
            val store = R16MigrationBootstrapStore(file)
            val initial = store.initialize(1)
            assertEquals(R16MigrationStatus.NOT_STARTED, initial.status)

            val preparing =
                initial.copy(
                    revision = 1,
                    status = R16MigrationStatus.PREPARING,
                    migrationId = "migration-1",
                    currentPhase = LegacyImportPhase.PREFLIGHT,
                    updatedAtEpochMs = 2,
                )
            store.compareAndSet(initial.revision, preparing)

            val loaded = store.load() as R16MigrationBootstrapLoadResult.Loaded
            assertEquals(preparing, loaded.state)
            assertThrows(IllegalStateException::class.java) {
                store.compareAndSet(initial.revision, preparing)
            }
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
        }
    }

    @Test
    fun `bootstrap refuses corruption and premature verification`() {
        assertThrows(IllegalArgumentException::class.java) {
            R16MigrationBootstrapState(
                revision = 2,
                status = R16MigrationStatus.VERIFYING,
                migrationId = "migration-1",
                currentPhase = LegacyImportPhase.VERIFY,
                completedPhases = setOf(LegacyImportPhase.PREFLIGHT),
                lastStableKey = null,
                backupSatisfied = true,
                legacyDatabaseSha256 = "a".repeat(64),
                updatedAtEpochMs = 3,
            )
        }

        val file = tempFile()
        try {
            file.writeText("not-json")
            val result = R16MigrationBootstrapStore(file).load()
            assertTrue(result is R16MigrationBootstrapLoadResult.Corrupt)
            assertThrows(IllegalStateException::class.java) {
                R16MigrationBootstrapStore(file).initialize(4)
            }
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
        }
    }

    @Test
    fun `forceRepair recovers from corrupt state and reconstructFromAudit matches audit record`() {
        val audit =
            app.shippy.data.db.entity.MigrationAuditEntity(
                migrationId = "mig-reconstruct",
                sourceVersion = 10,
                targetVersion = 4,
                startedAtEpochMs = 100,
                completedAtEpochMs = null,
                sourceCountsJson = "{}",
                targetCountsJson = "{}",
                warningsJson = "[]",
                checksum = null,
                status = "IMPORTING",
            )
        val reconstructed =
            R16MigrationBootstrapState.reconstructFromAudit(
                audit = audit,
                backupSatisfied = true,
                legacyDatabaseSha256 = "b".repeat(64),
                nowEpochMs = 200,
            )
        assertEquals("mig-reconstruct", reconstructed.migrationId)
        assertEquals(R16MigrationStatus.IMPORTING, reconstructed.status)
        assertTrue(reconstructed.backupSatisfied)
        assertTrue(LegacyImportPhase.PREFLIGHT in reconstructed.completedPhases)

        val file = tempFile()
        try {
            file.writeText("corrupt garbage")
            val store = R16MigrationBootstrapStore(file)
            assertTrue(store.load() is R16MigrationBootstrapLoadResult.Corrupt)

            store.forceRepair(reconstructed)
            val loaded = store.load() as R16MigrationBootstrapLoadResult.Loaded
            assertEquals(reconstructed, loaded.state)

            val reset = store.resetToNotStarted(300)
            assertEquals(R16MigrationStatus.NOT_STARTED, reset.status)
            assertEquals(0L, reset.revision)
            val loadedReset = store.load() as R16MigrationBootstrapLoadResult.Loaded
            assertEquals(reset, loadedReset.state)
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
        }
    }

    private fun tempFile(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, "r16-bootstrap-${UUID.randomUUID()}.json")
    }
}
