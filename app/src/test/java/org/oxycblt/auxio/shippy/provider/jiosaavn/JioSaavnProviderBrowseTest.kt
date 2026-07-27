/*
 * Copyright (c) 2026 Auxio Project
 * JioSaavnProviderBrowseTest.kt is part of Auxio.
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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntityType
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

class JioSaavnProviderBrowseTest {
    @Test
    fun `search returns browseable album artist and playlist fixtures`() = runBlocking {
        val provider = JioSaavnProvider(FixtureTransport())

        val result = provider.search("fixture")

        assertTrue(result is ProviderResult.Success)
        val page = (result as ProviderResult.Success).value
        assertEquals(1, page.tracks.size)
        assertEquals(
            setOf(ProviderEntityType.ALBUM, ProviderEntityType.ARTIST, ProviderEntityType.PLAYLIST),
            page.entities.map { it.type }.toSet(),
        )
        assertEquals(
            "fixture-album",
            page.entities.first { it.type == ProviderEntityType.ALBUM }.sourceItemId,
        )
        assertTrue(page.entities.all { it.originalUrl?.startsWith("https://") == true })
    }

    @Test
    fun `browse uses opaque tokens and correct result lists`() = runBlocking {
        val transport = FixtureTransport()
        val provider = JioSaavnProvider(transport)

        ProviderEntityType.entries.forEach { type ->
            val entity =
                ProviderEntity(
                    ProviderId("jiosaavn"),
                    "fixture-$type",
                    type,
                    "Fixture $type",
                    originalUrl = "https://www.jiosaavn.com/$type/fixture-$type",
                )
            val result = provider.browse(entity)

            assertTrue(result is ProviderResult.Success)
            assertEquals(1, (result as ProviderResult.Success).value.tracks.size)
            assertTrue(
                transport.urls.any {
                    it.contains("token=fixture-$type") &&
                        it.contains("type=${type.name.lowercase()}")
                }
            )
        }
    }

    @Test
    fun `artist browse exposes bounded continuation and requests the next page`() = runBlocking {
        val urls = mutableListOf<String>()
        val songs =
            List(20) { index -> SONG.replace("fixture-song", "fixture-song-$index") }
                .joinToString(",")
        val provider =
            JioSaavnProvider(
                object : ProviderHttpTransport {
                    override suspend fun execute(
                        request: ProviderHttpRequest
                    ): ProviderHttpResponse {
                        urls += request.url
                        return ProviderHttpResponse(
                            200,
                            emptyMap(),
                            """{"data":{"topSongs":[$songs]}}""".toByteArray(),
                        )
                    }
                }
            )
        val entity =
            ProviderEntity(
                ProviderId("jiosaavn"),
                "fixture-artist",
                ProviderEntityType.ARTIST,
                "Fixture Artist",
                originalUrl = "https://www.jiosaavn.com/artist/fixture-artist",
            )

        val first = provider.browse(entity) as ProviderResult.Success
        val second = provider.browse(entity, first.value.continuation) as ProviderResult.Success

        assertEquals("1", first.value.continuation)
        assertEquals("2", second.value.continuation)
        assertTrue(urls.first().contains("&p=0&"))
        assertTrue(urls.last().contains("&p=1&"))
    }

    @Test
    fun `malformed entity category does not discard usable songs`() = runBlocking {
        val provider =
            JioSaavnProvider(
                object : ProviderHttpTransport {
                    override suspend fun execute(
                        request: ProviderHttpRequest
                    ): ProviderHttpResponse {
                        val body = if (request.url.contains("search.getResults")) SONGS else "{"
                        return ProviderHttpResponse(200, emptyMap(), body.toByteArray())
                    }
                }
            )

        val result = provider.search("fixture")

        assertTrue(result is ProviderResult.Success)
        assertEquals(1, (result as ProviderResult.Success).value.tracks.size)
    }

    private class FixtureTransport : ProviderHttpTransport {
        val urls = mutableListOf<String>()

        override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse {
            urls += request.url
            val body =
                when {
                    request.url.contains("search.getResults") -> SONGS
                    request.url.contains("search.getAlbumResults") -> ALBUM
                    request.url.contains("search.getArtistResults") -> ARTIST
                    request.url.contains("search.getPlaylistResults") -> PLAYLIST
                    request.url.contains("type=artist") -> """{"data":{"topSongs":[$SONG]}}"""
                    else -> """{"data":{"list":[$SONG]}}"""
                }
            return ProviderHttpResponse(200, emptyMap(), body.toByteArray())
        }
    }

    private companion object {
        const val SONG =
            """{"id":"fixture-song","title":"Fixture Song","subtitle":"Fixture Artist"}"""
        const val SONGS = """{"results":[$SONG]}"""
        const val ALBUM =
            """{"results":[{"title":"Fixture Album","subtitle":"Album Artist","image":"https://c.saavncdn.com/a-150x150.jpg","perma_url":"https://www.jiosaavn.com/album/fixture-album"},{"title":"Bad Numeric Token","perma_url":"https://www.jiosaavn.com/album/12345"}]}"""
        const val ARTIST =
            """{"results":[{"title":"Fixture Artist","role":"Singer","image":"https://c.saavncdn.com/b-150x150.jpg","perma_url":"https://www.jiosaavn.com/artist/fixture-artist"}]}"""
        const val PLAYLIST =
            """{"results":[{"title":"Fixture Playlist","description":"A fixture","image":"https://c.saavncdn.com/c-150x150.jpg","perma_url":"https://www.jiosaavn.com/featured/fixture-playlist"}]}"""
    }
}
