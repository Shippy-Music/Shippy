/*
 * Copyright (c) 2026 Auxio Project
 * R16GlobalSearchCoordinatorTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.search

import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.music.Explicitness
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.sources.observation.SourceTrackObservation
import app.shippy.sources.provider.SourceDiscoveryFailure
import app.shippy.sources.provider.SourceDiscoveryFailureKind
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R16GlobalSearchCoordinatorTest {
    @Test
    fun `latest query drops stale provider completion`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val job = SupervisorJob()
        val provider = provider("jiosaavn")
        val scope = CoroutineScope(job + Dispatchers.Default)
        val coordinator =
            R16GlobalSearchCoordinator(
                scope = scope,
                configuredProviders = { listOf(provider) },
                localSearch = { query -> listOf(localResult(query)) },
                providerSearch = { query, _ ->
                    if (query == "old") {
                        firstStarted.complete(Unit)
                        withContext(NonCancellable) { releaseFirst.await() }
                    }
                    listOf(R16ProviderSearchResult(provider, listOf(observation("$query-song"))))
                },
                persistAndPlay = { error("Not used") },
                remoteDebounceMs = 0,
            )

        coordinator.submitQuery("old")
        withTimeout(2_000) { firstStarted.await() }
        coordinator.submitQuery("new")
        withTimeout(2_000) {
            while (
                coordinator.state.value.providers.single().content !=
                    R16ProviderSearchContent.Results(listOf(observation("new-song")))
            ) delay(1)
        }
        releaseFirst.complete(Unit)
        delay(20)

        assertEquals("new", coordinator.state.value.query)
        assertEquals(listOf(localResult("new")), coordinator.state.value.localResults)
        job.cancel()
    }

    @Test
    fun `provider failure preserves local results and retry only refreshes that provider`() =
        runBlocking {
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.Default)
            val jio = provider("jiosaavn")
            val youtube = provider("youtube")
            var retry = false
            val coordinator =
                R16GlobalSearchCoordinator(
                    scope = scope,
                    configuredProviders = { listOf(jio, youtube) },
                    localSearch = { listOf(localResult("fixture")) },
                    providerSearch = { _, requested ->
                        when (requested) {
                            jio.id ->
                                listOf(R16ProviderSearchResult(jio, listOf(observation("song"))))
                            youtube.id ->
                                listOf(
                                    R16ProviderSearchResult(
                                        youtube,
                                        if (retry)
                                            listOf(observation("video", provider = "youtube"))
                                        else emptyList(),
                                        if (retry) null
                                        else
                                            SourceDiscoveryFailure(
                                                SourceDiscoveryFailureKind.NETWORK,
                                                retryable = true,
                                            ),
                                    )
                                )
                            else -> emptyList()
                        }
                    },
                    persistAndPlay = { error("Not used") },
                    remoteDebounceMs = 0,
                )

            coordinator.submitQuery("fixture")
            withTimeout(2_000) {
                while (
                    coordinator.state.value.providers[1].content
                        !is R16ProviderSearchContent.Failure
                ) delay(1)
            }
            retry = true
            coordinator.retryProvider(youtube.id)
            withTimeout(2_000) {
                while (
                    coordinator.state.value.providers[1].content
                        !is R16ProviderSearchContent.Results
                ) delay(1)
            }

            assertEquals(listOf(localResult("fixture")), coordinator.state.value.localResults)
            assertEquals(
                R16ProviderSearchContent.Results(listOf(observation("song"))),
                coordinator.state.value.providers[0].content,
            )
            assertEquals(
                R16ProviderSearchContent.Results(
                    listOf(observation("video", provider = "youtube"))
                ),
                coordinator.state.value.providers[1].content,
            )
            job.cancel()
        }

    @Test
    fun `fast provider publishes while earlier provider remains loading in configured order`() =
        runBlocking {
            val slowStarted = CompletableDeferred<Unit>()
            val releaseSlow = CompletableDeferred<Unit>()
            val job = SupervisorJob()
            val slow = provider("youtube")
            val fast = provider("jiosaavn")
            val coordinator =
                R16GlobalSearchCoordinator(
                    scope = CoroutineScope(coroutineContext + job),
                    configuredProviders = { listOf(slow, fast) },
                    localSearch = { listOf(localResult("fixture")) },
                    providerSearch = { _, id ->
                        val selected = if (id == slow.id) slow else fast
                        if (id == slow.id) {
                            slowStarted.complete(Unit)
                            releaseSlow.await()
                        }
                        listOf(
                            R16ProviderSearchResult(
                                selected,
                                listOf(observation(selected.id.value)),
                            )
                        )
                    },
                    persistAndPlay = { error("Not used") },
                    remoteDebounceMs = 0,
                )
            try {
                coordinator.submitQuery("fixture")
                withTimeout(2_000) {
                    slowStarted.await()
                    while (
                        coordinator.state.value.providers[1].content
                            !is R16ProviderSearchContent.Results
                    ) delay(1)
                }
                assertEquals(
                    listOf(slow, fast),
                    coordinator.state.value.providers.map { it.provider },
                )
                assertEquals(
                    R16ProviderSearchContent.Loading,
                    coordinator.state.value.providers[0].content,
                )
                assertEquals(listOf(localResult("fixture")), coordinator.state.value.localResults)
                releaseSlow.complete(Unit)
                withTimeout(2_000) {
                    while (
                        coordinator.state.value.providers[0].content
                            !is R16ProviderSearchContent.Results
                    ) delay(1)
                }
                assertEquals(
                    listOf(slow, fast),
                    coordinator.state.value.providers.map { it.provider },
                )
            } finally {
                job.cancel()
            }
        }

    @Test
    fun `replaced query cancels pending provider retry without changing new sections`() =
        runBlocking {
            val retryStarted = CompletableDeferred<Unit>()
            val retryCancelled = CompletableDeferred<Unit>()
            val job = SupervisorJob()
            val provider = provider("jiosaavn")
            var calls = 0
            val coordinator =
                R16GlobalSearchCoordinator(
                    scope = CoroutineScope(coroutineContext + job),
                    configuredProviders = { listOf(provider) },
                    localSearch = { emptyList() },
                    providerSearch = { query, _ ->
                        calls++
                        if (calls == 2) {
                            retryStarted.complete(Unit)
                            try {
                                CompletableDeferred<Unit>().await()
                            } finally {
                                retryCancelled.complete(Unit)
                            }
                        }
                        listOf(R16ProviderSearchResult(provider, listOf(observation(query))))
                    },
                    persistAndPlay = { error("Not used") },
                    remoteDebounceMs = 0,
                )
            try {
                coordinator.submitQuery("old")
                withTimeout(2_000) {
                    while (
                        coordinator.state.value.providers.single().content
                            !is R16ProviderSearchContent.Results
                    ) delay(1)
                }
                coordinator.retryProvider(provider.id)
                withTimeout(2_000) { retryStarted.await() }
                coordinator.submitQuery("new")
                withTimeout(2_000) {
                    retryCancelled.await()
                    while (
                        coordinator.state.value.providers.single().content
                            !is R16ProviderSearchContent.Results
                    ) delay(1)
                }
                assertEquals(
                    R16ProviderSearchContent.Results(listOf(observation("new"))),
                    coordinator.state.value.providers.single().content,
                )
            } finally {
                job.cancel()
            }
        }

    @Test
    fun `provider selection emits canonical recording media ID never provider URL`() = runBlocking {
        val source = observation("provider-song", originalUrl = "https://example.com/song")
        val canonicalMediaId = "r16:recording:11111111-1111-1111-1111-111111111111"
        val result = CompletableDeferred<R16SearchPlayResult>()
        val job = SupervisorJob()
        val coordinator =
            R16GlobalSearchCoordinator(
                scope = CoroutineScope(job + Dispatchers.Default),
                configuredProviders = { listOf(provider("jiosaavn")) },
                localSearch = { emptyList() },
                providerSearch = { _, _ -> emptyList() },
                persistAndPlay = { selected ->
                    assertEquals(source, selected)
                    R16SearchPlayResult.Played(canonicalMediaId)
                },
            )

        coordinator.selectProvider(source) { result.complete(it) }

        val delivered = withTimeout(2_000) { result.await() }
        assertEquals(R16SearchPlayResult.Played(canonicalMediaId), delivered)
        assertTrue(canonicalMediaId != source.originalUrl)
        job.cancel()
    }

    @Test
    fun `omitted provider section resolves to empty rather than loading forever`() = runBlocking {
        val job = SupervisorJob()
        val missing = provider("youtube")
        val coordinator =
            R16GlobalSearchCoordinator(
                scope = CoroutineScope(job + Dispatchers.Default),
                configuredProviders = { listOf(missing) },
                localSearch = { emptyList() },
                providerSearch = { _, _ -> emptyList() },
                persistAndPlay = { error("Not used") },
                remoteDebounceMs = 0,
            )

        coordinator.submitQuery("fixture")
        withTimeout(2_000) {
            while (
                coordinator.state.value.providers.single().content
                    is R16ProviderSearchContent.Loading
            ) delay(1)
        }
        assertEquals(
            R16ProviderSearchContent.Results(emptyList()),
            coordinator.state.value.providers.single().content,
        )
        job.cancel()
    }

    private fun provider(id: String) = R16SearchProvider(ProviderId(id), id)

    private fun localResult(query: String) =
        R16CanonicalSearchResult(
            RecordingId("22222222-2222-2222-2222-222222222222"),
            "$query local",
            "Fixture Artist",
        )

    private fun observation(
        sourceItemId: String,
        originalUrl: String? = null,
        provider: String = "jiosaavn",
    ) =
        SourceTrackObservation(
            sourceKey = SourceKey(ProviderId(provider), SourceItemType.RECORDING, sourceItemId),
            sourceKind = SourceKind.JIOSAAVN,
            title = "Fixture",
            artistNames = listOf("Fixture Artist"),
            releaseTitle = "Fixture Release",
            durationMs = 180_000,
            version = RecordingVersion(VersionKind.ORIGINAL),
            explicitness = Explicitness.UNKNOWN,
            artwork = emptyList(),
            externalIdentifiers = emptySet(),
            originalUrl = originalUrl,
            asset = null,
            capturedAt = Instant.parse("2026-08-20T00:00:00Z"),
        )
}
