/*
 * Copyright (c) 2026 Auxio Project
 * CrewAndroidRuntimeSmokeTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.playback.service.ServiceRetentionPolicy
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaCache
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

/**
 * Deliberately hardware-free device smoke coverage for the two lifecycle boundaries JVM tests
 * cannot exercise: Android-private storage and task-removal retention policy.
 */
@RunWith(AndroidJUnit4::class)
class CrewAndroidRuntimeSmokeTest {
    private val root =
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "crew-instrumentation-smoke",
        )

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun activeCrewTaskRemovalRetainsPausedPlaybackService() {
        assertTrue(
            ServiceRetentionPolicy.shouldRetainAfterTaskRemoval(
                isPlaying = false,
                exitOnTaskRemoval = true,
                hasActiveCrew = true,
            )
        )
        assertFalse(
            ServiceRetentionPolicy.shouldRetainAfterTaskRemoval(
                isPlaying = false,
                exitOnTaskRemoval = false,
                hasActiveCrew = false,
            )
        )
    }

    @Test
    fun beginningAndEndingCrewRemovesCrashLeftoversAndActiveTemporaryStorage() {
        File(root, "crash-leftover/orphan.media").apply {
            parentFile?.mkdirs()
            writeText("not library media")
        }
        val cache = CrewTemporaryMediaCache(root, maxBytes = 1024L)
        val session = CrewSessionId("instrumentation-session", ProtocolVersion(1))

        cache.beginSession(session)

        assertFalse(File(root, "crash-leftover").exists())
        assertTrue(root.listFiles()?.isNotEmpty() == true)

        cache.endSession(session)

        assertTrue(root.listFiles().isNullOrEmpty())
    }
}
