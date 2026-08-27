/*
 * Copyright (c) 2026 Auxio Project
 * R16IdentifyTrackViewModelTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.identity

import android.content.Context
import app.shippy.data.R16DataRuntime
import app.shippy.data.migration.R16StartupStateReader
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner
import org.oxycblt.auxio.shippy.r16.authority.R16AuthorityMode
import org.oxycblt.auxio.shippy.r16.authority.R16AuthoritySelector
import org.oxycblt.auxio.shippy.r16.authority.R16DataRuntimeOpener
import org.oxycblt.auxio.shippy.r16.migration.R16MigrationProcessGate
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class R16IdentifyTrackViewModelTest {
    private lateinit var runtime: R16DataRuntime
    private lateinit var owner: R16ActiveDataRuntimeOwner
    private lateinit var viewModel: R16IdentifyTrackViewModel

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        runtime = R16DataRuntime.open(context)
        owner =
            R16ActiveDataRuntimeOwner(
                authoritySelector = FakeAuthoritySelector(R16AuthorityMode.ACTIVE),
                runtimeOpener = R16DataRuntimeOpener { runtime },
            )
        viewModel = R16IdentifyTrackViewModel(owner)
    }

    @After
    fun tearDown() {
        runtime.close()
    }

    @Test
    fun `initial state is empty and not loading`() {
        val state = viewModel.state.value
        assertEquals("", state.query)
        assertFalse(state.isLoading)
        assertTrue(state.candidates.isEmpty())
        assertNull(state.confirmedRecordingId)
        assertNull(state.errorMessage)
    }

    @Test
    fun `searchCandidates with blank query leaves candidates empty`() = runBlocking {
        viewModel.searchCandidates("   ")
        viewModel.state.waitUntil { !it.isLoading }
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertTrue(state.candidates.isEmpty())
    }

    @Test
    fun `confirmCandidate with null sourceReferenceId persists decision`() = runBlocking {
        val candidate =
            IdentifyCandidate(
                recordingId = "rec-1",
                title = "Song A",
                artist = "Artist A",
                album = "Album A",
                artworkLocation = null,
                confidence = IdentifyConfidenceKind.AUTHORITATIVE,
            )

        viewModel.confirmCandidate(null, candidate)
        viewModel.state.waitUntil { it.confirmedRecordingId != null }
        assertEquals("rec-1", viewModel.state.value.confirmedRecordingId)
    }

    @Test
    fun `inactive runtime handles errors gracefully`() = runBlocking {
        val inactiveOwner =
            R16ActiveDataRuntimeOwner(
                authoritySelector = FakeAuthoritySelector(R16AuthorityMode.LEGACY),
                runtimeOpener = R16DataRuntimeOpener { runtime },
            )
        val inactiveVm = R16IdentifyTrackViewModel(inactiveOwner)

        val candidate =
            IdentifyCandidate(
                recordingId = "rec-1",
                title = "Song A",
                artist = "Artist A",
                album = "Album A",
                artworkLocation = null,
                confidence = IdentifyConfidenceKind.AUTHORITATIVE,
            )
        inactiveVm.confirmCandidate("source-1", candidate)
        inactiveVm.state.waitUntil { it.errorMessage != null }
        assertEquals("Failed to confirm candidate match", inactiveVm.state.value.errorMessage)
    }

    private class FakeAuthoritySelector(private val mode: R16AuthorityMode) :
        R16AuthoritySelector(
            R16StartupStateReader(File("dummy")),
            R16MigrationProcessGate(File("dummy")),
        ) {
        override fun select(): R16AuthorityMode = mode
    }

    private suspend fun <T> StateFlow<T>.waitUntil(
        timeoutMs: Long = 3000,
        predicate: (T) -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (!predicate(value)) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (predicate(value)) return
            if (System.currentTimeMillis() - start > timeoutMs) {
                error("Timed out waiting for state condition. Current state: $value")
            }
            delay(20)
        }
    }
}
