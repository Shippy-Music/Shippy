/*
 * Copyright (c) 2026 Auxio Project
 * R16StartupStateAndCutoverTest.kt is part of Auxio.
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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.MigrationAuditEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16StartupStateAndCutoverTest {
    private lateinit var root: File
    private lateinit var bootstrapFile: File
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "shippy-m14-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        bootstrapFile = File(root, "bootstrap-v1.json")
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun `startup reader is read only and fails closed for corrupt state`() {
        val reader = R16StartupStateReader(bootstrapFile)

        assertEquals(R16StartupState.LEGACY, reader.read())
        assertTrue(!bootstrapFile.exists())

        R16MigrationBootstrapStore(bootstrapFile).initialize(1)
        assertEquals(R16StartupState.MIGRATION_RECOVERY, reader.read())

        bootstrapFile.writeText("not-json")
        assertEquals(R16StartupState.RECOVERY_REQUIRED, reader.read())
        assertEquals("not-json", bootstrapFile.readText())
    }

    @Test
    fun `M14 writes ACTIVE last and retry closes the crash window idempotently`() = runBlocking {
        writeReadyBootstrap()
        writeReadyAudit()
        val reader = R16StartupStateReader(bootstrapFile)
        assertEquals(R16StartupState.READY_TO_SWITCH, reader.read())

        val crash = IllegalStateException("injected process death")
        val thrown =
            runCatching {
                    R16M14Cutover(
                            bootstrapFile = bootstrapFile,
                            database = database,
                            nowEpochMs = { 100L },
                            afterAuditCommitted = { throw crash },
                        )
                        .activate()
                }
                .exceptionOrNull()
        assertSame(crash, thrown)
        assertEquals(R16StartupState.READY_TO_SWITCH, reader.read())
        assertEquals(ACTIVE, database.migrationAuditDao().get(MIGRATION_ID)?.status)

        val activated = R16M14Cutover(bootstrapFile, database, nowEpochMs = { 101L }).activate()
        assertEquals(R16M14CutoverOutcome.ACTIVATED, activated.outcome)
        assertEquals(R16StartupState.R16_ACTIVE, reader.read())

        val activeState =
            (R16MigrationBootstrapStore(bootstrapFile).load()
                    as R16MigrationBootstrapLoadResult.Loaded)
                .state
        assertEquals(R16MigrationStatus.ACTIVE, activeState.status)
        assertEquals(LegacyImportPhase.entries.toSet(), activeState.completedPhases)
        assertNull(activeState.currentPhase)

        val auditAfterActivation = checkNotNull(database.migrationAuditDao().get(MIGRATION_ID))
        assertNotNull(auditAfterActivation.completedAtEpochMs)
        assertNotNull(auditAfterActivation.checksum)
        assertEquals(ACTIVE, auditAfterActivation.status)
        assertTrue(
            JSONObject(checkNotNull(auditAfterActivation.targetCountsJson))
                .getJSONObject("m14Cutover")
                .getBoolean("smokeRead")
        )

        val repeated = R16M14Cutover(bootstrapFile, database).activate()
        assertEquals(R16M14CutoverOutcome.ALREADY_ACTIVE, repeated.outcome)
        assertEquals(activeState.revision, repeated.bootstrapRevision)
        assertEquals(auditAfterActivation, database.migrationAuditDao().get(MIGRATION_ID))

        val rootJson = JSONObject(bootstrapFile.readText())
        assertEquals(1, rootJson.getInt("formatVersion"))
    }

    @Test
    fun `M14 rejects a premature bootstrap without changing authority`() = runBlocking {
        val store = R16MigrationBootstrapStore(bootstrapFile)
        val initial = store.initialize(1)
        val preparing =
            initial.copy(
                revision = 1,
                status = R16MigrationStatus.PREPARING,
                migrationId = MIGRATION_ID,
                currentPhase = LegacyImportPhase.PREFLIGHT,
                updatedAtEpochMs = 2,
            )
        store.compareAndSet(initial.revision, preparing)

        assertTrue(
            runCatching { R16M14Cutover(bootstrapFile, database).activate() }.exceptionOrNull()
                is IllegalStateException
        )
        assertEquals(
            R16StartupState.MIGRATION_RECOVERY,
            R16StartupStateReader(bootstrapFile).read(),
        )
        assertEquals(
            R16MigrationStatus.PREPARING,
            (store.load() as R16MigrationBootstrapLoadResult.Loaded).state.status,
        )
    }

    private fun writeReadyBootstrap() {
        val store = R16MigrationBootstrapStore(bootstrapFile)
        val initial = store.initialize(1)
        val preparing =
            initial.copy(
                revision = 1,
                status = R16MigrationStatus.PREPARING,
                migrationId = MIGRATION_ID,
                currentPhase = LegacyImportPhase.PREFLIGHT,
                updatedAtEpochMs = 2,
            )
        store.compareAndSet(initial.revision, preparing)

        val beforeVerify =
            LegacyImportPhase.entries
                .filter { it.ordinal < LegacyImportPhase.VERIFY.ordinal }
                .toSet()
        val importing =
            preparing.copy(
                revision = 2,
                status = R16MigrationStatus.IMPORTING,
                currentPhase = LegacyImportPhase.VERIFY,
                completedPhases = beforeVerify,
                backupSatisfied = true,
                legacyDatabaseSha256 = HASH,
                updatedAtEpochMs = 3,
            )
        store.compareAndSet(preparing.revision, importing)
        val verifying =
            importing.copy(
                revision = 3,
                status = R16MigrationStatus.VERIFYING,
                updatedAtEpochMs = 4,
            )
        store.compareAndSet(importing.revision, verifying)
        val ready =
            verifying.copy(
                revision = 4,
                status = R16MigrationStatus.READY_TO_SWITCH,
                currentPhase = LegacyImportPhase.CUTOVER,
                completedPhases = beforeVerify + LegacyImportPhase.VERIFY,
                updatedAtEpochMs = 5,
            )
        store.compareAndSet(verifying.revision, ready)
    }

    private suspend fun writeReadyAudit() {
        database
            .migrationAuditDao()
            .start(
                MigrationAuditEntity(
                    migrationId = MIGRATION_ID,
                    sourceVersion = 10,
                    targetVersion = ShippyR16Database.SCHEMA_VERSION,
                    startedAtEpochMs = 1,
                    completedAtEpochMs = null,
                    sourceCountsJson = "{}",
                    targetCountsJson = null,
                    warningsJson = "[]",
                    checksum = null,
                    status = "PREPARING",
                )
            )
        database
            .migrationAuditDao()
            .updateProgress(
                migrationId = MIGRATION_ID,
                targetCountsJson =
                    JSONObject()
                        .put("issues", org.json.JSONArray())
                        .put(
                            "m13RecoveryArchive",
                            JSONObject().put("format", "ShippyBackupV1").put("verified", true),
                        )
                        .toString(),
                warningsJson = "[]",
                status = "READY_TO_SWITCH",
            )
    }

    private companion object {
        const val MIGRATION_ID = "m14-test"
        const val ACTIVE = "ACTIVE"
        val HASH = "a".repeat(64)
    }
}
