/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationOrchestrator.kt is part of Auxio.
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

import app.shippy.data.migration.LegacyImportPhase
import app.shippy.data.migration.R16MigrationBootstrapState
import app.shippy.data.migration.R16MigrationBootstrapStore
import app.shippy.data.migration.R16MigrationStatus
import java.util.concurrent.CancellationException

/** The result returned by one completed migration phase. */
internal data class R16MigrationPhaseResult(
    val complete: Boolean = true,
    val lastStableKey: String? = null,
    val legacyDatabaseSha256: String? = null,
    val backupSatisfied: Boolean? = null,
    val auditCommitted: Boolean = false,
    val verificationPassed: Boolean = false,
) {
    init {
        require(lastStableKey == null || lastStableKey.isNotBlank()) {
            "Migration checkpoint key must be null or non-blank"
        }
        require(legacyDatabaseSha256 == null || legacyDatabaseSha256.matches(SHA256_PATTERN)) {
            "Legacy database checksum must be lowercase SHA-256"
        }
    }
}

/**
 * Context supplied to a phase handler; handlers own their bounded importer work and audit writes.
 */
internal data class R16MigrationPhaseContext(
    val migrationId: String,
    val phase: LegacyImportPhase,
    val lastStableKey: String?,
    val completedPhases: Set<LegacyImportPhase>,
    val legacyDatabaseSha256: String?,
    val backupSatisfied: Boolean,
    val cancellationRequested: suspend () -> Boolean,
)

/** One phase adapter around the existing preflight/import/verifier implementations. */
internal interface R16MigrationPhaseHandler {
    val phase: LegacyImportPhase

    suspend fun run(context: R16MigrationPhaseContext): R16MigrationPhaseResult
}

internal enum class R16MigrationOutcome {
    IMPORTING,
    READY_TO_SWITCH,
    CANCELLED,
    FAILED_RECOVERABLE,
    ACTIVE,
}

internal data class R16MigrationOrchestrationResult(
    val outcome: R16MigrationOutcome,
    val state: R16MigrationBootstrapState,
    val failureMessage: String? = null,
)

/**
 * Owns ordered pre-cutover migration progress. M14 is intentionally not a handler here: reaching
 * READY_TO_SWITCH leaves R15.3 authoritative until a separate, explicitly reviewed cutover path
 * activates R16.
 */
