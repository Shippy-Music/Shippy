/*
 * Copyright (c) 2026 Auxio Project
 * CrewRecoveryCoordinator.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.rejoin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.shippy.crew.diagnostics.CrewDiagnosticEvent
import org.oxycblt.auxio.shippy.crew.diagnostics.CrewDiagnosticRecorder

/**
 * The narrow app/service-start entry point for recovery. It serializes startup and network wakes
 * against the existing [CrewSessionOrchestrator], so a process restart cannot create parallel
 * engines or accidentally turn a retryable connection failure into a new Crew.
 */
interface CrewRecoveryPort {
    suspend fun restoreAfterProcessStart(): CrewRestoreResult

    suspend fun onNetworkAvailable(): CrewRestoreResult
}

class CrewSessionOrchestratorRecoveryPort(private val orchestrator: CrewSessionOrchestrator) :
    CrewRecoveryPort {
    override suspend fun restoreAfterProcessStart() = orchestrator.restoreAfterProcessStart()

    override suspend fun onNetworkAvailable() = orchestrator.onNetworkAvailable()
}

sealed interface CrewRecoveryPresentation {
    data object Idle : CrewRecoveryPresentation

    data object Restoring : CrewRecoveryPresentation

    data object Reconnecting : CrewRecoveryPresentation

    data object Connected : CrewRecoveryPresentation

    data object ExpiredOrRevoked : CrewRecoveryPresentation
}

class CrewRecoveryCoordinator(
    private val recovery: CrewRecoveryPort,
    private val diagnostics: CrewDiagnosticRecorder? = null,
) {
    private val mutex = Mutex()
    private var presentation: CrewRecoveryPresentation = CrewRecoveryPresentation.Idle

    suspend fun restoreFromAppOrServiceStart(): CrewRecoveryPresentation =
        mutex.withLock {
            presentation = CrewRecoveryPresentation.Restoring
            apply(recovery.restoreAfterProcessStart())
        }

    /** Call from the existing default-network callback; it is safe to invoke repeatedly. */
    suspend fun onNetworkChanged(): CrewRecoveryPresentation =
        mutex.withLock {
            presentation = CrewRecoveryPresentation.Reconnecting
            apply(recovery.onNetworkAvailable())
        }

    fun currentPresentation(): CrewRecoveryPresentation = presentation

    private fun apply(result: CrewRestoreResult): CrewRecoveryPresentation {
        presentation =
            when (result) {
                CrewRestoreResult.NothingToRestore -> CrewRecoveryPresentation.Idle
                CrewRestoreResult.RestoredAndConnected -> CrewRecoveryPresentation.Connected
                CrewRestoreResult.RestoredAwaitingNetwork -> CrewRecoveryPresentation.Reconnecting
                is CrewRestoreResult.Discarded -> CrewRecoveryPresentation.ExpiredOrRevoked
            }
        val diagnostic =
            when (presentation) {
                CrewRecoveryPresentation.Connected ->
                    CrewDiagnosticEvent.Kind.RESTORED to "recovery_connected"
                CrewRecoveryPresentation.Reconnecting ->
                    CrewDiagnosticEvent.Kind.RECONNECTING to "recovery_reconnecting"
                CrewRecoveryPresentation.ExpiredOrRevoked ->
                    CrewDiagnosticEvent.Kind.EXPIRED to "recovery_expired_or_revoked"
                CrewRecoveryPresentation.Idle -> CrewDiagnosticEvent.Kind.ENDED to "recovery_idle"
                CrewRecoveryPresentation.Restoring ->
                    error("Recovery result must settle presentation")
            }
        diagnostics?.record(diagnostic.first, diagnostic.second)
        return presentation
    }
}
