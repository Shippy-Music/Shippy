/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaylistDestinationPickerViewModelTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.ui

import androidx.lifecycle.SavedStateHandle
import app.shippy.core.identity.QueueEntryId
import app.shippy.data.migration.R16StartupStateReader
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner
import org.oxycblt.auxio.shippy.r16.authority.R16AuthoritySelector
import org.oxycblt.auxio.shippy.r16.authority.R16DataRuntimeOpener
import org.oxycblt.auxio.shippy.r16.migration.R16MigrationProcessGate
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16PlaylistDestinationPickerViewModelTest {
    private val validRecordingUuid = UUID.randomUUID().toString()
    private val validQueueUuid = UUID.randomUUID().toString()

    @Test
    fun `captured identity is preserved from saved state`() {
        val nonExistentFile = File("non-existent-bootstrap.json")
        val owner =
            R16ActiveDataRuntimeOwner(
                authoritySelector =
                    R16AuthoritySelector(
                        R16StartupStateReader(nonExistentFile),
                        R16MigrationProcessGate(nonExistentFile),
                    ),
                runtimeOpener = R16DataRuntimeOpener { error("not needed") },
            )
        val savedState =
            SavedStateHandle(
                mapOf(
                    R16PlaylistDestinationPickerViewModel.ARG_RECORDING_ID to validRecordingUuid,
                    R16PlaylistDestinationPickerViewModel.ARG_QUEUE_ENTRY_ID to validQueueUuid,
                    R16PlaylistDestinationPickerViewModel.ARG_TITLE to "Song",
                    R16PlaylistDestinationPickerViewModel.ARG_ARTIST to "Artist",
                )
            )

        val viewModel = R16PlaylistDestinationPickerViewModel(owner, savedState)

        assertTrue(viewModel.hasCapturedIdentity())
        assertEquals(QueueEntryId(validQueueUuid), viewModel.queueEntryId)
        assertEquals("Song", viewModel.capturedTitle)
        assertEquals("Artist", viewModel.capturedArtist)
    }

    @Test
    fun `missing identity fails hasCapturedIdentity`() {
        val nonExistentFile = File("non-existent-bootstrap.json")
        val owner =
            R16ActiveDataRuntimeOwner(
                authoritySelector =
                    R16AuthoritySelector(
                        R16StartupStateReader(nonExistentFile),
                        R16MigrationProcessGate(nonExistentFile),
                    ),
                runtimeOpener = R16DataRuntimeOpener { error("not needed") },
            )
        val savedState = SavedStateHandle()
        val viewModel = R16PlaylistDestinationPickerViewModel(owner, savedState)

        assertFalse(viewModel.hasCapturedIdentity())
    }

    @Test
    fun `acknowledgeCompletion clears matching operation`() {
        val nonExistentFile = File("non-existent-bootstrap.json")
        val owner =
            R16ActiveDataRuntimeOwner(
                authoritySelector =
                    R16AuthoritySelector(
                        R16StartupStateReader(nonExistentFile),
                        R16MigrationProcessGate(nonExistentFile),
                    ),
                runtimeOpener = R16DataRuntimeOpener { error("not needed") },
            )
        val savedState =
            SavedStateHandle(
                mapOf(
                    R16PlaylistDestinationPickerViewModel.ARG_RECORDING_ID to validRecordingUuid,
                    R16PlaylistDestinationPickerViewModel.ARG_QUEUE_ENTRY_ID to validQueueUuid,
                )
            )

        val viewModel = R16PlaylistDestinationPickerViewModel(owner, savedState)
        viewModel.acknowledgeCompletion(123L)
        assertNull(viewModel.completion.value)
    }
}
