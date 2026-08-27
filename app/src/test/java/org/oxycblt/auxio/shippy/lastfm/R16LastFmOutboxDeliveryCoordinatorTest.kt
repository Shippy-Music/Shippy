/*
 * Copyright (c) 2026 Auxio Project
 * R16LastFmOutboxDeliveryCoordinatorTest.kt is part of Auxio.
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

import app.shippy.data.lastfm.R16LastFmOutboxEntry
import app.shippy.data.lastfm.R16LastFmOutboxRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R16LastFmOutboxDeliveryCoordinatorTest {
    @Test
    fun `missing credentials leaves durable rows untouched`() = runBlocking {
        val outbox = FakeOutbox((1..2).map(::entry).toMutableList())
        var submissions = 0
        val coordinator =
            coordinator(credentials = null) { _, _ ->
                submissions++
                LastFmScrobbleResult.Retry("unexpected")
            }

        assertEquals(R16LastFmOutboxFlushResult.Succeeded, coordinator.flush(outbox, 100))
        assertEquals(0, submissions)
        assertEquals(listOf("outbox-1", "outbox-2"), outbox.ids())
        assertTrue(outbox.attempts.isEmpty())
    }

    @Test
    fun `delivered batch deletes accepted and ignored entries in fifo order`() = runBlocking {
        val outbox = FakeOutbox((1..3).map(::entry).toMutableList())
        val calls = mutableListOf<List<String>>()
        val coordinator = coordinator { entries, _ ->
            calls += entries.map(LastFmScrobblePayload::id)
            LastFmScrobbleResult.Delivered(
                acceptedIds = setOf("outbox-1", "outbox-3"),
                ignored = listOf(LastFmIgnoredScrobble("outbox-2", 6, "ignored")),
            )
        }

        assertEquals(R16LastFmOutboxFlushResult.Succeeded, coordinator.flush(outbox, 101))
        assertEquals(listOf(listOf("outbox-1", "outbox-2", "outbox-3")), calls)
        assertEquals(listOf("outbox-1", "outbox-2", "outbox-3"), outbox.attempts)
        assertTrue(outbox.entries.isEmpty())
    }

    @Test
    fun `retry retains batch and stops without submitting a second batch`() = runBlocking {
        val outbox = FakeOutbox((1..51).map(::entry).toMutableList())
        var submissions = 0
        val coordinator = coordinator { _, _ ->
            submissions++
            LastFmScrobbleResult.Retry("network")
        }

        assertEquals(R16LastFmOutboxFlushResult.Retry, coordinator.flush(outbox, 102))
        assertEquals(1, submissions)
        assertEquals(50, outbox.attempts.size)
        assertEquals(51, outbox.entries.size)
    }

    @Test
    fun `reauth marks state and retains batch without worker retry`() = runBlocking {
        val outbox = FakeOutbox((1..2).map(::entry).toMutableList())
        val reauth = LastFmReauthState()
        val coordinator = coordinator(reauth = reauth) { _, _ -> LastFmScrobbleResult.Reauth }

        assertEquals(R16LastFmOutboxFlushResult.Succeeded, coordinator.flush(outbox, 103))
        assertTrue(reauth.required.value)
        assertEquals(2, outbox.entries.size)

        val secondRun = coordinator.flush(outbox, 104)
        assertEquals(R16LastFmOutboxFlushResult.Succeeded, secondRun)
        assertEquals(2, outbox.attempts.size)
    }

    @Test
    fun `run is bounded to four fifo batches`() = runBlocking {
        val outbox = FakeOutbox((1..201).map(::entry).toMutableList())
        val calls = mutableListOf<List<String>>()
        val coordinator = coordinator { entries, _ ->
            calls += entries.map(LastFmScrobblePayload::id)
            LastFmScrobbleResult.Delivered(
                acceptedIds = entries.mapTo(linkedSetOf(), LastFmScrobblePayload::id),
                ignored = emptyList(),
            )
        }

        assertEquals(R16LastFmOutboxFlushResult.Succeeded, coordinator.flush(outbox, 105))
        assertEquals(4, calls.size)
        assertEquals(200, outbox.attempts.size)
        assertEquals(listOf("outbox-201"), outbox.ids())
        assertFalse(calls.zipWithNext().any { (a, b) -> a.last() >= b.first() })
    }

    @Test
    fun `delivery with account A finds A entries while delivery with account B does not`() =
        runBlocking {
            val accountA = LastFmAccountId.hash("alice")
            val accountB = LastFmAccountId.hash("bob")
            val outbox =
                FakeOutbox(
                    mutableListOf(entry(1, accountId = accountA), entry(2, accountId = accountB))
                )

            val coordinatorAlice =
                coordinator(credentials = LastFmCredentials("key", "secret", "session", "alice")) {
                    entries,
                    auth ->
                    assertEquals("alice", auth.username)
                    LastFmScrobbleResult.Delivered(
                        acceptedIds = entries.mapTo(linkedSetOf(), LastFmScrobblePayload::id),
                        ignored = emptyList(),
                    )
                }

            assertEquals(R16LastFmOutboxFlushResult.Succeeded, coordinatorAlice.flush(outbox, 200))
            assertEquals(listOf("outbox-1"), outbox.attempts)
            assertEquals(listOf("outbox-2"), outbox.ids())

            val coordinatorBob =
                coordinator(credentials = LastFmCredentials("key", "secret", "session", "bob")) {
                    entries,
                    auth ->
                    assertEquals("bob", auth.username)
                    LastFmScrobbleResult.Delivered(
                        acceptedIds = entries.mapTo(linkedSetOf(), LastFmScrobblePayload::id),
                        ignored = emptyList(),
                    )
                }

            assertEquals(R16LastFmOutboxFlushResult.Succeeded, coordinatorBob.flush(outbox, 201))
            assertEquals(listOf("outbox-1", "outbox-2"), outbox.attempts)
            assertTrue(outbox.entries.isEmpty())
        }

    private fun coordinator(
        credentials: LastFmCredentials? = LastFmCredentials("key", "secret", "session", "user"),
        reauth: LastFmReauthState = LastFmReauthState(),
        submit: suspend (List<LastFmScrobblePayload>, LastFmCredentials) -> LastFmScrobbleResult,
    ) =
        R16LastFmOutboxDeliveryCoordinator(
            credentials = FakeCredentials(credentials),
            reauth = reauth,
            submit = submit,
        )

    private fun entry(index: Int, accountId: String = defaultAccountId) =
        R16LastFmOutboxEntry(
            outboxId = "outbox-$index",
            accountId = accountId,
            listeningSessionId = "session-$index",
            recordingId = "recording-$index",
            artist = "Artist $index",
            track = "Track $index",
            album = null,
            durationSeconds = 240,
            startedAtEpochSeconds = index.toLong(),
            chosenByUser = true,
            queuedAtEpochMs = index.toLong(),
            attemptCount = 0,
            lastAttemptAtEpochMs = null,
        )

    private companion object {
        val defaultAccountId = LastFmAccountId.hash("user")
    }
}

private class FakeCredentials(private val value: LastFmCredentials?) : LastFmCredentialRepository {
    override suspend fun load(): LastFmCredentials? = value

    override suspend fun save(credentials: LastFmCredentials) = Unit

    override suspend fun clear() = Unit
}

private class FakeOutbox(initial: MutableList<R16LastFmOutboxEntry>) : R16LastFmOutboxRepository {
    val entries: MutableList<R16LastFmOutboxEntry> = initial
    val attempts = mutableListOf<String>()

    override suspend fun oldest(limit: Int): List<R16LastFmOutboxEntry> = entries.take(limit)

    override suspend fun oldest(accountId: String, limit: Int): List<R16LastFmOutboxEntry> =
        entries.filter { it.accountId == accountId }.take(limit)

    override suspend fun recordAttempt(outboxId: String, attemptedAtEpochMs: Long): Boolean {
        val exists = entries.any { it.outboxId == outboxId }
        if (exists) attempts += outboxId
        return exists
    }

    override suspend fun deleteAccepted(outboxIds: Set<String>): Int {
        val before = entries.size
        entries.removeAll { it.outboxId in outboxIds }
        return before - entries.size
    }

    override suspend fun count(): Int = entries.size

    fun ids(): List<String> = entries.map(R16LastFmOutboxEntry::outboxId)
}
