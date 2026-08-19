/*
 * Copyright (c) 2026 Auxio Project
 * LastFmOverviewJsonTest.kt is part of Auxio.
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LastFmOverviewJsonTest {
    @Test
    fun profile_parses_only_bounded_valid_data() {
        val result =
            LastFmOverviewJson.profile(
                """{"user":{"name":"alice","playcount":"42"}}""".toByteArray()
            )
        val overview = (result as LastFmOverviewResult.Success).overview
        assertEquals("alice", overview.username)
        assertEquals(42L, overview.playCount)
    }

    @Test
    fun tracks_are_capped_at_eight() {
        val tracks =
            (1..10).joinToString(",") {
                """{"name":"t$it","artist":{"name":"a$it"},"playcount":"$it"}"""
            }
        val result =
            LastFmOverviewJson.tracks("""{"toptracks":{"track":[$tracks]}}""".toByteArray())
        assertEquals(8, (result as LastFmOverviewResult.Success).overview.topTracks.size)
    }

    @Test
    fun similar_tracks_reject_placeholder_artwork_and_cap_results() {
        val tracks =
            (1..10).joinToString(",") {
                """{"name":"t$it","artist":{"name":"a$it"},"image":[{"#text":"https://last.fm/placeholder-$it"}]}"""
            }
        val result =
            LastFmOverviewJson.similar("""{"similartracks":{"track":[$tracks]}}""".toByteArray())
        val recommendations = (result as LastFmOverviewResult.Success).overview.recommendations
        assertEquals(8, recommendations.size)
        assertEquals(null, recommendations.first().artworkUrl)
    }

    @Test
    fun malformed_and_oversize_bodies_fail_closed() {
        assertEquals(
            LastFmOverviewResult.Failure.Malformed,
            LastFmOverviewJson.profile("{}".toByteArray()),
        )
        assertEquals(
            LastFmOverviewResult.Failure.Malformed,
            LastFmOverviewJson.profile(ByteArray(96 * 1024 + 1)),
        )
    }

    @Test
    fun cache_codec_round_trips_and_rejects_too_many_tracks() {
        val value =
            LastFmOverview(
                "alice",
                2,
                listOf(LastFmOverviewTrack("song", "artist", null, null, 1)),
                listOf(LastFmOverviewTrack("similar", "artist", null, null, null)),
            )
        assertEquals(value, LastFmOverviewCacheCodec.decode(LastFmOverviewCacheCodec.encode(value)))
        val tracks = (1..9).joinToString(",") { """{"n":"n","a":"a"}""" }
        val tooMany = """{"v":1,"u":"a","p":0,"t":[$tracks]}"""
        assertThrows(IllegalArgumentException::class.java) {
            LastFmOverviewCacheCodec.decode(tooMany.toByteArray())
        }
    }

    @Test
    fun credentials_to_string_redacts_secrets() {
        val text =
            LastFmCredentials("key-value", "secret-value", "session-value", "alice").toString()
        assertFalse(text.contains("secret-value"))
        assertFalse(text.contains("session-value"))
    }
}
