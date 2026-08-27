/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackSpineFactory.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import androidx.media3.common.Player
import app.shippy.data.R16DataRuntime
import app.shippy.data.playback.R16PlaybackCheckpointRepository
import app.shippy.data.playback.R16PlaybackPresentationRepository
import app.shippy.data.playback.R16PlaybackSourceRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import org.oxycblt.auxio.playback.service.PlaybackRequestHeaders
import org.oxycblt.auxio.shippy.lastfm.LastFmAccountId
import org.oxycblt.auxio.shippy.lastfm.LastFmCredentialRepository
import org.oxycblt.auxio.shippy.lastfm.R16LastFmOutboxWorkScheduler
import org.oxycblt.auxio.shippy.media.cache.PlaybackCacheManager
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.r16.maintenance.R16LivePlaybackQueue

/**
 * Inactive app-owned R16 construction seam. Injection has no playback side effect; a future
 * authority selector must pass the sole Media3 [Player] only after it has released R15 authority.
 */
class R16PlaybackSpineFactory
internal constructor(
    private val providers: ProviderRegistry,
    private val requestHeaders: PlaybackRequestHeaders,
    private val scheduleLastFmOutbox: () -> Unit = {},
    private val listeningSessionIdentityProvider: ListeningSessionIdentityProvider =
        NoOpListeningSessionIdentityProvider,
    private val playbackCache: PlaybackCacheManager? = null,
    private val livePlaybackQueue: R16LivePlaybackQueue? = null,
) {
    @Inject
    constructor(
        providers: ProviderRegistry,
        requestHeaders: PlaybackRequestHeaders,
        lastFmOutboxWorkScheduler: R16LastFmOutboxWorkScheduler,
        credentialRepository: LastFmCredentialRepository,
        playbackCache: PlaybackCacheManager,
        livePlaybackQueue: R16LivePlaybackQueue,
    ) : this(
        providers,
        requestHeaders,
        lastFmOutboxWorkScheduler::schedule,
        listeningSessionIdentityProvider =
            ListeningSessionIdentityProvider {
                val credentials = credentialRepository.load()
                if (credentials != null) {
                    ListeningSessionIdentity(
                        scrobbleAuthorized = true,
                        accountId = LastFmAccountId.hash(credentials.username),
                    )
                } else {
                    ListeningSessionIdentity.Unauthorized
                }
            },
        playbackCache,
        livePlaybackQueue,
    )

    /**
     * Transfers ownership of [player] to the returned spine. This method is intentionally not
     * called by the current R15.3 service and must not be called while that service owns a player.
     */
    fun create(
        parentScope: CoroutineScope,
        player: Player,
        data: R16DataRuntime,
    ): R16PlaybackSpine {
        val registry = PlaybackLocatorRegistry()
        val listeningSessionDelivery =
            R16ListeningSessionDelivery(parentScope, data.listeningSessions, scheduleLastFmOutbox)
        return createSpine(
            parentScope,
            Media3PlayerAdapter(
                player,
                RegistryMedia3ItemFactory(registry, requestHeaders),
                playbackCache = playbackCache,
            ),
            data.playbackCheckpoints,
            data.playbackPresentations,
            data.playbackSources,
            registry,
            listeningSessionDelivery,
            listeningSessionIdentityProvider,
        )
    }

    internal fun create(
        parentScope: CoroutineScope,
        engine: PlayerEngine,
        checkpoints: R16PlaybackCheckpointRepository,
        presentations: R16PlaybackPresentationRepository,
        sources: R16PlaybackSourceRepository,
        listeningSessionDelivery: R16ListeningSessionDelivery? = null,
        listeningSessionIdentityProvider: ListeningSessionIdentityProvider =
            this.listeningSessionIdentityProvider,
    ): R16PlaybackSpine {
        val registry = PlaybackLocatorRegistry()
        return createSpine(
            parentScope,
            engine,
            checkpoints,
            presentations,
            sources,
            registry,
            listeningSessionDelivery,
            listeningSessionIdentityProvider,
        )
    }

    private fun createSpine(
        parentScope: CoroutineScope,
        engine: PlayerEngine,
        checkpoints: R16PlaybackCheckpointRepository,
        presentations: R16PlaybackPresentationRepository,
        sources: R16PlaybackSourceRepository,
        registry: PlaybackLocatorRegistry,
        listeningSessionDelivery: R16ListeningSessionDelivery? = null,
        listeningSessionIdentityProvider: ListeningSessionIdentityProvider =
            NoOpListeningSessionIdentityProvider,
    ) =
        R16PlaybackSpine(
            parentScope,
            engine,
            RegistryPlaybackSourcePreparer(
                R16PlaybackLocatorResolver(sources, providers),
                registry,
            ),
            checkpoints,
            presentations,
            listeningSessionDelivery = listeningSessionDelivery,
            listeningSessionIdentityProvider = listeningSessionIdentityProvider,
            livePlaybackQueue = livePlaybackQueue,
        )
}
