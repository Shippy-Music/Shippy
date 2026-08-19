/*
 * Copyright (c) 2026 Auxio Project
 * R16ProviderObservationRepository.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.source

import app.shippy.core.identity.ProviderId
import app.shippy.core.identitymatch.RecordingVersionParser
import app.shippy.core.music.ArtworkReference
import app.shippy.core.music.Explicitness
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.sources.observation.SourceTrackObservation
import app.shippy.sources.provider.SourceDiscoveryFailure
import app.shippy.sources.provider.SourceDiscoveryFailureKind
import app.shippy.sources.provider.SourceDiscoveryRepository
import app.shippy.sources.provider.SourceDiscoverySection
import app.shippy.sources.provider.SourceDiscoverySnapshot
import app.shippy.sources.provider.SourceEntityKey
import app.shippy.sources.provider.SourceEntityObservation
import app.shippy.sources.provider.SourceEntityType
import app.shippy.sources.provider.SourceProviderDescriptor
import java.net.URI
import java.time.Clock
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackVersion
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.search.ProviderSearchSection
import org.oxycblt.auxio.shippy.search.UnifiedSearchRepository

/**
 * Inactive R16 edge over the current provider stack. It converts search metadata without persisting
 * results or changing the legacy UI/playback authority.
 */
class R16ProviderObservationRepository(
    private val legacy: UnifiedSearchRepository,
    private val clock: Clock = Clock.systemUTC(),
) : SourceDiscoveryRepository {
    override fun providers(): List<SourceProviderDescriptor> =
        legacy.providers().mapNotNull(ProviderDescriptor::toSourceDescriptor)

    override suspend fun search(query: String, providerId: ProviderId?): SourceDiscoverySnapshot {
        val snapshot =
            legacy.search(
                query,
                providerId?.let { org.oxycblt.auxio.shippy.domain.ProviderId(it.value) },
            )
        if (snapshot.query.isEmpty()) return SourceDiscoverySnapshot.EMPTY
        val capturedAt = clock.instant()
        return SourceDiscoverySnapshot(
            query = snapshot.query,
            sections = snapshot.sections.map { it.toSourceSection(capturedAt) },
        )
    }
}

private data class SourceNamespace(
    val providerId: ProviderId,
    val kind: SourceKind,
    val itemType: SourceItemType,
)

private fun ProviderDescriptor.toSourceDescriptor(): SourceProviderDescriptor? =
    namespace()?.let { SourceProviderDescriptor(ProviderId(id.value), displayName) }

private fun ProviderDescriptor.namespace(): SourceNamespace? =
    when (id.value) {
        JIOSAAVN_PROVIDER ->
            SourceNamespace(
                ProviderId(JIOSAAVN_PROVIDER),
                SourceKind.JIOSAAVN,
                SourceItemType.RECORDING,
            )
        YOUTUBE_PROVIDER ->
            SourceNamespace(ProviderId(YOUTUBE_PROVIDER), SourceKind.YOUTUBE, SourceItemType.VIDEO)
        YOUTUBE_MUSIC_PROVIDER ->
            SourceNamespace(
                ProviderId(YOUTUBE_PROVIDER),
                SourceKind.YOUTUBE_MUSIC,
                SourceItemType.VIDEO,
            )
        else -> null
    }

private fun ProviderSearchSection.toSourceSection(
    capturedAt: java.time.Instant
): SourceDiscoverySection {
    val descriptor = SourceProviderDescriptor(ProviderId(provider.id.value), provider.displayName)
    val namespace =
        provider.namespace()
            ?: return SourceDiscoverySection(
                provider = descriptor,
                tracks = emptyList(),
                failure =
                    SourceDiscoveryFailure(
                        SourceDiscoveryFailureKind.UNSUPPORTED,
                        retryable = false,
                        message = "Provider has no R16 source namespace",
                    ),
                discardedTrackCount = tracks.size,
            )
    failure?.let {
        return SourceDiscoverySection(
            provider = descriptor,
            tracks = emptyList(),
            failure = it.toSourceFailure(),
        )
    }

    val observations = tracks.mapNotNull { it.toObservation(namespace, provider, capturedAt) }
    return SourceDiscoverySection(
        provider = descriptor,
        tracks = observations,
        entities = entities.mapNotNull { it.toObservation(namespace) },
        continuation = continuation?.takeIf(String::isNotBlank),
        discardedTrackCount = tracks.size - observations.size,
    )
}

