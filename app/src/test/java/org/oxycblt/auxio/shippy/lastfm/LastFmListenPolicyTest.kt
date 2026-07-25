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
}
