package org.oxycblt.auxio.shippy.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LastFmOverviewJsonTest {
    @Test fun profile_parses_only_bounded_valid_data() {
        val result = LastFmOverviewJson.profile("""{"user":{"name":"alice","playcount":"42"}}""".toByteArray())
        val overview = (result as LastFmOverviewResult.Success).overview
        assertEquals("alice", overview.username)
        assertEquals(42L, overview.playCount)
    }

    @Test fun tracks_are_capped_at_eight() {
        val tracks = (1..10).joinToString(",") { """{"name":"t$it","artist":{"name":"a$it"},"playcount":"$it"}""" }
        val result = LastFmOverviewJson.tracks("""{"toptracks":{"track":[$tracks]}}""".toByteArray())
        assertEquals(8, (result as LastFmOverviewResult.Success).overview.topTracks.size)
    }

    @Test fun malformed_and_oversize_bodies_fail_closed() {
        assertEquals(LastFmOverviewResult.Failure.Malformed, LastFmOverviewJson.profile("{}".toByteArray()))
        assertEquals(LastFmOverviewResult.Failure.Malformed, LastFmOverviewJson.profile(ByteArray(96 * 1024 + 1)))
    }

    @Test fun cache_codec_round_trips_and_rejects_too_many_tracks() {
        val value = LastFmOverview("alice", 2, listOf(LastFmOverviewTrack("song", "artist", null, null, 1)))
        assertEquals(value, LastFmOverviewCacheCodec.decode(LastFmOverviewCacheCodec.encode(value)))
        val tracks = (1..9).joinToString(",") { """{"n":"n","a":"a"}""" }
        val tooMany = """{"v":1,"u":"a","p":0,"t":[$tracks]}"""
        assertThrows(IllegalArgumentException::class.java) { LastFmOverviewCacheCodec.decode(tooMany.toByteArray()) }
    }

    @Test fun credentials_to_string_redacts_secrets() {
        val text = LastFmCredentials("key-value", "secret-value", "session-value", "alice").toString()
        assertFalse(text.contains("secret-value"))
        assertFalse(text.contains("session-value"))
    }
}
