/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationOrchestratorTest.kt is part of Auxio.
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
package app.shippy.data.migration.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.migration.LegacyImportPhase
import app.shippy.data.migration.R16MigrationBootstrapStore
import app.shippy.data.migration.R16MigrationStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16MigrationOrchestratorTest {
    @Test
    fun `ordered phases reach ready to switch without invoking cutover`() = runBlocking {
        val file = tempFile()
        try {
            val calls = mutableListOf<LegacyImportPhase>()
            val handlers = handlers(calls)
            val result =
                R16MigrationOrchestrator(R16MigrationBootstrapStore(file), handlers)
                    .run("migration-ordered")

            assertEquals(R16MigrationOutcome.READY_TO_SWITCH, result.outcome)
            assertEquals(R16MigrationStatus.READY_TO_SWITCH, result.state.status)
            assertEquals(LegacyImportPhase.CUTOVER, result.state.currentPhase)
            assertEquals(IMPORT_PHASES, calls)
            assertTrue(LegacyImportPhase.VERIFY in result.state.completedPhases)
            assertFalse(LegacyImportPhase.CUTOVER in calls)
        } finally {
            delete(file)
        }
    }

    @Test
    fun `persisted failed state resumes from the unfinished phase after cancellation`() =
        runBlocking {
            val file = tempFile()
            try {
                val calls = mutableListOf<LegacyImportPhase>()
                var cancel = true
                val first =
                    R16MigrationOrchestrator(R16MigrationBootstrapStore(file), handlers(calls))
                val cancelled =
                    first.run("migration-resume") {
                        cancel && calls.contains(LegacyImportPhase.PREFLIGHT)
                    }

                assertEquals(R16MigrationOutcome.CANCELLED, cancelled.outcome)
                assertEquals(R16MigrationStatus.FAILED_RECOVERABLE, cancelled.state.status)
                assertEquals(LegacyImportPhase.PREFLIGHT, cancelled.state.currentPhase)
                assertEquals(emptySet<LegacyImportPhase>(), cancelled.state.completedPhases)

                cancel = false
                val resumed =
                    R16MigrationOrchestrator(R16MigrationBootstrapStore(file), handlers(calls))
                        .run("migration-resume")

                assertEquals(R16MigrationOutcome.READY_TO_SWITCH, resumed.outcome)
                assertEquals(IMPORT_PHASES, calls.distinct())
                assertEquals(2, calls.count { it == LegacyImportPhase.PREFLIGHT })
            } finally {
                delete(file)
            }
        }

    @Test
    fun `handler failure is recoverable and retry continues at the same phase`() = runBlocking {
        val file = tempFile()
        try {
            val calls = mutableListOf<LegacyImportPhase>()
            var fail = true
            val handlers =
                handlers(
                    calls,
                    onPhase = { phase ->
                        if (phase == LegacyImportPhase.LIBRARY_RELATIONSHIPS && fail) {
                            fail = false
                            error("fixture failure")
                        }
                    },
                )
            val first =
                R16MigrationOrchestrator(R16MigrationBootstrapStore(file), handlers)
                    .run("migration-failure")

            assertEquals(R16MigrationOutcome.FAILED_RECOVERABLE, first.outcome)
            assertEquals(R16MigrationStatus.FAILED_RECOVERABLE, first.state.status)
            assertEquals(LegacyImportPhase.LIBRARY_RELATIONSHIPS, first.state.currentPhase)
            assertEquals(IMPORT_PHASES.take(3).toSet(), first.state.completedPhases)

            val resumed =
                R16MigrationOrchestrator(R16MigrationBootstrapStore(file), handlers)
                    .run("migration-failure")
            assertEquals(R16MigrationOutcome.READY_TO_SWITCH, resumed.outcome)
            assertEquals(2, calls.count { it == LegacyImportPhase.LIBRARY_RELATIONSHIPS })
        } finally {
            delete(file)
        }
    }

    @Test
    fun `bounded phase checkpoint returns importing and clears key when advancing`() = runBlocking {
        val file = tempFile()
        try {
            val calls = mutableListOf<LegacyImportPhase>()
            val keys = mutableMapOf<LegacyImportPhase, MutableList<String?>>()
            val handlers =
                handlers(
                    calls = calls,
                    incompletePhase = LegacyImportPhase.CANONICAL_TRACKS,
                    onContext = { context ->
                        keys.getOrPut(context.phase) { mutableListOf() } += context.lastStableKey
                    },
                )
            val first =
                R16MigrationOrchestrator(R16MigrationBootstrapStore(file), handlers)
                    .run("migration-bounded")

            assertEquals(R16MigrationOutcome.IMPORTING, first.outcome)
            assertEquals(R16MigrationStatus.IMPORTING, first.state.status)
            assertEquals(LegacyImportPhase.CANONICAL_TRACKS, first.state.currentPhase)
            assertEquals("track-50", first.state.lastStableKey)
            assertEquals(setOf(LegacyImportPhase.PREFLIGHT), first.state.completedPhases)

            val resumed =
                R16MigrationOrchestrator(R16MigrationBootstrapStore(file), handlers)
                    .run("migration-bounded")
            assertEquals(R16MigrationOutcome.READY_TO_SWITCH, resumed.outcome)
            assertEquals(
                listOf(null, "track-50"),
                keys.getValue(LegacyImportPhase.CANONICAL_TRACKS),
            )
            assertEquals(listOf<String?>(null), keys.getValue(LegacyImportPhase.CANDIDATES))
            assertEquals(null, resumed.state.lastStableKey)
        } finally {
            delete(file)
        }
    }

    @Test
    fun `backup and verification evidence gate ready state`() = runBlocking {
        val backupFile = tempFile()
        val verificationFile = tempFile()
        try {
            val backupCalls = mutableListOf<LegacyImportPhase>()
            val noBackup = handlers(backupCalls, backupSatisfied = false)
            val backupResult =
                R16MigrationOrchestrator(R16MigrationBootstrapStore(backupFile), noBackup)
                    .run("migration-no-backup")
            assertEquals(R16MigrationOutcome.FAILED_RECOVERABLE, backupResult.outcome)
            assertEquals(setOf<LegacyImportPhase>(), backupResult.state.completedPhases)
            assertEquals(listOf(LegacyImportPhase.PREFLIGHT), backupCalls)

            val verificationCalls = mutableListOf<LegacyImportPhase>()
            val noVerification = handlers(verificationCalls, verificationPassed = false)
            val verificationResult =
                R16MigrationOrchestrator(
                        R16MigrationBootstrapStore(verificationFile),
                        noVerification,
                    )
                    .run("migration-no-verification")
            assertEquals(R16MigrationOutcome.FAILED_RECOVERABLE, verificationResult.outcome)
            assertEquals(R16MigrationStatus.FAILED_RECOVERABLE, verificationResult.state.status)
            assertFalse(verificationResult.state.status == R16MigrationStatus.READY_TO_SWITCH)
            assertEquals(LegacyImportPhase.VERIFY, verificationResult.state.currentPhase)
        } finally {
            delete(backupFile)
            delete(verificationFile)
        }
    }

    private fun handlers(
        calls: MutableList<LegacyImportPhase>,
        backupSatisfied: Boolean = true,
        verificationPassed: Boolean = true,
        incompletePhase: LegacyImportPhase? = null,
        onPhase: (LegacyImportPhase) -> Unit = {},
        onContext: (R16MigrationPhaseContext) -> Unit = {},
    ): List<R16MigrationPhaseHandler> {
        var incompleteDelivered = false
        return IMPORT_PHASES.map { phase ->
            object : R16MigrationPhaseHandler {
                override val phase = phase

                override suspend fun run(
                    context: R16MigrationPhaseContext
                ): R16MigrationPhaseResult {
                    calls += phase
                    onContext(context)
                    onPhase(phase)
                    val incomplete = phase == incompletePhase && !incompleteDelivered
                    if (incomplete) incompleteDelivered = true
                    return R16MigrationPhaseResult(
                        complete = !incomplete,
                        lastStableKey = "track-50".takeIf { incomplete },
                        legacyDatabaseSha256 = HASH.takeIf { phase == LegacyImportPhase.PREFLIGHT },
                        backupSatisfied =
                            backupSatisfied.takeIf { phase == LegacyImportPhase.PREFLIGHT },
                        auditCommitted = true,
                        verificationPassed = verificationPassed && phase == LegacyImportPhase.VERIFY,
                    )
                }
            }
        }
    }

    private fun delete(file: File) {
        file.delete()
        File(file.path + ".bak").delete()
    }

    private fun tempFile(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, "r16-orchestrator-${UUID.randomUUID()}.json")
    }

    private companion object {
        val IMPORT_PHASES = LegacyImportPhase.entries.dropLast(1)
        val HASH = "a".repeat(64)
    }
}
