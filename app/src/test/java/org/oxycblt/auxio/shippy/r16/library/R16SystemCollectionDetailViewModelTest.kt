/*
 * Copyright (c) 2026 Auxio Project
 * R16SystemCollectionDetailViewModelTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.library

import android.os.Looper
import androidx.lifecycle.viewModelScope
import java.time.Duration
import java.util.Optional
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class R16SystemCollectionDetailViewModelTest {
    @Test
    fun `typing cancels obsolete queries and playback retains the applied filter until debounce`() {
        val model =
            R16SystemCollectionDetailViewModel(R16LibraryReadModelsActivation(Optional.empty()))
        val main = shadowOf(Looper.getMainLooper())
        try {
            model.updateSearchQuery("river")
            main.idleFor(Duration.ofMillis(200))
            assertEquals("river", model.playbackQuery())
            model.updateSearchQuery("water")
            main.idleFor(Duration.ofMillis(100))
            model.updateSearchQuery("underwater")
            main.idleFor(Duration.ofMillis(199))
            assertEquals("river", model.playbackQuery())
            main.idleFor(Duration.ofMillis(1))
            assertEquals("underwater", model.playbackQuery())
            model.updateSearchQuery("pending")
            model.updateSearchQuery("")
            main.idle()
            assertNull(model.playbackQuery())
            main.idleFor(Duration.ofMillis(200))
            assertNull(model.playbackQuery())
        } finally {
            model.viewModelScope.cancel()
        }
    }
}
