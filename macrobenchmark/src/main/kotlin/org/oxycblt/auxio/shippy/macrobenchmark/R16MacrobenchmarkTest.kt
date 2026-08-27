/*
 * Copyright (c) 2026 Auxio Project
 * R16MacrobenchmarkTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.macrobenchmark

import android.graphics.Point
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only R16 performance contract scenarios.
 *
 * These tests measure the benchmark app after its benchmark-only provider installs the
 * deterministic R16 fixture. The fixture never ships in production variants. Every journey checks
 * the exact scale contract so partial or stale data cannot produce a green result.
 */
@RunWith(AndroidJUnit4::class)
class R16MacrobenchmarkTest {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun fixtureIsReadyFromCleanInstall() {
        requireScaleFixture()
    }

    @Test
    fun coldStart() {
        requireScaleFixture()
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
            measureBlock = { startActivityAndWait() },
        )
    }

    @Test fun openLibrary() = measureFrames { openLibrary() }

    @Test
    fun switchLibraryTabs() = measureFrames {
        openLibrary()
        required("home_tabs").swipe(Direction.LEFT, 1f)
        device.waitForIdle()
    }

    @Test
    fun openTenThousandEntryPlaylist() = measureFrames {
        openLibrary()
        openDeterministicLargePlaylist()
        device.waitForIdle()
    }

    @Test
    fun scrollSongs() = measureFrames {
        openLibrary()
        required("home_song_recycler").scroll(Direction.DOWN, 1f)
        device.waitForIdle()
    }

    @Test
    fun searchLocal() = measureFrames {
        startFromHome()
        required("search_fragment").click()
        device.waitForIdle()
        required("search_edit_text").text = SEARCH_QUERY
        device.pressEnter()
        device.waitForIdle()
    }

    @Test
    fun tapLocalTrack() = measureFrames {
        openLibrary()
        required("home_song_recycler").children.firstOrNull()?.click()
            ?: error("R16 fixture must expose a local track row")
        device.waitForIdle()
    }

    @Test
    fun openAndCollapseNowPlaying() = measureFrames {
        startFromHome()
        required("home_current").click()
        device.waitForIdle()
        required("playback_sheet").swipe(Direction.DOWN, 1f)
        device.waitForIdle()
    }

    @Test
    fun openQueue() = measureFrames {
        startFromHome()
        required("playback_queue").click()
        device.waitForIdle()
    }

    @Test
    fun openLyrics() = measureFrames {
        startFromHome()
        required("home_current").click()
        device.waitForIdle()
        // This is only available when the reference fixture includes lyrics and the device
        // uses the lyrics-capable playback layout; required() makes the prerequisite fail
        // loudly instead of turning the journey into startup-only coverage.
        required("playback_lyrics_open").click()
        device.waitForIdle()
        required("playback_lyrics_container")
    }

    @Test
    fun reorderQueue() = measureFrames {
        startFromHome()
        required("playback_queue").click()
        device.waitForIdle()
        val handles =
            required("queue_recycler").children.mapNotNull {
                it.findObject(By.res(TARGET_PACKAGE, "song_drag_handle"))
            }
        check(handles.size >= 2) {
            "R16 queue-reorder prerequisite missing: expected at least two queue rows with " +
                "@$TARGET_PACKAGE:song_drag_handle"
        }
        val firstBounds = handles.first().visibleBounds
        handles
            .first()
            .drag(Point(firstBounds.centerX(), firstBounds.centerY() + firstBounds.height()))
        device.waitForIdle()
    }

    @Ignore(
        "Blocked: the R16 Shippy collection detail exposes action_edit_order but no benchmark-visible " +
            "drag handle/accessibility action; its current ItemTouchHelper disables long-press drag. " +
            "Do not treat this disabled item as playlist-reorder coverage."
    )
    @Test
    fun reorderPlaylist(): Nothing =
        error("R16 playlist-reorder prerequisite is not exposed by the current UI")

    @Ignore(
        "Blocked: current R16 UI/cutover has no deterministic provider-source selector and no " +
            "distinct cached-play source hook. A single generic play tap cannot prove either path."
    )
    @Test
    fun playProviderAndCachedSources(): Nothing =
        error("R16 provider/cached-play prerequisites are not exposed by the current UI")

    private fun measureFrames(block: MacrobenchmarkScope.() -> Unit) = run {
        requireScaleFixture()
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
            measureBlock = block,
        )
    }

    private fun MacrobenchmarkScope.startFromHome() {
        startActivityAndWait()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.openLibrary() {
        startFromHome()
        required("library_fragment").click()
        device.waitForIdle()
        required("home_song_recycler")
    }

    private fun MacrobenchmarkScope.openDeterministicLargePlaylist() {
        val playlist =
            required("home_playlist_recycler").findObject(By.text(LARGE_PLAYLIST_NAME))
                ?: error(
                    "R16 fixture is present but the deterministic large playlist row " +
                        "'$LARGE_PLAYLIST_NAME' is not exposed by the current library UI"
                )
        playlist.click()
    }

    private fun MacrobenchmarkScope.required(id: String): UiObject2 =
        requireNotNull(device.findObject(By.res(TARGET_PACKAGE, id))) {
            "R16 benchmark fixture/app state did not expose @$id"
        }

    private fun requireScaleFixture() {
        forceStopTarget()
        val result =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .contentResolver
                .call(Uri.parse(FIXTURE_URI), FIXTURE_PREPARE_METHOD, null, null)
                ?: error(
                    "R16 scale fixture installer returned no result; install the benchmark app " +
                        "variant before collecting evidence"
                )
        check(result.getBoolean(FIXTURE_READY_KEY, false)) {
            "R16 scale fixture probe did not confirm readiness"
        }
        check(result.getInt(FIXTURE_SCHEMA_VERSION_KEY, -1) == SCHEMA_VERSION) {
            "R16 scale fixture probe returned an unexpected schema version"
        }
        check(result.getInt(FIXTURE_RECORDINGS_KEY, -1) == RECORDING_COUNT) {
            "R16 scale fixture probe returned an unexpected recording count"
        }
        check(result.getInt(FIXTURE_PLAYLISTS_KEY, -1) == PLAYLIST_COUNT) {
            "R16 scale fixture probe returned an unexpected playlist count"
        }
        check(result.getInt(FIXTURE_PLAYLIST_ENTRIES_KEY, -1) == LARGE_PLAYLIST_ENTRY_COUNT) {
            "R16 scale fixture probe returned an unexpected large-playlist count"
        }
        check(result.getInt(FIXTURE_HISTORY_KEY, -1) == HISTORY_ROW_COUNT) {
            "R16 scale fixture probe returned an unexpected history count"
        }
    }

    private fun forceStopTarget() {
        val result =
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("am force-stop $TARGET_PACKAGE")
        ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
    }

    private companion object {
        const val TARGET_PACKAGE = "org.oxycblt.auxio"
        const val ITERATIONS = 5
        const val SEARCH_QUERY = "r16"
        const val LARGE_PLAYLIST_NAME = "R16 Duplicate Playlist"
        const val FIXTURE_URI = "content://org.oxycblt.auxio.r16.benchmark-fixture"
        const val FIXTURE_PREPARE_METHOD = "prepare_scale_v1"
        const val FIXTURE_READY_KEY = "ready"
        const val FIXTURE_SCHEMA_VERSION_KEY = "schema_version"
        const val FIXTURE_RECORDINGS_KEY = "recordings"
        const val FIXTURE_PLAYLISTS_KEY = "playlists"
        const val FIXTURE_PLAYLIST_ENTRIES_KEY = "playlist_entries"
        const val FIXTURE_HISTORY_KEY = "history_rows"
        const val RECORDING_COUNT = 50_000
        const val PLAYLIST_COUNT = 1_000
        const val LARGE_PLAYLIST_ENTRY_COUNT = 10_000
        const val HISTORY_ROW_COUNT = 20_000
        const val SCHEMA_VERSION = 2
    }
}
