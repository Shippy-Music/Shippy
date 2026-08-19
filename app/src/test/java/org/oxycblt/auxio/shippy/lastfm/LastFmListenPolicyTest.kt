/*
 * Copyright (c) 2026 Auxio Project
 * LastFmListenPolicyTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.lastfm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmListenPolicyTest {
    @Test
    fun `threshold counts advancing time once`() {
        val policy = LastFmListenPolicy(60_000)
        policy.update(0)
        repeat(5) { assertFalse(policy.update((it + 1) * 5_000L)) }
        assertTrue(policy.update(30_000))
        assertFalse(policy.update(31_000))
    }

    @Test
    fun `short tracks pauses and seeks cannot fake a scrobble`() {
        val short = LastFmListenPolicy(30_000)
        short.update(0)
        repeat(6) { assertFalse(short.update((it + 1) * 5_000L)) }

        val policy = LastFmListenPolicy(120_000)
        policy.update(0)
        assertFalse(policy.update(50_000))
        policy.pause()
        assertFalse(policy.update(1_000))
        repeat(11) { assertFalse(policy.update(1_000L + (it + 1) * 5_000L)) }
        assertTrue(policy.update(61_000))
    }

    @Test
    fun `unknown duration waits four minutes`() {
        val policy = LastFmListenPolicy(null)
        policy.update(0)
        repeat(47) { assertFalse(policy.update((it + 1) * 5_000L)) }
        assertTrue(policy.update(240_000))
    }

    @Test
    fun `one second clock ticks count only advancing playback`() {
        val policy = LastFmListenPolicy(40_000)
        policy.update(0)
        repeat(10) { assertFalse(policy.update(0)) }
        repeat(19) { assertFalse(policy.update((it + 1) * 1_000L)) }
        assertTrue(policy.update(20_000))
    }
}
