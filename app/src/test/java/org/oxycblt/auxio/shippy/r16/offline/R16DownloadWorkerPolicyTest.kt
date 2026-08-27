/*
 * Copyright (c) 2026 Auxio Project
 * R16DownloadWorkerPolicyTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.offline

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R16DownloadWorkerPolicyTest {
    @Test
    fun `job id validation closes staging path traversal`() {
        assertTrue(isSafeR16DownloadJobId("download:550e8400-e29b-41d4-a716-446655440000"))
        assertFalse(isSafeR16DownloadJobId("../download"))
        assertFalse(isSafeR16DownloadJobId("folder\\download"))
        assertFalse(isSafeR16DownloadJobId(" download"))
        assertFalse(isSafeR16DownloadJobId("download\u0000"))
        assertFalse(isSafeR16DownloadJobId("x".repeat(129)))
    }

    @Test
    fun `offline publication is unconstrained and retries remain bounded`() {
        assertEquals(NetworkType.NOT_REQUIRED, R16DownloadWorkScheduler.NETWORK_TYPE)
        assertEquals(
            R16DownloadWorkerDecision.RETRY,
            r16DownloadWorkerDecision(R16DownloadExecutionResult.Retry, 0),
        )
        assertEquals(
            R16DownloadWorkerDecision.RETRY,
            r16DownloadWorkerDecision(R16DownloadExecutionResult.Retry, 1),
        )
        assertEquals(
            R16DownloadWorkerDecision.FAILURE,
            r16DownloadWorkerDecision(R16DownloadExecutionResult.Retry, 2),
        )
        assertEquals(
            R16DownloadWorkerDecision.FAILURE,
            r16DownloadWorkerDecision(R16DownloadExecutionResult.AwaitingUser, 0),
        )
        assertEquals(
            R16DownloadWorkerDecision.SUCCESS,
            r16DownloadWorkerDecision(R16DownloadExecutionResult.Completed, 0),
        )
    }
}
