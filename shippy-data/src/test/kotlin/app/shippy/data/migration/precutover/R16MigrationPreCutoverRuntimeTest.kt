/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationPreCutoverRuntimeTest.kt is part of Auxio.
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
package app.shippy.data.migration.precutover

import app.shippy.data.migration.LegacyImportPhase
import app.shippy.data.migration.R16MigrationBootstrapStore
import app.shippy.data.migration.orchestration.R16MigrationOrchestrator
import app.shippy.data.migration.orchestration.R16MigrationPhaseContext
import app.shippy.data.migration.orchestration.R16MigrationPhaseHandler
import app.shippy.data.migration.orchestration.R16MigrationPhaseResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16MigrationPreCutoverRuntimeTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "shippy-runtime-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `runtime resumes durable bounded checkpoint after process death`() = runBlocking {
        val calls = mutableListOf<LegacyImportPhase>()
        val bootstrap = File(root, "bootstrap.json")
        var delivered = false
        val first =
            R16MigrationPreCutoverRuntime.forTesting(
                bootstrapFile = bootstrap,
                orchestrator =
                    orchestrator(bootstrap, calls) { phase ->
                        if (phase == LegacyImportPhase.CANONICAL_TRACKS && !delivered) {
                            delivered = true
                            R16MigrationPhaseResult(
                                complete = false,
                                lastStableKey = "track-page-1",
                                auditCommitted = true,
                            )
                        } else {
                            completeResult(phase)
                        }
                    },
            )

        val page = first.run("resume-me")
        assertEquals(R16MigrationPreCutoverOutcome.IMPORTING, page.outcome)
        assertEquals(R16MigrationPreCutoverStatus.IMPORTING, page.state.status)
        assertEquals(R16MigrationPreCutoverPhase.CANONICAL_TRACKS, page.state.currentPhase)
        assertTrue(page.state.checkpointPresent)
        first.close()

        val resumed =
            R16MigrationPreCutoverRuntime.forTesting(
                bootstrapFile = bootstrap,
                orchestrator = orchestrator(bootstrap, calls) { phase -> completeResult(phase) },
            )
        val ready = resumed.retry("resume-me")
        assertEquals(R16MigrationPreCutoverOutcome.READY_TO_SWITCH, ready.outcome)
        assertEquals(R16MigrationPreCutoverStatus.READY_TO_SWITCH, ready.state.status)
        assertEquals(null, ready.state.currentPhase)
        assertFalse(ready.state.checkpointPresent)
        assertEquals(R16MigrationPreCutoverPhase.entries.size, ready.state.completedPhaseCount)
        resumed.close()
    }

    @Test
    fun `runtime cancellation is recoverable and occurs before a handler runs`() = runBlocking {
        val calls = mutableListOf<LegacyImportPhase>()
        val bootstrap = File(root, "bootstrap.json")
        val runtime =
            R16MigrationPreCutoverRuntime.forTesting(
                bootstrapFile = bootstrap,
                orchestrator = orchestrator(bootstrap, calls) { phase -> completeResult(phase) },
            )

        val result = runtime.run("cancel-me") { true }
        assertEquals(R16MigrationPreCutoverOutcome.CANCELLED, result.outcome)
        assertEquals(R16MigrationPreCutoverStatus.FAILED_RECOVERABLE, result.state.status)
        assertEquals("CANCELLED", result.failure?.code)
        assertTrue(calls.isEmpty())
        runtime.close()
    }

    @Test
    fun `runtime surfaces bounded recoverable failure and can retry`() = runBlocking {
        val calls = mutableListOf<LegacyImportPhase>()
        val bootstrap = File(root, "bootstrap.json")
        var fail = true
        val runtime =
            R16MigrationPreCutoverRuntime.forTesting(
                bootstrapFile = bootstrap,
                orchestrator =
                    orchestrator(bootstrap, calls) { phase ->
                        if (phase == LegacyImportPhase.CANDIDATES && fail) {
                            fail = false
                            error("fixture failure at C:\\private\\secret-token")
                        }
                        completeResult(phase)
                    },
            )

        val failed = runtime.run("retry-me")
        assertEquals(R16MigrationPreCutoverOutcome.FAILED_RECOVERABLE, failed.outcome)
        assertEquals("MIGRATION_FAILED", failed.failure?.code)
        assertTrue(failed.failure?.message?.length ?: Int.MAX_VALUE < 512)

        val report = ByteArrayOutputStream()
        runtime.exportFailureReport(failed, report)
        assertTrue(report.size() <= 16 * 1_024)
        assertFalse(report.toString(StandardCharsets.UTF_8.name()).contains("secret-token"))

        val retried = runtime.run("retry-me")
        assertEquals(R16MigrationPreCutoverOutcome.READY_TO_SWITCH, retried.outcome)
        runtime.close()
    }

    @Test
    fun `runtime reports low storage without exposing raw failure details`() = runBlocking {
        val bootstrap = File(root, "low-storage-bootstrap.json")
        val runtime =
            R16MigrationPreCutoverRuntime.forTesting(
                bootstrapFile = bootstrap,
                orchestrator =
                    orchestrator(bootstrap, mutableListOf()) {
                        error("Insufficient free space for the legacy recovery snapshot")
                    },
            )

        val result = runtime.run("low-storage")

        assertEquals(R16MigrationPreCutoverOutcome.FAILED_RECOVERABLE, result.outcome)
        assertEquals("LOW_STORAGE", result.failure?.code)
        assertEquals(
            "Not enough free storage is available for a safe migration.",
            result.failure?.message,
        )
        runtime.close()
    }

    @Test
    fun `explicit M14 action is available only after READY_TO_SWITCH`() = runBlocking {
        val calls = mutableListOf<LegacyImportPhase>()
        val bootstrap = File(root, "bootstrap.json")
        var cutovers = 0
        val runtime =
            R16MigrationPreCutoverRuntime.forTesting(
                bootstrapFile = bootstrap,
                orchestrator = orchestrator(bootstrap, calls) { phase -> completeResult(phase) },
                cutoverAction = { cutovers++ },
            )

        assertTrue(
            runCatching { runtime.activateCutover() }.exceptionOrNull() is IllegalStateException
        )
        assertEquals(0, cutovers)
        val first = runtime.run("ready-lock")
        val callCount = calls.size
        val second = runtime.run("ready-lock")
        runtime.activateCutover()

        assertEquals(R16MigrationPreCutoverOutcome.READY_TO_SWITCH, first.outcome)
        assertEquals(R16MigrationPreCutoverOutcome.READY_TO_SWITCH, second.outcome)
        assertEquals(callCount, calls.size)
        assertEquals(1, cutovers)
        assertTrue(first.state.completedPhases.none { it.code == "M14" })
        assertTrue(R16MigrationPreCutoverPhase.entries.none { it.code == "M14" })
        runtime.close()
    }

    @Test
    fun `corrupt durable bootstrap is surfaced without replacement`() {
        val bootstrap = File(root, "bootstrap.json")
        bootstrap.writeText("not-json")
        val runtime =
            R16MigrationPreCutoverRuntime.forTesting(
                bootstrapFile = bootstrap,
                orchestrator =
                    orchestrator(bootstrap, mutableListOf()) { phase -> completeResult(phase) },
            )

        assertThrows(R16MigrationPreCutoverBootstrapException::class.java) { runtime.loadState() }
        assertEquals("not-json", bootstrap.readText())
        runtime.close()
    }

    @Test
    fun `active and cutover bootstrap state is rejected by the facade`() {
        val bootstrap = File(root, "bootstrap.json")
        writeActiveBootstrap(bootstrap)
        val runtime =
            R16MigrationPreCutoverRuntime.forTesting(
                bootstrapFile = bootstrap,
                orchestrator =
                    orchestrator(bootstrap, mutableListOf()) { phase -> completeResult(phase) },
            )

        assertThrows(R16MigrationPreCutoverCutoverStateException::class.java) {
            runtime.loadState()
        }
        runtime.close()
    }

    private fun orchestrator(
        bootstrapFile: File,
        calls: MutableList<LegacyImportPhase>,
        result: (LegacyImportPhase) -> R16MigrationPhaseResult,
    ): R16MigrationOrchestrator =
        R16MigrationOrchestrator(
            bootstrap = R16MigrationBootstrapStore(bootstrapFile),
            handlers =
                LegacyImportPhase.entries.dropLast(1).map { phase ->
                    object : R16MigrationPhaseHandler {
                        override val phase = phase

                        override suspend fun run(
                            context: R16MigrationPhaseContext
                        ): R16MigrationPhaseResult {
                            calls += phase
                            return result(phase)
                        }
                    }
                },
        )

    private fun completeResult(phase: LegacyImportPhase): R16MigrationPhaseResult =
        R16MigrationPhaseResult(
            complete = true,
            legacyDatabaseSha256 = HASH.takeIf { phase == LegacyImportPhase.PREFLIGHT },
            backupSatisfied = true.takeIf { phase == LegacyImportPhase.PREFLIGHT },
            auditCommitted = true,
            verificationPassed = phase == LegacyImportPhase.VERIFY,
        )

    private fun writeActiveBootstrap(file: File) {
        val payload =
            JSONObject()
                .put("revision", 1)
                .put("status", "ACTIVE")
                .put("migrationId", "active-test")
                .put("currentPhase", JSONObject.NULL)
                .put(
                    "completedPhases",
                    JSONArray(LegacyImportPhase.entries.map(LegacyImportPhase::code)),
                )
                .put("lastStableKey", JSONObject.NULL)
                .put("backupSatisfied", true)
                .put("legacyDatabaseSha256", HASH)
                .put("updatedAtEpochMs", 1)
                .toString()
        val root =
            JSONObject()
                .put("format", "ShippyR16MigrationBootstrap")
                .put("formatVersion", 1)
                .put("payload", JSONObject(payload))
                .put("sha256", payload.sha256())
        file.writeText(root.toString())
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val HASH = "a".repeat(64)
    }
}