internal class R16MigrationOrchestrator(
    private val bootstrap: R16MigrationBootstrapStore,
    handlers: List<R16MigrationPhaseHandler>,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val handlersByPhase: Map<LegacyImportPhase, R16MigrationPhaseHandler>

    init {
        val expected = IMPORT_PHASES.toSet()
        val actual = handlers.map(R16MigrationPhaseHandler::phase)
        require(actual.size == actual.toSet().size) { "Migration phase handlers must be unique" }
        require(actual.toSet() == expected) {
            "Migration handlers must cover exactly M0-M13; M14 is not orchestrated"
        }
        handlersByPhase = handlers.associateBy(R16MigrationPhaseHandler::phase)
    }

    suspend fun run(
        migrationId: String,
        cancellationRequested: suspend () -> Boolean = { false },
    ): R16MigrationOrchestrationResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        var state = bootstrap.initialize(monotonicNow())
        require(state.migrationId == null || state.migrationId == migrationId) {
            "Migration ID does not match the persisted bootstrap state"
        }

        if (state.status == R16MigrationStatus.ACTIVE) {
            return R16MigrationOrchestrationResult(R16MigrationOutcome.ACTIVE, state)
        }
        if (state.status == R16MigrationStatus.READY_TO_SWITCH) {
            return R16MigrationOrchestrationResult(R16MigrationOutcome.READY_TO_SWITCH, state)
        }

        state = prepare(state, migrationId)
        while (true) {
            val phase = nextPhase(state)
            if (phase == null || phase == LegacyImportPhase.CUTOVER) {
                check(LegacyImportPhase.VERIFY in state.completedPhases) {
                    "Migration reached cutover without completing M13 verification"
                }
                return R16MigrationOrchestrationResult(R16MigrationOutcome.READY_TO_SWITCH, state)
            }
            check(phase in IMPORT_PHASES) { "Unsupported migration phase ${phase.code}" }
            state = preparePhase(state, phase)

            if (cancellationRequested()) {
                return cancelled(state, "Migration cancelled before ${phase.code}")
            }

            val handler = checkNotNull(handlersByPhase[phase])
            val result =
                try {
                    handler.run(
                        R16MigrationPhaseContext(
                            migrationId = migrationId,
                            phase = phase,
                            lastStableKey = state.lastStableKey,
                            completedPhases = state.completedPhases,
                            legacyDatabaseSha256 = state.legacyDatabaseSha256,
                            backupSatisfied = state.backupSatisfied,
                            cancellationRequested = cancellationRequested,
                        )
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    return recoverableFailure(state, error.message ?: error.javaClass.simpleName)
                }

            if (cancellationRequested()) {
                return cancelled(state, "Migration cancelled after ${phase.code}")
            }

            try {
                validateResult(state, phase, result)
            } catch (error: Exception) {
                return recoverableFailure(state, error.message ?: error.javaClass.simpleName)
            }

            if (!result.complete) {
                state = checkpointPhase(state, phase, result)
                return R16MigrationOrchestrationResult(R16MigrationOutcome.IMPORTING, state)
            }

            state = completePhase(state, phase, result)
            if (state.status == R16MigrationStatus.READY_TO_SWITCH) {
                return R16MigrationOrchestrationResult(R16MigrationOutcome.READY_TO_SWITCH, state)
            }
        }
    }

    /** Stop an active pre-cutover import without marking any new phase complete. */
    suspend fun cancel(
        migrationId: String,
        reason: String = "Migration cancelled by request",
    ): R16MigrationOrchestrationResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(reason.isNotBlank()) { "Migration cancellation reason must not be blank" }
        val state = bootstrap.initialize(monotonicNow())
        require(state.migrationId == null || state.migrationId == migrationId) {
            "Migration ID does not match the persisted bootstrap state"
        }
        if (state.status == R16MigrationStatus.ACTIVE) {
            return R16MigrationOrchestrationResult(R16MigrationOutcome.ACTIVE, state)
        }
        if (state.status == R16MigrationStatus.READY_TO_SWITCH) {
            return R16MigrationOrchestrationResult(R16MigrationOutcome.CANCELLED, state, reason)
        }
        if (state.status == R16MigrationStatus.NOT_STARTED) {
            return R16MigrationOrchestrationResult(R16MigrationOutcome.CANCELLED, state, reason)
        }
        if (state.status == R16MigrationStatus.FAILED_RECOVERABLE) {
            return R16MigrationOrchestrationResult(R16MigrationOutcome.CANCELLED, state, reason)
        }
        val cancelled =
            save(state) { current -> current.copy(status = R16MigrationStatus.FAILED_RECOVERABLE) }
        return R16MigrationOrchestrationResult(R16MigrationOutcome.CANCELLED, cancelled, reason)
    }

    private fun prepare(
        initial: R16MigrationBootstrapState,
        migrationId: String,
    ): R16MigrationBootstrapState {
        var state = initial
        if (state.status == R16MigrationStatus.NOT_STARTED) {
            state =
                save(state) { current ->
                    current.copy(
                        status = R16MigrationStatus.PREPARING,
                        migrationId = migrationId,
                        currentPhase = LegacyImportPhase.PREFLIGHT,
                    )
                }
        } else if (state.status == R16MigrationStatus.FAILED_RECOVERABLE) {
            val phase = nextPhase(state)
            state =
                save(state) { current ->
                    current.copy(
                        status =
                            if (phase == LegacyImportPhase.PREFLIGHT) {
                                R16MigrationStatus.PREPARING
                            } else if (phase == LegacyImportPhase.VERIFY) {
                                R16MigrationStatus.VERIFYING
                            } else {
                                R16MigrationStatus.IMPORTING
                            }
                    )
                }
        }
        return state
    }

    private fun preparePhase(
        initial: R16MigrationBootstrapState,
        phase: LegacyImportPhase,
    ): R16MigrationBootstrapState {
        if (phase == LegacyImportPhase.PREFLIGHT) {
            check(initial.status == R16MigrationStatus.PREPARING) {
                "M0 preflight must run while migration is preparing"
            }
            return initial
        }
        if (phase == LegacyImportPhase.VERIFY) {
            if (initial.status == R16MigrationStatus.VERIFYING) return initial
            check(
                initial.status == R16MigrationStatus.IMPORTING ||
                    initial.status == R16MigrationStatus.FAILED_RECOVERABLE
            ) {
                "M13 verification must follow importing"
            }
            return save(initial) { current -> current.copy(status = R16MigrationStatus.VERIFYING) }
        }
        if (initial.status == R16MigrationStatus.PREPARING) {
            return save(initial) { current -> current.copy(status = R16MigrationStatus.IMPORTING) }
        }
        check(
            initial.status == R16MigrationStatus.IMPORTING ||
                initial.status == R16MigrationStatus.FAILED_RECOVERABLE
        ) {
            "Migration phase ${phase.code} cannot run from ${initial.status}"
        }
        return initial
    }

    private fun validateResult(
        state: R16MigrationBootstrapState,
        phase: LegacyImportPhase,
        result: R16MigrationPhaseResult,
    ) {
        check(result.auditCommitted) { "${phase.code} handler did not commit audit evidence" }
        val checksum = result.legacyDatabaseSha256
        if (checksum != null && state.legacyDatabaseSha256 != null) {
            check(checksum == state.legacyDatabaseSha256) {
                "Legacy database checksum changed during migration"
            }
        }
        if (!result.complete) {
            check(phase != LegacyImportPhase.PREFLIGHT && phase != LegacyImportPhase.VERIFY) {
                "${phase.code} cannot return an incomplete result"
            }
            check(!result.lastStableKey.isNullOrBlank()) {
                "${phase.code} incomplete result requires a stable checkpoint key"
            }
            return
        }
        when (phase) {
            LegacyImportPhase.PREFLIGHT -> {
                check(!checksum.isNullOrBlank()) {
                    "M0 preflight must return the legacy database checksum"
                }
                check(result.backupSatisfied == true) {
                    "M0 preflight must satisfy the verified backup gate"
                }
            }
            LegacyImportPhase.VERIFY -> {
                check(state.backupSatisfied) { "M13 verification requires a verified backup" }
                check(result.verificationPassed) { "M13 verification did not pass" }
            }
            else -> Unit
        }
    }

    private fun checkpointPhase(
        state: R16MigrationBootstrapState,
        phase: LegacyImportPhase,
        result: R16MigrationPhaseResult,
    ): R16MigrationBootstrapState {
        check(nextPhase(state) == phase) { "Migration phase checkpoint order is invalid" }
        return save(state) { current ->
            current.copy(
                status = R16MigrationStatus.IMPORTING,
                currentPhase = phase,
                lastStableKey = result.lastStableKey,
                legacyDatabaseSha256 = result.legacyDatabaseSha256 ?: current.legacyDatabaseSha256,
                backupSatisfied = current.backupSatisfied || result.backupSatisfied == true,
            )
        }
    }

    private fun completePhase(
        state: R16MigrationBootstrapState,
        phase: LegacyImportPhase,
        result: R16MigrationPhaseResult,
    ): R16MigrationBootstrapState {
        check(nextPhase(state) == phase) { "Migration phase completion order is invalid" }
        val completed = state.completedPhases + phase
        val next = LegacyImportPhase.entries.getOrNull(completed.size)
        val ready = phase == LegacyImportPhase.VERIFY
        return save(state) { current ->
            current.copy(
                status =
                    if (ready) R16MigrationStatus.READY_TO_SWITCH else R16MigrationStatus.IMPORTING,
                currentPhase = next,
                completedPhases = completed,
                lastStableKey = null,
                legacyDatabaseSha256 = result.legacyDatabaseSha256 ?: current.legacyDatabaseSha256,
                backupSatisfied = current.backupSatisfied || result.backupSatisfied == true,
            )
        }
    }

    private fun nextPhase(state: R16MigrationBootstrapState): LegacyImportPhase? =
        state.currentPhase ?: LegacyImportPhase.entries.getOrNull(state.completedPhases.size)

    private fun cancelled(
        state: R16MigrationBootstrapState,
        reason: String,
    ): R16MigrationOrchestrationResult =
        if (state.status == R16MigrationStatus.FAILED_RECOVERABLE) {
            R16MigrationOrchestrationResult(R16MigrationOutcome.CANCELLED, state, reason)
        } else {
            R16MigrationOrchestrationResult(
                R16MigrationOutcome.CANCELLED,
                save(state) { current ->
                    current.copy(status = R16MigrationStatus.FAILED_RECOVERABLE)
                },
                reason,
            )
        }

    private fun recoverableFailure(
        state: R16MigrationBootstrapState,
        reason: String,
    ): R16MigrationOrchestrationResult =
        if (state.status == R16MigrationStatus.FAILED_RECOVERABLE) {
            R16MigrationOrchestrationResult(R16MigrationOutcome.FAILED_RECOVERABLE, state, reason)
        } else {
            R16MigrationOrchestrationResult(
                R16MigrationOutcome.FAILED_RECOVERABLE,
                save(state) { current ->
                    current.copy(status = R16MigrationStatus.FAILED_RECOVERABLE)
                },
                reason,
            )
        }

    private fun save(
        state: R16MigrationBootstrapState,
        update: (R16MigrationBootstrapState) -> R16MigrationBootstrapState,
    ): R16MigrationBootstrapState {
        val next =
            update(state)
                .copy(
                    revision = state.revision + 1,
                    updatedAtEpochMs = monotonicNow(state.updatedAtEpochMs),
                )
        return bootstrap.compareAndSet(state.revision, next)
    }

    private fun monotonicNow(previous: Long = 0L): Long = maxOf(nowEpochMs(), previous)

    companion object {
        private val IMPORT_PHASES = LegacyImportPhase.entries.dropLast(1)
    }
}

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
