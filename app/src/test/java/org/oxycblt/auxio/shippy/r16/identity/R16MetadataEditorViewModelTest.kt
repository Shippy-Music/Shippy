/*
 * Copyright (c) 2026 Auxio Project
 * R16MetadataEditorViewModelTest.kt is part of Auxio.
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
class R16MetadataEditorViewModelTest {
    private lateinit var runtime: R16DataRuntime
    private lateinit var owner: R16ActiveDataRuntimeOwner
    private lateinit var viewModel: R16MetadataEditorViewModel

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        runtime = R16DataRuntime.open(context)
        owner =
            R16ActiveDataRuntimeOwner(
                authoritySelector = FakeAuthoritySelector(R16AuthorityMode.ACTIVE),
                runtimeOpener = R16DataRuntimeOpener { runtime },
            )
        viewModel = R16MetadataEditorViewModel(owner)
    }

    @After
    fun tearDown() {
        runtime.close()
    }

    @Test
    fun `initial state is empty`() {
        val state = viewModel.state.value
        assertEquals("", state.recordingId)
        assertEquals("", state.title)
        assertEquals("", state.artist)
        assertEquals("", state.release)
        assertFalse(state.isTitleOverridden)
        assertFalse(state.isArtistOverridden)
        assertFalse(state.isReleaseOverridden)
        assertFalse(state.isSaved)
        assertNull(state.errorMessage)
    }

    @Test
    fun `loadRecording on non-existent recording sets empty canonical state`() = runBlocking {
        viewModel.loadRecording(VALID_RECORDING_ID)
        viewModel.state.waitUntil { it.recordingId == VALID_RECORDING_ID }
        val state = viewModel.state.value
        assertEquals(VALID_RECORDING_ID, state.recordingId)
        assertEquals("", state.canonicalTitle)
        assertEquals("", state.title)
        assertFalse(state.isTitleOverridden)
    }

    @Test
    fun `unmerge on empty recording sets isSaved`() = runBlocking {
        viewModel.loadRecording(VALID_RECORDING_ID)
        viewModel.state.waitUntil { it.recordingId == VALID_RECORDING_ID }

        viewModel.unmerge()
        viewModel.state.waitUntil { it.isSaved }

        val state = viewModel.state.value
        assertTrue(state.isSaved)
    }

    @Test
    fun `inactive runtime handles errors gracefully`() = runBlocking {
        val inactiveOwner =
            R16ActiveDataRuntimeOwner(
                authoritySelector = FakeAuthoritySelector(R16AuthorityMode.LEGACY),
                runtimeOpener = R16DataRuntimeOpener { runtime },
            )
        val inactiveVm = R16MetadataEditorViewModel(inactiveOwner)

        inactiveVm.loadRecording(VALID_RECORDING_ID)
        inactiveVm.state.waitUntil { it.recordingId == VALID_RECORDING_ID }
        inactiveVm.saveOverrides("Title", "Artist", "Release")
        inactiveVm.state.waitUntil { it.errorMessage != null }
        assertEquals("R16 database is not active", inactiveVm.state.value.errorMessage)
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

    private companion object {
        const val VALID_RECORDING_ID = "00000000-0000-0000-0000-000000000001"
    }
}