private fun Track.toObservation(
    namespace: SourceNamespace,
    provider: ProviderDescriptor,
    capturedAt: java.time.Instant,
): SourceTrackObservation? {
    val candidate =
        candidates.singleOrNull {
            it.kind == CandidateKind.PROVIDER && it.providerId?.value == provider.id.value
        } ?: return null
    val sourceItemId = candidate.sourceItemId.validSourceToken() ?: return null
    if (namespace.itemType == SourceItemType.VIDEO && !YOUTUBE_VIDEO_ID.matches(sourceItemId)) {
        return null
    }
    return SourceTrackObservation(
        sourceKey = SourceKey(namespace.providerId, namespace.itemType, sourceItemId),
        sourceKind = namespace.kind,
        title = title,
        artistNames = artists,
        releaseTitle = album,
        durationMs = durationMs,
        version = version.toRecordingVersion(title),
        explicitness =
            when (version.explicit) {
                true -> Explicitness.EXPLICIT
                false -> Explicitness.CLEAN
                null -> Explicitness.UNKNOWN
            },
        artwork = artwork.publicHttpsOrNull()?.let(::ArtworkReference)?.let(::listOf).orEmpty(),
        externalIdentifiers = emptySet(),
        originalUrl =
            if (namespace.itemType == SourceItemType.VIDEO) {
                "https://www.youtube.com/watch?v=$sourceItemId"
            } else {
                null
            },
        asset = null,
        capturedAt = capturedAt,
    )
}

private fun ProviderEntity.toObservation(namespace: SourceNamespace): SourceEntityObservation? {
    val sourceItemId = sourceItemId.validSourceToken() ?: return null
    return SourceEntityObservation(
        key =
            SourceEntityKey(
                providerId = namespace.providerId,
                type =
                    when (type) {
                        org.oxycblt.auxio.shippy.provider.ProviderEntityType.ALBUM ->
                            SourceEntityType.ALBUM
                        org.oxycblt.auxio.shippy.provider.ProviderEntityType.ARTIST ->
                            SourceEntityType.ARTIST
                        org.oxycblt.auxio.shippy.provider.ProviderEntityType.PLAYLIST ->
                            SourceEntityType.PLAYLIST
                    },
                sourceItemId = sourceItemId,
            ),
        sourceKind = namespace.kind,
        title = title,
        subtitle = subtitle,
        artwork = artwork.publicHttpsOrNull()?.let(::ArtworkReference),
        originalUrl = originalUrl.publicHttpsOrNull(),
    )
}

private fun ProviderResult.Failure.toSourceFailure() =
    SourceDiscoveryFailure(
        kind =
            when (kind) {
                ProviderFailureKind.NETWORK -> SourceDiscoveryFailureKind.NETWORK
                ProviderFailureKind.RATE_LIMITED -> SourceDiscoveryFailureKind.RATE_LIMITED
                ProviderFailureKind.AUTHENTICATION -> SourceDiscoveryFailureKind.AUTHENTICATION
                ProviderFailureKind.REGION -> SourceDiscoveryFailureKind.REGION
                ProviderFailureKind.UNAVAILABLE -> SourceDiscoveryFailureKind.UNAVAILABLE
                ProviderFailureKind.MALFORMED_RESPONSE ->
                    SourceDiscoveryFailureKind.MALFORMED_RESPONSE
                ProviderFailureKind.UNSUPPORTED -> SourceDiscoveryFailureKind.UNSUPPORTED
            },
        retryable = retryable,
        message = message,
    )

private fun TrackVersion.toRecordingVersion(title: String): RecordingVersion {
    val hints = buildList {
        label?.let(::add)
        if (isLive) add("live")
        if (isRemix) add("remix")
        title.versionQualifier()?.let(::add)
    }
    if (hints.isEmpty()) return RecordingVersion(VersionKind.ORIGINAL)
    val parsed = RecordingVersionParser.parse(hints.joinToString(" "))
    return if (parsed.traits.isEmpty() && label == null && !isLive && !isRemix) {
        RecordingVersion(VersionKind.ORIGINAL)
    } else {
        parsed
    }
}

private fun String.versionQualifier(): String? =
    VERSION_QUALIFIER.find(this)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull(String::isNotBlank)
        ?.trim()
        ?.takeIf(VERSION_MARKER::containsMatchIn)

private fun String.validSourceToken(): String? =
    trim().takeIf { it.isNotEmpty() && it.length <= 512 && !it.contains("://") }

private fun String?.publicHttpsOrNull(): String? =
    this?.takeIf { value ->
        value.length <= 2_048 &&
            runCatching { URI(value) }
                .getOrNull()
                ?.let { uri ->
                    uri.scheme.equals("https", ignoreCase = true) &&
                        !uri.host.isNullOrBlank() &&
                        uri.userInfo == null
                } == true
    }

private const val JIOSAAVN_PROVIDER = "jiosaavn"
private const val YOUTUBE_PROVIDER = "youtube"
private const val YOUTUBE_MUSIC_PROVIDER = "youtube_music"
private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{8,32}")
private val VERSION_QUALIFIER = Regex("(?:\\(([^)]+)\\)|\\[([^]]+)]|[-–—]\\s*([^-–—]+))\\s*$")
private val VERSION_MARKER =
    Regex(
        "\\b(live|remix|mix|acoustic|instrumental|radio edit|remaster(?:ed)?|cover|karaoke|sped up|slowed|reverb)\\b",
        RegexOption.IGNORE_CASE,
    )
