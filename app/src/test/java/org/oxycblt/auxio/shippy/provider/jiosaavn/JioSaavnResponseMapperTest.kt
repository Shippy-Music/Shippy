/*
 * Copyright (c) 2026 Auxio Project
 * JioSaavnResponseMapperTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider.jiosaavn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JioSaavnResponseMapperTest {
    @Test
    fun `nested donor search response maps to typed song`() {
        val song =
            JioSaavnResponseMapper.mapSong(
                mapOf(
                    "id" to "song-id",
                    "type" to "song",
                    "title" to "Rock &amp; Roll",
                    "subtitle" to "Fallback artist",
                    "image" to "http://img.test/150x150.jpg",
                    "perma_url" to "https://www.jiosaavn.com/song/song-id",
                    "more_info" to
                        mapOf(
                            "album" to "Album &#039;One&#039;",
                            "duration" to "245",
                            "language" to "hindi",
                            "320kbps" to "true",
                            "has_lyrics" to "0",
                            "artistMap" to
                                mapOf(
                                    "primary_artists" to
                                        listOf(
                                            mapOf("name" to "First &amp; Artist"),
                                            mapOf("name" to "Second"),
                                        )
                                ),
                        ),
                )
            )

        assertEquals("song-id", song.id)
        assertEquals("Rock & Roll", song.title)
        assertEquals("Album 'One'", song.album)
        assertEquals("First & Artist, Second", song.artist)
        assertEquals(245L, song.durationSeconds)
        assertEquals("Hindi", song.language)
        assertTrue(song.supports320Kbps!!)
        assertFalse(song.hasLyrics!!)
        assertEquals("https://img.test/500x500.jpg", song.artworkUrl)
        assertNull(song.mediaUrl)
    }

    @Test
    fun `primary artist map wins over composer field`() {
        val song =
            JioSaavnResponseMapper.mapSong(
                mapOf(
                    "id" to "id",
                    "song" to "Title",
                    "duration" to "",
                    "more_info" to
                        mapOf(
                            "music" to "Composer",
                            "320kbps" to "unknown",
                            "artistMap" to
                                mapOf(
                                    "primary_artists" to
                                        listOf(mapOf("name" to "Lead &quot;Artist&quot;"))
                                ),
                        ),
                )
            )

        assertEquals("Lead \"Artist\"", song.artist)
        assertNull(song.durationSeconds)
        assertNull(song.supports320Kbps)
    }

    @Test
    fun `nested artwork falls back to more info and is normalized`() {
        val song =
            JioSaavnResponseMapper.mapSong(
                mapOf(
                    "id" to "id",
                    "title" to "Title",
                    "more_info" to mapOf("image" to "http://img.test/150x150.jpg"),
                )
            )

        assertEquals("https://img.test/500x500.jpg", song.artworkUrl)
    }

    @Test
    fun `music field is used only when primary artists are absent`() {
        val song =
            JioSaavnResponseMapper.mapSong(
                mapOf(
                    "id" to "id",
                    "song" to "Title",
                    "more_info" to mapOf("music" to "Fallback composer"),
                )
            )

        assertEquals("Fallback composer", song.artist)
    }

    @Test
    fun `missing required identity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            JioSaavnResponseMapper.mapSong(mapOf("title" to "No id"))
        }
    }
}
