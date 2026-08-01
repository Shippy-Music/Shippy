/*
 * Copyright (c) 2026 Auxio Project
 * CrewPermissionsTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.ui

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class CrewPermissionsTest {
    @Test
    fun `android 7 through 9 requests coarse location`() {
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), crewRuntimePermissions(24))
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), crewRuntimePermissions(28))
    }

    @Test
    fun `android 10 and 11 request coarse and fine location`() {
        val expected =
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        assertEquals(expected, crewRuntimePermissions(29))
        assertEquals(expected, crewRuntimePermissions(30))
    }

    @Test
    fun `android 12 requests bluetooth and version-correct location`() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            crewRuntimePermissions(31),
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            ),
            crewRuntimePermissions(32),
        )
    }

    @Test
    fun `android 13 plus requests nearby wifi and bluetooth`() {
        val expected =
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
        assertEquals(expected, crewRuntimePermissions(33))
        assertEquals(expected, crewRuntimePermissions(35))
        assertEquals(expected, crewRuntimePermissions(36))
    }
}
