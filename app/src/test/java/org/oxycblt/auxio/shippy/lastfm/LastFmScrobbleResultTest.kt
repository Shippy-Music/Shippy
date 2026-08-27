/*
 * Copyright (c) 2026 Auxio Project
 * LastFmScrobbleResultTest.kt is part of Auxio.
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse

class LastFmScrobbleResultTest {
    @Test
    fun `batch response preserves accepted ids and ignored messages`() {
        val response =
            parseLastFmScrobbleResponse(
                response(
                    """
                    <lfm status="ok">
                      <scrobbles accepted="1" ignored="1">
                        <scrobble><ignoredMessage code="0" /></scrobble>
                        <scrobble><ignoredMessage code="2">Track was ignored</ignoredMessage></scrobble>
                      </scrobbles>
                    </lfm>
                    """
                ),
                listOf("first", "second"),
            )

        assertEquals(
            LastFmScrobbleResult.Delivered(
                acceptedIds = setOf("first"),
                ignored = listOf(LastFmIgnoredScrobble("second", 2, "Track was ignored")),
            ),
            response,
        )
    }

    @Test
    fun `malformed batch response retries without deleting entries`() {
        val response =
            parseLastFmScrobbleResponse(
                response("<lfm status=\"ok\"><scrobbles accepted=\"1\" ignored=\"0\" /></lfm>"),
                listOf("first"),
            )

        assertEquals(LastFmScrobbleResult.Retry("malformed response"), response)
    }

    @Test
    fun `invalid session requires reauthentication`() {
        val response =
            parseLastFmScrobbleResponse(
                response("<lfm status=\"failed\"><error code=\"9\">Invalid session</error></lfm>"),
                listOf("first"),
            )

        assertEquals(LastFmScrobbleResult.Reauth, response)
    }

    @Test
    fun `transient errors retain the batch for retry`() {
        listOf(11, 16, 29).forEach { code ->
            assertEquals(
                LastFmScrobbleResult.Retry("http 200"),
                parseLastFmScrobbleResponse(
                    response("<lfm status=\"failed\"><error code=\"$code\" /></lfm>"),
                    listOf("first"),
                ),
            )
        }
    }

    @Test
    fun `outbox identity is deterministic per queue occurrence`() {
        val track = LastFmTrack("Artist", "Title", null, 60_000)
        val first = track.outbox(QueueItemId("occurrence"), "test_account", 10, 20)
        val second = track.outbox(QueueItemId("occurrence"), "test_account", 11, 21)

        assertEquals(first.id, second.id)
        assertTrue(first.id.startsWith("queue-item:"))
    }

    private fun response(body: String) =
        ProviderHttpResponse(statusCode = 200, headers = emptyMap(), body = body.toByteArray())
}
