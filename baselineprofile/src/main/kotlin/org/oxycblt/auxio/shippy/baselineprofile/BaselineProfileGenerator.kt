/*
 * Copyright (c) 2026 Auxio Project
 * BaselineProfileGenerator.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.baselineprofile

import android.graphics.Point
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
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
 * Collects a Baseline Profile only during an on-device reference run.
 *
 * This harness does not establish device evidence by itself; keep any generated output paired with
 * the recorded fixture, variant, device, and run metadata. The startup test is intentionally
 * independent of data. The fixture-backed journey is a separate test and fails loudly when the
 * deterministic R16 fixture is missing, so a startup-only run cannot be mistaken for complete
 * release evidence.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        }

    @Test
    fun fixtureBackedPrimaryJourneys() {
        requireScaleFixture()
        baselineProfileRule.collect(packageName = TARGET_PACKAGE) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            tapRequired("library_fragment")
            required("home_song_recycler")
            required("home_tabs").swipe(Direction.LEFT, 1f)
            openDeterministicLargePlaylist()
            device.waitForIdle()

            device.pressBack()
            device.waitForIdle()
            tapRequired("search_fragment")
            required("search_edit_text").apply {
                text = SEARCH_QUERY
                device.pressEnter()
            }
            device.waitForIdle()

            tapRequired("shippy_home_fragment")
            required("home_current").click()
            device.waitForIdle()
            // The lyrics control is fixture/layout dependent. A missing control is a failed
            // prerequisite, never silently reduced to a player-only profile.
            tapRequired("playback_lyrics_open")
            required("playback_lyrics_container")

            tapRequired("playback_queue")
            reorderQueue()
        }
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

    private fun MacrobenchmarkScope.openDeterministicLargePlaylist() {
        val playlist =
            required("home_playlist_recycler").findObject(By.text(LARGE_PLAYLIST_NAME))
                ?: error(
                    "R16 fixture is present but the deterministic large playlist row " +
                        "'$LARGE_PLAYLIST_NAME' is not exposed by the current library UI"
                )
        playlist.click()
    }

    private fun MacrobenchmarkScope.reorderQueue() {
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

    private fun MacrobenchmarkScope.tapRequired(id: String) {
        required(id).click()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.required(id: String): UiObject2 =
        requireNotNull(device.findObject(By.res(TARGET_PACKAGE, id))) {
            "R16 baseline-profile fixture/app state did not expose @$id"
        }

    private companion object {
        const val TARGET_PACKAGE = "org.oxycblt.auxio"
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
