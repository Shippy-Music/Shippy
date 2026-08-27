/*
 * Copyright (c) 2026 Auxio Project
 * R16LastFmOutboxDeliveryCoordinator.kt is part of Auxio.
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

/** The only result that asks WorkManager to try the durable batch again. */
internal sealed interface R16LastFmOutboxFlushResult {
    data object Succeeded : R16LastFmOutboxFlushResult

    data object Retry : R16LastFmOutboxFlushResult
}

/**
 * Drains a bounded prefix of the R16 durable Last.fm outbox.
 *
 * This class deliberately has no scheduling or Android lifecycle behavior. The future R16 owner
 * decides when to call it; the worker is only an adapter around this deterministic delivery loop.
 */
internal class R16LastFmOutboxDeliveryCoordinator(
    private val credentials: LastFmCredentialRepository,
    private val reauth: LastFmReauthState,
    private val submit:
        suspend (
            entries: List<LastFmScrobblePayload>, credentials: LastFmCredentials,
        ) -> LastFmScrobbleResult,
) {
    suspend fun flush(
        outbox: R16LastFmOutboxRepository,
        attemptedAtEpochMs: Long = System.currentTimeMillis(),
    ): R16LastFmOutboxFlushResult {
        val auth = credentials.load() ?: return R16LastFmOutboxFlushResult.Succeeded
        if (reauth.required.value) return R16LastFmOutboxFlushResult.Succeeded

        val accountId = LastFmAccountId.hash(auth.username)

        repeat(MAX_BATCHES_PER_RUN) {
            val batch = outbox.oldest(accountId, R16LastFmOutboxRepository.MAX_BATCH_SIZE)
            if (batch.isEmpty()) return R16LastFmOutboxFlushResult.Succeeded

            // A concurrent terminal delete is harmless. Do not submit an entry whose durable row
            // disappeared while this bounded batch was being prepared.
            val submitted =
                buildList(batch.size) {
                    batch.forEach { entry ->
                        if (outbox.recordAttempt(entry.outboxId, attemptedAtEpochMs)) add(entry)
                    }
                }
            if (submitted.isEmpty()) return@repeat

            when (val result = submit(submitted.map(R16LastFmOutboxEntry::payload), auth)) {
                is LastFmScrobbleResult.Delivered -> {
                    val submittedIds = submitted.mapTo(linkedSetOf()) { it.outboxId }
                    val terminalIds =
                        (result.acceptedIds + result.ignored.mapTo(linkedSetOf()) { it.id })
                            .intersect(submittedIds)
                    if (terminalIds.isNotEmpty()) outbox.deleteAccepted(terminalIds)
                    reauth.clear()
                    // A valid Last.fm batch accounts for every submitted entry. Retain any
                    // unaccounted row and ask WorkManager for a bounded retry instead of risking
                    // silent loss if a future parser/client ever violates that contract.
                    if (terminalIds.size != submittedIds.size) {
                        return R16LastFmOutboxFlushResult.Retry
                    }
                }
                is LastFmScrobbleResult.Dropped -> {
                    val submittedIds = submitted.mapTo(linkedSetOf()) { it.outboxId }
                    val terminalIds = result.ids.intersect(submittedIds)
                    if (terminalIds.isNotEmpty()) outbox.deleteAccepted(terminalIds)
                    if (terminalIds.size != submittedIds.size) {
                        return R16LastFmOutboxFlushResult.Retry
                    }
                }
                LastFmScrobbleResult.Reauth -> {
                    reauth.markRequired()
                    return R16LastFmOutboxFlushResult.Succeeded
                }
                is LastFmScrobbleResult.Retry -> {
                    return R16LastFmOutboxFlushResult.Retry
                }
            }
        }
        return R16LastFmOutboxFlushResult.Succeeded
    }
}

private fun R16LastFmOutboxEntry.payload() =
    LastFmScrobblePayload(
        id = outboxId,
        artist = artist,
        track = track,
        album = album,
        durationSeconds = durationSeconds,
        startedAtEpochSeconds = startedAtEpochSeconds,
    )

private const val MAX_BATCHES_PER_RUN = 4
