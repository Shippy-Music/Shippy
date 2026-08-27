/*
 * Copyright (c) 2026 Auxio Project
 * R16BackupAdaptersTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.backup

import app.shippy.data.backup.R16BackupValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.lastfm.LastFmCredentialRepository
import org.oxycblt.auxio.shippy.lastfm.LastFmCredentials

class R16BackupAdaptersTest {
    @Test
    fun `portable settings adapter reads only explicit portable keys`() {
        val values =
            R16PortableSettingsAdapter(
                    mapOf(
                        "KEY_THEME2" to 2,
                        "KEY_BLACK_THEME" to true,
                        "auxio_pre_amp_with" to 1.5f,
                        "auxio_bar_action" to 42,
                        "auxio_task_exit" to true,
                        "auxio_music_locations2" to "content://private-location",
                        "shippy_download_destination_uri" to "content://private-download",
                    )
                )
                .snapshot()
                .values

        assertEquals(R16BackupValue.LongValue(2), values["ui.theme"])
        assertEquals(R16BackupValue.BooleanValue(true), values["ui.blackTheme"])
        assertEquals(R16BackupValue.DoubleValue(1.5), values["playback.preAmpWith"])
        assertEquals(R16BackupValue.LongValue(42), values["playback.barAction"])
        assertEquals(R16BackupValue.BooleanValue(true), values["playback.exitOnTaskRemoval"])
        assertFalse(values.keys.any { it.contains("location", ignoreCase = true) })
        assertFalse(values.keys.any { it.contains("destination", ignoreCase = true) })
    }

    @Test
    fun `lastfm adapter keeps username and preferences but never credentials`() {
        val credentials = LastFmCredentials("api-key", "api-secret", "session-key", "scrobbler")
        val adapter = R16SanitizedLastFmConfigAdapter(credentials)

        val snapshot = adapter.snapshot()

        assertEquals("scrobbler", snapshot.username)
        assertTrue(snapshot.preferences.isEmpty())
        assertFalse(snapshot.toString().contains("api-secret"))
        assertFalse(snapshot.toString().contains("session-key"))
        assertTrue(snapshot.preferences.keys.none { it.contains("secret", ignoreCase = true) })
    }

    @Test
    fun `lastfm adapter can load the existing credential repository off main thread`() =
        runBlocking {
            val adapter =
                R16SanitizedLastFmConfigAdapter.fromRepository(
                    object : LastFmCredentialRepository {
                        override suspend fun load(): LastFmCredentials =
                            LastFmCredentials("api", "secret", "session", "loaded-user")

                        override suspend fun save(credentials: LastFmCredentials) = Unit

                        override suspend fun clear() = Unit
                    }
                )

            assertEquals("loaded-user", adapter.snapshot().username)
        }
}
