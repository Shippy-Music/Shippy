/*
 * Copyright (c) 2026 Auxio Project
 * CrewRecoveryCoordinatorTest.kt is part of Auxio.
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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.diagnostics.CrewDiagnosticEvent
import org.oxycblt.auxio.shippy.crew.diagnostics.CrewDiagnosticRecorder

class CrewRecoveryCoordinatorTest {
    @Test
    fun `startup retry is reconnecting and later network wake becomes connected`() {
        runBlocking {
            val port = FakeRecoveryPort(CrewRestoreResult.RestoredAwaitingNetwork)
            val diagnostics = CrewDiagnosticRecorder(elapsedRealtimeMs = { 1L })
            val coordinator = CrewRecoveryCoordinator(port, diagnostics)

            assertEquals(
                CrewRecoveryPresentation.Reconnecting,
                coordinator.restoreFromAppOrServiceStart(),
            )
            port.networkResult = CrewRestoreResult.RestoredAndConnected

            assertEquals(CrewRecoveryPresentation.Connected, coordinator.onNetworkChanged())
            assertEquals(1, port.startupCalls)
            assertEquals(1, port.networkCalls)
            assertEquals(
                listOf(CrewDiagnosticEvent.Kind.RECONNECTING, CrewDiagnosticEvent.Kind.RESTORED),
                diagnostics.snapshot().events.map { it.kind },
            )
        }
    }

    @Test
    fun `discarded recovery never exposes a phantom active presentation`() {
        runBlocking {
            val coordinator =
                CrewRecoveryCoordinator(
                    FakeRecoveryPort(
                        CrewRestoreResult.Discarded(CrewRejoinRestoreDecision.DISCARD_EXPIRED_LEASE)
                    )
                )

            assertEquals(
                CrewRecoveryPresentation.ExpiredOrRevoked,
                coordinator.restoreFromAppOrServiceStart(),
            )
            assertEquals(
                CrewRecoveryPresentation.ExpiredOrRevoked,
                coordinator.currentPresentation(),
            )
        }
    }

    private class FakeRecoveryPort(startupResult: CrewRestoreResult) : CrewRecoveryPort {
        var startupResult = startupResult
        var networkResult = startupResult
        var startupCalls = 0
        var networkCalls = 0

        override suspend fun restoreAfterProcessStart(): CrewRestoreResult {
            startupCalls++
            return startupResult
        }

        override suspend fun onNetworkAvailable(): CrewRestoreResult {
            networkCalls++
            return networkResult
        }
    }
}
